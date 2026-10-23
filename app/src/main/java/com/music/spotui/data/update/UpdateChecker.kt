package com.music.spotui.data.update

import android.content.Context
import android.util.Log
import com.music.spotui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Checks for updates from the project's GitHub version.json file.
 * Uses platform TLS validation and the app's configured system/user trust anchors.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private val ENDPOINTS = listOf(
        "https://api.github.com/repos/pmeorsd-afk/Spotuikohserr/contents/version.json",
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/version.json",
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/refs/heads/main/version.json",
        "https://cdn.jsdelivr.net/gh/pmeorsd-afk/Spotuikohserr@main/version.json",
    )
    private const val PREFS = "update_prefs"
    private const val KEY_SKIP = "skip_fingerprint"

    private val httpClient: OkHttpClient by lazy {
        // Update metadata now selects an installable package. Keep certificate and hostname
        // validation enabled; installed filtering CAs are supported by network_security_config.
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .build()
    }

    data class UpdateInfo(
        val versionCode: Int,
        val versionName: String,
        val title: String,
        val message: String,
        val downloadUrl: String,
        val forceUpdate: Boolean = false,
        val updateButtonText: String = "הורד עדכון עכשיו",
        val cancelButtonText: String = "הזכר לי מאוחר יותר",
        val apkUrl: String = "",
        val apkSha256: String = "",
        val fingerprint: String = "$versionCode:$versionName",
    )

    suspend fun check(context: Context): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = runCatching { fetchUpdateConfig() }
            .onFailure { Log.e(TAG, "Update check error", it) }
            .getOrNull() ?: return@withContext null

        Log.d(TAG, "Local version: code=${BuildConfig.VERSION_CODE}, name=${BuildConfig.VERSION_NAME}")
        Log.d(TAG, "Remote version: code=${info.versionCode}, name=${info.versionName}")

        val isCodeNewer = info.versionCode > BuildConfig.VERSION_CODE
        val isNameNewer = isNewer(info.versionName, BuildConfig.VERSION_NAME)
        if (!isCodeNewer && !isNameNewer) {
            Log.d(TAG, "Installed version is up to date.")
            return@withContext null
        }

        if (!info.forceUpdate) {
            val skipped = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SKIP, null)
            if (skipped == info.fingerprint) {
                Log.d(TAG, "User chose to skip this release: ${info.fingerprint}")
                return@withContext null
            }
        }

        info
    }

    fun skipRelease(context: Context, info: UpdateInfo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, info.fingerprint).apply()
    }

    private fun fetchUpdateConfig(): UpdateInfo? {
        val timestamp = System.currentTimeMillis()
        for (baseUrl in ENDPOINTS) {
            val urlWithCacheBuster = "$baseUrl?t=$timestamp"
            try {
                val request = Request.Builder()
                    .url(urlWithCacheBuster)
                    .header("User-Agent", "Mozilla/5.0 (Spotui; Android)")
                    .header("Accept", "application/vnd.github.v3.raw, application/json, text/plain, */*")
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank() || !text.trim().startsWith("{")) return@use

                    return UpdateManifest.parse(text, isAdmin = BuildConfig.IS_ADMIN)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed fetching from $baseUrl: ${e.message}")
            }
        }
        return null
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
