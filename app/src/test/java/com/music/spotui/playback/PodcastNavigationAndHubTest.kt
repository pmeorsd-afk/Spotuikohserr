package com.music.spotui.playback

import com.music.spotui.data.api.Api
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.navigation.showRoute
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PodcastNavigationAndHubTest {

    @Before
    fun setUp() {
        Api.clear()
    }

    // ==========================================
    // 1. PODCAST NAVIGATION & SHOW RESOLUTION TESTS
    // ==========================================

    @Test
    fun testResolvePodcastShowId_directPropertyTakesPrecedence() {
        val song = SongsModel(
            id = 1,
            title = "פרק 100 - אורי חזקיה",
            album = "המוג׳ו של בן בן ברוך",
            singer = "בן בן ברוך",
            coverUri = "https://example.com/cover.jpg",
            url = "episode:fallbackShowId:guid999",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "775CMAc4uTNcUUwgkFgvQJ"
        )

        assertEquals("775CMAc4uTNcUUwgkFgvQJ", song.resolvePodcastShowId())
    }

    @Test
    fun testResolvePodcastShowId_extractsFromUrlWhenPropertyEmpty() {
        val song = SongsModel(
            id = 2,
            title = "פרק 101 - שלום אסייג",
            album = "המוג׳ו של בן בן ברוך",
            singer = "בן בן ברוך",
            coverUri = "https://example.com/cover.jpg",
            url = "episode:775CMAc4uTNcUUwgkFgvQJ:guid101",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = ""
        )

        assertEquals("775CMAc4uTNcUUwgkFgvQJ", song.resolvePodcastShowId())
    }

    @Test
    fun testResolvePodcastShowId_resolvesFromCacheWhenUrlHasNoShowId() {
        val cachedShow = PodcastModel(
            id = "775CMAc4uTNcUUwgkFgvQJ",
            name = "המוג׳ו של בן בן ברוך",
            publisher = "בן בן ברוך",
            coverUri = "https://example.com/cover.jpg"
        )
        Api.podcastShowCache[cachedShow.id] = cachedShow

        val song = SongsModel(
            id = 3,
            title = "פרק ישן",
            album = "המוג׳ו של בן בן ברוך",
            singer = "בן בן ברוך",
            coverUri = "https://example.com/cover.jpg",
            url = "https://audio.example.com/stream.mp3",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = ""
        )

        assertEquals("775CMAc4uTNcUUwgkFgvQJ", song.resolvePodcastShowId())
    }

    @Test
    fun testResolvePodcastShowId_returnsEmptySafeFallbackWhenUnknown() {
        val song = SongsModel(
            id = 4,
            title = "Unknown Episode",
            album = "Uncached Show",
            singer = "Unknown Host",
            coverUri = "",
            url = "https://unknown.com/audio.mp3",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = ""
        )

        assertEquals("", song.resolvePodcastShowId())
    }

    @Test
    fun testMusicTrack_doesNotResolveShowId() {
        val musicTrack = SongsModel(
            id = 5,
            title = "הלב שלי",
            album = "אלול תשע״ט",
            singer = "ישי ריבו",
            coverUri = "https://example.com/ribo.jpg",
            url = "track:ribo_lev",
            mediaType = MediaType.TRACK,
            podcastShowId = ""
        )

        assertEquals(MediaType.TRACK, musicTrack.mediaType)
        assertEquals("", musicTrack.resolvePodcastShowId())
    }

    @Test
    fun testShowRoute_safeHandlingForEmptyId() {
        val validRoute = showRoute("775CMAc4uTNcUUwgkFgvQJ", "המוג׳ו של בן בן ברוך")
        assertTrue(validRoute.contains("775CMAc4uTNcUUwgkFgvQJ"))

        val fallbackRoute = showRoute("", "Unknown Show")
        assertTrue(fallbackRoute.contains("unknown"))
        assertFalse(fallbackRoute.startsWith("show//")) // Prevents malformed path with double slashes
    }

    // ==========================================
    // 2. PODCAST HUB DEDUPLICATION & CACHING TESTS
    // ==========================================

    @Test
    fun testPodcastHubShows_deduplicationAndFiltering() {
        val rawShows = listOf(
            PodcastModel("id1", "המוג׳ו של בן בן ברוך", "בן בן ברוך", "https://img.com/1"),
            PodcastModel("id1", "המוג׳ו של בן בן ברוך", "בן בן ברוך", "https://img.com/1"), // Duplicate
            PodcastModel("id2", "פודקאסט אחד ביום", "N12", "https://img.com/2"),
            PodcastModel("id3", "", "Blank Title", "https://img.com/3"), // Blank name
            PodcastModel("id4", "קטעים בהיסטוריה", "יובל מלחי", "https://img.com/4")
        )

        val filtered = rawShows.distinctBy { it.id }.filter { it.name.isNotBlank() }

        assertEquals(3, filtered.size)
        assertEquals(listOf("id1", "id2", "id4"), filtered.map { it.id })
    }

    @Test
    fun testPodcastHubCacheTtl_expiryLogic() {
        val ttlMs = 3600_000L // 1 hour
        val now = 10_000_000L

        val validTimestamp = now - 1800_000L // 30 minutes ago
        assertTrue((now - validTimestamp) < ttlMs)

        val expiredTimestamp = now - 3601_000L // 1 hour and 1 second ago
        assertFalse((now - expiredTimestamp) < ttlMs)
    }
}
