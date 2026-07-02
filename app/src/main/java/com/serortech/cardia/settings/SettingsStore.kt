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

    /** Quota journalier associé à la clé (informatif, renvoyé à l'enregistrement). */
    var dailyLimit: Int
        get() = prefs.getInt(KEY_DAILY_LIMIT, 0)
        set(value) = prefs.edit { putInt(KEY_DAILY_LIMIT, value) }

    /** Tolérance sur le ratio largeur/hauteur pour la détection du contour de carte. */
    var ratioTolerance: Float
        get() = prefs.getFloat(KEY_RATIO_TOL, DEFAULT_RATIO_TOLERANCE)
        set(value) = prefs.edit { putFloat(KEY_RATIO_TOL, value) }

    /** Seuil de netteté mini (variance du Laplacien) pour garder une frame à l'acquisition. */
    var qualitySharpMin: Float
        get() = prefs.getFloat(KEY_SHARP_MIN, DEFAULT_SHARP_MIN)
        set(value) = prefs.edit { putFloat(KEY_SHARP_MIN, value) }

    /** Efface la licence (pour réactiver avec un nouveau code). */
    fun clearLicense() = prefs.edit {
        remove(KEY_LICENSE)
        remove(KEY_DAILY_LIMIT)
    }

    companion object {
        /** Tolérance par défaut sur le ratio (±) — voir CardOutlineDetector. */
        const val DEFAULT_RATIO_TOLERANCE = 0.05f

        /** Seuil de netteté par défaut (à caler via le mode calibrage). */
        const val DEFAULT_SHARP_MIN = 120f

        private const val KEY_LICENSE = "license_key"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DAILY_LIMIT = "daily_limit"
        private const val KEY_RATIO_TOL = "ratio_tolerance"
        private const val KEY_SHARP_MIN = "quality_sharp_min"
    }
}
