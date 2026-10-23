package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.util.SearchArtworkPermissions
import com.music.spotui.util.SearchImageApprovalRequests
import com.music.spotui.util.KosherWhitelistManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class SearchImageApprovalRequestsTest {
    private val url = "https://i.scdn.co/image/retry-Cover?size=300"
    private val otherUrl = "$url&other=1"
    private val firstDecision = "2026-10-18T06:30:00.000Z"
    private val nextDecision = "2026-10-18T06:31:00.000Z"
    private val values = mutableMapOf<String, Any>()
    private lateinit var original: String

    private fun preferences(storage: MutableMap<String, Any>): SharedPreferences {
        fun editor(): SharedPreferences.Editor {
            val writes = mutableMapOf<String, Any>()
            val removals = mutableSetOf<String>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putLong" -> { writes[args!![0] as String] = args[1]; proxy }
                    "remove" -> { removals += args!![0] as String; proxy }
                    "apply" -> { removals.forEach(storage::remove); storage.putAll(writes); null }
                    else -> error("Unexpected editor operation ${method.name}")
                }
            } as SharedPreferences.Editor
        }
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getAll" -> storage.toMap()
                "getLong", "getString" -> storage[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference operation ${method.name}")
            }
        } as SharedPreferences
    }
    private fun context(storage: MutableMap<String, Any> = values) = object : ContextWrapper(null) {
        private val prefs = preferences(storage)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            assertEquals("search_image_requests", name)
            return prefs
        }
    }
    private val app = context()

    @Before fun before() {
        original = KosherWhitelistManager.exportWhitelistJson()
        load()
    }
    @After fun after() { assertTrue(KosherWhitelistManager.replaceStateFromJson(original)) }

    private fun entry(value: String = url, status: String = "blocked", updatedAt: String = firstDecision) = JSONObject().apply {
        put("id", SearchArtworkPermissions.key(value)); put("url", value); put("scope", "search")
        put("status", status); put("updated_at", updatedAt)
    }
    private fun load(vararg entries: JSONObject) {
        assertTrue(KosherWhitelistManager.replaceStateFromJson(JSONObject().apply {
            put("artists", JSONArray()); put("tracks", JSONArray())
            put("search_images", JSONArray().apply { entries.forEach { put(it) } })
        }.toString()))
    }
    private fun send(value: String = url) {
        SearchImageApprovalRequests.recordSent(app, value, SearchImageApprovalRequests.decisionAtSend(value))
    }

    @Test fun rejectionMakesRequestImmediatelyRetryableWithoutGrantingImage() {
        send()
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        assertTrue(SearchImageApprovalRequests.hasPending(app))
        load(entry())
        assertFalse(SearchImageApprovalRequests.isPending(app, url))
        assertFalse(SearchImageApprovalRequests.hasPending(app))
        assertFalse(SearchArtworkPermissions.isApproved(url))
    }

    @Test fun resubmissionWaitsForNewDecisionEvenWhenRejectedAgain() {
        load(entry())
        send()
        load(entry()) // The previous rejection may be synchronized again after resubmitting.
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        load(entry(updatedAt = nextDecision))
        assertFalse(SearchImageApprovalRequests.isPending(app, url))
        assertFalse(SearchArtworkPermissions.isApproved(url))
        send()
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
    }

    @Test fun anotherImagesDecisionDoesNotResolveThePendingRequest() {
        send()
        load(entry(value = otherUrl))
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        assertFalse(SearchImageApprovalRequests.isPending(app, otherUrl))
    }

    @Test fun fastDecisionBeforeSendCompletionStillResolvesTheRequest() {
        val captured = SearchImageApprovalRequests.decisionAtSend(url)
        load(entry())
        SearchImageApprovalRequests.recordSent(app, url, captured)
        assertFalse(SearchImageApprovalRequests.isPending(app, url))
    }

    @Test fun retryStateAndDecisionTimestampSurviveCacheAndPreferencesReload() {
        load(entry())
        send()
        val cached = KosherWhitelistManager.exportWhitelistJson()
        val restored = context(values.toMutableMap())
        load()
        assertTrue(KosherWhitelistManager.replaceStateFromJson(cached))
        assertEquals(firstDecision, JSONObject(cached).getJSONArray("search_images").getJSONObject(0).getString("updated_at"))
        assertTrue(SearchImageApprovalRequests.isPending(restored, url))
        load(entry(updatedAt = nextDecision))
        assertFalse(SearchImageApprovalRequests.isPending(restored, url))
    }

    @Test fun legacyPendingRecordIsReleasedByExistingRejection() {
        values[SearchArtworkPermissions.key(url)] = System.currentTimeMillis()
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        load(entry())
        assertFalse(SearchImageApprovalRequests.isPending(app, url))
        assertFalse(SearchImageApprovalRequests.hasPending(app))
        send()
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
    }

    @Test fun approvalResolvesOnlyItsOwnRequest() {
        send()
        send(otherUrl)
        load(entry(status = "approved"))
        assertFalse(SearchImageApprovalRequests.isPending(app, url))
        assertTrue(SearchArtworkPermissions.isApproved(url))
        assertTrue(SearchImageApprovalRequests.isPending(app, otherUrl))
        assertTrue(SearchImageApprovalRequests.hasPending(app))
        assertFalse(SearchArtworkPermissions.isApproved(otherUrl))
    }

    @Test fun expiredRequestsPruneTheirBaselineWithoutLosingActiveBaselines() {
        load(entry())
        send()
        val id = SearchArtworkPermissions.key(url)
        values[id] = System.currentTimeMillis() - 11 * 60 * 1000L
        assertFalse(SearchImageApprovalRequests.isPending(app, url))
        assertFalse(SearchImageApprovalRequests.hasPending(app))
        send(otherUrl)
        assertFalse(values.containsKey(id))
        assertFalse(values.containsKey("$id:decision"))
        send()
        send(otherUrl)
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        assertTrue(SearchImageApprovalRequests.isPending(app, otherUrl))
    }

    @Test fun invalidScopeAndImageDigestCannotResolveARequest() {
        send()
        load(entry().put("scope", "player"))
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        load(entry().put("id", SearchArtworkPermissions.key(otherUrl)))
        assertTrue(SearchImageApprovalRequests.isPending(app, url))
        assertFalse(SearchArtworkPermissions.isApproved(url))
    }
}
