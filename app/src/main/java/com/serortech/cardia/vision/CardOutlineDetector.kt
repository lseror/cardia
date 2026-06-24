package com.serortech.cardia.vision

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Parallélogramme détecté, exprimé dans l'image « droite » (orientation d'affichage). */
data class CardQuad(
    val corners: List<PointF>,    // 4 coins (ordre du contour)
    val midpoints: List<PointF>,  // milieux des côtés : m01, m12, m23, m30
    val segA: Float,              // longueur médiane m01–m23
    val segB: Float,              // longueur médiane m12–m30
    val ratio: Float,             // min/max des deux médianes
    val srcWidth: Int,
    val srcHeight: Int,
)

/** Résultat enrichi (diagnostics HUD + frame de debug optionnelle). */
data class OutlineResult(
    val quad: CardQuad?,
    val ocvLoaded: Boolean,
    val contourCount: Int,
    val bestAreaPct: Int,   // aire du plus gros contour (toute forme), en %
    val bestRatio: Float,   // ratio du plus gros quadrilatère candidat
    val error: String?,
    val debugJpeg: ByteArray? = null,
)

/**
 * Détecte une carte EN LOCAL (OpenCV), sans réseau ni IA.
 * Parallélogramme (4 coins via enveloppe convexe) dont :
 *  - le ratio largeur/hauteur, mesuré par les médianes (milieux des côtés
 *    opposés, robuste à la perspective), est proche de 63/88 ≈ 0,716 (±0,05) ;
 *  - l'orientation est PORTRAIT : la plus longue médiane (la hauteur) est plus
 *    verticale qu'horizontale dans l'image affichée (téléphone tenu droit).
 */
class CardOutlineDetector {

    fun detect(image: ImageProxy, encodeDebug: Boolean = false): OutlineResult {
        if (!ensureLoaded()) return OutlineResult(null, false, 0, 0, 0f, "OpenCV non chargé")

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
            // Plan Y -> Mat gris, robuste au rowStride/padding.
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
            padded.submat(0, rows, 0, w).copyTo(full)
            padded.release()

            val targetW = 480
            val scale = if (w > targetW) targetW.toFloat() / w else 1f
            if (scale < 1f) {
                Imgproc.resize(full, small, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA)
            } else {
                full.copyTo(small)
            }

            Imgproc.GaussianBlur(small, blur, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blur, edges, 50.0, 150.0)
            Imgproc.morphologyEx(
                edges, edges, Imgproc.MORPH_CLOSE,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)),
            )
            Imgproc.findContours(
                edges, contours, hierarchy,
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            val imgArea = (small.width() * small.height()).toDouble()
            val inv = 1f / scale
            val (uw, uh) = if (rotation == 90 || rotation == 270) h to w else w to h

            var diagArea = 0.0       // plus gros contour toute forme (areaPct)
            var diagQuadArea = 0.0   // plus gros 4-gone (pour bestRatio diag)
            var diagQuadRatio = 0f
            var bestQuadArea = 0.0
            var bestQuad: CardQuad? = null
            var bestReduced: List<Point>? = null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > diagArea) diagArea = area
                if (area < MIN_AREA_RATIO * imgArea) continue

                // Enveloppe convexe -> approx 4 coins (parallélogramme).
                val hullIdx = MatOfInt()
                Imgproc.convexHull(contour, hullIdx)
                val cpts = contour.toArray()
                val hullPts = hullIdx.toArray().map { cpts[it] }
                hullIdx.release()
                if (hullPts.size < 4) continue
                val hull2f = MatOfPoint2f(*hullPts.toTypedArray())
                val peri = Imgproc.arcLength(hull2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(hull2f, approx, 0.02 * peri, true)
                hull2f.release()

                if (approx.total() == 4L) {
                    val q = approx.toArray().toList()
                    val poly = MatOfPoint(*q.toTypedArray())
                    val convex = Imgproc.isContourConvex(poly)
                    poly.release()
                    if (convex) {
                        // Coins -> image droite (la notion « portrait » est définie là).
                        val up = q.map { rotatePoint((it.x * inv).toFloat(), (it.y * inv).toFloat(), rotation, w, h) }
                        val mids = sideMidpoints(up)
                        val segA = dist(mids[0], mids[2])
                        val segB = dist(mids[1], mids[3])
                        if (segA > 0f && segB > 0f) {
                            val ratio = min(segA, segB) / max(segA, segB)
                            if (area > diagQuadArea) { diagQuadArea = area; diagQuadRatio = ratio }

                            // Portrait : la plus longue médiane (= hauteur) doit être ~verticale.
                            val portrait = if (segA >= segB) {
                                abs(mids[2].y - mids[0].y) > abs(mids[2].x - mids[0].x)
                            } else {
                                abs(mids[3].y - mids[1].y) > abs(mids[3].x - mids[1].x)
                            }

                            if (portrait && abs(ratio - CARD_RATIO) <= TOLERANCE && area > bestQuadArea) {
                                bestQuadArea = area
                                bestQuad = CardQuad(up, mids, segA, segB, ratio, uw, uh)
                                bestReduced = q
                            }
                        }
                    }
                }
                approx.release()
            }

            val bestAreaPct = if (imgArea > 0) (diagArea / imgArea * 100).toInt() else 0
            val debugJpeg = if (encodeDebug) encodeDebugFrame(small, bestReduced) else null
            return OutlineResult(bestQuad, true, contours.size, bestAreaPct, diagQuadRatio, null, debugJpeg)
        } catch (t: Throwable) {
            return OutlineResult(null, true, 0, 0, 0f, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            full.release(); small.release(); blur.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Dessine, sur une copie couleur de la frame réduite, le quad vert + milieux rouges + valeurs. */
    private fun encodeDebugFrame(small: Mat, corners: List<Point>?): ByteArray {
        val color = Mat()
        Imgproc.cvtColor(small, color, Imgproc.COLOR_GRAY2BGR)
        if (corners != null) {
            Imgproc.polylines(color, listOf(MatOfPoint(*corners.toTypedArray())), true, GREEN, 2)
            val mids = sideMidpoints(corners.map { PointF(it.x.toFloat(), it.y.toFloat()) })
            mids.forEach { Imgproc.circle(color, Point(it.x.toDouble(), it.y.toDouble()), 4, RED, -1) }
            val segA = dist(mids[0], mids[2])
            val segB = dist(mids[1], mids[3])
            val r = min(segA, segB) / max(segA, segB)
            Imgproc.putText(
                color, "A=${segA.toInt()} B=${segB.toInt()} r=${"%.2f".format(r)}",
                Point(8.0, 20.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, GREEN, 1,
            )
        }
        val out = MatOfByte()
        Imgcodecs.imencode(".jpg", color, out, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 70))
        val bytes = out.toArray()
        out.release(); color.release()
        return bytes
    }

    private fun sideMidpoints(c: List<PointF>): List<PointF> = listOf(
        midF(c[0], c[1]), midF(c[1], c[2]), midF(c[2], c[3]), midF(c[3], c[0]),
    )

    private fun midF(a: PointF, b: PointF) = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    private fun dist(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun rotatePoint(x: Float, y: Float, rotation: Int, w: Int, h: Int): PointF = when (rotation) {
        90 -> PointF(h - 1 - y, x)
        180 -> PointF(w - 1 - x, h - 1 - y)
        270 -> PointF(y, w - 1 - x)
        else -> PointF(x, y)
    }

    companion object {
        private const val MIN_AREA_RATIO = 0.05
        private const val CARD_RATIO = 63f / 88f // ≈ 0,716
        private const val TOLERANCE = 0.05f
        private val GREEN = Scalar(0.0, 230.0, 118.0)
        private val RED = Scalar(0.0, 0.0, 255.0)

        @Volatile private var loaded = false

        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (!loaded) loaded = OpenCVLoader.initLocal()
            return loaded
        }
    }
}
