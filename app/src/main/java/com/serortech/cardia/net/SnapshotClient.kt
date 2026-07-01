package com.serortech.cardia.net

import android.util.Base64
import com.serortech.cardia.vision.OutlineResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Snapshot de télémétrie déclenché par bouton : pousse vers {baseUrl}/snapshot la
 * capture d'écran (photo + surcouche) + l'intégralité des diagnostics du détecteur,
 * y compris les candidats rejetés. Le serveur le persiste (survit aux redémarrages).
 */
object SnapshotClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Envoie le snapshot (appel bloquant). Retourne null si OK, sinon un message d'erreur. */
    fun post(
        baseUrl: String,
        licenseKey: String,
        result: OutlineResult,
        jpeg: ByteArray,
        tolerance: Float,
        rotation: Int,
        appVersion: String,
    ): String? {
        if (baseUrl.isBlank()) return "URL du serveur absente."
        if (licenseKey.isBlank()) return "Clé de licence absente."

        val body = serialize(result).apply {
            put("tolerance", tolerance.toDouble())
            put("rotation", rotation)
            put("appVersion", appVersion)
            put("image", JSONObject().apply {
                put("kind", "base64")
                put("mediaType", "image/jpeg")
                put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            })
            // Frame brute (sans surcouche) pour diagnostic. Champ hors "image" → le
            // serveur la conserve telle quelle dans latest.json.
            result.rawJpeg?.let {
                put("rawImage", JSONObject().apply {
                    put("kind", "base64")
                    put("mediaType", "image/jpeg")
                    put("data", Base64.encodeToString(it, Base64.NO_WRAP))
                })
            }
        }
        val req = Request.Builder()
            .url("$baseUrl/snapshot")
            .addHeader("Authorization", "Bearer $licenseKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) null
                else {
                    val raw = resp.body?.string().orEmpty()
                    runCatching { JSONObject(raw).getString("error") }.getOrNull() ?: "HTTP ${resp.code}"
                }
            }
        } catch (e: Exception) {
            "Serveur injoignable : ${e.message}"
        }
    }

    /** OutlineResult complet (quads + candidats + diagnostics) en JSON, sans l'image. */
    private fun serialize(r: OutlineResult): JSONObject = JSONObject().apply {
        put("ocv", r.ocvLoaded)
        put("contourCount", r.contourCount)
        put("bestAreaPct", r.bestAreaPct)
        put("bestRatio", r.bestRatio.toDouble())
        put("quadCount", r.quads.size)
        put("error", r.error)
        r.frameColor?.let {
            put("frameColor", "#%02X%02X%02X".format((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF))
        }
        put("quads", JSONArray().apply {
            r.quads.forEach { q ->
                put(JSONObject().apply {
                    put("rounded", q.rounded)
                    put("segA", q.segA.toDouble())
                    put("segB", q.segB.toDouble())
                    put("ratio", q.ratio.toDouble())
                    put("srcW", q.srcWidth)
                    put("srcH", q.srcHeight)
                    put("corners", pointsArray(q.corners))
                    put("midpoints", pointsArray(q.midpoints))
                })
            }
        })
        put("candidates", JSONArray().apply {
            r.candidates.forEach { c ->
                put(JSONObject().apply {
                    put("area", c.area)
                    put("fillRatio", c.fillRatio)
                    put("ratio", c.ratio.toDouble())
                    put("portrait", c.portrait)
                    put("rounded", c.rounded)
                    put("accepted", c.accepted)
                    put("rejectReason", c.rejectReason)
                })
            }
        })
    }

    private fun pointsArray(pts: List<android.graphics.PointF>): JSONArray = JSONArray().apply {
        pts.forEach { put(JSONObject().apply { put("x", it.x.toDouble()); put("y", it.y.toDouble()) }) }
    }
}
