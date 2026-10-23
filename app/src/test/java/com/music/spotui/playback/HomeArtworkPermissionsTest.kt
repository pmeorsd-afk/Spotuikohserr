package com.music.spotui.playback

import com.music.spotui.data.entity.HomeItem
import com.music.spotui.ui.components.homeCollectionUri
import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.util.HomeImageApprovalRequest
import com.music.spotui.util.KosherWhitelistManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HomeArtworkPermissionsTest {
    private val url = "https://i.scdn.co/image/exact-Cover?size=300"
    private val item = HomeItem.Playlist("Mix", url, "Playlist • Publisher", "0123456789ABCDEFGHIJKL")
    private lateinit var original: String
    // Admin intentionally bypasses isUrlAllowed; inspect registration so the isolation assertion
    // remains meaningful in both flavors without changing that existing Admin behavior.
    private fun globallyRegistered(): Boolean {
        val field = KosherWhitelistManager::class.java.getDeclaredField("allowedImageUrls").apply { isAccessible = true }
        return (field.get(KosherWhitelistManager) as Set<*>).contains(url)
    }
    @Before fun before() { original = KosherWhitelistManager.exportWhitelistJson(); load(JSONArray()) }
    @After fun after() { KosherWhitelistManager.replaceStateFromJson(original) }
    private fun entry(value: String = url, status: String = "approved", scope: String = "home") = JSONObject().apply {
        put("id", HomeArtworkPermissions.key(value)); put("url", value); put("scope", scope); put("status", status)
    }
    private fun load(entries: JSONArray) { assertTrue(KosherWhitelistManager.replaceStateFromJson(JSONObject().apply {
        put("artists", JSONArray()); put("tracks", JSONArray()); put("home_images", entries)
    }.toString())) }

    @Test fun approvalDoesNotPopulateGlobalUrlWhitelist() {
        load(JSONArray().put(entry()))
        assertTrue(HomeArtworkPermissions.isAllowedOnHome(item))
        assertFalse(globallyRegistered())
        assertFalse(HomeArtworkPermissions.isApproved(url.replace("300", "640")))
        assertFalse(HomeArtworkPermissions.isApproved(url.replace("Cover", "cover")))
    }
    @Test fun newerImageForSamePlaylistRequiresItsOwnApproval() {
        load(JSONArray().put(entry()))
        assertFalse(HomeArtworkPermissions.isApproved(item.copy(imageUrl = "$url&v=2").imageUrl))
    }
    @Test fun exactCoverApprovalExtendsToCollectionWithoutBecomingGlobal() {
        load(JSONArray().put(entry()))
        assertTrue(HomeArtworkPermissions.isAllowedInCollection(item))
        assertFalse(globallyRegistered())
        if (!com.music.spotui.BuildConfig.IS_ADMIN) {
            assertFalse(HomeArtworkPermissions.isAllowedInCollection(item.copy(imageUrl = "$url&v=2")))
        }
        load(JSONArray().put(entry(status = "blocked")))
        assertFalse(HomeArtworkPermissions.isApproved(url))
        if (!com.music.spotui.BuildConfig.IS_ADMIN) assertFalse(HomeArtworkPermissions.isAllowedInCollection(item))
        assertFalse(globallyRegistered())
    }
    @Test fun rejectionRevokesAndAbsentFieldRevokes() {
        load(JSONArray().put(entry()))
        load(JSONArray().put(entry(status = "blocked")))
        assertFalse(HomeArtworkPermissions.isApproved(url))
        load(JSONArray().put(entry()))
        KosherWhitelistManager.replaceStateFromJson("{\"artists\":[],\"tracks\":[]}")
        assertFalse(HomeArtworkPermissions.isApproved(url))
    }
    @Test fun cacheExportRoundTripRetainsOnlyScopedGrant() {
        load(JSONArray().put(entry()))
        val exported = KosherWhitelistManager.exportWhitelistJson()
        load(JSONArray())
        assertTrue(KosherWhitelistManager.replaceStateFromJson(exported))
        assertTrue(HomeArtworkPermissions.isApproved(url))
        assertFalse(globallyRegistered())
        assertEquals(0, JSONObject(exported).getJSONArray("artists").length())
        assertEquals(0, JSONObject(exported).getJSONArray("tracks").length())
    }
    @Test fun invalidScopeDigestStatusAndDuplicateFailClosed() {
        for (bad in listOf(entry(scope = "player"), entry().put("id", "wrong"), entry(status = "pending"), entry().apply { remove("status") })) {
            load(JSONArray().put(bad)); assertFalse(HomeArtworkPermissions.isApproved(url))
        }
        load(JSONArray().put(entry()).put(entry()))
        assertFalse(HomeArtworkPermissions.isApproved(url))
    }
    @Test fun requestRequiresRemoteImageAndContainsExactReviewLink() {
        for (bad in listOf("", "file:///cover.png", "content://media/1", "http://example.com/a", "https://u:p@example.com/a", "https://example.com/a#f", "https://localhost/a")) {
            assertNull(HomeImageApprovalRequest.create("title", bad))
        }
        val request = requireNotNull(HomeImageApprovalRequest.create("title [x] *literal*", url))
        val rows = request.keyboard().getJSONArray("inline_keyboard")
        assertEquals(url, rows.getJSONArray(1).getJSONObject(0).getString("url"))
        for (index in 0..1) assertTrue(rows.getJSONArray(0).getJSONObject(index).getString("callback_data").toByteArray().size <= 64)
        assertFalse(HomeArtworkPermissions.isApproved(url))
    }
    @Test fun collectionActionsKeepTypeAndExactId() {
        val valid = item.copy(id = "0123456789ABCDEFGHIJKL")
        assertEquals("spotify:playlist:0123456789ABCDEFGHIJKL", homeCollectionUri(valid))
        assertNull(homeCollectionUri(valid.copy(id = "spotify:album:0123456789ABCDEFGHIJKL")))
        assertNull(homeCollectionUri(valid.copy(id = "local_playlist")))
        assertNull(homeCollectionUri(HomeItem.LikedSongs(2)))
    }
}
