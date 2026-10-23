package com.music.spotui.playback

import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.util.HomeImageRequestReadiness
import com.music.spotui.util.homeImageRequestReadiness
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class CrossDeviceArtworkApprovalTest {
    private val url = "https://example.test/exact-radio.jpg"
    private lateinit var original: JSONArray
    @Before fun before() { original = HomeArtworkPermissions.toJson(); HomeArtworkPermissions.replace(null) }
    @After fun after() { HomeArtworkPermissions.replace(original) }
    private fun decisions(status: String = "approved", artwork: String = url) = JSONArray().put(JSONObject().apply {
        put("id", HomeArtworkPermissions.key(artwork)); put("url", artwork)
        put("scope", "home"); put("status", status); put("updated_at", "2026-10-20T18:00:00Z")
    })

    @Test fun anotherPhonesApprovalIsLoadedBeforeDecidingToSend() = runBlocking {
        assertFalse(HomeArtworkPermissions.isApproved(url))
        val result = homeImageRequestReadiness(url) { HomeArtworkPermissions.replace(decisions()); true }
        assertEquals(HomeImageRequestReadiness.APPROVED, result)
        // A fresh process/device receives the same server snapshot, not another device's pending preferences.
        val server = HomeArtworkPermissions.toJson().toString()
        HomeArtworkPermissions.replace(null)
        HomeArtworkPermissions.replace(JSONArray(server))
        assertTrue(HomeArtworkPermissions.isApproved(url))
    }
    @Test fun rejectedAndAbsentImagesRemainRequestableAfterSuccessfulSync() = runBlocking {
        assertEquals(HomeImageRequestReadiness.READY, homeImageRequestReadiness(url) {
            HomeArtworkPermissions.replace(decisions("blocked")); true
        })
        assertEquals(HomeImageRequestReadiness.READY, homeImageRequestReadiness(url) {
            HomeArtworkPermissions.replace(null); true
        })
    }
    @Test fun sameTitleOrArtistDoesNotAuthorizeAChangedImage() = runBlocking {
        assertEquals(HomeImageRequestReadiness.READY, homeImageRequestReadiness(url) {
            HomeArtworkPermissions.replace(decisions(artwork = "$url?other-cover")); true
        })
        assertFalse(HomeArtworkPermissions.isApproved(url))
    }
    @Test fun offlineStaleDeviceCannotSendAnUnverifiedDuplicateRequest() = runBlocking {
        assertEquals(HomeImageRequestReadiness.SYNC_FAILED, homeImageRequestReadiness(url) { false })
        assertFalse(HomeArtworkPermissions.isApproved(url))
    }
}
