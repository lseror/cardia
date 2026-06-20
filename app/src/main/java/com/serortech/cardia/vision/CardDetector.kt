package com.serortech.cardia.vision

import android.content.Context
import android.util.Base64
import com.serortech.cardia.settings.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DetectionException(message: String) : Exception(message)

/** Détecte via OpenAI vision si une carte à collectionner est présente dans l'image. */
class CardDetector(private val ctx: Context) {

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun detect(jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val key = ApiKeyStore(ctx).openAiKey
        if (key.isBlank()) throw DetectionException("Aucune clé OpenAI. Renseigne-la dans les Réglages.")

        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", "Analyse cette image."))
            put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$b64"),
                ),
            )
        }
        val payload = JSONObject().apply {
            put("model", MODEL)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", PROMPT))
                put(JSONObject().put("role", "user").put("content", userContent))
            })
        }
        val req = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }
                    .getOrNull() ?: "HTTP ${resp.code}"
                throw DetectionException("Détection : $msg")
            }
            val content = runCatching {
                JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            }.getOrElse { throw DetectionException("Réponse inattendue.") }
            runCatching { JSONObject(content).optBoolean("card", false) }.getOrDefault(false)
        }
    }

    companion object {
        private const val MODEL = "gpt-4o-mini"
        private val PROMPT = """
            Tu détermines si une carte à collectionner (type Pokémon / carte à jouer TCG)
            est bien visible dans l'image. Réponds uniquement en JSON : {"card": true} si
            une carte est présente et identifiable comme telle, sinon {"card": false}.
        """.trimIndent()
    }
}
