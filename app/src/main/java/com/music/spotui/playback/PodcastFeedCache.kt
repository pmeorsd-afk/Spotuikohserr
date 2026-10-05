package com.music.spotui.playback

import android.content.Context
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe persistent cache for podcast show RSS feed URLs.
 * Maps normalized podcast show identity to verified RSS feed URLs,
 * preventing repeated iTunes Search API / Discovery network calls.
 */
object PodcastFeedCache {
    private const val TAG = "PodcastFeedCache"
    private const val PREFS_NAME = "podcast_feed_cache_v1"

    private val memCache = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    fun normalizeKey(showName: String): String {
        return showName
            .lowercase()
            .replace('–', ' ')
            .replace('—', ' ')
            .replace('-', ' ')
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun init(context: Context) {
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
                Log.d(TAG, "Loaded ${memCache.size} podcast feed mappings from disk")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load podcast feed cache", e)
            }
        }
    }

    /**
     * Look up cached feedUrl by show name.
     */
    fun get(context: Context, showName: String): String? {
        if (showName.isBlank()) return null
        init(context)

        val direct = memCache[showName]
        if (!direct.isNullOrBlank()) return direct

        val norm = normalizeKey(showName)
        return memCache[norm]
    }

    /**
     * Cache a discovered feedUrl for a show name.
     */
    fun put(context: Context, showName: String, feedUrl: String) {
        if (showName.isBlank() || feedUrl.isBlank() || !feedUrl.startsWith("http")) return
        init(context)

        val norm = normalizeKey(showName)
        memCache[showName] = feedUrl
        memCache[norm] = feedUrl

        try {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(showName, feedUrl)
                .putString(norm, feedUrl)
                .apply()
            Log.d(TAG, "Cached feedUrl for show '$showName' ($norm): $feedUrl")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist feedUrl for show $showName", e)
        }
    }

    /**
     * For unit tests: seed or clear cache.
     */
    fun clearForTesting() {
        memCache.clear()
        loaded = true
    }
}
