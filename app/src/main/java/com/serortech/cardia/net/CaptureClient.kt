package com.serortech.cardia.net

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CaptureException(message: String) : Exception(message)

/** Archive l'image capturée en S3 (POST {baseUrl}/capture) avant identification. */
object CaptureClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Upload l'image ; renvoie la clé S3. Lève CaptureException en cas d'échec. */
    suspend fun upload(baseUrl: String, licenseKey: String, jpeg: ByteArray): String = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || licenseKey.isBlank()) throw CaptureException("Serveur ou licence absents")
        val payload = JSONObject().apply {
            put("image", JSONObject().apply {
                put("kind", "base64")
                put("mediaType", "image/jpeg")
                put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
            })
        }
        val req = Request.Builder()
            .url("$baseUrl/capture")
            .addHeader("Authorization", "Bearer $licenseKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw CaptureException("Serveur injoignable : ${e.message}")
        }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getString("error") }.getOrNull() ?: "HTTP ${it.code}"
                throw CaptureException(msg)
            }
            runCatching { JSONObject(raw).optString("key") }.getOrDefault("")
        }
    }
}
