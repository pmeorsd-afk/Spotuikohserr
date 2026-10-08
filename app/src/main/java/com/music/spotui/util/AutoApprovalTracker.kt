package com.music.spotui.util

import android.content.Context
import android.util.Log
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks songs submitted to Telegram for cover image approval,
 * ensuring automatic background submission without duplicates or user disruption.
 * Requires the user to listen to at least 30 seconds of the track before submitting.
 */
object AutoApprovalTracker {

    private const val TAG = "AutoApprovalTracker"
    private const val PREFS_NAME = "auto_approval_tracker_prefs"
    private const val EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L // 7 days cache

    const val DEFAULT_REQUIRED_LISTEN_MS = 30_000L // 30 seconds required listening time

    @Volatile
    var requiredListeningMs: Long = DEFAULT_REQUIRED_LISTEN_MS

    // Fast in-memory cache for O(1) checks during app process lifetime
    private val inMemorySentKeys = ConcurrentHashMap.newKeySet<String>()

    // Concurrency tracking for current playback
    private var currentTrackingKey: String? = null
    private var trackingJob: Job? = null
    private var accumulatedListeningMs: Long = 0L
    private var isCurrentlyPlaying: Boolean = false

    /**
     * Checks if a track key was already submitted recently.
     */
    fun isSubmitted(context: Context?, key: String): Boolean {
        if (key.isBlank()) return true
        val normalizedKey = key.trim().lowercase()

        if (inMemorySentKeys.contains(normalizedKey)) {
            return true
        }

        if (context != null) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val timestamp = prefs.getLong(normalizedKey, 0L)
                if (timestamp > 0L) {
                    val now = System.currentTimeMillis()
                    if (now - timestamp < EXPIRATION_MS) {
                        inMemorySentKeys.add(normalizedKey)
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking submitted key from prefs", e)
            }
        }

        return false
    }

    /**
     * Marks a track key as submitted to prevent duplicate alerts.
     */
    fun markSubmitted(context: Context?, key: String) {
        if (key.isBlank()) return
        val normalizedKey = key.trim().lowercase()
        inMemorySentKeys.add(normalizedKey)

        if (context != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putLong(normalizedKey, System.currentTimeMillis()).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving submitted key to prefs", e)
                }
            }
        }
    }

    /**
     * Called whenever playback state or track changes.
     * Monitors active listening time and dispatches Telegram approval request only
     * after 30 seconds of listening have elapsed.
     */
    @Synchronized
    fun onPlaybackStateChanged(context: Context?, song: SongsModel?, isPlaying: Boolean) {
        if (song == null || song.title.isBlank() || song.mediaType == MediaType.PODCAST_EPISODE) {
            cancelCurrentTracking()
            return
        }

        val cleanTrackId = KosherWhitelistManager.canonicalTrackId(song)
            .ifBlank { song.spotifyTrackId }
            .trim()

        // If the song or its artist is already whitelisted, do not monitor or submit
        val alreadyWhitelisted = KosherWhitelistManager.isTrackInWhitelist(
            trackId = cleanTrackId,
            trackTitle = song.title,
            artistName = song.singer
        )
        if (alreadyWhitelisted) {
            cancelCurrentTracking()
            return
        }

        val dedupeKey = cleanTrackId.ifBlank {
            "${song.title} - ${song.singer}".trim()
        }.lowercase()

        if (isSubmitted(context, dedupeKey)) {
            cancelCurrentTracking()
            return
        }

        isCurrentlyPlaying = isPlaying

        if (!isPlaying) {
            // Playback paused: pause the monitoring job, retain accumulated time for this track
            trackingJob?.cancel()
            trackingJob = null
            Log.d(TAG, "Playback paused for track '$dedupeKey' with ${accumulatedListeningMs}ms accumulated.")
            return
        }

        // Playback is active: check if track changed
        if (currentTrackingKey != dedupeKey) {
            trackingJob?.cancel()
            currentTrackingKey = dedupeKey
            accumulatedListeningMs = 0L
        }

        // If a monitoring loop is already actively running for this track, let it continue
        if (trackingJob?.isActive == true) {
            return
        }

        val targetKey = dedupeKey
        val appContext = context?.applicationContext

        trackingJob = CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Started 30s playback monitor for: '${song.title}' by '${song.singer}' ($targetKey)")
            while (isActive && isCurrentlyPlaying && currentTrackingKey == targetKey) {
                delay(1000L)
                if (!isActive || !isCurrentlyPlaying || currentTrackingKey != targetKey) break

                accumulatedListeningMs += 1000L

                val currentPosMs = try {
                    SongPlayer.getCurrentPosition()
                } catch (e: Exception) {
                    0L
                }

                val thresholdMet = accumulatedListeningMs >= requiredListeningMs ||
                        (currentPosMs >= requiredListeningMs && accumulatedListeningMs >= 3000L)

                if (thresholdMet) {
                    if (isSubmitted(appContext, targetKey)) {
                        break
                    }

                    markSubmitted(appContext, targetKey)
                    Log.i(TAG, "User listened to '${song.title}' for 30 seconds! Auto-submitting to Telegram (id: $cleanTrackId)...")

                    if (appContext != null) {
                        TelegramNotifier.sendTrackApprovalRequest(
                            context = appContext,
                            trackTitle = song.title,
                            artistName = song.singer,
                            trackId = cleanTrackId,
                            coverUrl = song.coverUri,
                            silent = true
                        )
                    }
                    break
                }
            }
        }
    }

    private fun cancelCurrentTracking() {
        trackingJob?.cancel()
        trackingJob = null
        currentTrackingKey = null
        accumulatedListeningMs = 0L
        isCurrentlyPlaying = false
    }

    /**
     * Backwards-compatible convenience method.
     */
    fun onTrackPlayed(context: Context?, song: SongsModel?) {
        onPlaybackStateChanged(context, song, isPlaying = true)
    }
}
