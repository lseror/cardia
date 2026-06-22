package com.serortech.cardia.vision

import android.content.Context
import android.util.Base64
import com.serortech.cardia.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class DetectionException(message: String) : Exception(message)

/**
 * Détecte si une carte à collectionner est visible dans l'image.
 *
 * L'app ne porte aucune clé IA : elle envoie l'image + sa clé de licence au
 * serveur (POST /detect), qui appelle le provider vision et renvoie {card}.
 */
class CardDetector(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun detect(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val settings = SettingsStore(ctx)
        val license = settings.licenseKey
        val baseUrl = settings.serverUrl
        if (baseUrl.isBlank()) throw DetectionException("URL du serveur absente. Renseigne-la dans les Réglages.")
        if (license.isBlank()) throw DetectionException("Clé de licence absente. Renseigne-la dans les Réglages.")

        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("image", JSONObject().apply {
                put("kind", "base64")
                put("mediaType", "image/jpeg")
                put("data", b64)
            })
        }
        val req = Request.Builder()
            .url("$baseUrl/detect")
            .addHeader("Authorization", "Bearer $license")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw DetectionException("Serveur injoignable : ${e.message}")
        }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getString("error") }
                    .getOrNull() ?: "HTTP ${it.code}"
                throw DetectionException(msg)
            }
            runCatching { JSONObject(raw).optBoolean("card", false) }
                .getOrElse { throw DetectionException("Réponse inattendue du serveur.") }
        }
    }
}
