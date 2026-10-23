package com.music.spotui.playback

import com.music.spotui.BuildConfig
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.notification.AutoArtworkPolicy
import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.util.KosherWhitelistManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutoHomeArtworkTest {
    private val url = "https://pickasso.spotifycdn.com/image/ab67c0de0000deef/dt/v1/img/radio/artist/24HI9hevLjIQtj7xp2CeHs/en-GB"
    private lateinit var original: String
    private val playlist = HomeItem.Playlist("Peer Tasi Radio", url, "Playlist • Spotify", "1234567890abcdefghijkl")
    private val song = SongsModel(12, "Episode", "Show", "Publisher", url, "episode:show:guid", mediaType = MediaType.PODCAST_EPISODE)

    @Before fun setUp() { original = KosherWhitelistManager.exportWhitelistJson(); replace() }
    @After fun restore() { KosherWhitelistManager.replaceStateFromJson(JSONObject(original).put("version", 0).toString()) }
    private fun entry(status: String = "approved", artwork: String = url) = JSONObject().apply {
        put("id", HomeArtworkPermissions.key(artwork)); put("url", artwork); put("status", status)
        put("scope", "home"); put("updated_at", "2026-10-18T06:47:00Z")
    }
    private fun replace(rows: JSONArray = JSONArray()) = KosherWhitelistManager.replaceStateFromJson(JSONObject().apply {
        put("artists", JSONArray()); put("tracks", JSONArray()); put("home_images", rows)
    }.toString())

    @Test fun coldCarFolderUsesTheApprovedHomeCoverWithoutPhoneRendering() {
        replace(JSONArray().put(entry()))
        val folder = requireNotNull(AutoBrowseContent.homeFolder(playlist))
        var disk: String? = null
        AutoBrowseCatalog({ disk }, { disk = it }).register(folder)
        val restored = requireNotNull(AutoBrowseCatalog({ disk }, {}).find(folder.mediaId))
        assertTrue(AutoArtworkPolicy.allowed(restored))
        assertEquals(url, restored.artwork)
        if (!BuildConfig.IS_ADMIN) assertFalse(KosherWhitelistManager.isUrlAllowed(url))
    }

    @Test fun allCarFolderKindsUseOnlyTheExactApprovedCover() {
        replace(JSONArray().put(entry()))
        AutoBrowseCatalog.Kind.values().forEach { kind ->
            assertTrue(AutoArtworkPolicy.allowed(AutoBrowseCatalog.Entry(kind, "exact-id", "Name", artwork = url)))
        }
        if (!BuildConfig.IS_ADMIN) assertFalse(KosherWhitelistManager.isUrlAllowed(url))
    }

    @Test fun localeVariantHasDifferentPeopleAndMustNotBorrowPermission() {
        replace(JSONArray().put(entry()))
        val other = requireNotNull(AutoBrowseContent.homeFolder(playlist.copy(imageUrl = url.removeSuffix("-GB"))))
        assertFalse(HomeArtworkPermissions.isApproved(other.artwork))
        if (!BuildConfig.IS_ADMIN) assertFalse(AutoArtworkPolicy.allowed(other))
        assertFalse(AutoArtworkPolicy.matchesVersion(HomeArtworkPermissions.key(url), other.artwork))
    }

    @Test fun browseEpisodePermissionDoesNotGrantSharedPlayerOrGlobalArtwork() {
        replace(JSONArray().put(entry()))
        assertTrue(AutoArtworkPolicy.allowedBrowseSong(song))
        if (!BuildConfig.IS_ADMIN) {
            assertFalse(KosherWhitelistManager.isSongWhitelisted(song))
            assertFalse(KosherWhitelistManager.isUrlAllowed(url))
        }
    }

    @Test fun revocationAndChangedCoverAreRecheckedForAnExistingCarEntry() {
        val folder = requireNotNull(AutoBrowseContent.homeFolder(playlist))
        replace(JSONArray().put(entry()))
        assertTrue(AutoArtworkPolicy.allowed(folder))
        replace(JSONArray().put(entry("blocked")))
        if (!BuildConfig.IS_ADMIN) {
            assertFalse(AutoArtworkPolicy.allowed(folder))
            assertFalse(AutoArtworkPolicy.allowedBrowseSong(song))
        }
        assertTrue(AutoArtworkPolicy.matchesVersion(HomeArtworkPermissions.key(url), url))
        assertFalse(AutoArtworkPolicy.matchesVersion(HomeArtworkPermissions.key(url), "$url?new=1"))
    }

    @Test fun permissionsNotifyHeadlessCarOnlyWhenDecisionsChange() {
        val initial = HomeArtworkPermissions.updates.value
        replace(JSONArray().put(entry()))
        assertEquals(initial + 1, HomeArtworkPermissions.updates.value)
        replace(JSONArray().put(entry()))
        assertEquals(initial + 1, HomeArtworkPermissions.updates.value)
        replace(JSONArray().put(entry("blocked")))
        assertEquals(initial + 2, HomeArtworkPermissions.updates.value)
    }
}
