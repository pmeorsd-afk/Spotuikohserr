package com.music.spotui.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.spotui.BuildConfig
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Manages anonymous client telemetry and listening metrics.
 * Safely reports live active status and cumulative listening hours to backend.
 */
object AppTelemetryManager {

    private const val TAG = "AppTelemetryManager"
    private const val PREFS_NAME = "spotui_telemetry_prefs"
    private const val KEY_USER_ID = "anonymous_user_id"
    private const val KEY_TOTAL_SECONDS = "total_listening_seconds"

    // Primary Cloudflare Worker backend with fallback to Google Apps Script
    private const val PRIMARY_TELEMETRY_URL = "https://lingering-brook-93f6.orelgame156.workers.dev/api/telemetry"
    private const val FALLBACK_TELEMETRY_URL = "https://script.google.com/macros/s/AKfycbxjKBX2VHdyKfkih9EOgTOs5C08iFKqOEOaSeis1Ov1NZPBjR2HEVtMX-aAEricAXpPJw/exec"

    private const val HEARTBEAT_INTERVAL_MS = 60_000L // 60 seconds

    private var heartbeatJob: Job? = null
    private var lastHeartbeatTimeMs = 0L
    private var currentSong: SongsModel? = null
    private var isPlayingCurrent = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Gets or creates a persistent anonymous user identifier.
     */
    fun getAnonymousUserId(context: Context): String {
        val prefs = getPrefs(context)
        var userId = prefs.getString(KEY_USER_ID, null)
        if (userId.isNullOrBlank()) {
            userId = "usr_" + UUID.randomUUID().toString().replace("-", "").take(12)
            prefs.edit().putString(KEY_USER_ID, userId).apply()
        }
        return userId
    }

    /**
     * Returns the total cumulative listening seconds recorded on this device.
     */
    fun getTotalListeningSeconds(context: Context): Long {
        return getPrefs(context).getLong(KEY_TOTAL_SECONDS, 0L)
    }

    private fun addListeningSeconds(context: Context, seconds: Long) {
        if (seconds <= 0) return
        val prefs = getPrefs(context)
        val current = prefs.getLong(KEY_TOTAL_SECONDS, 0L)
        prefs.edit().putLong(KEY_TOTAL_SECONDS, current + seconds).apply()
    }

    /**
     * Called whenever playback state or active track changes.
     */
    fun onPlaybackStateChanged(context: Context?, song: SongsModel?, isPlaying: Boolean) {
        if (context == null) return
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val now = System.currentTimeMillis()
                val previousState = isPlayingCurrent
                val previousSong = currentSong
                isPlayingCurrent = isPlaying
                currentSong = song

                if (isPlaying) {
                    if (!previousState || previousSong?.id != song?.id) {
                        // Playback just started or track switched
                        lastHeartbeatTimeMs = now
                        sendTelemetryPayload(
                            context = appContext,
                            event = if (!previousState) "play" else "track_change",
                            song = song,
                            isPlaying = true,
                            secondsDelta = 0
                        )
                    }
                    startHeartbeatLoop(appContext)
                } else {
                    // Playback paused or stopped
                    stopHeartbeatLoop()
                    if (previousState && lastHeartbeatTimeMs > 0L) {
                        val deltaSeconds = ((now - lastHeartbeatTimeMs) / 1000L).coerceIn(0L, 300L)
                        if (deltaSeconds > 0) {
                            addListeningSeconds(appContext, deltaSeconds)
                        }
                        sendTelemetryPayload(
                            context = appContext,
                            event = "pause",
                            song = song ?: previousSong,
                            isPlaying = false,
                            secondsDelta = deltaSeconds
                        )
                        lastHeartbeatTimeMs = 0L
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling playback state change", e)
            }
        }
    }

    private fun startHeartbeatLoop(context: Context) {
        heartbeatJob?.cancel()
        heartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isPlayingCurrent) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (!isActive || !isPlayingCurrent) break

                val now = System.currentTimeMillis()
                val deltaSeconds = if (lastHeartbeatTimeMs > 0L) {
                    ((now - lastHeartbeatTimeMs) / 1000L).coerceIn(0L, 120L)
                } else {
                    60L
                }
                lastHeartbeatTimeMs = now

                addListeningSeconds(context, deltaSeconds)

                sendTelemetryPayload(
                    context = context,
                    event = "heartbeat",
                    song = currentSong,
                    isPlaying = true,
                    secondsDelta = deltaSeconds
                )
            }
        }
    }

    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun sendTelemetryPayload(
        context: Context,
        event: String,
        song: SongsModel?,
        isPlaying: Boolean,
        secondsDelta: Long
    ) {
        val userId = getAnonymousUserId(context)
        val totalSeconds = getTotalListeningSeconds(context)

        val cleanTrackId = if (song != null) {
            KosherWhitelistManager.canonicalTrackId(song)
                .ifBlank { song.spotifyTrackId }
                .trim()
        } else ""

        val trackObj = JSONObject().apply {
            put("title", song?.title ?: "")
            put("artist", song?.singer ?: "")
            put("spotifyId", cleanTrackId)
            put("isPodcast", song?.mediaType == MediaType.PODCAST_EPISODE)
        }

        val payload = JSONObject().apply {
            put("userId", userId)
            put("event", event)
            put("isPlaying", isPlaying)
            put("secondsDelta", secondsDelta)
            put("totalSecondsListened", totalSeconds)
            put("track", trackObj)
            put("appVersion", BuildConfig.VERSION_NAME)
            put("timestamp", System.currentTimeMillis())
        }

        val jsonString = payload.toString()

        // 1. Send to primary Cloudflare Worker
        val success = postJson(PRIMARY_TELEMETRY_URL, jsonString)
        if (!success) {
            // 2. Fallback to Google Apps Script if primary failed
            postJson(FALLBACK_TELEMETRY_URL, jsonString)
        }
    }

    private fun postJson(endpointUrl: String, jsonPayload: String): Boolean {
        return try {
            val url = URL(endpointUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.setRequestProperty("User-Agent", "SpotUI-Client/${BuildConfig.VERSION_NAME}")

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            Log.d(TAG, "Silent telemetry post to $endpointUrl skipped/failed: ${e.message}")
            false
        }
    }
}
