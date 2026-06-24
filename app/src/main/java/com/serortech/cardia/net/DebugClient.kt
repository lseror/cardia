package com.serortech.cardia.net

import android.util.Base64
import com.serortech.cardia.vision.OutlineResult
import okhttp3.Callback
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Télémétrie de mise au point du cadre OpenCV : pousse (fire-and-forget) la
 * dernière frame traitée + diagnostics vers {baseUrl}/debug. TEMPORAIRE.
 */
object DebugClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val noop = object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) { response.close() }
    }

    fun post(baseUrl: String, licenseKey: String, frames: Int, r: OutlineResult) {
        if (baseUrl.isBlank() || licenseKey.isBlank()) return
        val body = JSONObject().apply {
            put("frames", frames)
            put("ocv", r.ocvLoaded)
            put("contourCount", r.contourCount)
            put("bestAreaPct", r.bestAreaPct)
            put("bestRatio", r.bestRatio.toDouble())
            put("error", r.error)
            put("quadCount", r.quads.size)
            r.quads.firstOrNull()?.let {
                put("srcW", it.srcWidth)
                put("srcH", it.srcHeight)
            }
            r.debugJpeg?.let {
                put("image", JSONObject().apply {
                    put("kind", "base64")
                    put("data", Base64.encodeToString(it, Base64.NO_WRAP))
                })
            }
        }
        val req = Request.Builder()
            .url("$baseUrl/debug")
            .addHeader("Authorization", "Bearer $licenseKey")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(noop)
    }
}
