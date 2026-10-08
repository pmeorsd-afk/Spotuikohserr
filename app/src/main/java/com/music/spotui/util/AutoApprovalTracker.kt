package com.music.spotui.util

import android.content.Context
import android.util.Log
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks songs submitted to Telegram for cover image approval,
 * ensuring automatic background submission without duplicates or user disruption.
 */
object AutoApprovalTracker {

    private const val TAG = "AutoApprovalTracker"
    private const val PREFS_NAME = "auto_approval_tracker_prefs"
    private const val EXPIRATION_MS = 7 * 24 * 60 * 60 * 1000L // 7 days cache

    // Fast in-memory cache for O(1) checks during app process lifetime
    private val inMemorySentKeys = ConcurrentHashMap.newKeySet<String>()

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
     * Called whenever a track starts playing.
     * If the track's cover image is not whitelisted and has not been submitted recently,
     * automatically dispatches a silent approval request to the Telegram bot.
     */
    fun onTrackPlayed(context: Context?, song: SongsModel?) {
        if (song == null) return
        if (song.title.isBlank()) return
        // Do not request approvals for podcast episodes
        if (song.mediaType == MediaType.PODCAST_EPISODE) return

        val cleanTrackId = KosherWhitelistManager.canonicalTrackId(song)
            .ifBlank { song.spotifyTrackId }
            .trim()

        // If the song or its artist is already whitelisted, do not spam
        val alreadyWhitelisted = KosherWhitelistManager.isTrackInWhitelist(
            trackId = cleanTrackId,
            trackTitle = song.title,
            artistName = song.singer
        )
        if (alreadyWhitelisted) {
            return
        }

        val dedupeKey = cleanTrackId.ifBlank {
            "${song.title} - ${song.singer}".trim()
        }.lowercase()

        if (isSubmitted(context, dedupeKey)) {
            Log.d(TAG, "Track '$dedupeKey' was already submitted recently, skipping auto-request.")
            return
        }

        // Mark submitted immediately to prevent race conditions
        markSubmitted(context, dedupeKey)
        Log.i(TAG, "Auto-submitting unapproved track to Telegram: '${song.title}' by '${song.singer}' (id: $cleanTrackId)")

        if (context != null) {
            TelegramNotifier.sendTrackApprovalRequest(
                context = context,
                trackTitle = song.title,
                artistName = song.singer,
                trackId = cleanTrackId,
                coverUrl = song.coverUri,
                silent = true
            )
        }
    }
}
