package com.music.spotui.playback

import com.music.spotui.data.entity.*
import com.music.spotui.data.preferences.HistoryEntry
import com.music.spotui.data.preferences.ListeningHistoryLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class AutoHomeContentTest {
    private fun song(n: Int) = SongsModel(n, "Same title", "Album", "Artist", "cover/$n",
        "spotify:track:${n.toString().padStart(22, '0')}", n.toString().padStart(22, '0'))
    private fun playlist(id: String) = HomeItem.Playlist("Same station title", "cover/$id", "Spotify", id)
    private val liked = HomeItem.LikedSongs(2)
    private fun groups(feed: HomeFeedModel = HomeFeedModel(), history: List<HistoryEntry> = emptyList(),
        stations: List<HomeItem.Playlist> = emptyList()) = AutoHomeContent.groups(
        AutoHomeContent.Snapshot(feed, stations), AutoHomeContent.recent(history), "Recent", "Stations", "Library")

    @Test fun newListenerHasNoPretendHistoryFromPhoneFallbacks() {
        val feed = HomeFeedModel(topGrid = listOf(HomeItem.Track(song(2))), sections = listOf(
            HomeSection(id = HomeSectionIds.RECENTLY_PLAYED, title = "לאחרונה",
                items = listOf(liked, HomeItem.Track(song(1)), playlist("saved"))),
            HomeSection(title = "Mixes", items = listOf(playlist("mix")))))
        assertEquals(listOf("Library", "Mixes"), groups(feed).map { it.title })
        assertEquals(listOf(liked, playlist("saved")), groups(feed)[0].items)
    }

    @Test fun firstSuccessfulPlayPublishesHistoryWithoutAFeedRefresh() = runBlocking {
        val log = MutableStateFlow(emptyList<HistoryEntry>())
        val presentations = mutableListOf<List<AutoHomeContent.Group>>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            log.map { groups(history = it) }.collect { presentations += it }
        }
        val ownership = PlaybackOwnership()
        val recorder = PlaybackHistoryRecorder()
        val track = song(1)
        val ticket = ownership.begin(track.url, track.mediaType, track)
        fun observe(playing: Boolean) = recorder.onPlayingSnapshot(ownership, ticket.token, true, playing) {
            log.value = ListeningHistoryLog.append(log.value, HistoryEntry.fromSong(it, 10))
        }
        try {
            observe(true) // Source is not active yet.
            ownership.activate(ticket.token)
            observe(false) // Prepared, but paused.
            yield()
            assertEquals(listOf(emptyList<AutoHomeContent.Group>()), presentations)
            observe(true)
            yield()
            assertEquals(listOf(HomeItem.Track(track)), presentations.last().single().items)
            observe(false); observe(true); yield()
            assertEquals(2, presentations.size)
        } finally { observer.cancelAndJoin() }
    }

    @Test fun recentIsNewestFirstDistinctByTypedIdentityAndBoundedAfterFiltering() {
        val history = (1..12).map { HistoryEntry.fromSong(song(it), it.toLong()) } +
            HistoryEntry.fromSong(song(1).copy(coverUri = "latest"), 20) +
            HistoryEntry(100, 50, "Metadata only", "Unknown") +
            HistoryEntry.fromSong(song(2).copy(url = "spotify:episode:${song(2).spotifyTrackId}"), 99)
        val recent = AutoHomeContent.recent(history)
        assertEquals(listOf(1, 12, 11, 10, 9, 8, 7, 6), recent.map { it.id })
        assertEquals("latest", recent[0].coverUri)
    }

    @Test fun persistedEpisodeKeepsDirectIdentityAndNeverBecomesAMusicTrackOrAlbum() {
        val episode = song(1).copy(mediaType = MediaType.PODCAST_EPISODE,
            url = "episode:show:guid", podcastShowId = "show")
        val original = listOf(HistoryEntry.fromSong(episode, 20), HistoryEntry.fromSong(song(1), 10))
        val restored = ListeningHistoryLog.decode(ListeningHistoryLog.encode(original))
        val recent = AutoHomeContent.recent(restored)
        assertEquals(listOf(episode, song(1)), recent)
        assertNotEquals(AutoMediaCatalog.id("home", recent[0]), AutoMediaCatalog.id("home", recent[1]))
        assertEquals(original.map { HomeItem.Track(it.song!!) }, groups(history = restored).single().items)
    }

    @Test fun stationsUseOnlyTheActualRemoteShelfAndPreservePlaylistIdentityAndOrder() {
        val a = playlist("A")
        val b = playlist("B")
        val raw = HomeFeedModel(sections = listOf(
            HomeSection(title = "Popular radio", items = listOf(playlist("search-result"))),
            HomeSection(title = " Recommended   Stations ", items = listOf(a, b, a,
                HomeItem.Artist("Radio artist", "cover", "artist"), a.copy(id = ""))),
            HomeSection(title = "תחנות מומלצות", items = listOf(b, playlist("C")))))
        assertEquals(listOf(a, b, playlist("C")), AutoHomeContent.recommendedStations(raw))
        assertTrue(AutoHomeContent.recommendedStations(raw.copy(sections = raw.sections.take(1))).isEmpty())
        assertTrue(AutoHomeContent.recommendedStations(null).isEmpty())
    }

    @Test fun historyThenStationsThenAllOtherSectionsWithoutDuplicatingExactEntities() {
        val a = playlist("A")
        val b = playlist("B") // Same name, distinct playlist: keep both.
        val track = HomeItem.Track(song(1))
        val feed = HomeFeedModel(sections = listOf(
            HomeSection(title = "Mixes", items = listOf(a, b, track, HomeItem.Track(song(2)))),
            HomeSection(id = HomeSectionIds.RECENTLY_PLAYED, title = "לאחרונה", items = listOf(liked, track)),
            HomeSection(title = "Albums", items = listOf(HomeItem.Album("Album", "cover", "Artist", spotifyId = "A")))))
        val result = groups(feed, listOf(HistoryEntry.fromSong(song(1))), listOf(a))
        assertEquals(listOf("Recent", "Stations", "Mixes", "Library", "Albums"), result.map { it.title })
        assertEquals(listOf(b, HomeItem.Track(song(2))), result[2].items)
        assertEquals(6, result.sumOf { it.items.size })
        assertEquals(listOf(a, b, track, HomeItem.Track(song(2))), feed.sections[0].items)
    }

    @Test fun deletingHistoryDoesNotResurrectThePhonesCachedRecentTrack() {
        val feed = HomeFeedModel(sections = listOf(HomeSection(id = HomeSectionIds.RECENTLY_PLAYED,
            title = "לאחרונה", items = listOf(HomeItem.Track(song(1)), liked))))
        assertEquals(listOf("Recent", "Library"), groups(feed, listOf(HistoryEntry.fromSong(song(1)))).map { it.title })
        assertEquals(listOf(AutoHomeContent.Group("Library", listOf(liked))), groups(feed))
        assertEquals(listOf("Recent"), groups(history = listOf(HistoryEntry.fromSong(song(1)))).map { it.title })
    }
}
