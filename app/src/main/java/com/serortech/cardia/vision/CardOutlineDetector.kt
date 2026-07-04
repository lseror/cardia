package com.serortech.cardia.vision

import android.graphics.PointF
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
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

/** Parallélogramme détecté, exprimé dans l'image « droite » (portrait, orientation d'affichage). */
data class CardQuad(
    val corners: List<PointF>,
    val midpoints: List<PointF>,
    val segA: Float,
    val segB: Float,
    val ratio: Float,
    val srcWidth: Int,
    val srcHeight: Int,
    /** Coins réellement arrondis (bord de carte) vs vifs (cadre interne) → pilote le rendu. */
    val rounded: Boolean = false,
)

/** Diagnostic d'un contour candidat (snapshot uniquement) : pourquoi accepté/rejeté. */
data class CardCandidate(
    val area: Double,
    val fillRatio: Double,
    val ratio: Float,
    val portrait: Boolean,
    val rounded: Boolean,
    val accepted: Boolean,
    val rejectReason: String,  // "ok" | "fill" | "ratio" | "portrait" | "degenerate"
)

/** Résultat enrichi (diagnostics + composite + frame brute). */
data class OutlineResult(
    val quads: List<CardQuad>,
    val ocvLoaded: Boolean,
    val contourCount: Int,
    val bestAreaPct: Int,
    val bestRatio: Float,
    val frameColor: Int?,
    val error: String?,
    val debugJpeg: ByteArray? = null,
    val candidates: List<CardCandidate> = emptyList(),
    val snapshotJpeg: ByteArray? = null,
    val rawJpeg: ByteArray? = null,
    /** Netteté de la carte (variance du Laplacien) ; null si pas de carte. */
    val sharpness: Float? = null,
)

/** Candidat en cours d'évaluation : coins en coords « small » (portrait) + quad upright. */
private class Cand(
    val area: Double,
    val corners: List<Point>,   // small coords (portrait)
    val up: CardQuad,
    val cx: Float,
    val cy: Float,
)

/**
 * Détection locale (OpenCV), pipeline « coarse-to-fine » et **portrait** :
 *  1. On redresse la frame (rotation upright) → tout travaille en portrait.
 *  2. EXTÉRIEUR : segmentation par saturation (carte colorée vs fond neutre) +
 *     edges en secours ; pour chaque contour on prend l'ENVELOPPE CONVEXE (robuste
 *     aux bords fragmentés/arrondis) → minAreaRect → validation ratio/portrait/fill.
 *  3. INTÉRIEUR : rectification perspective de la carte puis recherche du cadre
 *     interne (protégée ; en cas d'échec on garde juste l'extérieur).
 */
class CardOutlineDetector {

    /** Tolérance sur le ratio (±), réglable à chaud depuis l'UI. */
    @Volatile var tolerance: Float = DEFAULT_TOLERANCE

    fun detect(image: ImageProxy, encodeDebug: Boolean = false, snapshot: Boolean = false): OutlineResult {
        if (!ensureLoaded()) return OutlineResult(emptyList(), false, 0, 0, 0f, null, "OpenCV non chargé")

        val w = image.width
        val h = image.height
        val rotation = image.imageInfo.rotationDegrees

        val rgbaSensor = Mat()
        val rgba = Mat()
        val bgr = Mat()
        val gray = Mat()
        val blur = Mat()
        val hsv = Mat()
        val sat = Mat()
        val satMask = Mat()
        val valueMask = Mat()
        val edges = Mat()
        try {
            // Plan RGBA -> Mat (robuste au rowStride).
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val buf = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            val rowPixels = rowStride / 4
            val rgbaPadded = Mat(h, rowPixels, CvType.CV_8UC4)
            rgbaPadded.put(0, 0, bytes)
            rgbaPadded.submat(0, h, 0, w).copyTo(rgbaSensor)
            rgbaPadded.release()

            // 1) Redressement portrait.
            when (rotation) {
                90 -> Core.rotate(rgbaSensor, rgba, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(rgbaSensor, rgba, Core.ROTATE_180)
                270 -> Core.rotate(rgbaSensor, rgba, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> rgbaSensor.copyTo(rgba)
            }
            val uw = rgba.cols()
            val uh = rgba.rows()
            val longSide = max(uw, uh)
            val scale = if (longSide > WORK) WORK.toFloat() / longSide else 1f
            val work = Mat()
            if (scale < 1f) {
                Imgproc.resize(rgba, work, Size(), scale.toDouble(), scale.toDouble(), Imgproc.INTER_AREA)
            } else {
                rgba.copyTo(work)
            }
            Imgproc.cvtColor(work, bgr, Imgproc.COLOR_RGBA2BGR)
            work.release()
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV)
            Core.extractChannel(hsv, sat, 1)

            val sw = bgr.cols()
            val sh = bgr.rows()
            val imgArea = (sw * sh).toDouble()
            val inv = 1f / scale

            // Masque saturation (carte colorée vs fond neutre).
            Imgproc.threshold(sat, satMask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(satMask, satMask, Imgproc.MORPH_CLOSE, kernel(15))
            Imgproc.morphologyEx(satMask, satMask, Imgproc.MORPH_OPEN, kernel(7))
            // Masque valeur (carte claire vs fond sombre) — cartes bord gris sur fond foncé.
            Imgproc.threshold(gray, valueMask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(valueMask, valueMask, Imgproc.MORPH_CLOSE, kernel(15))
            Imgproc.morphologyEx(valueMask, valueMask, Imgproc.MORPH_OPEN, kernel(9))
            // Edges (secours, robustes à un fond coloré).
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blur, edges, 50.0, 150.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel(5))

            val candDiags = if (snapshot) ArrayList<CardCandidate>() else null
            val candDraw = if (snapshot) ArrayList<Pair<List<Point>, String>>() else null
            var diagArea = 0.0
            var diagQuadArea = 0.0
            var diagQuadRatio = 0f
            var contourCount = 0
            val cands = ArrayList<Cand>()

            // 2) EXTÉRIEUR : candidats depuis saturation, valeur, PUIS edges.
            for (src in listOf(satMask, valueMask, edges)) {
                val cs = ArrayList<MatOfPoint>()
                val hi = Mat()
                Imgproc.findContours(src, cs, hi, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                hi.release()
                contourCount += cs.size
                for (contour in cs) {
                    val area = Imgproc.contourArea(contour)
                    if (area > diagArea) diagArea = area
                    if (area < MIN_AREA_RATIO * imgArea) { contour.release(); continue }

                    val cpts = contour.toArray()
                    // Enveloppe convexe : rattrape les bords fragmentés/arrondis.
                    val hullIdx = MatOfInt()
                    Imgproc.convexHull(contour, hullIdx)
                    val hullPts = hullIdx.toArray().map { cpts[it] }
                    hullIdx.release()
                    if (hullPts.size < 3) { contour.release(); continue }
                    val hullArea = Imgproc.contourArea(MatOfPoint(*hullPts.toTypedArray()))

                    val c2f = MatOfPoint2f(*cpts)
                    val rr = Imgproc.minAreaRect(c2f)
                    c2f.release()
                    val rectArea = rr.size.width * rr.size.height
                    val box = arrayOfNulls<Point>(4)
                    rr.points(box)
                    val q = box.filterNotNull()
                    if (rectArea <= 0.0 || q.size != 4) {
                        candDiags?.add(CardCandidate(area, 0.0, 0f, false, false, false, "degenerate"))
                        contour.release(); continue
                    }

                    val fill = hullArea / rectArea  // remplissage sur l'enveloppe convexe
                    val up = q.map { PointF((it.x * inv).toFloat(), (it.y * inv).toFloat()) }
                    val mids = sideMidpoints(up)
                    val segA = dist(mids[0], mids[2])
                    val segB = dist(mids[1], mids[3])
                    val ratio = if (segA > 0f && segB > 0f) min(segA, segB) / max(segA, segB) else 0f
                    if (segA > 0f && segB > 0f && area > diagQuadArea) { diagQuadArea = area; diagQuadRatio = ratio }
                    val portrait = if (segA >= segB) {
                        abs(mids[2].y - mids[0].y) > abs(mids[2].x - mids[0].x)
                    } else {
                        abs(mids[3].y - mids[1].y) > abs(mids[3].x - mids[1].x)
                    }
                    val rPx = CARD_CORNER_RADIUS_FRAC.toDouble() * min(rr.size.width, rr.size.height)
                    val rounded = q.map { minDist(it, cpts.toList()) }.average() > ROUND_GAP_FRAC * rPx

                    val reason = when {
                        segA <= 0f || segB <= 0f -> "degenerate"
                        rectArea > MAX_AREA_RATIO * imgArea -> "toobig"  // quasi pleine image = fond
                        fill < FILL_MIN -> "fill"
                        !portrait -> "portrait"
                        abs(ratio - CARD_RATIO) > tolerance -> "ratio"
                        else -> "ok"
                    }
                    candDiags?.add(CardCandidate(area, fill, ratio, portrait, rounded, reason == "ok", reason))
                    if (reason != "ok") { candDraw?.add(q to reason); contour.release(); continue }

                    val cx = q.sumOf { it.x }.toFloat() / 4f
                    val cy = q.sumOf { it.y }.toFloat() / 4f
                    cands.add(Cand(area, q, CardQuad(up, mids, segA, segB, ratio, uw, uh, rounded = true), cx, cy))
                    contour.release()
                }
            }
            candDiags?.sortByDescending { it.area }
            cands.sortByDescending { it.area }
            val outer = cands.firstOrNull()

            // 3) INTÉRIEUR : rectification + recherche du cadre interne (protégé).
            var inner: CardQuad? = null
            if (outer != null) {
                inner = try {
                    detectInner(bgr, outer.corners, inv, uw, uh)
                } catch (t: Throwable) {
                    null
                }
            }

            val quads = ArrayList<CardQuad>()
            outer?.let { quads.add(it.up) }
            inner?.let { quads.add(it) }

            // Couleur de l'anneau (entre extérieur et intérieur) si les deux présents.
            var frameColor: Int? = null
            if (outer != null) {
                val innerCorners = inner?.let { toSmallCorners(it, scale) }
                    ?: shrink(outer.corners, outer.cx, outer.cy, 0.85f)
                frameColor = sampleRingColor(bgr, outer.corners, innerCorners)
            }

            val bestAreaPct = if (imgArea > 0) (diagArea / imgArea * 100).toInt() else 0
            var snapshotJpeg: ByteArray? = null
            var rawJpeg: ByteArray? = null
            if (snapshot) {
                rawJpeg = encodeJpeg(bgr)  // frame propre (portrait) AVANT surcouche
                snapshotJpeg = encodeSnapshotFrame(
                    bgr,
                    outer?.corners,
                    inner?.let { toSmallCorners(it, scale) },
                    candDraw ?: emptyList(),
                    contourCount, quads.size, bestAreaPct,
                )
            }
            val sharpness = if (outer != null) {
                try { cropSharpness(gray, outer.corners) } catch (t: Throwable) { null }
            } else {
                null
            }
            return OutlineResult(
                quads, true, contourCount, bestAreaPct, diagQuadRatio, frameColor, null,
                null, candDiags ?: emptyList(), snapshotJpeg, rawJpeg, sharpness,
            )
        } catch (t: Throwable) {
            return OutlineResult(emptyList(), true, 0, 0, 0f, null, t.javaClass.simpleName + ": " + (t.message ?: ""))
        } finally {
            rgbaSensor.release(); rgba.release(); bgr.release(); gray.release(); blur.release()
            hsv.release(); sat.release(); satMask.release(); valueMask.release(); edges.release()
        }
    }

    /** Rectifie la carte (perspective) puis cherche le cadre interne ; null si rien de fiable. */
    private fun detectInner(bgr: Mat, outerSmall: List<Point>, inv: Float, uw: Int, uh: Int): CardQuad? {
        val ordered = orderCorners(outerSmall)  // TL, TR, BR, BL
        val wr = INNER_RECT_W
        val hr = (INNER_RECT_W * 88.0 / 63.0)
        val srcM = MatOfPoint2f(ordered[0], ordered[1], ordered[2], ordered[3])
        val dstM = MatOfPoint2f(Point(0.0, 0.0), Point(wr - 1, 0.0), Point(wr - 1, hr - 1), Point(0.0, hr - 1))
        val m = Imgproc.getPerspectiveTransform(srcM, dstM)
        srcM.release(); dstM.release()
        val warp = Mat()
        val wg = Mat()
        val we = Mat()
        try {
            Imgproc.warpPerspective(bgr, warp, m, Size(wr, hr))
            Imgproc.cvtColor(warp, wg, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(wg, wg, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(wg, we, 40.0, 120.0)
            Imgproc.morphologyEx(we, we, Imgproc.MORPH_CLOSE, kernel(5))
            Imgproc.dilate(we, we, kernel(3))
            val cs = ArrayList<MatOfPoint>()
            val hi = Mat()
            Imgproc.findContours(we, cs, hi, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hi.release()
            val cardArea = wr * hr
            val cx = wr / 2.0
            val cy = hr / 2.0
            var best: org.opencv.core.RotatedRect? = null
            var bestArea = 0.0
            for (c in cs) {
                val a = Imgproc.contourArea(c)
                if (a < 0.10 * cardArea || a > 0.92 * cardArea) { c.release(); continue }
                val c2f = MatOfPoint2f(*c.toArray())
                val rr = Imgproc.minAreaRect(c2f)
                c2f.release()
                val (bw, bh) = rr.size.width to rr.size.height
                if (bw < 5 || bh < 5) { c.release(); continue }
                val hullIdx = MatOfInt(); Imgproc.convexHull(c, hullIdx)
                val cpts = c.toArray()
                val hullPts = hullIdx.toArray().map { cpts[it] }; hullIdx.release()
                val fillH = Imgproc.contourArea(MatOfPoint(*hullPts.toTypedArray())) / (bw * bh)
                val centered = abs(rr.center.x - cx) < 0.15 * wr && abs(rr.center.y - cy) < 0.15 * hr
                if (fillH > 0.80 && centered && a > bestArea) { bestArea = a; best = rr }
                c.release()
            }
            val rr = best ?: return null
            val box = arrayOfNulls<Point>(4); rr.points(box)
            val warpPts = MatOfPoint2f(*box.filterNotNull().toTypedArray())
            val minv = Mat(); Core.invert(m, minv)
            val smallPts = MatOfPoint2f()
            Core.perspectiveTransform(warpPts, smallPts, minv)
            warpPts.release(); minv.release()
            val up = smallPts.toArray().map { PointF((it.x * inv).toFloat(), (it.y * inv).toFloat()) }
            smallPts.release()
            if (up.size != 4) return null
            val mids = sideMidpoints(up)
            val segA = dist(mids[0], mids[2]); val segB = dist(mids[1], mids[3])
            val ratio = if (segA > 0f && segB > 0f) min(segA, segB) / max(segA, segB) else 0f
            return CardQuad(up, mids, segA, segB, ratio, uw, uh, rounded = false)
        } finally {
            warp.release(); wg.release(); we.release(); m.release()
        }
    }

    /** Coins d'un quad upright ramenés en coords « small » (pour dessin/masque). */
    private fun toSmallCorners(q: CardQuad, scale: Float): List<Point> =
        q.corners.map { Point((it.x * scale).toDouble(), (it.y * scale).toDouble()) }

    /** Ordonne 4 coins en TL, TR, BR, BL. */
    private fun orderCorners(pts: List<Point>): List<Point> {
        val byY = pts.sortedBy { it.y }
        val top = byY.take(2).sortedBy { it.x }
        val bot = byY.drop(2).sortedBy { it.x }
        return listOf(top[0], top[1], bot[1], bot[0])
    }

    /** Couleur moyenne (BGR→ARGB) de l'anneau entre extérieur et intérieur. */
    private fun sampleRingColor(bgr: Mat, outer: List<Point>, inner: List<Point>): Int? {
        val mask = Mat.zeros(bgr.size(), CvType.CV_8UC1)
        try {
            val o = MatOfPoint(*outer.toTypedArray()); Imgproc.fillConvexPoly(mask, o, Scalar(255.0)); o.release()
            val i = MatOfPoint(*inner.toTypedArray()); Imgproc.fillConvexPoly(mask, i, Scalar(0.0)); i.release()
            val m = Core.mean(bgr, mask) // BGR
            val b = m.`val`[0].roundToInt().coerceIn(0, 255)
            val g = m.`val`[1].roundToInt().coerceIn(0, 255)
            val r = m.`val`[2].roundToInt().coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        } finally {
            mask.release()
        }
    }

    private fun shrink(pts: List<Point>, cx: Float, cy: Float, f: Float): List<Point> =
        pts.map { Point(cx + (it.x - cx) * f, cy + (it.y - cy) * f) }

    /** Composite : frame (BGR) + extérieur (vert) + intérieur (bleu) + rejets (orange) + résumé. */
    private fun encodeSnapshotFrame(
        colorBgr: Mat,
        outer: List<Point>?,
        inner: List<Point>?,
        rejected: List<Pair<List<Point>, String>>,
        contourCount: Int,
        quadCount: Int,
        bestAreaPct: Int,
    ): ByteArray {
        rejected.forEach { (corners, reason) ->
            Imgproc.polylines(colorBgr, listOf(MatOfPoint(*corners.toTypedArray())), true, ORANGE, 1)
            corners.firstOrNull()?.let {
                Imgproc.putText(colorBgr, reason, Point(it.x + 2, it.y - 4), Imgproc.FONT_HERSHEY_SIMPLEX, 0.4, ORANGE, 1)
            }
        }
        outer?.let { Imgproc.polylines(colorBgr, listOf(MatOfPoint(*it.toTypedArray())), true, GREEN, 2) }
        inner?.let { Imgproc.polylines(colorBgr, listOf(MatOfPoint(*it.toTypedArray())), true, BLUE, 2) }
        Imgproc.putText(
            colorBgr, "q=$quadCount cnt=$contourCount area=$bestAreaPct%",
            Point(8.0, 18.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, GREEN, 1,
        )
        return encodeJpeg(colorBgr)
    }

    private fun encodeJpeg(mat: Mat): ByteArray {
        val out = MatOfByte()
        Imgcodecs.imencode(".jpg", mat, out, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80))
        val bytes = out.toArray()
        out.release()
        return bytes
    }

    private fun sideMidpoints(c: List<PointF>): List<PointF> = listOf(
        midF(c[0], c[1]), midF(c[1], c[2]), midF(c[2], c[3]), midF(c[3], c[0]),
    )

    private fun midF(a: PointF, b: PointF) = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
    private fun dist(a: PointF, b: PointF) = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()

    private fun minDist(p: Point, pts: List<Point>): Double {
        var best = Double.MAX_VALUE
        for (q in pts) {
            val d = hypot(p.x - q.x, p.y - q.y)
            if (d < best) best = d
        }
        return best
    }

    private fun kernel(n: Int): Mat =
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(n.toDouble(), n.toDouble()))

    /** Netteté = variance du Laplacien sur la carte (boîte englobante des coins). */
    private fun cropSharpness(gray: Mat, corners: List<Point>): Float {
        val x0 = corners.minOf { it.x }.toInt().coerceIn(0, gray.cols() - 1)
        val y0 = corners.minOf { it.y }.toInt().coerceIn(0, gray.rows() - 1)
        val x1 = corners.maxOf { it.x }.toInt().coerceIn(x0 + 1, gray.cols())
        val y1 = corners.maxOf { it.y }.toInt().coerceIn(y0 + 1, gray.rows())
        val crop = gray.submat(y0, y1, x0, x1)
        val lap = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        try {
            Imgproc.Laplacian(crop, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, std)
            val s = std.get(0, 0)[0]
            return (s * s).toFloat()
        } finally {
            lap.release(); mean.release(); std.release()
        }
    }

    companion object {
        private const val WORK = 640
        private const val MIN_AREA_RATIO = 0.05
        private const val MAX_AREA_RATIO = 0.85
        const val CARD_RATIO = 63f / 88f
        const val DEFAULT_TOLERANCE = 0.05f
        const val CARD_CORNER_RADIUS_FRAC = 0.048f
        private const val FILL_MIN = 0.82
        private const val ROUND_GAP_FRAC = 0.25
        private const val INNER_RECT_W = 300.0
        private val GREEN = Scalar(0.0, 230.0, 118.0)
        private val BLUE = Scalar(255.0, 80.0, 0.0)   // BGR
        private val ORANGE = Scalar(0.0, 165.0, 255.0) // BGR

        @Volatile private var loaded = false

        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (!loaded) loaded = OpenCVLoader.initLocal()
            return loaded
        }
    }
}
