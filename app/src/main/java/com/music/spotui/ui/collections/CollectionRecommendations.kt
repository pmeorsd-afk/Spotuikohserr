package com.music.spotui.ui.collections

import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import java.util.Locale

internal fun isRadioCollection(name: String): Boolean =
    Regex("(?:^|[\\s•|:–-])(?:radio|רדיו)(?:$|[\\s•|:–-])", RegexOption.IGNORE_CASE).containsMatchIn(name)

internal data class CollectionCandidate(val item: HomeItem.Playlist, val seed: String, val sourceRank: Int)

internal object CollectionRecommendations {
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    fun seeds(songs: List<SongsModel>): List<String> = songs.filter { !it.isPodcast() && it.singer.isNotBlank() }
        .groupingBy { it.singer.trim() }.eachCount().entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { normalized(it.key) }).take(3).map { it.key }

    /** Candidates come from real artist-specific catalog searches; no guessed IDs or random fallback. */
    fun rank(currentId: String, seeds: List<String>, candidates: List<CollectionCandidate>, listenedArtists: Set<String>): List<HomeItem.Playlist> {
        val interest = listenedArtists.map(::normalized).toSet()
        val validSeeds = seeds.map(::normalized).toSet()
        val scored = candidates.filter { it.item.id != currentId && it.item.id.matches(Regex("[a-zA-Z0-9]{22}")) &&
            it.item.name.isNotBlank() && normalized(it.seed) in validSeeds }.map { candidate ->
            val seed = normalized(candidate.seed)
            val words = normalized(candidate.item.name + " " + candidate.item.subtitle)
            val relevance = (if (words.contains(seed)) 100 else 0) + (if (seed in interest) 25 else 0) +
                (24 - candidate.sourceRank.coerceIn(0, 24))
            candidate to relevance
        }.sortedWith(compareByDescending<Pair<CollectionCandidate, Int>> { it.second }.thenBy { it.first.item.id })
        val owners = mutableMapOf<String, Int>()
        return scored.distinctBy { it.first.item.id }.filter { (candidate, _) ->
            val owner = normalized(candidate.item.subtitle)
            val count = owners.getOrDefault(owner, 0)
            if (count >= 3) false else { owners[owner] = count + 1; true }
        }.take(6).map { it.first.item }
    }
}
