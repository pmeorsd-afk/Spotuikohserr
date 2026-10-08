package com.music.spotui.util

import android.content.Context
import android.util.Log
import com.music.spotui.BuildConfig
import com.music.spotui.MyApplication
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.SpotifyWebPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * PresenceCoordinator — Single authoritative coordinator for SpotUI presence & telemetry.
 *
 * Implements authoritative 4-state lifecycle:
 * - ACTIVE_IN_APP_PLAYING: UI foreground + audio playing
 * - ACTIVE_IN_APP_IDLE: UI foreground + audio idle
 * - BACKGROUND_LISTENING: UI background + audio playing
 * - OFFLINE: UI background + audio idle
 *
 * Maintains a stable sessionId across transient network disconnects and reconnects.
 * Uses OkHttp WebSocket with automatic reconnection and heartbeat leasing.
 */
object PresenceCoordinator {

    private const val TAG = "PresenceCoordinator"
    private const val PRIMARY_WS_URL = "wss://lingering-brook-93f6.orelgame156.workers.dev/ws/presence"
    private const val LOCAL_WS_URL = "ws://127.0.0.1:8787/ws/presence"

    enum class PresenceState {
        ACTIVE_IN_APP_PLAYING,
        ACTIVE_IN_APP_IDLE,
        BACKGROUND_LISTENING,
        OFFLINE
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var isUiForeground = false
    @Volatile private var isAudioPlaying = false
    @Volatile private var currentTrack: SongsModel? = null
    @Volatile private var playbackInstanceId: String? = null

    @Volatile private var currentState = PresenceState.OFFLINE
    @Volatile private var sessionId: String? = null

    private var webSocket: WebSocket? = null
    @Volatile private var isConnected = false
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var useLocal = BuildConfig.DEBUG

    fun initialize(context: Context) {
        // Wire into SongPlayer playback truth
        SongPlayer.onIsPlayingChangedListener = { playing ->
            onPlaybackStateChanged(playing)
        }
        Log.d(TAG, "PresenceCoordinator initialized")
    }

    /**
     * Source of truth for UI visibility: called from MainActivity onResume (true) / onStop (false).
     */
    @Synchronized
    fun setUiForeground(foreground: Boolean) {
        if (isUiForeground == foreground) return
        isUiForeground = foreground
        Log.d(TAG, "setUiForeground: $foreground")
        evaluateState()
    }

    /**
     * Source of truth for Audio playback: called directly from Player.Listener.onIsPlayingChanged.
     */
    @Synchronized
    fun onPlaybackStateChanged(playing: Boolean) {
        if (isAudioPlaying == playing) return
        isAudioPlaying = playing
        if (playing && playbackInstanceId == null) {
            playbackInstanceId = "play_" + UUID.randomUUID().toString()
        }
        Log.d(TAG, "onPlaybackStateChanged: $playing (instance: $playbackInstanceId)")
        evaluateState()
    }

    /**
     * Called when active song track metadata updates.
     */
    @Synchronized
    fun onTrackMetadataChanged(track: SongsModel?) {
        val previousTrack = currentTrack
        currentTrack = track
        if (track != null && previousTrack?.id != track.id) {
            playbackInstanceId = "play_" + UUID.randomUUID().toString()
            if (currentState != PresenceState.OFFLINE) {
                Log.d(TAG, "onTrackMetadataChanged: ${track.title} - ${track.singer} (instance: $playbackInstanceId)")
                sendStateChangeMessage()
            }
        }
    }

    /**
     * Called when PlaybackService is destroyed.
     */
    @Synchronized
    fun onPlaybackServiceDestroyed() {
        Log.d(TAG, "PlaybackService destroyed")
        isAudioPlaying = false
        evaluateState()
    }

    fun getCurrentState(): PresenceState = currentState
    fun getSessionId(): String? = sessionId

    private fun evaluateState() {
        val newState = when {
            isUiForeground && isAudioPlaying -> PresenceState.ACTIVE_IN_APP_PLAYING
            isUiForeground && !isAudioPlaying -> PresenceState.ACTIVE_IN_APP_IDLE
            !isUiForeground && isAudioPlaying -> PresenceState.BACKGROUND_LISTENING
            else -> PresenceState.OFFLINE
        }

        if (newState == currentState) return
        val previousState = currentState
        currentState = newState
        Log.d(TAG, "Presence transition: $previousState -> $newState (UI=$isUiForeground, Audio=$isAudioPlaying)")

        if (newState == PresenceState.OFFLINE) {
            handleTransitionToOffline()
        } else {
            handleTransitionToOnline(previousState, newState)
        }
    }

    private fun handleTransitionToOnline(previous: PresenceState, current: PresenceState) {
        // If transitioning from OFFLINE or session is null, start a fresh session
        if (previous == PresenceState.OFFLINE || sessionId == null) {
            sessionId = "sess_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().replace("-", "").take(6)
            Log.d(TAG, "Generated new sessionId: $sessionId")
        }

        if (!isConnected && webSocket == null) {
            connectWebSocket()
        } else {
            sendStateChangeMessage()
        }

        startHeartbeatLoop()
    }

    private fun handleTransitionToOffline() {
        sendByeMessage()
        disconnectWebSocket()
        stopHeartbeatLoop()
        sessionId = null
        playbackInstanceId = null
    }

    private fun connectWebSocket() {
        reconnectJob?.cancel()
        val sId = sessionId ?: return
        val context = MyApplication.instance ?: return
        val userId = AppTelemetryManager.getAnonymousUserId(context)

        val url = if (useLocal) LOCAL_WS_URL else PRIMARY_WS_URL
        val request = Request.Builder().url(url).build()

        Log.d(TAG, "Connecting WebSocket to: $url (session: $sId)")
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                Log.d(TAG, "WebSocket connected successfully (session: $sId)")
                sendHelloMessage(webSocket, sId, userId)
                if (currentTrack != null && (currentState == PresenceState.ACTIVE_IN_APP_PLAYING || currentState == PresenceState.BACKGROUND_LISTENING)) {
                    sendStateChangeMessage(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.v(TAG, "WS message: $text")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WS closing: $code / $reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                PresenceCoordinator.webSocket = null
                Log.d(TAG, "WS closed: $code / $reason")
                scheduleReconnectIfNeeded()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                PresenceCoordinator.webSocket = null
                Log.w(TAG, "WS failure: ${t.message} (wasLocal: $useLocal)")
                // If local attempt failed, fallback to production Cloudflare URL
                if (useLocal) {
                    useLocal = false
                }
                scheduleReconnectIfNeeded()
            }
        })
    }

    private fun scheduleReconnectIfNeeded() {
        if (currentState == PresenceState.OFFLINE || sessionId == null) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(3000L)
            if (currentState != PresenceState.OFFLINE && !isConnected && webSocket == null) {
                Log.d(TAG, "Reconnecting WebSocket with existing session: $sessionId")
                connectWebSocket()
            }
        }
    }

    private fun disconnectWebSocket() {
        reconnectJob?.cancel()
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }
        webSocket = null
        isConnected = false
    }

    private fun getSafePlaybackPosition(): Long {
        if (SpotifyWebPlayer.isPlaying && SpotifyWebPlayer.positionMs > 0) {
            return SpotifyWebPlayer.positionMs
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return try { SongPlayer.getCurrentPosition() } catch (e: Exception) { 0L }
        }
        var pos = 0L
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                pos = SongPlayer.getCurrentPosition()
            } catch (e: Exception) {}
            latch.countDown()
        }
        try {
            latch.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {}
        return pos
    }

    private fun sendHelloMessage(ws: WebSocket, sId: String, userId: String) {
        try {
            val json = JSONObject().apply {
                put("type", "hello")
                put("sessionId", sId)
                put("userId", userId)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("state", currentState.name)
                put("positionMs", getSafePlaybackPosition())
                if (playbackInstanceId != null) {
                    put("playbackInstanceId", playbackInstanceId)
                }
            }
            ws.send(json.toString())
            Log.d(TAG, "Sent hello: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending hello", e)
        }
    }

    private fun sendStateChangeMessage(ws: WebSocket? = webSocket) {
        val socket = ws ?: webSocket ?: return
        val sId = sessionId ?: return
        try {
            val track = currentTrack
            val json = JSONObject().apply {
                put("type", "state_change")
                put("sessionId", sId)
                put("state", currentState.name)
                put("isPlaying", isAudioPlaying)
                put("positionMs", getSafePlaybackPosition())
                if (playbackInstanceId != null) {
                    put("playbackInstanceId", playbackInstanceId)
                }
                if (track != null) {
                    val trackObj = JSONObject().apply {
                        val mediaTypeStr = if (track.isPodcast()) "episode" else "track"
                        val spotifyId = track.spotifyTrackId
                        val contentKey = if (spotifyId.isNotBlank()) {
                            "$mediaTypeStr:$spotifyId"
                        } else {
                            "$mediaTypeStr:" + (track.title + " - " + track.singer).hashCode().toString(36)
                        }
                        put("contentKey", contentKey)
                        put("title", track.title)
                        put("artist", track.singer)
                        put("album", track.album)
                        put("spotifyId", spotifyId)
                        put("mediaType", mediaTypeStr)
                        put("isPodcast", track.isPodcast())
                    }
                    put("track", trackObj)
                }
            }
            socket.send(json.toString())
            Log.d(TAG, "Sent state_change: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending state_change", e)
        }
    }

    private fun sendHeartbeatMessage() {
        val socket = webSocket ?: return
        val sId = sessionId ?: return
        try {
            val json = JSONObject().apply {
                put("type", "heartbeat")
                put("sessionId", sId)
                put("state", currentState.name)
                put("positionMs", getSafePlaybackPosition())
            }
            socket.send(json.toString())
            Log.d(TAG, "Sent heartbeat for session: $sId")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending heartbeat", e)
        }
    }

    private fun sendByeMessage() {
        val socket = webSocket ?: return
        val sId = sessionId ?: return
        try {
            val json = JSONObject().apply {
                put("type", "bye")
                put("sessionId", sId)
                put("reason", "user_exit")
            }
            socket.send(json.toString())
            Log.d(TAG, "Sent bye: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending bye", e)
        }
    }

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive && currentState != PresenceState.OFFLINE) {
                val intervalMs = if (isAudioPlaying) 20_000L else 30_000L
                delay(intervalMs)
                if (!isActive || currentState == PresenceState.OFFLINE) break
                if (isConnected) {
                    sendHeartbeatMessage()
                } else if (webSocket == null) {
                    connectWebSocket()
                }
            }
        }
    }

    private fun stopHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun shouldUseLocal(): Boolean = false
}
