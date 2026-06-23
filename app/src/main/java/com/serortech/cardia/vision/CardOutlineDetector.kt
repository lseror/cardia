package com.serortech.cardia.vision

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Quadrilatère détecté, exprimé dans l'image « droite » (orientation d'affichage). */
data class CardQuad(
    val corners: List<PointF>, // 4 coins, en pixels de l'image droite
    val srcWidth: Int,
    val srcHeight: Int,
)

/** Résultat enrichi (avec diagnostics pour le HUD de debug). */
data class OutlineResult(
    val quad: CardQuad?,
    val ocvLoaded: Boolean,
    val contourCount: Int,
    val bestAreaPct: Int,   // aire du meilleur candidat, en % de la frame
    val bestRatio: Float,   // ratio min/max côtés du meilleur candidat
    val error: String?,
)

/**
 * Détecte le contour d'une carte EN LOCAL (OpenCV), sans réseau ni IA.
 * On prend le plus grand contour suffisamment grand dont le rectangle englobant
 * (minAreaRect) a un ratio proche d'une carte, et on renvoie ses 4 coins.
 */
class CardOutlineDetector {

    fun detect(image: ImageProxy): OutlineResult {
        val loaded = ensureLoaded()
        if (!loaded) return OutlineResult(null, false, 0, 0, 0f, "OpenCV non chargé")

        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees

        val full = Mat()
        val small = Mat()
        val blur = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            // --- Plan Y (luminance) -> Mat gris, robuste au rowStride/padding ---
            val yPlane = image.planes[0]
            val rowStride = yPlane.rowStride
            val buf = yPlane.buffer
            val avail = buf.remaining()
            val rows = min(h, avail / rowStride)
            val needed = rows * rowStride
            val data = ByteArray(needed)
            buf.get(data, 0, needed)
            val padded = Mat(rows, rowStride, CvType.CV_8UC1)
            padded.put(0, 0, data)
            val gray = padded.submat(0, rows, 0, w) // retire le padding -> w x rows
            gray.copyTo(full)
            padded.release()

            // Sous-échantillonnage (~480 px de large) pour la vitesse.
            val targetW = 480
            val scale = if (w > targetW) targetW.toFloat() / w else 1f
            if (scale < 1f) {
                Imgproc.resize(full, small, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA)
            } else {
                full.copyTo(small)
            }

            Imgproc.GaussianBlur(small, blur, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blur, edges, 50.0, 150.0)
            Imgproc.dilate(
                edges, edges,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0)),
            )
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val imgArea = (small.width() * small.height()).toDouble()
            var bestCardArea = 0.0
            var bestRect: org.opencv.core.RotatedRect? = null
            // Diagnostics : meilleur candidat par aire, tous ratios confondus.
            var diagArea = 0.0
            var diagRatio = 0f

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < MIN_AREA_RATIO * imgArea) continue
                val c2f = MatOfPoint2f(*contour.toArray())
                val rect = Imgproc.minAreaRect(c2f)
                c2f.release()
                val rw = rect.size.width
                val rh = rect.size.height
                if (rw <= 0 || rh <= 0) continue
                val ratio = (min(rw, rh) / max(rw, rh)).toFloat()
                if (area > diagArea) { diagArea = area; diagRatio = ratio }
                if (ratio in MIN_RATIO..MAX_RATIO && area > bestCardArea) {
                    bestCardArea = area
                    bestRect = rect
                }
            }

            val bestAreaPct = if (imgArea > 0) (diagArea / imgArea * 100).toInt() else 0
            val rect = bestRect
                ?: return OutlineResult(null, true, contours.size, bestAreaPct, diagRatio, null)

            // 4 coins du rectangle (échelle réduite) -> capteur -> image droite.
            val box = Mat()
            Imgproc.boxPoints(rect, box)
            val inv = 1f / scale
            val (uw, uh) = if (rotation == 90 || rotation == 270) h to w else w to h
            val corners = (0 until 4).map { i ->
                val sx = (box.get(i, 0)[0] * inv).toFloat()
                val sy = (box.get(i, 1)[0] * inv).toFloat()
                rotatePoint(sx, sy, rotation, w, h)
            }
            box.release()
            return OutlineResult(
                CardQuad(corners, uw, uh), true, contours.size, bestAreaPct,
                (min(rect.size.width, rect.size.height) / max(rect.size.width, rect.size.height)).toFloat(),
                null,
            )
        } catch (t: Throwable) {
            return OutlineResult(null, true, 0, 0, 0f, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            full.release(); small.release(); blur.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    private fun rotatePoint(x: Float, y: Float, rotation: Int, w: Int, h: Int): PointF = when (rotation) {
        90 -> PointF(h - 1 - y, x)
        180 -> PointF(w - 1 - x, h - 1 - y)
        270 -> PointF(y, w - 1 - x)
        else -> PointF(x, y)
    }

    companion object {
        private const val MIN_AREA_RATIO = 0.05 // carte >= 5% de la frame
        private const val MIN_RATIO = 0.45f      // ratio carte ~0,714 ; marge perspective
        private const val MAX_RATIO = 0.98f

        @Volatile private var loaded = false

        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (!loaded) loaded = OpenCVLoader.initLocal()
            return loaded
        }
    }
}
