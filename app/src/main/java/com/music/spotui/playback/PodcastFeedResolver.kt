package com.music.spotui.playback

import android.content.Context
import android.util.Log
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Discovers and resolves RSS feed URLs for podcast episodes.
 * Uses persistent [PodcastFeedCache] to eliminate repeated network calls
 * and queries the iTunes Search API on cache misses.
 */
interface PodcastFeedResolver {
    suspend fun resolveFeedUrl(episode: SongsModel, context: Context): String?
    suspend fun resolveFeedUrlForShow(showName: String, context: Context?): String?
    suspend fun fetchFeedXml(feedUrl: String): String?
}

class DefaultPodcastFeedResolver(
    private val httpClient: OkHttpClient = defaultClient
) : PodcastFeedResolver {

    companion object {
        private const val TAG = "PodcastFeedResolver"
        private const val FEED_CACHE_TTL_MS = 5 * 60 * 1000L // 5 minutes

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        private data class CachedXml(val timestamp: Long, val xml: String)
        private val feedXmlCache = java.util.concurrent.ConcurrentHashMap<String, CachedXml>()
    }

    override suspend fun resolveFeedUrl(episode: SongsModel, context: Context): String? {
        val showName = episode.album.ifBlank { episode.singer }.trim()
        if (showName.isBlank()) {
            Log.w(TAG, "resolveFeedUrl: empty show name for episode '${episode.title}'")
            return null
        }
        return resolveFeedUrlForShow(showName, context)
    }

    override suspend fun resolveFeedUrlForShow(showName: String, context: Context?): String? = withContext(Dispatchers.IO) {
        val trimmedName = showName.trim()
        if (trimmedName.isBlank()) {
            Log.w(TAG, "resolveFeedUrlForShow: empty show name")
            return@withContext null
        }

        // 1. Persistent Cache Check
        if (context != null) {
            val cachedUrl = PodcastFeedCache.get(context, trimmedName)
            if (!cachedUrl.isNullOrBlank()) {
                Log.d(TAG, "resolveFeedUrlForShow: cache hit for show '$trimmedName' -> $cachedUrl")
                return@withContext cachedUrl
            }
        }

        Log.i(TAG, "resolveFeedUrlForShow: cache miss for show '$trimmedName', querying iTunes Search API...")

        // 2. iTunes Search API Discovery
        val discoveredUrl = discoverViaItunes(trimmedName)
        if (!discoveredUrl.isNullOrBlank()) {
            if (context != null) {
                PodcastFeedCache.put(context, trimmedName, discoveredUrl)
            }
            Log.i(TAG, "resolveFeedUrlForShow: discovered and cached feed for show '$trimmedName' -> $discoveredUrl")
            return@withContext discoveredUrl
        }

        // Fallback: If showName has a dash or subtitle (e.g. "קופה ראשית-פודקאסט"), try with primary name
        if (trimmedName.contains("-") || trimmedName.contains(":")) {
            val simplifiedName = trimmedName.split('-', ':').first().trim()
            if (simplifiedName.length >= 3 && simplifiedName != trimmedName) {
                Log.d(TAG, "resolveFeedUrlForShow: retrying with simplified show name '$simplifiedName'...")
                val retryUrl = discoverViaItunes(simplifiedName)
                if (!retryUrl.isNullOrBlank()) {
                    if (context != null) {
                        PodcastFeedCache.put(context, trimmedName, retryUrl)
                        PodcastFeedCache.put(context, simplifiedName, retryUrl)
                    }
                    return@withContext retryUrl
                }
            }
        }

        Log.w(TAG, "resolveFeedUrlForShow: no RSS feed found for show '$trimmedName'")
        null
    }

    override suspend fun fetchFeedXml(feedUrl: String): String? = withContext(Dispatchers.IO) {
        if (feedUrl.isBlank()) return@withContext null

        val cached = feedXmlCache[feedUrl]
        if (cached != null && (System.currentTimeMillis() - cached.timestamp < FEED_CACHE_TTL_MS)) {
            Log.d(TAG, "fetchFeedXml: in-memory cache hit for feed $feedUrl")
            return@withContext cached.xml
        }

        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "Spotui-Podcast-Client/1.0 (Android; RSS)")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "RSS fetch failed with HTTP ${response.code} for $feedUrl")
                    return@withContext null
                }
                val xml = response.body?.string()
                if (!xml.isNullOrBlank()) {
                    feedXmlCache[feedUrl] = CachedXml(System.currentTimeMillis(), xml)
                }
                xml
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS fetch exception for $feedUrl: ${e.message}")
            null
        }
    }

    private fun discoverViaItunes(term: String): String? {
        val encodedTerm = try {
            URLEncoder.encode(term, "UTF-8")
        } catch (e: Exception) {
            term
        }

        val requestUrl = "https://itunes.apple.com/search?term=$encodedTerm&media=podcast&entity=podcast&limit=5"
        val request = Request.Builder()
            .url(requestUrl)
            .header("User-Agent", "Spotui-Podcast-Client/1.0")
            .header("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "iTunes search failed with HTTP ${response.code} for term '$term'")
                    return null
                }
                val body = response.body?.string() ?: return null
                parseItunesFeedUrl(body, term)
            }
        } catch (e: Exception) {
            Log.e(TAG, "iTunes search exception for term '$term': ${e.message}")
            null
        }
    }

    /**
     * Parses iTunes Search API JSON and chooses the best matching podcast feed.
     */
    fun parseItunesFeedUrl(jsonBody: String, requestedTerm: String): String? {
        return try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(jsonBody).let {
                if (it is kotlinx.serialization.json.JsonObject) it else null
            } ?: return null
            val results = root["results"]?.let {
                if (it is kotlinx.serialization.json.JsonArray) it else null
            } ?: return null
            if (results.isEmpty()) return null

            val normTerm = PodcastEpisodeMatcher.normalizeTitle(requestedTerm)

            var bestFeedUrl: String? = null
            var bestScore = -1.0

            for (element in results) {
                val item = (element as? kotlinx.serialization.json.JsonObject) ?: continue
                val feedUrl = item["feedUrl"]?.let {
                    runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull()
                }?.trim() ?: continue
                if (feedUrl.isBlank() || !feedUrl.startsWith("http")) continue

                val collectionName = item["collectionName"]?.let {
                    runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull()
                } ?: ""
                val trackName = item["trackName"]?.let {
                    runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull()
                } ?: ""
                val artistName = item["artistName"]?.let {
                    runCatching { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.getOrNull()
                } ?: ""

                val showTitle = collectionName.ifBlank { trackName }
                val normTitle = PodcastEpisodeMatcher.normalizeTitle(showTitle)

                var score = 0.0
                if (normTitle == normTerm) {
                    score += 50.0
                } else if (normTitle.contains(normTerm) || normTerm.contains(normTitle)) {
                    score += 30.0
                }

                val normArtist = PodcastEpisodeMatcher.normalizeTitle(artistName)
                if (normArtist.isNotBlank() && normTerm.contains(normArtist)) {
                    score += 15.0
                }

                if (score > bestScore || bestFeedUrl == null) {
                    bestScore = score
                    bestFeedUrl = feedUrl
                }
            }

            bestFeedUrl
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse iTunes JSON response: ${e.message}")
            null
        }
    }
}
