package com.serortech.cardia.vision

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Quadrilatère détecté, exprimé dans l'image « droite » (orientation d'affichage). */
data class CardQuad(
    val corners: List<PointF>, // 4 coins, en pixels de l'image droite, dans l'ordre du contour
    val srcWidth: Int,         // largeur de l'image droite
    val srcHeight: Int,        // hauteur de l'image droite
)

/**
 * Détecte le contour d'une carte à collectionner dans une frame caméra, EN LOCAL
 * (OpenCV), sans aucun appel réseau ni IA. Approche « scanner de document » :
 * contours (Canny) → plus grand quadrilatère convexe au ratio proche d'une carte.
 *
 * Ne dessine rien : renvoie les 4 coins, à l'appelant de tracer l'overlay.
 */
class CardOutlineDetector {

    /** @return les 4 coins, ou null si aucune carte franche n'est détectée. */
    fun detect(image: ImageProxy): CardQuad? {
        if (!ensureLoaded()) return null

        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees

        // Plan Y (luminance) = niveaux de gris directs, sans conversion couleur.
        val yPlane = image.planes[0]
        val rowStride = yPlane.rowStride
        val buffer = yPlane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val full = Mat(h, rowStride, CvType.CV_8UC1)
        val small = Mat()
        val blur = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            full.put(0, 0, bytes)
            val gray = full.submat(0, h, 0, w) // retire le padding de rowStride

            // Sous-échantillonnage pour la vitesse (~480 px de large).
            val targetW = 480
            val scale = if (w > targetW) targetW.toFloat() / w else 1f
            if (scale < 1f) {
                Imgproc.resize(gray, small, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA)
            } else {
                gray.copyTo(small)
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
            var bestPts: Array<Point>? = null
            var bestArea = 0.0

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < MIN_AREA_RATIO * imgArea) continue

                val c2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(c2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
                c2f.release()

                if (approx.total() == 4L) {
                    val pts = approx.toArray()
                    val poly = MatOfPoint(*pts)
                    val convex = Imgproc.isContourConvex(poly)
                    poly.release()
                    if (convex) {
                        val rect = Imgproc.minAreaRect(approx)
                        val rw = rect.size.width
                        val rh = rect.size.height
                        if (rw > 0 && rh > 0) {
                            val ratio = min(rw, rh) / max(rw, rh)
                            if (ratio in MIN_RATIO..MAX_RATIO && area > bestArea) {
                                bestArea = area
                                bestPts = pts
                            }
                        }
                    }
                }
                approx.release()
            }

            val pts = bestPts ?: return null

            // Remise à l'échelle pleine résolution capteur, puis rotation -> image droite.
            val inv = 1f / scale
            val (uw, uh) = if (rotation == 90 || rotation == 270) h to w else w to h
            val corners = pts.map { p ->
                rotatePoint((p.x * inv).toFloat(), (p.y * inv).toFloat(), rotation, w, h)
            }
            return CardQuad(corners, uw, uh)
        } finally {
            full.release(); small.release(); blur.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Mappe un point des coords capteur (w×h) vers l'image droite après rotation. */
    private fun rotatePoint(x: Float, y: Float, rotation: Int, w: Int, h: Int): PointF = when (rotation) {
        90 -> PointF(h - 1 - y, x)
        180 -> PointF(w - 1 - x, h - 1 - y)
        270 -> PointF(y, w - 1 - x)
        else -> PointF(x, y)
    }

    companion object {
        private const val MIN_AREA_RATIO = 0.10 // la carte doit occuper >=10% de la frame
        private const val MIN_RATIO = 0.50      // ratio carte ~0,714 ; marge pour la perspective
        private const val MAX_RATIO = 0.95

        @Volatile private var loaded = false

        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (!loaded) loaded = OpenCVLoader.initLocal()
            return loaded
        }
    }
}
