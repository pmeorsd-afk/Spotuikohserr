package com.music.spotui.data.home

import android.content.Context
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.local.TrackListenStat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Home-only local taste. It never reads a Spotify account profile or changes a Spotify playlist. */
@Singleton
class HomeDiscoveryRepository @Inject constructor(
    private val source: PublicSpotifyHomeSource,
    @ApplicationContext context: Context,
) {
    private val file = File(context.filesDir, "home_personal_discovery_v1.json")
    private val mutex = Mutex()
    private var retryAfter = 0L

    internal fun cached(history: List<TrackListenStat>): PersonalHomeCatalog {
        return HomeDiscoveryCache.decode(read(), history, System.currentTimeMillis()) ?: PersonalHomeCatalog()
    }

    internal suspend fun refresh(history: List<TrackListenStat>): PersonalHomeCatalog = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!HomeRecommendationPolicy.ready(history)) {
                file.delete()
                return@withLock PersonalHomeCatalog()
            }
            val previous = cached(history)
            val now = System.currentTimeMillis()
            val root = read()
            val signature = HomeRecommendationPolicy.fingerprint(history)
            if (now < retryAfter || HomeDiscoveryCache.fresh(root, history, now)) return@withLock previous

            // No network request for each play, repeated Home tap or empty/error response.
            retryAfter = now + 5 * 60_000L
            val (result, resolvedSeedIds) = withTimeoutOrNull(90_000L) {
                val seeds = HomeRecommendationPolicy.seeds(history, now)
                val resolved = parallel(seeds) { source.track(it.trackId) }.filterNotNull().associateBy { it.id }
                val taste = HomeRecommendationPolicy.taste(seeds, resolved, now)
                if (taste.artists.isEmpty()) return@withTimeoutOrNull null
                val artistIds = taste.artists.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }
                    .thenBy { it.key }).take(4).map { it.key }
                // These links are returned on the exact artist's public catalog page.
                val linked = parallel(artistIds) { source.artistPlaylists(it).take(4) }.flatten().distinctBy { it.id }
                val publicRadio = source.home().radio
                val candidates = (linked + publicRadio).distinctBy { it.id }.take(36)
                val verified = parallel(candidates) { source.playlist(it.id) }.filterNotNull()
                if (verified.isEmpty()) return@withTimeoutOrNull null
                HomeRecommendationPolicy.compose(taste, verified) to resolved.keys
            } ?: return@withLock previous

            fun array(items: List<HomeItem.Playlist>) = JSONArray().apply {
                items.forEach { HomeFeedItemCodec.encode(it)?.let(::put) }
            }
            val updated = JSONObject().put("version", 1).put("at", now).put("signature", signature)
                .put("rankingVersion", HomeDiscoveryCache.RANKING_VERSION)
                .put("seeds", JSONArray(resolvedSeedIds.toList()))
                .put("mixes", array(result.mixes)).put("radio", array(result.radio)).put("history", array(result.history))
            val temporary = File(file.parentFile, file.name + ".tmp")
            temporary.writeText(updated.toString())
            if (!temporary.renameTo(file)) temporary.delete()
            result
        }
    }

    private fun read(): JSONObject? = runCatching { JSONObject(file.readText()).takeIf { it.optInt("version") == 1 } }.getOrNull()

    private suspend fun <T, R> parallel(items: List<T>, load: suspend (T) -> R): List<R> = coroutineScope {
        val gate = Semaphore(3)
        items.map { item -> async { gate.withPermit { load(item) } } }.awaitAll()
    }
}
