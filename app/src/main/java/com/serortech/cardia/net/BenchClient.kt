package com.serortech.cardia.net

import android.util.Base64
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Envoie (fire-and-forget) chaque frame brute d'un lot d'auto-capture vers le banc
 * de réglage (cardia.d8a.fr/ingest), groupée par batchId, pour composite + tuning.
 * Séparé de la télémétrie prod (www.d8a.fr) : ici c'est l'outil de dev.
 */
object BenchClient {

    private const val BASE = "https://cardia.d8a.fr"
    private const val TOKEN = "xGI1jFUNPbxKb73v7PCaPL0"

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val noop = object : Callback {
        override fun onFailure(call: Call, e: IOException) {}
        override fun onResponse(call: Call, response: Response) { response.close() }
    }

    fun post(batchId: String, index: Int, rawJpeg: ByteArray) {
        val body = JSONObject().apply {
            put("token", TOKEN)
            put("batch", batchId)
            put("index", index)
            put("data", Base64.encodeToString(rawJpeg, Base64.NO_WRAP))
        }
        val req = Request.Builder()
            .url("$BASE/ingest")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(noop)
    }
}
