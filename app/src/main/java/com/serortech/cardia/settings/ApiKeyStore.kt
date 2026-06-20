package com.serortech.cardia.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Clé OpenAI saisie par l'utilisateur, stockée chiffrée (Android Keystore). */
class ApiKeyStore(ctx: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx.applicationContext,
            "cardia_secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var openAiKey: String
        get() = prefs.getString(KEY_OPENAI, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_OPENAI, value.trim()) }

    companion object {
        private const val KEY_OPENAI = "openai_api_key"
    }
}
