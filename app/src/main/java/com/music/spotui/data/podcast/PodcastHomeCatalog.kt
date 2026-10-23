package com.music.spotui.data.podcast

import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.playback.ExternalMediaIdentityResolver
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.playback.SharedResolution
import com.music.spotui.util.PodcastDateFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

data class PodcastCatalog(val show: PodcastModel, val episodes: List<PodcastEpisodeUiModel>, val updatedAt: Long)
data class PodcastCatalogResult(val catalog: PodcastCatalog?, val failed: Boolean = false)

/** Metadata only. Playback must still resolve the exact episode through the existing RSS path. */
internal fun PodcastEpisodeUiModel.hasExactIdentity(): Boolean {
    if (id.isBlank() || id != playUrl || showId.isBlank() || title.isBlank()) return false
    return when (val identity = ExternalMediaIdentityResolver.parse(playUrl)) {
        is ExternalMediaIdentityResolver.Identity.InternalEpisode -> identity.showId == showId
        is ExternalMediaIdentityResolver.Identity.SpotifyEpisode -> identity.id == spotifyEpisodeId
        else -> false
    }
}

/** Coalesces catalog work across Home sections; cancelling one screen does not cancel other readers. */
internal class PodcastCatalogLoader(
    scope: CoroutineScope,
    initial: List<PodcastCatalog> = emptyList(),
    private val now: () -> Long = System::currentTimeMillis,
    private val persist: (List<PodcastCatalog>) -> Unit = {},
    private val fetch: suspend (PodcastModel) -> List<PodcastEpisodeUiModel>,
) {
    private val lock = Any()
    private val cache = LinkedHashMap<String, PodcastCatalog>().apply {
        initial.takeLast(24).forEach { put(it.show.id, it) }
    }
    private val requests = SharedResolution<String, PodcastCatalogResult>(scope)
    private val permits = Semaphore(2)

    fun peek(): List<PodcastCatalog> = synchronized(lock) { cache.values.toList() }

    suspend fun load(show: PodcastModel, force: Boolean = false): PodcastCatalogResult {
        if (show.id.isBlank() || show.name.isBlank()) return PodcastCatalogResult(null, true)
        val old = synchronized(lock) { cache[show.id] }
        if (!force && old != null && now() - old.updatedAt in 0 until 3_600_000L) {
            return PodcastCatalogResult(old)
        }
        return requests.get(show.id) {
            try {
                val episodes = permits.withPermit { withTimeoutOrNull(25_000) { fetch(show) } }
                    ?: return@get PodcastCatalogResult(old, true)
                val valid = PodcastDateFormatter.sortByPubDateDescending(
                    episodes.filter { it.showId == show.id && it.hasExactIdentity() }.distinctBy { it.id }
                ) { it.pubDate }.take(8)
                val catalog = PodcastCatalog(show, valid, now())
                synchronized(lock) {
                    cache.remove(show.id)
                    cache[show.id] = catalog
                    while (cache.size > 24) cache.remove(cache.keys.first()) // Cache eviction, never media selection.
                    persist(cache.values.toList())
                }
                PodcastCatalogResult(catalog)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                PodcastCatalogResult(old, true)
            }
        }
    }
}

data class PodcastHomeSignals(
    val followed: Set<String> = emptySet(),
    val affinity: Map<String, Double> = emptyMap(),
    val preferredTopics: Set<String> = emptySet(),
    val completed: Set<String> = emptySet(),
    val continuing: Set<String> = emptySet(),
)
data class PodcastHomeSections(
    val recommended: List<PodcastEpisodeUiModel> = emptyList(),
    val latest: List<PodcastEpisodeUiModel> = emptyList(),
    val continuing: List<PodcastEpisodeUiModel> = emptyList(),
    val following: List<PodcastEpisodeUiModel> = emptyList(),
)

internal object PodcastHomeRanking {
    fun key(episode: PodcastEpisodeUiModel): String? = PlaybackProgressStore.key(episode.toSongModel())

    fun build(catalogs: List<PodcastCatalog>, signals: PodcastHomeSignals, seed: Long, now: Long,
              savedContinuing: List<PodcastEpisodeUiModel> = emptyList()): PodcastHomeSections {
        val episodes = (catalogs.flatMap { it.episodes } + savedContinuing).filter { it.hasExactIdentity() }.distinctBy { it.id }
        val chronological = PodcastDateFormatter.sortByPubDateDescending(episodes) { it.pubDate }
        val continuing = chronological.filter { key(it) in signals.continuing && key(it) !in signals.completed }.take(3)
        val used = continuing.map { it.id }.toMutableSet()
        val shows = catalogs.associate { it.show.id to it.show }
        val random = Random(seed)
        // Seed is fixed for the visible session; refresh/next session rotates without changing identity.
        val scores = episodes.sortedBy { it.id }.associate { episode ->
            val ageDays = PodcastDateFormatter.parsePubDateToMillis(episode.pubDate)?.let {
                ((now - it).coerceAtLeast(0) / 86_400_000.0)
            }
            val fresh = ageDays?.let { 12.0 / (1.0 + it / 7.0) } ?: 0.0
            val topics = shows[episode.showId]?.topics.orEmpty().map { it.lowercase() }
            episode.id to (signals.affinity.getOrDefault(episode.showId, 0.0).coerceAtMost(20.0) +
                (if (episode.showId in signals.followed) 12.0 else 0.0) +
                (if (topics.any { it in signals.preferredTopics }) 7.0 else 0.0) + fresh + random.nextDouble(0.0, 12.0))
        }
        val counts = mutableMapOf<String, Int>()
        val recommended = episodes.filter { it.id !in used && key(it) !in signals.completed }
            .sortedByDescending { scores[it.id] }.filter {
                val count = counts.getOrDefault(it.showId, 0)
                if (count >= 2) false else { counts[it.showId] = count + 1; true }
            }.take(6)
        used.addAll(recommended.map { it.id })
        val following = chronological.filter { it.showId in signals.followed }
        val latestPool = if (signals.followed.isNotEmpty()) following else chronological
        // A date-less episode is playable, but must not be advertised as new.
        val latest = latestPool.filter { it.id !in used && PodcastDateFormatter.parsePubDateToMillis(it.pubDate) != null }.take(8)
        return PodcastHomeSections(recommended, latest, continuing, following)
    }
}

/** Only explicit chooser edits are committed; unrelated changes made while it is open survive. */
internal object PodcastFollowEdits {
    fun apply(current: List<PodcastModel>, desired: Map<String, Pair<PodcastModel, Boolean>>): List<PodcastModel> {
        val merged = current.associateBy { it.id }.toMutableMap()
        desired.forEach { (id, edit) ->
            if (id.isNotBlank() && edit.first.id == id && edit.first.name.isNotBlank()) {
                if (edit.second) merged[id] = edit.first else merged.remove(id)
            }
        }
        return merged.values.toList()
    }
}
