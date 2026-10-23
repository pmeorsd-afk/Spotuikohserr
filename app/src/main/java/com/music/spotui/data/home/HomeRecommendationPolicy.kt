package com.music.spotui.data.home

import com.music.spotui.data.entity.*
import com.music.spotui.data.local.TrackListenStat
import java.security.MessageDigest
import kotlin.math.exp
import kotlin.math.ln

internal data class CatalogArtist(val id: String, val name: String)
internal data class CatalogTrack(val id: String, val artists: List<CatalogArtist>)
internal data class CatalogPlaylist(val item: HomeItem.Playlist, val tracks: List<CatalogTrack>, val radio: Boolean)
internal data class PublicHomeCatalog(
    val albums: List<HomeItem.Album> = emptyList(),
    val radio: List<HomeItem.Playlist> = emptyList(),
    val discovery: List<HomeItem.Playlist> = emptyList(),
)
internal data class HomeTaste(val artists: Map<String, Double>, val recentArtists: Map<String, Double>)
internal data class RankedHomePlaylist(val item: HomeItem.Playlist, val matchedTracks: Int, val artistId: String, val score: Double)
internal data class PersonalHomeCatalog(
    val mixes: List<HomeItem.Playlist> = emptyList(),
    val radio: List<HomeItem.Playlist> = emptyList(),
    val history: List<HomeItem.Playlist> = emptyList(),
)

/** Pure, explainable ranking. Names/cover URLs never establish artist or track identity. */
internal object HomeRecommendationPolicy {
    private val idPattern = Regex("[A-Za-z0-9]{22}")
    private const val DAY = 86_400_000.0
    fun validId(id: String) = idPattern.matches(id)

    fun musicHistory(history: List<TrackListenStat>): List<TrackListenStat> = history.filter {
        it.mediaType == MediaType.TRACK && it.podcastShowId.isBlank() && it.spotifyEpisodeId.isBlank() &&
            !it.originalUrl.startsWith("episode:") && !it.originalUrl.startsWith("spotify:episode:") &&
            validId(it.trackId) && (it.playCount > 0 || it.completedCount > 0)
    }.distinctBy { it.trackId }

    fun ready(history: List<TrackListenStat>): Boolean = musicHistory(history).let {
        it.size >= 2 && it.sumOf { stat -> maxOf(stat.playCount, stat.completedCount).coerceAtLeast(0) } >= 3
    }

    fun weight(stat: TrackListenStat, now: Long): Double =
        (stat.playCount.coerceAtLeast(0) + stat.completedCount.coerceAtLeast(0) * 1.5) *
            exp(-((now - stat.lastPlayedAt).coerceAtLeast(0) / DAY) * ln(2.0) / 14.0)

    fun seeds(history: List<TrackListenStat>, now: Long): List<TrackListenStat> {
        val music = musicHistory(history)
        val strongest = music.sortedWith(compareByDescending<TrackListenStat> { weight(it, now) }
            .thenBy { it.trackId })
        val recent = music.sortedWith(compareByDescending<TrackListenStat> { it.lastPlayedAt }.thenBy { it.trackId }).take(5)
        // Reserve space for recent listening even when older favorites have many more plays.
        return (strongest.take(7) + recent + strongest).distinctBy { it.trackId }.take(12)
    }

    fun fingerprint(history: List<TrackListenStat>): String {
        val input = musicHistory(history).sortedBy { it.trackId }.joinToString(";") {
            "${it.trackId}:${it.playCount}:${it.completedCount}:${it.lastPlayedAt / 86_400_000L}"
        }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun taste(history: List<TrackListenStat>, resolved: Map<String, CatalogTrack>, now: Long): HomeTaste {
        val exact = musicHistory(history).filter { resolved[it.trackId]?.id == it.trackId }
        if (!ready(exact)) return HomeTaste(emptyMap(), emptyMap())
        fun aggregate(records: List<TrackListenStat>): Map<String, Double> {
            val scores = mutableMapOf<String, Double>()
            for (stat in records) {
                val artists = resolved.getValue(stat.trackId).artists.filter { validId(it.id) }.distinctBy { it.id }
                for (artist in artists) scores[artist.id] = scores.getOrDefault(artist.id, 0.0) +
                    weight(stat, now) / artists.size.coerceAtLeast(1)
            }
            val total = scores.values.sum()
            return if (total > 0) scores.mapValues { it.value / total } else emptyMap()
        }
        return HomeTaste(aggregate(exact), aggregate(exact.sortedByDescending { it.lastPlayedAt }.take(5)))
    }

    fun rank(affinity: Map<String, Double>, candidates: List<CatalogPlaylist>, radio: Boolean,
             excluded: Set<String> = emptySet()): List<RankedHomePlaylist> {
        val scored = candidates.filter { it.radio == radio && validId(it.item.id) &&
            it.item.id !in excluded && it.item.name.isNotBlank() }.distinctBy { it.item.id }.mapNotNull { candidate ->
            val tracks = candidate.tracks.filter { validId(it.id) }.distinctBy { it.id }
            val matches = tracks.mapNotNull { track ->
                track.artists.mapNotNull { artist -> affinity[artist.id]?.takeIf { it > 0 }?.let { artist.id to it } }
                    .sortedWith(compareByDescending<Pair<String, Double>> { it.second }.thenBy { it.first }).firstOrNull()
            }
            // A repeated track or a matching playlist title cannot satisfy this threshold.
            if (matches.size < 2) return@mapNotNull null
            val dominant = matches.groupBy { it.first }.mapValues { (_, values) -> values.sumOf { it.second } }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key }).first().key
            val score = matches.sumOf { it.second } / tracks.size.coerceAtLeast(1)
            RankedHomePlaylist(candidate.item, matches.size, dominant, score)
        }.sortedWith(compareByDescending<RankedHomePlaylist> { it.score }.thenByDescending { it.matchedTracks }.thenBy { it.item.id })
        val artistCounts = mutableMapOf<String, Int>()
        return scored.filter {
            val count = artistCounts.getOrDefault(it.artistId, 0)
            if (count >= 2) false else { artistCounts[it.artistId] = count + 1; true }
        }.take(8)
    }

    fun compose(taste: HomeTaste, candidates: List<CatalogPlaylist>): PersonalHomeCatalog {
        val mixes = rank(taste.artists, candidates, radio = false).map { it.item }
        val radio = rank(taste.artists, candidates, radio = true).map { it.item }
        val topMixIds = mixes.take(3).map { it.id }.toSet()
        val recent = rank(taste.recentArtists, candidates, radio = false)
        // Prefer other verified matches, but overlap must not erase a small legitimate history shelf.
        val history = recent.sortedBy { it.item.id in topMixIds }.map { it.item }
        return PersonalHomeCatalog(mixes, radio, history)
    }

    val discoveryIds = setOf(HomeSectionIds.TOP_MIXES, HomeSectionIds.POPULAR_RADIO, HomeSectionIds.HISTORY_BASED,
        HomeSectionIds.POPULAR_ALBUMS)

    /** Listening events may update recents, but never reorder a carousel under an active gesture. */
    fun keepVisibleDiscovery(next: HomeFeedModel, visible: HomeFeedModel?): HomeFeedModel {
        if (visible == null) return next
        val previous = visible.sections.filter { it.id in discoveryIds }.associateBy { it.id }
        if (previous.isEmpty()) return next
        val sections = next.sections.map { if (it.id in discoveryIds) previous[it.id]?.takeIf { old -> old.items.isNotEmpty() } ?: it else it }.toMutableList()
        previous.values.filter { old -> sections.none { it.id == old.id } }.forEach { sections.add(it) }
        val positions = visible.sections.mapIndexed { index, section -> section.id to index }.toMap()
        // Newly arrived shelves append during this visit; existing rows keep their order and geometry.
        // A deliberate Home refresh/re-entry restores canonical shelf order.
        return next.copy(sections = sections.sortedBy { positions[it.id] ?: Int.MAX_VALUE })
    }
}
