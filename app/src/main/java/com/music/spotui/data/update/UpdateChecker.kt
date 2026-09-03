package com.music.spotui.data.update

import android.content.Context
import android.util.Log
import com.music.spotui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks for updates from the project's GitHub version.json file.
 * Allows instant remote control over version numbering, title, description,
 * download link, and force update status.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/version.json"
    private const val PREFS = "update_prefs"
    private const val KEY_SKIP = "skip_fingerprint"

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val title: String,
        val message: String,
        val downloadUrl: String,
        val forceUpdate: Boolean = false,
        val updateButtonText: String = "הורד עדכון עכשיו",
        val cancelButtonText: String = "הזכר לי מאוחר יותר",
        val fingerprint: String = "$versionCode:$versionName",
    )

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = runCatching { fetchUpdateConfig() }
            .onFailure { Log.d(TAG, "update check failed: ${it.message}") }
            .getOrNull() ?: return@withContext null

        val isCodeNewer = info.versionCode > BuildConfig.VERSION_CODE
        val isNameNewer = isNewer(info.versionName, BuildConfig.VERSION_NAME)
        if (!isCodeNewer && !isNameNewer) return@withContext null

        if (!info.forceUpdate) {
            val skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SKIP, null)
            if (skipped == info.fingerprint) return@withContext null
        }

        info
    }

    fun skipRelease(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, info.fingerprint).apply()
    }

    private fun fetchUpdateConfig(): UpdateInfo? {
        val timestamp = System.currentTimeMillis()
        val urlWithCacheBuster = "$VERSION_JSON_URL?t=$timestamp"
        val conn = URL(urlWithCacheBuster).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        conn.setRequestProperty("User-Agent", "Spotui-App")
        conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
        conn.setRequestProperty("Pragma", "no-cache")
        try {
            if (conn.responseCode !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)

            val versionCode = json.optInt("versionCode", 0)
            val versionName = json.optString("versionName", "")
            val title = json.optString("title", "יצא עדכון חדש! 🎉")
            val message = json.optString("message", "גרסה חדשה זמינה להורדה. מומלץ לעדכן כדי ליהנות מכל השיפורים.")
            val downloadUrl = json.optString("downloadUrl", "https://github.com/pmeorsd-afk/Spotuikohserr/releases/latest")
            val forceUpdate = json.optBoolean("forceUpdate", false)
            val updateButtonText = json.optString("updateButtonText", "הורד עדכון עכשיו")
            val cancelButtonText = json.optString("cancelButtonText", "הזכר לי מאוחר יותר")

            return UpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                title = title,
                message = message,
                downloadUrl = downloadUrl,
                forceUpdate = forceUpdate,
                updateButtonText = updateButtonText,
                cancelButtonText = cancelButtonText,
                fingerprint = "$versionCode:$versionName",
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun isNewer(remote: String, installed: String): Boolean {
        val r = remote.split('.').mapNotNull { it.toIntOrNull() }
        val i = installed.split('.').mapNotNull { it.toIntOrNull() }
        if (r.isEmpty() || i.isEmpty()) return false
        for (n in 0 until maxOf(r.size, i.size)) {
            val a = r.getOrElse(n) { 0 }
            val b = i.getOrElse(n) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
