package com.serortech.cardia.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RegistrationException(message: String) : Exception(message)

data class RegisterResult(val installKey: String, val dailyLimit: Int)

/**
 * Appels au serveur CardIA (extension de l'API TCGPricer) qui ne concernent pas
 * la détection. Pour l'instant : l'enregistrement par code d'invitation.
 *
 * L'app ne porte aucune clé IA : elle échange un code d'invitation contre une
 * clé de licence (POST {baseUrl}/register), qu'elle stocke ensuite chiffrée.
 */
object CardiaClient {

    private val client = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Échange un code d'invitation contre une clé de licence. */
    suspend fun register(baseUrl: String, code: String): RegisterResult = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code)
        val req = Request.Builder()
            .url("$baseUrl/register")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val resp = try {
            client.newCall(req).execute()
        } catch (e: IOException) {
            throw RegistrationException("Serveur injoignable : ${e.message}")
        }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val msg = runCatching { JSONObject(raw).getString("error") }
                    .getOrNull() ?: "HTTP ${it.code}"
                throw RegistrationException(msg)
            }
            val json = runCatching { JSONObject(raw) }
                .getOrElse { throw RegistrationException("Réponse inattendue du serveur.") }
            val key = json.optString("install_key").ifBlank {
                throw RegistrationException("Réponse sans clé de licence.")
            }
            RegisterResult(key, json.optInt("daily_limit", 0))
        }
    }
}
