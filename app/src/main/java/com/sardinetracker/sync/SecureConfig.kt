package com.sardinetracker.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The user's per-account settings: which server to sync to, the bearer token
 * for its /api/health-sync endpoint, and which account id the readings belong
 * to. Immutable once loaded; [SecureConfig] is the only thing that reads/writes
 * them.
 */
data class Settings(
    val serverUrl: String,
    val token: String,
    val userId: Int,
) {
    /** Server base with any trailing slash removed — what the WebView loads. */
    val baseUrl: String get() = serverUrl.trimEnd('/')

    /** Full endpoint the sync POSTs to. */
    val healthSyncUrl: String get() = baseUrl + Config.API_PATH
}

/**
 * Encrypted-at-rest storage for [Settings], mirroring the iOS app's Keychain
 * use. The token grants write access to the sync endpoint, so it never lives in
 * source or in plain SharedPreferences — EncryptedSharedPreferences keeps it
 * encrypted with a key held in the Android Keystore.
 */
object SecureConfig {
    private const val PREFS_NAME = "sardinesync_secure"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "bearer_token"
    private const val KEY_USER_ID = "user_id"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** Current settings, or null if the app hasn't been configured yet. */
    fun load(context: Context): Settings? {
        return try {
            val p = prefs(context)
            val url = p.getString(KEY_SERVER_URL, "").orEmpty()
            val token = p.getString(KEY_TOKEN, "").orEmpty()
            val userId = p.getInt(KEY_USER_ID, 0)
            if (url.isBlank() || token.isBlank() || userId <= 0) null
            else Settings(url, token, userId)
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, serverUrl: String, token: String, userId: Int) {
        prefs(context).edit()
            .putString(KEY_SERVER_URL, serverUrl.trim())
            .putString(KEY_TOKEN, token.trim())
            .putInt(KEY_USER_ID, userId)
            .apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
