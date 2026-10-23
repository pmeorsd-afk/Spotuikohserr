package com.music.spotui.playback

import com.music.spotui.data.entity.*
import com.music.spotui.ui.collections.*
import org.junit.Assert.*
import org.junit.Test

class CollectionRecommendationsTest {
    private fun id(n: Int) = n.toString().padStart(22, '0')
    private fun song(artist: String, type: MediaType = MediaType.TRACK) = SongsModel(1, "Title", "", artist, "", "source", mediaType = type)
    private fun candidate(n: Int, title: String, seed: String = "Artist", rank: Int = 0, owner: String = "Spotify") =
        CollectionCandidate(HomeItem.Playlist(title, "https://example.com/$n.jpg", owner, id(n)), seed, rank)

    @Test fun radioNamesDoNotMisclassifyRadioheadOrOrdinaryPlaylists() {
        assertTrue(isRadioCollection("Omer Adam Radio")); assertTrue(isRadioCollection("רדיו לפי אושר כהן"))
        assertFalse(isRadioCollection("Radiohead Mix")); assertFalse(isRadioCollection("Beach Party"))
    }
    @Test fun seedsUseMostRepresentedMusicArtistsWithoutPodcastPublishers() {
        val songs = listOf(song("B"), song("A"), song("A"), song("Podcaster", MediaType.PODCAST_EPISODE), song(""))
        assertEquals(listOf("A", "B"), CollectionRecommendations.seeds(songs))
    }
    @Test fun rankingUsesCatalogRelevanceAndPersonalAffinity() {
        val candidates = listOf(candidate(1, "Other playlist", rank = 0), candidate(2, "Artist classics", rank = 8),
            candidate(3, "Favorite collection", seed = "Favorite", rank = 8, owner = "Owner"))
        assertEquals(listOf(id(2), id(3), id(1)), CollectionRecommendations.rank(id(9),
            listOf("Artist", "Favorite"), candidates, emptySet()).map { it.id })
        val result = CollectionRecommendations.rank(id(9), listOf("Artist", "Favorite"), candidates, setOf("Favorite"))
        assertEquals(listOf(id(3), id(2), id(1)), result.map { it.id })
    }
    @Test fun excludesCurrentInvalidUnrelatedAndDuplicateIds() {
        val target = candidate(1, "Artist playlist")
        val input = listOf(target, target, candidate(2, "Artist", seed = "unrelated"), candidate(3, "Artist"),
            candidate(4, "Artist").let { it.copy(item = it.item.copy(id = "fake")) })
        assertEquals(listOf(id(3)), CollectionRecommendations.rank(id(1), listOf("Artist"), input, emptySet()).map { it.id })
    }
    @Test fun resultsAreBoundedDiverseAndDeterministicWithoutInventingFallback() {
        val items = (1..20).map { candidate(it, "Artist collection", owner = if (it <= 10) "Owner A" else "Owner B") }
        val ranked = CollectionRecommendations.rank(id(99), listOf("Artist"), items, emptySet())
        assertEquals(6, ranked.size); assertTrue(ranked.groupBy { it.subtitle }.all { it.value.size <= 3 })
        assertEquals(ranked, CollectionRecommendations.rank(id(99), listOf("Artist"), items.reversed(), emptySet()))
        assertTrue(CollectionRecommendations.rank(id(99), emptyList(), items, emptySet()).isEmpty())
    }
}
