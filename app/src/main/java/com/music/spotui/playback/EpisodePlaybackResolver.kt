package com.music.spotui.playback

import android.content.Context
import android.util.Log
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Interface for resolving podcast episode playback sources.
 * Kept completely independent from music track matching and YouTube search.
 */
interface EpisodePlaybackResolver {
    /**
     * Resolves an episode [SongsModel] into an executable [PlaybackSource].
     * Returns null if no playable source can be determined.
     */
    suspend fun resolve(episode: SongsModel, context: Context): PlaybackSource?
}

/**
 * Production implementation for Phase B:
 * Discovers RSS feeds, parses episode enclosures, performs fuzzy title/duration matching,
 * executes lightweight HTTP validation, and returns [PlaybackSource.DirectAudio].
 */
class DefaultEpisodePlaybackResolver(
    private val feedResolver: PodcastFeedResolver = DefaultPodcastFeedResolver(),
    private val httpClient: OkHttpClient = defaultClient
) : EpisodePlaybackResolver {

    companion object {
        private const val TAG = "EpisodeResolver"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    override suspend fun resolve(episode: SongsModel, context: Context): PlaybackSource? = withContext(Dispatchers.IO) {
        val showName = episode.album.ifBlank { episode.singer }
        Log.i(TAG, "resolve: resolving podcast episode '${episode.title}' for show '$showName' (duration=${episode.durationMs}ms)")

        // 1. Resolve RSS Feed URL (via cache or discovery)
        val feedUrl = feedResolver.resolveFeedUrl(episode, context)
        if (feedUrl.isNullOrBlank()) {
            Log.w(TAG, "resolve: failed to obtain feedUrl for show '$showName'")
            return@withContext null
        }

        // 2. Fetch RSS Feed XML
        val rssXml = feedResolver.fetchFeedXml(feedUrl) ?: fetchFeedXml(feedUrl)
        if (rssXml.isNullOrBlank()) {
            Log.w(TAG, "resolve: failed to fetch RSS feed from $feedUrl")
            return@withContext null
        }

        // 3. Parse RSS Items
        val episodes = PodcastRssParser.parse(rssXml)
        if (episodes.isEmpty()) {
            Log.w(TAG, "resolve: parsed 0 episodes from RSS feed $feedUrl")
            return@withContext null
        }
        Log.d(TAG, "resolve: parsed ${episodes.size} episodes from feed")

        // 4. Match Episode using Title, Duration & Metadata signals
        val matched = PodcastEpisodeMatcher.match(episode, episodes)
        if (matched == null) {
            Log.w(TAG, "resolve: no matching episode found in RSS feed for '${episode.title}' (show='$showName')")
            return@withContext null
        }
        Log.i(TAG, "resolve: matched RSS episode '${matched.title}' (guid='${matched.guid}', duration=${matched.durationMs}ms)")

        // 5. Enclosure Validation
        val enclosureUrl = matched.enclosureUrl
        if (enclosureUrl.isNullOrBlank() || !enclosureUrl.startsWith("http")) {
            Log.w(TAG, "resolve: matched episode has invalid enclosure URL: $enclosureUrl")
            return@withContext null
        }
        val mimeType = matched.enclosureType?.ifBlank { "audio/mpeg" } ?: "audio/mpeg"

        // 6. Lightweight HTTP Validation (1-byte probe / HEAD, no full download)
        val isReachable = validateAudioEnclosure(enclosureUrl)
        if (!isReachable) {
            Log.w(TAG, "resolve: audio enclosure failed lightweight HTTP validation: $enclosureUrl")
            return@withContext null
        }

        Log.i(TAG, "resolve: successfully resolved DirectAudio for '${episode.title}' -> $enclosureUrl ($mimeType)")
        PlaybackSource.DirectAudio(
            url = enclosureUrl,
            mimeType = mimeType
        )
    }

    private fun fetchFeedXml(feedUrl: String): String? {
        val request = Request.Builder()
            .url(feedUrl)
            .header("User-Agent", "Spotui-Podcast-Client/1.0 (Android; RSS)")
            .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "RSS fetch failed with HTTP ${response.code} for $feedUrl")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "RSS fetch exception for $feedUrl: ${e.message}")
            null
        }
    }

    /**
     * Lightweight HTTP validation:
     * Tries a HEAD request, falling back to a 1-byte Range GET (Range: bytes=0-0).
     * Strictly verifies 2xx without downloading the multi-megabyte audio stream.
     */
    fun validateAudioEnclosure(url: String): Boolean {
        // Attempt 1: HEAD
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "Spotui-Podcast-Client/1.0")
            .build()

        try {
            httpClient.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) {
                    return true
                }
            }
        } catch (_: Exception) {
            // Some podcast CDN endpoints reject HEAD requests; fallback to Range GET
        }

        // Attempt 2: 1-byte Range GET
        val rangeRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", "Spotui-Podcast-Client/1.0")
            .build()

        return try {
            httpClient.newCall(rangeRequest).execute().use { response ->
                response.isSuccessful || response.code == 206 || response.code == 200
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio validation failed for $url: ${e.message}")
            false
        }
    }
}
