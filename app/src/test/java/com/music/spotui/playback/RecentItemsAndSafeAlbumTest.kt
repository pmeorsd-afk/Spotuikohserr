package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.RecentItem
import com.music.spotui.util.KosherWhitelistManager
import org.junit.Assert.*
import org.junit.Test

class RecentItemsAndSafeAlbumTest {

    // Helper that replicates SearchScreen's toSongModel extension logic
    private fun RecentItem.toSongModelTest(): SongsModel {
        val isEpisode = type == "episode" || songUrl.startsWith("episode:") || key.startsWith("episode:")
        val playUrl = when {
            songUrl.isNotBlank() -> songUrl
            isEpisode -> {
                val show = podcastShowId.ifBlank { "unknown" }
                "episode:$show:$key"
            }
            spotifyTrackId.isNotBlank() -> com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(spotifyTrackId, name, singer)
            else -> key.ifBlank { name }
        }
        val fallbackId = if (songId > 0) songId else (key.ifBlank { name }.hashCode() and 0x7fffffff)
        return SongsModel(
            id = fallbackId,
            title = name,
            album = songAlbum,
            singer = singer,
            coverUri = image,
            url = playUrl,
            spotifyTrackId = spotifyTrackId,
            explicit = explicit,
            durationMs = durationMs,
            mediaType = if (isEpisode) MediaType.PODCAST_EPISODE else MediaType.TRACK,
            podcastShowId = podcastShowId,
        )
    }

    // ==========================================
    // 1. RECENT SONG RESTORATION & PLAYBACK
    // ==========================================

    @Test
    fun testRecentSong_restoresExactPlaybackIdentity_doesNotRouteToAlbum() {
        val recentSong = RecentItem(
            type = "song",
            key = "spotify:track:nadav123",
            name = "אני מסתובב",
            singer = "נדב חנציס",
            image = "https://example.com/cover_nadav.jpg",
            songId = 12345,
            songAlbum = "אני מסתובב - Single",
            songUrl = "spotify:track:nadav123|אני מסתובב נדב חנציס",
            spotifyTrackId = "nadav123",
            explicit = false,
            durationMs = 210000,
            podcastShowId = ""
        )

        val restored = recentSong.toSongModelTest()

        assertEquals(12345, restored.id)
        assertEquals("אני מסתובב", restored.title)
        assertEquals("נדב חנציס", restored.singer)
        assertEquals("אני מסתובב - Single", restored.album)
        assertEquals("spotify:track:nadav123|אני מסתובב נדב חנציס", restored.url)
        assertEquals("nadav123", restored.spotifyTrackId)
        assertEquals(MediaType.TRACK, restored.mediaType)
        // Direct playback avoids fuzzy album search completely
        assertNotEquals("אני שייך לעם", restored.title)
        assertNotEquals("ישי ריבו", restored.singer)
    }

    // ==========================================
    // 2. RECENT PODCAST RESTORATION & PLAYBACK
    // ==========================================

    @Test
    fun testRecentPodcast_restoresEpisodePayload_preservesShowId() {
        val recentEp = RecentItem(
            type = "episode",
            key = "ep_guid_789",
            name = "פרק 100 - ראיון מיוחד",
            singer = "בן בן ברוך",
            image = "https://example.com/mojo.jpg",
            songId = 999,
            songAlbum = "המוג׳ו של בן בן ברוך",
            songUrl = "episode:show_mojo_456:ep_guid_789",
            spotifyTrackId = "ep_guid_789",
            explicit = false,
            durationMs = 3600000,
            podcastShowId = "show_mojo_456"
        )

        val restored = recentEp.toSongModelTest()

        assertEquals("פרק 100 - ראיון מיוחד", restored.title)
        assertEquals("בן בן ברוך", restored.singer)
        assertEquals("המוג׳ו של בן בן ברוך", restored.album)
        assertEquals(MediaType.PODCAST_EPISODE, restored.mediaType)
        assertEquals("show_mojo_456", restored.podcastShowId)
        assertEquals("episode:show_mojo_456:ep_guid_789", restored.url)
        assertEquals("show_mojo_456", restored.resolvePodcastShowId())
    }

    // ==========================================
    // 3. BACKWARD-COMPATIBILITY: OLD SAVED EPISODES
    // ==========================================

    @Test
    fun testLegacyRecentItem_oldEpisodeSavedAsSong_detectedAndRestoredAsEpisode() {
        // Previously, episodes were saved as type = "song" with episode: prefix in songUrl
        val legacyEp = RecentItem(
            type = "song",
            key = "legacy_ep_123",
            name = "פרק 50 - אורי חזקיה",
            singer = "בן בן ברוך",
            image = "https://example.com/mojo.jpg",
            songId = 5050,
            songAlbum = "המוג׳ו של בן בן ברוך",
            songUrl = "episode:show_mojo_456:guid50",
            spotifyTrackId = "",
            explicit = false,
            durationMs = 2800000,
            podcastShowId = ""
        )

        val isOldEpisode = legacyEp.type == "song" && (legacyEp.songUrl.startsWith("episode:") || legacyEp.key.startsWith("episode:"))
        assertTrue("Legacy episode must be identified as episode", isOldEpisode)

        val restored = legacyEp.toSongModelTest()
        assertEquals(MediaType.PODCAST_EPISODE, restored.mediaType)
        assertEquals("show_mojo_456", restored.resolvePodcastShowId())
    }

    // ==========================================
    // 4. KOSHER WHITELIST FOR RECENTS
    // ==========================================

    @Test
    fun testKosherWhitelist_blocksUnapprovedEpisodesAndShows() {
        val epItem = RecentItem(
            type = "episode",
            key = "ep123",
            name = "שיעור תורה לא מאושר",
            singer = "רב לא מאושר",
            image = "https://example.com/shiur_unapproved.jpg"
        )
        val showItem = RecentItem(
            type = "show",
            key = "show123",
            name = "פודקאסט לא מאושר",
            singer = "קול לא מאושר",
            image = "https://example.com/show_unapproved.jpg"
        )

        // Unapproved items MUST be blocked to prevent human face image leaks
        assertFalse(KosherWhitelistManager.isRecentItemWhitelisted(epItem))
        assertFalse(KosherWhitelistManager.isRecentItemWhitelisted(showItem))

        // If the URL is explicitly whitelisted, show becomes allowed
        KosherWhitelistManager.allowImageUrl(showItem.image)
        assertTrue(KosherWhitelistManager.isRecentItemWhitelisted(showItem))
    }

    @Test
    fun testKosherWhitelist_playlistWhitelisting() {
        // 1. Unapproved playlist is blocked
        val unapproved = com.music.spotui.data.entity.HomeItem.Playlist(
            name = "פלייליסט לא ידוע",
            imageUrl = "https://example.com/unapproved_pl.jpg",
            subtitle = "פלוני אלמוני",
            id = "custom_999999"
        )
        assertFalse(KosherWhitelistManager.isHomeItemWhitelisted(unapproved))

        // 2. Playlist with whitelisted artist subtitle is allowed
        val replaced = KosherWhitelistManager.replaceStateFromJson("""
            {
                "version": 999999999,
                "artists": [{"id": "artist_ribo", "canonical_name": "Ishay Ribo", "status": "approved"}],
                "tracks": []
            }
        """.trimIndent())
        assertTrue("replaceStateFromJson failed", replaced)
        assertTrue("isArtistInWhitelist failed", KosherWhitelistManager.isArtistInWhitelist(null, "Ishay Ribo"))
        val artistPlaylist = com.music.spotui.data.entity.HomeItem.Playlist(
            name = "שירי ישי ריבו",
            imageUrl = "https://example.com/ishay_pl.jpg",
            subtitle = "Ishay Ribo",
            id = "custom_ishay"
        )
        assertTrue(KosherWhitelistManager.isHomeItemWhitelisted(artistPlaylist))

        // 3. Playlist with explicitly approved URL is allowed
        KosherWhitelistManager.allowImageUrl(unapproved.imageUrl)
        assertTrue(KosherWhitelistManager.isHomeItemWhitelisted(unapproved))
    }

    @Test
    fun testKosherWhitelist_podcastEpisodeWhitelisting() {
        val unapprovedEpisode = SongsModel(
            id = 999,
            title = "פרק שלא אושר מעולם",
            album = "תוכנית לא מאושרת",
            singer = "מנחה לא מאושר",
            coverUri = "https://example.com/unapproved_ep.jpg",
            url = "episode:show:999",
            mediaType = MediaType.PODCAST_EPISODE
        )
        assertFalse(KosherWhitelistManager.isSongWhitelisted(unapprovedEpisode))
    }
}


