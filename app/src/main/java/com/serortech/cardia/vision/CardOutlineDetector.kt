package com.serortech.cardia.vision

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
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
import kotlin.math.roundToInt

/** Parallélogramme détecté, exprimé dans l'image « droite » (orientation d'affichage). */
data class CardQuad(
    val corners: List<PointF>,
    val midpoints: List<PointF>,
    val segA: Float,
    val segB: Float,
    val ratio: Float,
    val srcWidth: Int,
    val srcHeight: Int,
)

/** Résultat enrichi (diagnostics HUD + couleur du cadre + frame de debug). */
data class OutlineResult(
    val quads: List<CardQuad>,
    val ocvLoaded: Boolean,
    val contourCount: Int,
    val bestAreaPct: Int,
    val bestRatio: Float,
    val frameColor: Int?,    // couleur moyenne de l'anneau (cadre de la carte), ARGB ; null si aucun quad
    val error: String?,
    val debugJpeg: ByteArray? = null,
)

private class Cand(
    val area: Double,
    val reduced: List<Point>,
    val up: CardQuad,
    val cx: Float,
    val cy: Float,
)

/**
 * Détecte une ou plusieurs cartes EN LOCAL (OpenCV), sans réseau ni IA.
 * Parallélogrammes portrait au ratio ~63/88 (médianes), emboîtés conservés
 * (bord carte + cadre interne). Échantillonne la couleur de l'anneau entre les
 * deux plus grands quads (= le cadre coloré de la carte).
 * L'analyse fournit du RGBA (cf. ImageAnalysis OUTPUT_IMAGE_FORMAT_RGBA_8888).
 */
class CardOutlineDetector {

    fun detect(image: ImageProxy, encodeDebug: Boolean = false): OutlineResult {
        if (!ensureLoaded()) return OutlineResult(emptyList(), false, 0, 0, 0f, null, "OpenCV non chargé")

        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees

        val rgbaFull = Mat()
        val gray = Mat()
        val small = Mat()
        val bgrSmall = Mat()
        val blur = Mat()
        val edges = Mat()
        val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        try {
            // Plan RGBA -> Mat couleur (robuste au rowStride), puis gris.
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val rowPixels = rowStride / 4
            val rgbaPadded = Mat(h, rowPixels, CvType.CV_8UC4)
            rgbaPadded.put(0, 0, bytes)
            rgbaPadded.submat(0, h, 0, w).copyTo(rgbaFull)
            rgbaPadded.release()
            Imgproc.cvtColor(rgbaFull, gray, Imgproc.COLOR_RGBA2GRAY)

            val targetW = 480
            val scale = if (w > targetW) targetW.toFloat() / w else 1f
            if (scale < 1f) {
                Imgproc.resize(gray, small, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA)
            } else {
                gray.copyTo(small)
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

            var diagArea = 0.0
            var diagQuadArea = 0.0
            var diagQuadRatio = 0f
            val cands = ArrayList<Cand>()

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > diagArea) diagArea = area
                if (area < MIN_AREA_RATIO * imgArea) continue

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
                        val up = q.map { rotatePoint((it.x * inv).toFloat(), (it.y * inv).toFloat(), rotation, w, h) }
                        val mids = sideMidpoints(up)
                        val segA = dist(mids[0], mids[2])
                        val segB = dist(mids[1], mids[3])
                        if (segA > 0f && segB > 0f) {
                            val ratio = min(segA, segB) / max(segA, segB)
                            if (area > diagQuadArea) { diagQuadArea = area; diagQuadRatio = ratio }
                            val portrait = if (segA >= segB) {
                                abs(mids[2].y - mids[0].y) > abs(mids[2].x - mids[0].x)
                            } else {
                                abs(mids[3].y - mids[1].y) > abs(mids[3].x - mids[1].x)
                            }
                            if (portrait && abs(ratio - CARD_RATIO) <= TOLERANCE) {
                                val cx = q.sumOf { it.x }.toFloat() / 4f
                                val cy = q.sumOf { it.y }.toFloat() / 4f
                                cands.add(Cand(area, q, CardQuad(up, mids, segA, segB, ratio, uw, uh), cx, cy))
                            }
                        }
                    }
                }
                approx.release()
            }

            cands.sortByDescending { it.area }
            val frameDiag = hypot(small.width().toDouble(), small.height().toDouble())
            val kept = ArrayList<Cand>()
            for (c in cands) {
                val dup = kept.any { k ->
                    val d = hypot((c.cx - k.cx).toDouble(), (c.cy - k.cy).toDouble())
                    d < DUP_CENTER_FRAC * frameDiag &&
                        min(c.area, k.area) / max(c.area, k.area) > DUP_AREA_FRAC
                }
                if (!dup) kept.add(c)
                if (kept.size >= MAX_QUADS) break
            }

            // Couleur de l'anneau (cadre) : entre les 2 plus grands quads (sinon bande interne).
            var frameColor: Int? = null
            if (kept.isNotEmpty()) {
                Imgproc.resize(rgbaFull, bgrSmall, small.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(bgrSmall, bgrSmall, Imgproc.COLOR_RGBA2RGB)
                frameColor = sampleRingColor(bgrSmall, kept)
            }

            val bestAreaPct = if (imgArea > 0) (diagArea / imgArea * 100).toInt() else 0
            val debugJpeg = if (encodeDebug) encodeDebugFrame(small, kept.map { it.reduced }, frameColor) else null
            return OutlineResult(kept.map { it.up }, true, contours.size, bestAreaPct, diagQuadRatio, frameColor, null, debugJpeg)
        } catch (t: Throwable) {
            return OutlineResult(emptyList(), true, 0, 0, 0f, null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            rgbaFull.release(); gray.release(); small.release(); bgrSmall.release()
            blur.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
        }
    }

    /** Couleur moyenne (RGB) de l'anneau entre le plus grand quad et le suivant (ou bande interne). */
    private fun sampleRingColor(rgb: Mat, kept: List<Cand>): Int? {
        val mask = Mat.zeros(rgb.size(), CvType.CV_8UC1)
        try {
            val outer = MatOfPoint(*kept[0].reduced.toTypedArray())
            Imgproc.fillConvexPoly(mask, outer, Scalar(255.0))
            val inner = if (kept.size >= 2) {
                MatOfPoint(*kept[1].reduced.toTypedArray())
            } else {
                // Pas de cadre interne détecté : on creuse une bande à 85% vers le centre.
                MatOfPoint(*shrink(kept[0].reduced, kept[0].cx, kept[0].cy, 0.85f).toTypedArray())
            }
            Imgproc.fillConvexPoly(mask, inner, Scalar(0.0))
            inner.release(); outer.release()
            val m = Core.mean(rgb, mask) // RGB
            val r = m.`val`[0].roundToInt().coerceIn(0, 255)
            val g = m.`val`[1].roundToInt().coerceIn(0, 255)
            val b = m.`val`[2].roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } finally {
            mask.release()
        }
    }

    private fun shrink(pts: List<Point>, cx: Float, cy: Float, f: Float): List<Point> =
        pts.map { Point(cx + (it.x - cx) * f, cy + (it.y - cy) * f) }

    private fun encodeDebugFrame(small: Mat, quads: List<List<Point>>, frameColor: Int?): ByteArray {
        val color = Mat()
        Imgproc.cvtColor(small, color, Imgproc.COLOR_GRAY2BGR)
        quads.forEachIndexed { idx, corners ->
            Imgproc.polylines(color, listOf(MatOfPoint(*corners.toTypedArray())), true, GREEN, 2)
            val mids = sideMidpoints(corners.map { PointF(it.x.toFloat(), it.y.toFloat()) })
            mids.forEach { Imgproc.circle(color, Point(it.x.toDouble(), it.y.toDouble()), 4, RED, -1) }
            val segA = dist(mids[0], mids[2])
            val segB = dist(mids[1], mids[3])
            val r = min(segA, segB) / max(segA, segB)
            Imgproc.putText(
                color, "A=${segA.toInt()} B=${segB.toInt()} r=${"%.2f".format(r)}",
                Point(8.0, 20.0 + idx * 18.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, GREEN, 1,
            )
        }
        if (frameColor != null) {
            val r = (frameColor shr 16) and 0xFF
            val g = (frameColor shr 8) and 0xFF
            val b = frameColor and 0xFF
            Imgproc.rectangle(color, Point(8.0, 28.0), Point(40.0, 60.0), Scalar(b.toDouble(), g.toDouble(), r.toDouble()), -1)
            Imgproc.putText(
                color, "#%02X%02X%02X".format(r, g, b),
                Point(46.0, 52.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, GREEN, 1,
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
        private const val CARD_RATIO = 63f / 88f
        private const val TOLERANCE = 0.05f
        private const val DUP_CENTER_FRAC = 0.06
        private const val DUP_AREA_FRAC = 0.90
        private const val MAX_QUADS = 4
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
