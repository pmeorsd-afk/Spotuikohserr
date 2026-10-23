package com.music.spotui.data.home

import com.music.spotui.data.entity.*
import com.music.spotui.data.local.TrackListenStat
import org.junit.Assert.*
import org.junit.Test

class HomeRecommendationPolicyTest {
    private fun id(value: Int) = value.toString().padStart(22, '0')
    private val now = 1_800_000_000_000L
    private fun stat(value: Int, count: Int = 2, at: Long = now) = TrackListenStat(
        id(value), "Song", "Display name is not identity", "", count, 0, at)
    private fun track(value: Int, artist: Int) = CatalogTrack(id(value), listOf(CatalogArtist(id(artist), "Name")))
    private fun candidate(value: Int, artist: Int, radio: Boolean = false) = CatalogPlaylist(
        HomeItem.Playlist("Same title", "", "", id(value)), listOf(track(1000 + value, artist), track(2000 + value, artist)), radio)
    private fun taste(artist: Int): HomeTaste {
        val history = listOf(stat(1), stat(2))
        return HomeRecommendationPolicy.taste(history, mapOf(id(1) to track(1, artist), id(2) to track(2, artist)), now)
    }

    @Test fun twoGuestHistoriesChooseDifferentExistingPlaylistsFromSameCatalog() {
        val pool = listOf(candidate(10, 100), candidate(11, 200), candidate(12, 100, true), candidate(13, 200, true))
        val a = HomeRecommendationPolicy.compose(taste(100), pool)
        val b = HomeRecommendationPolicy.compose(taste(200), pool)
        assertEquals(listOf(id(10)), a.mixes.map { it.id })
        assertEquals(listOf(id(11)), b.mixes.map { it.id })
        assertEquals(listOf(id(12)), a.radio.map { it.id })
        assertEquals(listOf(id(13)), b.radio.map { it.id })
    }

    @Test fun playlistNameDoesNotProveAnArtistMatch() {
        val misleading = candidate(10, 200).let { it.copy(item = it.item.copy(name = "Name Mix")) }
        assertTrue(HomeRecommendationPolicy.compose(taste(100), listOf(misleading)).mixes.isEmpty())
    }

    @Test fun playbackStartAndPodcastsCannotCreateMusicTaste() {
        val records = listOf(stat(1, 0), stat(2).copy(mediaType = MediaType.PODCAST_EPISODE),
            stat(3).copy(originalUrl = "episode:show:guid"), stat(4).copy(trackId = "youtube:abc"),
            stat(5).copy(originalUrl = "spotify:episode:${id(5)}"),
            stat(6).copy(podcastShowId = id(50)), stat(7).copy(spotifyEpisodeId = id(7)))
        assertTrue(HomeRecommendationPolicy.musicHistory(records).isEmpty())
        assertFalse(HomeRecommendationPolicy.ready(records))
    }

    @Test fun oneAccidentalSongDoesNotPersonalizeEveryShelf() {
        assertFalse(HomeRecommendationPolicy.ready(listOf(stat(1, 30))))
        assertFalse(HomeRecommendationPolicy.ready(listOf(stat(1, 1), stat(2, 1))))
        assertTrue(HomeRecommendationPolicy.ready(listOf(stat(1, 2), stat(2, 1))))
    }

    @Test fun unresolvedOrContradictoryTrackIdentityDoesNotBecomeAnArtistSeed() {
        assertTrue(HomeRecommendationPolicy.taste(listOf(stat(1), stat(2)),
            mapOf(id(1) to track(9, 100), id(2) to track(2, 100)), now).artists.isEmpty())
    }

    @Test fun duplicatesCannotManufactureTwoMatchingTracks() {
        val same = track(1, 100)
        val c = candidate(10, 100).copy(tracks = listOf(same, same, same))
        assertTrue(HomeRecommendationPolicy.rank(taste(100).artists, listOf(c), false).isEmpty())
    }

    @Test fun equalScoresRemainStableWhenNetworkCompletionOrderChanges() {
        val candidates = listOf(candidate(11, 100), candidate(10, 100))
        assertEquals(HomeRecommendationPolicy.rank(taste(100).artists, candidates, false),
            HomeRecommendationPolicy.rank(taste(100).artists, candidates.reversed(), false))
        assertEquals(id(10), HomeRecommendationPolicy.rank(taste(100).artists, candidates, false).first().item.id)
    }

    @Test fun oneArtistCannotFillTheWholeShelf() {
        val ranked = HomeRecommendationPolicy.rank(mapOf(id(100) to .6, id(200) to .4),
            listOf(candidate(10, 100), candidate(11, 100), candidate(12, 100), candidate(13, 200)), false)
        assertEquals(2, ranked.count { it.artistId == id(100) })
        assertTrue(ranked.any { it.artistId == id(200) })
    }

    @Test fun recentListeningReceivesMoreWeightAndCompletionCounts() {
        assertTrue(HomeRecommendationPolicy.weight(stat(1), now) >
            HomeRecommendationPolicy.weight(stat(1, at = now - 28 * 86_400_000L), now))
        assertTrue(HomeRecommendationPolicy.weight(stat(1).copy(completedCount = 1), now) >
            HomeRecommendationPolicy.weight(stat(1), now))
    }

    @Test fun recentTasteUsesRecentSongsEvenWhenAnOlderArtistHasHigherTotal() {
        val records = (1..6).map { stat(it, if (it == 1) 100 else 2, now - (7-it)*1000) }
        val resolved = records.associate { it.trackId to CatalogTrack(it.trackId,
            listOf(CatalogArtist(id(if (it == records.first()) 100 else 200), "Name"))) }
        val result = HomeRecommendationPolicy.taste(records, resolved, now)
        assertTrue(result.artists.getValue(id(100)) > result.artists.getValue(id(200)))
        assertEquals(setOf(id(200)), result.recentArtists.keys)
    }

    @Test fun playbackStartsDoNotInvalidateTheDiscoveryFingerprint() {
        val history = listOf(stat(1), stat(2))
        assertEquals(HomeRecommendationPolicy.fingerprint(history), HomeRecommendationPolicy.fingerprint(history + stat(3, 0)))
        assertNotEquals(HomeRecommendationPolicy.fingerprint(history), HomeRecommendationPolicy.fingerprint(history + stat(3, 1)))
    }

    @Test fun boundedSeedSelectionIncludesRecentListeningAlongsideOlderFavorites() {
        val olderFavorites = (1..20).map { stat(it, 100, now - 86_400_000L) }
        val recentSongs = (21..25).map { stat(it, 1, now - it * 1000L) }
        val selected = HomeRecommendationPolicy.seeds(olderFavorites + recentSongs, now)
        assertEquals(12, selected.size)
        assertTrue(selected.map { it.trackId }.containsAll(recentSongs.map { it.trackId }))
        assertEquals(7, selected.count { it.playCount == 100 })
        assertEquals(selected, HomeRecommendationPolicy.seeds((olderFavorites + recentSongs).reversed(), now))
    }

    @Test fun listeningUpdatesKeepVisibleCarouselsButUpdateRecents() {
        val old = HomeFeedModel(sections = listOf(HomeSection(HomeSectionIds.TOP_MIXES, "Old", items = listOf(candidate(10, 100).item)),
            HomeSection(HomeSectionIds.RECENTLY_PLAYED, "Old recents")))
        val next = old.copy(sections = listOf(HomeSection(HomeSectionIds.TOP_MIXES, "New", items = listOf(candidate(11, 200).item)),
            HomeSection(HomeSectionIds.RECENTLY_PLAYED, "New recents")))
        val result = HomeRecommendationPolicy.keepVisibleDiscovery(next, old)
        assertEquals(old.sections[0], result.sections[0])
        assertEquals("New recents", result.sections[1].title)
    }

    @Test fun popularAlbumsAreIndependentOfLocalTasteAndKeepProviderOrder() {
        val albums = listOf(HomeItem.Album("B", "", "", spotifyId = id(21)), HomeItem.Album("A", "", "", spotifyId = id(20)))
        val public = PublicHomeCatalog(albums, listOf(candidate(12, 100, true).item))
        fun albumsFor(artist: Int) = HomeDiscoveryPresentation.apply(HomeFeedModel(), public,
            HomeRecommendationPolicy.compose(taste(artist), listOf(candidate(10, 100), candidate(11, 200))))
            .sections.single { it.id == HomeSectionIds.POPULAR_ALBUMS }.items
        assertEquals(albums, albumsFor(100))
        assertEquals(albumsFor(100), albumsFor(200))
    }

    @Test fun coldStartDoesNotLabelGenericDiscoveryAsPersonal() {
        val feed = HomeDiscoveryPresentation.apply(HomeFeedModel(), PublicHomeCatalog(discovery = listOf(candidate(10, 100).item)), PersonalHomeCatalog())
        assertEquals("פלייליסטים לגלות", feed.sections.single().title)
        assertTrue(feed.sections.none { it.id == HomeSectionIds.HISTORY_BASED })
    }

    @Test fun legacyNewReleasesAndKeywordHistoryDoNotSurviveAsVerifiedShelves() {
        val old = HomeFeedModel(sections = listOf(HomeSection(HomeSectionIds.POPULAR_ALBUMS, "Albums"),
            HomeSection(HomeSectionIds.HISTORY_BASED, "History"), HomeSection(HomeSectionIds.TOP_MIXES, "Your mixes")))
        val migrated = HomeDiscoveryPresentation.migrateLegacy(old)
        assertEquals(1, migrated.sections.size)
        assertEquals("פלייליסטים לגלות", migrated.sections.single().title)
    }
}
