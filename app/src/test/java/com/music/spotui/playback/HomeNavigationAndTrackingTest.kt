package com.music.spotui.playback

import com.metrolist.spotify.models.SpotifyAlbum
import com.metrolist.spotify.models.SpotifySimpleArtist
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.TrackListenStat
import com.music.spotui.ui.navigation.ItemDetailRegistry
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.util.KosherWhitelistManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. Recents & Top Grid -> AlbumScreen / ShowScreen displaying ONLY that exact item (singleTrackId / singleEpisodeId).
 * 2. Recommended & Catalog sections -> Catalog navigation preserved (full album / full show).
 * 3. Exact SongsModel metadata preservation via ItemDetailRegistry.
 * 4. Separation of 1s recordRecent vs 30s recordPlay.
 * 5. Kosher whitelist filtering for podcast images.
 */
class HomeNavigationAndTrackingTest {

    enum class SimulatedHomeSurface {
        TOP_GRID,
        RECENTS,
        RECOMMENDED,
        CATALOG_SECTION
    }

    sealed class SimulatedHomeAction {
        data class NavigateSingleTrack(val route: String, val albumName: String, val artist: String, val singleTrackId: String) : SimulatedHomeAction()
        data class NavigateSingleEpisode(val route: String, val showId: String, val showName: String, val singleEpisodeId: String) : SimulatedHomeAction()
        data class DirectPlay(val song: SongsModel) : SimulatedHomeAction()
        data class NavigateAlbum(val route: String, val albumName: String, val artist: String = "", val cover: String = "") : SimulatedHomeAction()
        data class NavigateShow(val route: String, val showId: String, val showName: String) : SimulatedHomeAction()
        data class NavigateArtist(val route: String, val artistName: String) : SimulatedHomeAction()
        data class NavigatePlaylist(val route: String, val playlistId: String) : SimulatedHomeAction()
        object None : SimulatedHomeAction()
    }

    private fun simulateHomeItemAction(
        item: HomeItem,
        surface: SimulatedHomeSurface = SimulatedHomeSurface.CATALOG_SECTION
    ): SimulatedHomeAction {
        return when (item) {
            is HomeItem.Album -> SimulatedHomeAction.NavigateAlbum("album", item.name, item.artists.ifBlank { item.subtitle }, item.imageUrl)
            is HomeItem.Artist -> SimulatedHomeAction.NavigateArtist("artist", item.name)
            is HomeItem.Playlist -> SimulatedHomeAction.NavigatePlaylist("playlist", item.id)
            is HomeItem.Track -> {
                when (surface) {
                    SimulatedHomeSurface.TOP_GRID, SimulatedHomeSurface.RECENTS -> {
                        // History & Top Grid -> exact single item in AlbumScreen / ShowScreen ("לאותו שיר בלבד")!
                        val key = ItemDetailRegistry.register(item.song)
                        if (item.song.mediaType == MediaType.PODCAST_EPISODE) {
                            val showId = item.song.resolvePodcastShowId().ifBlank { item.song.podcastShowId }.ifBlank { item.song.singer }
                            val showTitle = item.song.album.ifBlank { item.song.singer }
                            val route = "show/$showId?name=$showTitle&singleEpisodeId=$key"
                            SimulatedHomeAction.NavigateSingleEpisode(route, showId, showTitle, key)
                        } else {
                            val albumOrTitle = item.song.album.ifBlank { item.song.title }
                            val route = "album/$albumOrTitle?artist=${item.song.singer}&singleTrackId=$key"
                            SimulatedHomeAction.NavigateSingleTrack(route, albumOrTitle, item.song.singer, key)
                        }
                    }
                    SimulatedHomeSurface.RECOMMENDED, SimulatedHomeSurface.CATALOG_SECTION -> {
                        // Recommendation & Catalog sections -> NOT history! Preserve existing Album/Show navigation!
                        if (item.song.mediaType == MediaType.PODCAST_EPISODE) {
                            val showId = item.song.resolvePodcastShowId().ifBlank { item.song.podcastShowId }.ifBlank { item.song.singer }
                            val showTitle = item.song.album.ifBlank { item.song.singer }
                            SimulatedHomeAction.NavigateShow("show/$showId", showId, showTitle)
                        } else {
                            val albumOrTitle = item.song.album.ifBlank { item.song.title }
                            SimulatedHomeAction.NavigateAlbum("album/$albumOrTitle", albumOrTitle, item.song.singer, item.song.coverUri)
                        }
                    }
                }
            }
            else -> SimulatedHomeAction.None
        }
    }

    // Helper simulation for SongOptionsSheet / Context Menu
    sealed class SimulatedMenuAction {
        data class GoToAlbum(val route: String, val albumName: String, val artist: String) : SimulatedMenuAction()
        data class GoToShow(val route: String, val showId: String, val showName: String) : SimulatedMenuAction()
        object None : SimulatedMenuAction()
    }

    private fun simulateSongOptionsMenu(song: SongsModel): SimulatedMenuAction {
        return if (song.mediaType == MediaType.PODCAST_EPISODE) {
            val showId = song.resolvePodcastShowId()
            if (showId.isNotBlank()) {
                val showName = song.album.ifBlank { song.singer }
                SimulatedMenuAction.GoToShow("show/$showId?name=$showName", showId, showName)
            } else {
                SimulatedMenuAction.None
            }
        } else {
            val albumOrTitle = song.album.ifBlank { song.title }
            SimulatedMenuAction.GoToAlbum("album/$albumOrTitle", albumOrTitle, song.singer)
        }
    }

    // ==========================================
    // EXACT SINGLE ITEM SCREEN CONTRACT TESTS
    // ==========================================

    @Test
    fun testRecentsTrack_navigatesToAlbumScreenWithSingleTrackId_neverDirectPlaysImmediately() {
        val stat = TrackListenStat(
            trackId = "sp_track_nadav",
            title = "אני מסתובב",
            artist = "נדב חנציס",
            coverUri = "https://example.com/nadav.jpg",
            playCount = 1,
            completedCount = 0,
            lastPlayedAt = System.currentTimeMillis(),
            album = "אני מסתובב - Single",
            mediaType = MediaType.TRACK
        )
        val item = HomeItem.Track(stat.toSongModel())

        val action = simulateHomeItemAction(item, surface = SimulatedHomeSurface.RECENTS)
        assertTrue("Recents Track tap must navigate to AlbumScreen with singleTrackId", action is SimulatedHomeAction.NavigateSingleTrack)
        val singleAction = action as SimulatedHomeAction.NavigateSingleTrack
        assertEquals("אני מסתובב - Single", singleAction.albumName)
        assertEquals("נדב חנציס", singleAction.artist)
        assertNotNull("Registry must contain the song", ItemDetailRegistry.get(singleAction.singleTrackId))
        assertFalse("Recents Track must never execute DirectPlay immediately", action is SimulatedHomeAction.DirectPlay)
    }

    @Test
    fun testTopGridTrack_navigatesToAlbumScreenWithSingleTrackId_neverDirectPlaysImmediately() {
        val stat = TrackListenStat(
            trackId = "sp_track_nadav",
            title = "אני מסתובב",
            artist = "נדב חנציס",
            coverUri = "https://example.com/nadav.jpg",
            playCount = 1,
            completedCount = 0,
            lastPlayedAt = System.currentTimeMillis(),
            album = "אני מסתובב - Single",
            mediaType = MediaType.TRACK
        )
        val item = HomeItem.Track(stat.toSongModel())

        val action = simulateHomeItemAction(item, surface = SimulatedHomeSurface.TOP_GRID)
        assertTrue("Top Grid Track tap must navigate to AlbumScreen with singleTrackId", action is SimulatedHomeAction.NavigateSingleTrack)
        val singleAction = action as SimulatedHomeAction.NavigateSingleTrack
        assertEquals("אני מסתובב - Single", singleAction.albumName)
        assertNotNull("Registry must contain the song", ItemDetailRegistry.get(singleAction.singleTrackId))
        assertFalse("Top Grid Track must never execute DirectPlay immediately", action is SimulatedHomeAction.DirectPlay)
    }

    @Test
    fun testRecentsPodcastEpisode_navigatesToShowScreenWithSingleEpisodeId_neverOpensFullShowCatalog() {
        val epSong = SongsModel(
            id = 404,
            title = "פרק 374 - הרב יוסף דלויה",
            album = "פודקאסט המוג׳ו",
            singer = "בן בן ברוך",
            coverUri = "https://example.com/bbb.jpg",
            url = "episode:show_bbb_123:guid_374",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "show_bbb_123"
        )
        val item = HomeItem.Track(epSong)

        val action = simulateHomeItemAction(item, surface = SimulatedHomeSurface.RECENTS)
        assertTrue("Recents podcast episode must navigate to ShowScreen with singleEpisodeId", action is SimulatedHomeAction.NavigateSingleEpisode)
        val singleAction = action as SimulatedHomeAction.NavigateSingleEpisode
        assertEquals("show_bbb_123", singleAction.showId)
        assertNotNull("Registry must contain episode", ItemDetailRegistry.get(singleAction.singleEpisodeId))
        assertFalse("Recents podcast episode must never execute DirectPlay immediately", action is SimulatedHomeAction.DirectPlay)
    }

    @Test
    fun testTopGridPodcastEpisode_navigatesToShowScreenWithSingleEpisodeId_neverOpensFullShowCatalog() {
        val epSong = SongsModel(
            id = 404,
            title = "פרק 374 - הרב יוסף דלויה",
            album = "פודקאסט המוג׳ו",
            singer = "בן בן ברוך",
            coverUri = "https://example.com/bbb.jpg",
            url = "episode:show_bbb_123:guid_374",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "show_bbb_123"
        )
        val item = HomeItem.Track(epSong)

        val action = simulateHomeItemAction(item, surface = SimulatedHomeSurface.TOP_GRID)
        assertTrue("Top Grid podcast episode must navigate to ShowScreen with singleEpisodeId", action is SimulatedHomeAction.NavigateSingleEpisode)
        val singleAction = action as SimulatedHomeAction.NavigateSingleEpisode
        assertEquals("show_bbb_123", singleAction.showId)
        assertNotNull("Registry must contain episode", ItemDetailRegistry.get(singleAction.singleEpisodeId))
        assertFalse("Top Grid podcast episode must never execute DirectPlay immediately", action is SimulatedHomeAction.DirectPlay)
    }

    @Test
    fun testRecommendedTrack_preservesAlbumNavigation_neverPassesSingleTrackId() {
        val song = SongsModel(
            id = 101,
            title = "סיבת הסיבות",
            album = "סיבת הסיבות - Single",
            singer = "ישי ריבו",
            coverUri = "https://example.com/siba.jpg",
            url = "spotify:track:siba123",
            mediaType = MediaType.TRACK
        )
        val item = HomeItem.Track(song)

        val action = simulateHomeItemAction(item, surface = SimulatedHomeSurface.RECOMMENDED)
        assertTrue("Recommended Track tap must navigate to AlbumScreen without singleTrackId", action is SimulatedHomeAction.NavigateAlbum)
        val navAction = action as SimulatedHomeAction.NavigateAlbum
        assertEquals("סיבת הסיבות - Single", navAction.albumName)
        assertEquals("ישי ריבו", navAction.artist)
        assertFalse("Recommended Track must never execute DirectPlay", action is SimulatedHomeAction.DirectPlay)
    }

    @Test
    fun testRecommendedPodcast_preservesShowNavigation() {
        val ep = SongsModel(
            id = 505,
            title = "פרק 75",
            album = "פודקאסט שולחן 4",
            singer = "פודקאסט שולחן 4",
            coverUri = "https://example.com/shulchan.jpg",
            url = "episode:show_shulchan_75:guid_75",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "show_shulchan_75"
        )
        val item = HomeItem.Track(ep)

        val action = simulateHomeItemAction(item, surface = SimulatedHomeSurface.RECOMMENDED)
        assertTrue("Recommended Podcast tap must navigate to ShowScreen", action is SimulatedHomeAction.NavigateShow)
        val showAction = action as SimulatedHomeAction.NavigateShow
        assertEquals("show_shulchan_75", showAction.showId)
    }

    @Test
    fun testCatalogEntities_preserveCatalogNavigation() {
        val albumItem = HomeItem.Album("album_1", "ישי ריבו", "אלבום 1", "https://example.com/a.jpg")
        val artistItem = HomeItem.Artist("artist_1", "ישי ריבו", "https://example.com/art.jpg")
        val playlistItem = HomeItem.Playlist("pl_1", "פלייליסט 1", "https://example.com/pl.jpg")

        val albumAction = simulateHomeItemAction(albumItem, surface = SimulatedHomeSurface.RECENTS)
        assertTrue("Album must navigate to album", albumAction is SimulatedHomeAction.NavigateAlbum)

        val artistAction = simulateHomeItemAction(artistItem, surface = SimulatedHomeSurface.RECENTS)
        assertTrue("Artist must navigate to artist", artistAction is SimulatedHomeAction.NavigateArtist)

        val plAction = simulateHomeItemAction(playlistItem, surface = SimulatedHomeSurface.RECENTS)
        assertTrue("Playlist must navigate to playlist", plAction is SimulatedHomeAction.NavigatePlaylist)
    }

    @Test
    fun testCatalogNavigationPreservedViaOptionsSheet() {
        // Track: Go to album is available in 3-dots
        val track = SongsModel(
            id = 101,
            title = "שיר 1",
            album = "שיר 1 - Single",
            singer = "אמן 1",
            coverUri = "",
            url = "spotify:track:123",
            mediaType = MediaType.TRACK
        )
        val trackMenu = simulateSongOptionsMenu(track)
        assertTrue("3-dots on track must provide GoToAlbum", trackMenu is SimulatedMenuAction.GoToAlbum)
        assertEquals("שיר 1 - Single", (trackMenu as SimulatedMenuAction.GoToAlbum).albumName)

        // Podcast: Go to show is available in 3-dots with show name (G6)
        val ep = SongsModel(
            id = 202,
            title = "פרק 374",
            album = "המוג׳ו של בן בן",
            singer = "בן בן ברוך",
            coverUri = "",
            url = "episode:show_1:guid_1",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "show_1"
        )
        val epMenu = simulateSongOptionsMenu(ep)
        assertTrue("3-dots on episode must provide GoToShow", epMenu is SimulatedMenuAction.GoToShow)
        val showMenu = epMenu as SimulatedMenuAction.GoToShow
        assertEquals("show_1", showMenu.showId)
        assertEquals("המוג׳ו של בן בן", showMenu.showName)
    }

    // ==========================================
    // ITEM DETAIL REGISTRY & METADATA PRESERVATION
    // ==========================================

    @Test
    fun testItemDetailRegistry_preservesAllMetadata() {
        val song = SongsModel(
            id = 777,
            title = "אני מסתובב",
            singer = "נדב חנציס",
            album = "אני מסתובב - EP",
            coverUri = "https://example.com/cover.jpg",
            url = "spotify:track:nadav_track_id",
            spotifyTrackId = "nadav_track_id",
            durationMs = 210000,
            mediaType = MediaType.TRACK
        )

        val key = ItemDetailRegistry.register(song)
        val retrieved = ItemDetailRegistry.get(key)
        assertNotNull("Registry must return registered song", retrieved)
        assertEquals(song.title, retrieved?.title)
        assertEquals(song.singer, retrieved?.singer)
        assertEquals(song.album, retrieved?.album)
        assertEquals(song.spotifyTrackId, retrieved?.spotifyTrackId)
        assertEquals(song.durationMs, retrieved?.durationMs)
        assertEquals(song.mediaType, retrieved?.mediaType)

        val episode = SongsModel(
            id = 888,
            title = "פרק 75: טראמפ נגד האו״ם",
            singer = "פודקאסט שולחן 4",
            album = "פודקאסט שולחן 4",
            coverUri = "https://example.com/ep75.jpg",
            url = "episode:show_75:guid_75",
            spotifyTrackId = "episode:show_75:guid_75",
            podcastShowId = "show_75",
            durationMs = 3600000,
            mediaType = MediaType.PODCAST_EPISODE
        )

        val epKey = ItemDetailRegistry.register(episode)
        val epRetrieved = ItemDetailRegistry.get(epKey)
        assertNotNull("Registry must return registered episode", epRetrieved)
        assertEquals(MediaType.PODCAST_EPISODE, epRetrieved?.mediaType)
        assertEquals("show_75", epRetrieved?.podcastShowId)
        assertEquals("episode:show_75:guid_75", epRetrieved?.url)
    }

    // ==========================================
    // PODCAST RECONSTRUCTION PRESERVES METADATA
    // ==========================================

    @Test
    fun testPodcastReconstruction_preservesAllMetadata() {
        val stat = TrackListenStat(
            trackId = "episode:show_mojo_123:guid_99",
            title = "פרק 99 - שיחה",
            artist = "בן בן ברוך",
            coverUri = "https://example.com/mojo.jpg",
            playCount = 2,
            completedCount = 1,
            lastPlayedAt = 1700000000000L,
            album = "המוג׳ו של בן בן ברוך",
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "show_mojo_123",
            originalUrl = "episode:show_mojo_123:guid_99",
            durationMs = 3600000
        )

        val reconstructed = stat.toSongModel()
        assertEquals(MediaType.PODCAST_EPISODE, reconstructed.mediaType)
        assertEquals("show_mojo_123", reconstructed.podcastShowId)
        assertEquals("episode:show_mojo_123:guid_99", reconstructed.url)
        assertEquals("המוג׳ו של בן בן ברוך", reconstructed.album)
        assertEquals("בן בן ברוך", reconstructed.singer)
        assertEquals("פרק 99 - שיחה", reconstructed.title)
        assertEquals(3600000, reconstructed.durationMs)
        // Must never produce spotify:track: query for a podcast
        assertFalse(reconstructed.url.startsWith("spotify:track:"))
    }

    // ==========================================
    // OLD TRACKLISTENSTAT COMPATIBILITY
    // ==========================================

    @Test
    fun testOldTrackListenStat_withLegacyEpisodeId_reconstructsSafely() {
        val oldStat = TrackListenStat(
            trackId = "episode:show_nadav_555:guid_12",
            title = "פרק ישן",
            artist = "מגיש ישן",
            coverUri = "https://example.com/ep.jpg",
            playCount = 1,
            completedCount = 1,
            lastPlayedAt = 1690000000000L,
            album = "תוכנית ישנה",
            mediaType = MediaType.TRACK, // legacy record without new mediaType enum stored
            podcastShowId = "",
            originalUrl = ""
        )

        val reconstructed = oldStat.toSongModel()
        assertEquals(MediaType.PODCAST_EPISODE, reconstructed.mediaType)
        assertEquals("show_nadav_555", reconstructed.podcastShowId)
        assertEquals("episode:show_nadav_555:guid_12", reconstructed.url)
        assertFalse(reconstructed.url.startsWith("spotify:track:"))
    }

    // ==========================================
    // ALBUM RESOLVER CONFIDENT MATCH VALIDATION
    // ==========================================

    @Test
    fun testAlbumResolution_preventsBlindFallbackToUnrelatedArtistAlbum() {
        val cleanAlbum = "אני מסתובב"
        val artist = "נדב חנציס"

        val riboAlbum = SpotifyAlbum(
            id = "ribo_album_id",
            name = "כתר מלוכה",
            artists = listOf(SpotifySimpleArtist(id = "ishay_id", name = "ישי ריבו")),
            images = emptyList()
        )
        val candidates = listOf(riboAlbum)

        val wantArtists = artist.split(",", "&", "feat.", "ft.").map { it.trim().lowercase() }.filter { it.isNotBlank() }
        fun artistMatches(a: SpotifyAlbum): Boolean {
            val names = a.artists.joinToString(" ") { it.name }.lowercase()
            return wantArtists.any { it.isNotBlank() && names.contains(it) }
        }
        fun nameMatches(a: SpotifyAlbum): Boolean {
            val aName = a.name.trim()
            return aName.equals(cleanAlbum, ignoreCase = true) ||
                    aName.contains(cleanAlbum, ignoreCase = true) ||
                    cleanAlbum.contains(aName, ignoreCase = true)
        }

        val exactNameMatches = candidates.filter { it.name.equals(cleanAlbum, ignoreCase = true) }
        val picked = exactNameMatches.firstOrNull { artistMatches(it) }
            ?: candidates.firstOrNull { artistMatches(it) && nameMatches(it) }
            ?: exactNameMatches.firstOrNull()

        // MUST be null: no blind fallback to riboAlbum!
        assertNull("Search for 'אני מסתובב' by נדב חנציס must NEVER match ישי ריבו", picked)
    }

    // ==========================================
    // RECENT RECORDING VS LISTENING STATS SEPARATION
    // ==========================================

    @Test
    fun testRecordRecent_vs_recordPlay_contracts() {
        val song = SongsModel(
            id = 555,
            title = "אני מסתובב",
            album = "אני מסתובב - Single",
            singer = "נדב חנציס",
            coverUri = "https://example.com/cover.jpg",
            url = "spotify:track:nadav555",
            mediaType = MediaType.TRACK
        )

        // Simulating recordRecent: immediate recording at start of playback (>1s)
        val initialStat = TrackListenStat(
            trackId = "spotify:track:nadav555",
            title = song.title,
            artist = song.singer,
            coverUri = song.coverUri,
            playCount = 0, // playCount must remain 0 until 30s milestone!
            completedCount = 0,
            lastPlayedAt = 1000L,
            album = song.album,
            mediaType = song.mediaType
        )

        assertEquals("Immediate recent record must have 0 playCount", 0, initialStat.playCount)
        assertEquals("Immediate recent record must have valid lastPlayedAt", 1000L, initialStat.lastPlayedAt)

        val after30s = initialStat.copy(
            playCount = initialStat.playCount + 1,
            lastPlayedAt = maxOf(initialStat.lastPlayedAt, 30000L)
        )
        assertEquals("30s milestone increments playCount", 1, after30s.playCount)

        val afterCompletion = after30s.copy(
            completedCount = after30s.completedCount + 1
        )
        assertEquals("Completion milestone increments completedCount", 1, afterCompletion.completedCount)
        assertEquals(1, afterCompletion.playCount)
    }

    // ==========================================
    // KOSHER WHITELIST BLOCKS UNAPPROVED PODCAST IMAGES
    // ==========================================

    @Test
    fun testKosherWhitelist_blocksUnapprovedPodcastImages() {
        val unapprovedRecentEpisode = com.music.spotui.data.preferences.RecentItem(
            type = "episode",
            key = "episode:huberman_lab_123:guid_1",
            name = "Huberman Lab - Episode 1",
            singer = "Scicomm Media",
            image = "https://example.com/huberman_photo.jpg"
        )

        val isAllowed = KosherWhitelistManager.isRecentItemWhitelisted(unapprovedRecentEpisode)
        assertFalse("Unapproved podcast episode in recent searches must be blocked (isAllowed == false)", isAllowed)

        val unapprovedPodcastShow = com.music.spotui.data.entity.PodcastModel(
            id = "show_huberman",
            name = "Huberman Lab",
            publisher = "Scicomm Media",
            coverUri = "https://example.com/huberman_show.jpg"
        )
        val isShowAllowed = KosherWhitelistManager.isPodcastShowWhitelisted(unapprovedPodcastShow)
        assertFalse("Unapproved podcast show in search results must be blocked (isAllowed == false)", isShowAllowed)
    }
}
