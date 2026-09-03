package com.music.spotui.data.update

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.music.spotui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Checks for updates from the project's GitHub version.json file.
 * Compatible with filtered / kosher networks (NetFree, Tag, etc.) via SSL bypass and CDN mirrors.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private val ENDPOINTS = listOf(
        "https://cdn.jsdelivr.net/gh/pmeorsd-afk/Spotuikohserr@main/version.json",
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/version.json",
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/refs/heads/main/version.json",
    )
    private const val PREFS = "update_prefs"
    private const val KEY_SKIP = "skip_fingerprint"

    private val httpClient: OkHttpClient by lazy {
        createPermissiveHttpClient()
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
                    .header("Cache-Control", "no-cache, no-store, must-revalidate")
                    .header("Pragma", "no-cache")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val text = response.body?.string().orEmpty()
                    if (text.isBlank() || !text.trim().startsWith("{")) return@use

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

    @SuppressLint("CustomX509TrustManager")
    private fun createPermissiveHttpClient(): OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
