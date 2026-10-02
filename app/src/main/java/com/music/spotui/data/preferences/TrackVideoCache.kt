package com.music.spotui.data.preferences

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent high-speed cache mapping Spotify track IDs and search queries
 * to YouTube videoIds.
 *
 * This eliminates the 1.5 - 3.0s YouTube search and scoring delay for any
 * track that has been played, preloaded, or resolved before.
 */
object TrackVideoCache {
    private const val TAG = "TrackVideoCache"
    private const val PREFS_NAME = "track_video_cache_v2"
    private val memCache = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    private fun normalizeKey(key: String): String =
        key.trim().lowercase()

    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.all.forEach { (k, v) ->
                    if (v is String && v.isNotBlank()) {
                        memCache[k] = v
                    }
                }
                loaded = true
                Log.d(TAG, "Loaded ${memCache.size} track-to-video mappings from disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load track video cache", e)
            }
        }
    }

    /**
     * Get the cached YouTube videoId for a Spotify trackId or normalized query.
     */
    fun get(context: Context, key: String): String? {
        if (key.isBlank()) return null
        init(context)
        val direct = memCache[key]
        if (direct != null) return direct
        val norm = normalizeKey(key)
        return memCache[norm]
    }

    /**
     * Store the resolved YouTube videoId for a trackId or query.
     */
    fun put(context: Context, key: String, videoId: String) {
        if (key.isBlank() || videoId.isBlank() || !videoId.matches(Regex("""[A-Za-z0-9_-]{11}"""))) return
        init(context)
        memCache[key] = videoId
        val norm = normalizeKey(key)
        memCache[norm] = videoId

        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(key, videoId)
                .putString(norm, videoId)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist videoId mapping for $key", e)
        }
    }
}
