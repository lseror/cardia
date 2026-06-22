package com.serortech.cardia.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Réglages de l'app, stockés chiffrés (Android Keystore).
 *
 * L'app ne porte AUCUNE clé IA : seulement une clé de licence applicative et
 * l'URL du serveur. C'est le serveur qui détient la vraie clé IA et appelle
 * OpenAI / Anthropic pour la détection.
 */
class SettingsStore(ctx: Context) {

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

    /** Clé de licence applicative (Authorization: Bearer …). */
    var licenseKey: String
        get() = prefs.getString(KEY_LICENSE, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_LICENSE, value.trim()) }

    /** URL de base du serveur de détection (ex. http://10.0.0.13:8787). */
    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_SERVER_URL, value.trim().trimEnd('/')) }

    companion object {
        private const val KEY_LICENSE = "license_key"
        private const val KEY_SERVER_URL = "server_url"
    }
}
