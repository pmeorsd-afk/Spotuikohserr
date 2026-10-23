package com.music.spotui.playback

import com.music.spotui.BuildConfig
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.overlay.WazeOverlayArtwork as Artwork
import com.music.spotui.util.KosherWhitelistManager as Whitelist
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class WazeOverlayArtworkTest {
    private val track = SongsModel(9, "Allowed track", "Album", "Approved artist", "https://example.test/waze.jpg",
        "spotify:track:1234567890abcdefghijkl|Allowed track Approved artist", "1234567890abcdefghijkl")
    private lateinit var before: String
    @Before fun setUp() {
        before = Whitelist.exportWhitelistJson()
        assertTrue(Whitelist.replaceStateFromJson("""{"version":0,"artists":[],"tracks":[{"id":"1234567890abcdefghijkl","title":"Allowed track","artist":"Approved artist","status":"approved"}]}"""))
    }
    @After fun restore() { assertTrue(Whitelist.replaceStateFromJson(JSONObject(before).put("version", 0).toString())) }

    @Test fun approvedArtworkWorksWithoutVisitingAnyPhoneScreenOrWarmingUrlCache() {
        if (!BuildConfig.IS_ADMIN) assertFalse(Whitelist.isUrlAllowed(track.coverUri))
        assertTrue(Artwork.allowed(track))
        assertTrue(Whitelist.isUrlAllowed(track.coverUri))
    }
    @Test fun revokedPermissionIsReevaluatedEvenIfTheUrlWasPreviouslyRendered() {
        assertTrue(Artwork.allowed(track))
        Whitelist.replaceStateFromJson("""{"version":0,"artists":[],"tracks":[]}""")
        assertEquals(BuildConfig.IS_ADMIN, Artwork.allowed(track))
    }
    @Test fun unknownAndMissingArtworkStayBlockedWithExistingAdminPolicy() {
        assertTrue(Artwork.allowed(track)) // A warm shared URL does not authorize another track.
        assertFalse(Artwork.allowed(null))
        assertFalse(Artwork.allowed(track.copy(coverUri = "")))
        assertEquals(BuildConfig.IS_ADMIN, Artwork.allowed(track.copy(title = "Unknown", singer = "Unknown",
            spotifyTrackId = "abcdefghijkl1234567890", url = "spotify:track:abcdefghijkl1234567890|Unknown")))
    }
    @Test fun currentArtworkUsesTheExactQueueItemAndRejectsTentativeMetadata() {
        assertEquals(track, Artwork.current(listOf(track), 9, track.title, track.singer, null))
        assertNull(Artwork.current(listOf(track), 9, "Next pending title", track.singer, null))
        assertNull(Artwork.current(listOf(track), 8, track.title, track.singer, null))
        assertNull(Artwork.current(listOf(track, track.copy(title = "Collision")), 9, track.title, track.singer, null))
    }
    @Test fun persistedTypedItemWorksButNeverReplacesAnUnresolvedLiveItem() {
        assertEquals(track, Artwork.current(emptyList(), 0, "", "", track))
        assertNull(Artwork.current(emptyList(), 8, "Pending", "Artist", track))
        assertNull(Artwork.current(emptyList(), 0, "", "", track.copy(url = "")))
    }
    @Test fun episodeIdentityIsPreservedAndUsesTheExistingPermissionRules() {
        val episode = track.copy(mediaType = MediaType.PODCAST_EPISODE, title = "Episode",
            spotifyTrackId = "abcdefghijkl1234567890", url = "episode:show:guid", podcastShowId = "show")
        assertEquals(episode, Artwork.current(listOf(episode), 9, episode.title, episode.singer, null))
        assertEquals(BuildConfig.IS_ADMIN, Artwork.allowed(episode))
    }

    @Test fun verifiedBackgroundItemDoesNotNeedThePhoneQueueToLoadArtwork() {
        assertEquals(track, Artwork.current(emptyList(), track.id, track.title, track.singer, null, track))
    }
    @Test fun pendingDifferentDisplayCannotBorrowTheActualOwnersArtworkPermission() {
        val pending = track.copy(title = "Next pending item", coverUri = "https://example.test/next.jpg")
        assertNull(Artwork.current(listOf(pending), pending.id, pending.title, pending.singer, null, track))
    }
}
