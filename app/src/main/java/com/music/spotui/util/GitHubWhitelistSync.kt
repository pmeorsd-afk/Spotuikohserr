package com.music.spotui.util

import android.content.Context
import android.util.Base64
import android.util.Log
import com.music.spotui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

object GitHubWhitelistSync {

    private const val TAG = "GitHubWhitelistSync"
    private const val REPO_OWNER = "pmeorsd-afk"
    private const val REPO_NAME = "Spotuikohserr"
    private const val FILE_PATH = "whitelist.json"
    private const val BRANCH = "main"

    private const val CONTENTS_URL =
        "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/contents/$FILE_PATH"

    private const val PREFS_NAME = "admin_github_prefs"
    private const val KEY_TOKEN = "github_pat"

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val custom = prefs.getString(KEY_TOKEN, "")?.trim() ?: ""
        if (custom.isNotBlank()) return custom
        return BuildConfig.GITHUB_ADMIN_TOKEN.trim()
    }

    fun setToken(context: Context, token: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token.trim())
            .apply()
    }

    fun hasToken(context: Context): Boolean = getToken(context).isNotBlank()

    /**
     * Pushes the current whitelist JSON directly to GitHub repository via GitHub REST API.
     * Step 1: GET current file to obtain latest commit SHA.
     * Step 2: PUT updated JSON base64-encoded to commit and push.
     */
    suspend fun pushToGitHub(context: Context, jsonContent: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val token = getToken(context)
            if (token.isBlank()) {
                return@withContext Result.failure(IllegalStateException("No GitHub token configured"))
            }

            // 1. GET current SHA from GitHub
            val getConn = (URL("$CONTENTS_URL?ref=$BRANCH").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "SpotUI-Admin")
            }

            val getCode = getConn.responseCode
            var currentSha: String? = null
            if (getCode in 200..299) {
                val getResp = getConn.inputStream.bufferedReader().readText()
                val getJson = JSONObject(getResp)
                currentSha = getJson.optString("sha")
            } else {
                Log.w(TAG, "GET contents returned code $getCode: ${getConn.errorStream?.bufferedReader()?.readText()}")
            }

            // 2. PUT updated content to GitHub
            val putConn = (URL(CONTENTS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                connectTimeout = 10000
                readTimeout = 10000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "SpotUI-Admin")
            }

            val base64Content = Base64.encodeToString(
                jsonContent.toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )

            val payload = JSONObject().apply {
                put("message", "Update whitelist from SpotUI Admin")
                put("content", base64Content)
                put("branch", BRANCH)
                if (!currentSha.isNullOrBlank()) {
                    put("sha", currentSha)
                }
            }

            OutputStreamWriter(putConn.outputStream, StandardCharsets.UTF_8).use {
                it.write(payload.toString())
                it.flush()
            }

            val putCode = putConn.responseCode
            if (putCode in 200..299) {
                val putResp = putConn.inputStream.bufferedReader().readText()
                Log.i(TAG, "Successfully committed whitelist.json to GitHub: $putResp")
                Result.success("סונכרן בהצלחה ל-GitHub!")
            } else {
                val err = putConn.errorStream?.bufferedReader()?.readText() ?: "HTTP $putCode"
                Log.e(TAG, "Failed to commit whitelist to GitHub ($putCode): $err")
                Result.failure(Exception("שגיאת GitHub ($putCode): $err"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception pushing to GitHub", e)
            Result.failure(e)
        }
    }
}
