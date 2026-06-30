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
    /** Coins réellement arrondis (bord de carte) vs vifs (cadre interne) → pilote le rendu. */
    val rounded: Boolean = false,
)

/**
 * Diagnostic d'un contour candidat (au-dessus du seuil d'aire), rempli uniquement
 * en mode snapshot pour comprendre pourquoi un cadre est accepté ou rejeté.
 */
data class CardCandidate(
    val area: Double,
    val fillRatio: Double,
    val ratio: Float,
    val portrait: Boolean,
    val rounded: Boolean,
    val accepted: Boolean,
    val rejectReason: String,  // "ok" | "fill" | "ratio" | "portrait" | "degenerate"
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
    /** Candidats diagnostiqués (snapshot uniquement). */
    val candidates: List<CardCandidate> = emptyList(),
    /** Composite couleur (frame caméra + surcouche) pour le snapshot ; null hors snapshot. */
    val snapshotJpeg: ByteArray? = null,
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

    /** Tolérance sur le ratio (±), réglable à chaud depuis l'UI. Lue à chaque frame. */
    @Volatile var tolerance: Float = DEFAULT_TOLERANCE

    fun detect(image: ImageProxy, encodeDebug: Boolean = false, snapshot: Boolean = false): OutlineResult {
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
            val candDiags = if (snapshot) ArrayList<CardCandidate>() else null
            // Rects des candidats rejetés à dessiner sur le composite (snapshot).
            val candDraw = if (snapshot) ArrayList<Pair<List<Point>, String>>() else null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > diagArea) diagArea = area
                if (area < MIN_AREA_RATIO * imgArea) continue

                // Rectangle tourné d'aire minimale : fournit 4 coins même quand les
                // coins de la carte sont arrondis (ce qu'approxPolyDP ne réduisait pas
                // à 4 sommets, d'où le bord externe ignoré jusqu'ici).
                val cpts = contour.toArray()
                val c2f = MatOfPoint2f(*cpts)
                val rr = Imgproc.minAreaRect(c2f)
                c2f.release()
                val rectArea = rr.size.width * rr.size.height
                val box = arrayOfNulls<Point>(4)
                rr.points(box)
                val q = box.filterNotNull()
                if (rectArea <= 0.0 || q.size != 4) {
                    candDiags?.add(CardCandidate(area, 0.0, 0f, false, false, false, "degenerate"))
                    continue
                }

                // Taux de remplissage : rejette les blobs non rectangulaires. Les coins
                // arrondis ne coûtent que ~0,1% d'aire, un vrai cadre reste donc >> seuil.
                val fill = area / rectArea
                val up = q.map { rotatePoint((it.x * inv).toFloat(), (it.y * inv).toFloat(), rotation, w, h) }
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

                // Coins réellement arrondis (bord carte) vs vifs (cadre interne) :
                // écart moyen entre chaque coin du rect et le contour réel. Coin vif → ~0 ;
                // coin coupé par l'arrondi → > seuil. Non bloquant : sert au rendu.
                val rPx = CARD_CORNER_RADIUS_FRAC.toDouble() * min(rr.size.width, rr.size.height)
                val rounded = q.map { minDist(it, cpts.toList()) }.average() > ROUND_GAP_FRAC * rPx

                val reason = when {
                    segA <= 0f || segB <= 0f -> "degenerate"
                    fill < FILL_MIN -> "fill"
                    !portrait -> "portrait"
                    abs(ratio - CARD_RATIO) > tolerance -> "ratio"
                    else -> "ok"
                }
                candDiags?.add(CardCandidate(area, fill, ratio, portrait, rounded, reason == "ok", reason))
                if (reason != "ok") {
                    candDraw?.add(q to reason)
                    continue
                }

                val cx = q.sumOf { it.x }.toFloat() / 4f
                val cy = q.sumOf { it.y }.toFloat() / 4f
                cands.add(Cand(area, q, CardQuad(up, mids, segA, segB, ratio, uw, uh, rounded), cx, cy))
            }
            // Plus gros candidats d'abord (utile pour lire le diagnostic).
            candDiags?.sortByDescending { it.area }

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
            // Composite couleur pour le snapshot : vraie frame caméra + cadres acceptés
            // (vert) + candidats rejetés (orange + raison). Remplace la copie d'écran
            // (PixelCopy ne capture pas la Surface caméra → image noire).
            val snapshotJpeg = if (snapshot) {
                Imgproc.resize(rgbaFull, bgrSmall, small.size(), 0.0, 0.0, Imgproc.INTER_AREA)
                Imgproc.cvtColor(bgrSmall, bgrSmall, Imgproc.COLOR_RGBA2BGR)
                encodeSnapshotFrame(
                    bgrSmall, kept.map { it.reduced }, candDraw ?: emptyList(),
                    contours.size, kept.size, bestAreaPct,
                )
            } else {
                null
            }
            return OutlineResult(
                kept.map { it.up }, true, contours.size, bestAreaPct, diagQuadRatio, frameColor, null,
                debugJpeg, candDiags ?: emptyList(), snapshotJpeg,
            )
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

    /**
     * Composite couleur du snapshot : frame caméra (BGR) + cadres acceptés (vert) +
     * rects des candidats rejetés (orange) annotés de leur raison, + bandeau résumé.
     */
    private fun encodeSnapshotFrame(
        colorBgr: Mat,
        accepted: List<List<Point>>,
        rejected: List<Pair<List<Point>, String>>,
        contourCount: Int,
        quadCount: Int,
        bestAreaPct: Int,
    ): ByteArray {
        rejected.forEach { (corners, reason) ->
            Imgproc.polylines(colorBgr, listOf(MatOfPoint(*corners.toTypedArray())), true, ORANGE, 2)
            val c = corners.firstOrNull()
            if (c != null) {
                Imgproc.putText(
                    colorBgr, reason, Point(c.x + 2, c.y - 4),
                    Imgproc.FONT_HERSHEY_SIMPLEX, 0.45, ORANGE, 1,
                )
            }
        }
        accepted.forEach { corners ->
            Imgproc.polylines(colorBgr, listOf(MatOfPoint(*corners.toTypedArray())), true, GREEN, 2)
        }
        Imgproc.putText(
            colorBgr, "q=$quadCount cnt=$contourCount area=$bestAreaPct%",
            Point(8.0, 18.0), Imgproc.FONT_HERSHEY_SIMPLEX, 0.5, GREEN, 1,
        )
        val out = MatOfByte()
        Imgcodecs.imencode(".jpg", colorBgr, out, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 80))
        val bytes = out.toArray()
        out.release()
        return bytes
    }

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

    /** Distance minimale du point [p] à l'ensemble de points [pts] (contour). */
    private fun minDist(p: Point, pts: List<Point>): Double {
        var best = Double.MAX_VALUE
        for (q in pts) {
            val d = hypot(p.x - q.x, p.y - q.y)
            if (d < best) best = d
        }
        return best
    }

    private fun rotatePoint(x: Float, y: Float, rotation: Int, w: Int, h: Int): PointF = when (rotation) {
        90 -> PointF(h - 1 - y, x)
        180 -> PointF(w - 1 - x, h - 1 - y)
        270 -> PointF(y, w - 1 - x)
        else -> PointF(x, y)
    }

    companion object {
        private const val MIN_AREA_RATIO = 0.05
        private const val CARD_RATIO = 63f / 88f
        const val DEFAULT_TOLERANCE = 0.05f

        /** Rayon de courbure d'une carte 63×88 (~3 mm) rapporté au petit côté. */
        const val CARD_CORNER_RADIUS_FRAC = 0.048f
        /** Seuil de remplissage contour/rectMin : en dessous, le blob n'est pas rectangulaire. */
        private const val FILL_MIN = 0.82
        /** Un coin est jugé arrondi si l'écart moyen au contour dépasse cette fraction du rayon. */
        private const val ROUND_GAP_FRAC = 0.25
        private const val DUP_CENTER_FRAC = 0.06
        private const val DUP_AREA_FRAC = 0.90
        private const val MAX_QUADS = 4
        private val GREEN = Scalar(0.0, 230.0, 118.0)
        private val RED = Scalar(0.0, 0.0, 255.0)
        private val ORANGE = Scalar(0.0, 165.0, 255.0) // BGR

        @Volatile private var loaded = false

        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (!loaded) loaded = OpenCVLoader.initLocal()
            return loaded
        }
    }
}
