package com.music.spotui.playback

import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.SearchArtworkPermissions
import com.music.spotui.util.SearchImageApprovalRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SearchArtworkPermissionsTest {
    private val url = "https://i.scdn.co/image/exact-Cover?size=300"
    private lateinit var original: String
    @Before fun before() { original = KosherWhitelistManager.exportWhitelistJson(); load() }
    @After fun after() { assertTrue(KosherWhitelistManager.replaceStateFromJson(original)) }
    private fun entry(status: String = "approved", scope: String = "search") = JSONObject().apply {
        put("id", SearchArtworkPermissions.key(url)); put("url", url); put("scope", scope)
        put("status", status); put("updated_at", "2026-10-18T10:00:00Z")
    }
    private fun load(search: JSONArray = JSONArray(), home: JSONArray = JSONArray()) {
        assertTrue(KosherWhitelistManager.replaceStateFromJson(JSONObject().apply {
            put("artists", JSONArray()); put("tracks", JSONArray())
            put("home_images", home); put("search_images", search)
        }.toString()))
    }
    private fun globallyRegistered(): Boolean {
        // Admin bypasses global rendering checks; inspect actual registration in both flavors.
        val field = KosherWhitelistManager::class.java.getDeclaredField("allowedImageUrls").apply { isAccessible = true }
        return (field.get(KosherWhitelistManager) as Set<*>).contains(url)
    }

    @Test fun searchApprovalNeverGrantsHomeOrGlobalArtwork() {
        load(JSONArray().put(entry()))
        assertTrue(SearchArtworkPermissions.isApproved(url))
        assertFalse(HomeArtworkPermissions.isApproved(url))
        assertFalse(globallyRegistered())
        assertFalse(SearchArtworkPermissions.isApproved(url.replace("300", "640")))
        assertFalse(SearchArtworkPermissions.isApproved(url.replace("Cover", "cover")))
    }
    @Test fun homeApprovalDoesNotGrantCategoryArtwork() {
        load(home = JSONArray().put(entry(scope = "home")))
        assertTrue(HomeArtworkPermissions.isApproved(url))
        assertFalse(SearchArtworkPermissions.isApproved(url))
    }
    @Test fun searchRejectionAndMissingFieldDoNotRevokeHomeGrant() {
        val home = JSONArray().put(entry(scope = "home"))
        load(JSONArray().put(entry()), home)
        load(JSONArray().put(entry("blocked")), home)
        assertFalse(SearchArtworkPermissions.isApproved(url))
        assertTrue(HomeArtworkPermissions.isApproved(url))
        load(JSONArray().put(entry()), home)
        assertTrue(KosherWhitelistManager.replaceStateFromJson(JSONObject()
            .put("artists", JSONArray()).put("tracks", JSONArray()).put("home_images", home).toString()))
        assertFalse(SearchArtworkPermissions.isApproved(url))
        assertTrue(HomeArtworkPermissions.isApproved(url))
    }
    @Test fun cacheRoundTripPreservesSeparateScopesAndDecisionTimestamp() {
        load(JSONArray().put(entry()), JSONArray().put(entry("blocked", "home")))
        val exported = KosherWhitelistManager.exportWhitelistJson()
        load()
        assertTrue(KosherWhitelistManager.replaceStateFromJson(exported))
        assertTrue(SearchArtworkPermissions.isApproved(url))
        assertFalse(HomeArtworkPermissions.isApproved(url))
        assertFalse(globallyRegistered())
        assertEquals("2026-10-18T10:00:00Z", JSONObject(exported).getJSONArray("search_images").getJSONObject(0).getString("updated_at"))
    }
    @Test fun wrongScopeDigestStatusAndDuplicateFailClosed() {
        for (bad in listOf(entry(scope = "home"), entry(scope = "player"), entry().put("id", "wrong"), entry("pending"), entry().apply { remove("status") })) {
            load(JSONArray().put(bad)); assertFalse(SearchArtworkPermissions.isApproved(url))
        }
        load(JSONArray().put(entry()).put(entry()))
        assertFalse(SearchArtworkPermissions.isApproved(url))
    }
    @Test fun requestUsesExactReviewUrlAndSearchOnlyCallbacksWithinTelegramLimit() {
        for (bad in listOf("", "file:///cover.png", "content://media/1", "http://example.com/a", "https://u:p@example.com/a", "https://example.com/a#f", "https://localhost/a")) {
            assertNull(SearchImageApprovalRequest.create("title", bad))
        }
        val request = requireNotNull(SearchImageApprovalRequest.create("title [x] *literal*", url))
        assertTrue(request.text.contains("בקטגוריות מסך החיפוש בלבד"))
        val rows = request.keyboard().getJSONArray("inline_keyboard")
        assertEquals(url, rows.getJSONArray(1).getJSONObject(0).getString("url"))
        for ((index, action) in listOf("appr", "deny").withIndex()) {
            val callback = rows.getJSONArray(0).getJSONObject(index).getString("callback_data")
            assertEquals("$action:search:${SearchArtworkPermissions.key(url)}", callback)
            assertTrue(callback.toByteArray(Charsets.UTF_8).size <= 64)
        }
        assertFalse(SearchArtworkPermissions.isApproved(url))
    }
}
