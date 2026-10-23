package com.music.spotui.playback

import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AutoHomeFeedCacheTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private fun snapshot(id: String) = AutoHomeContent.Snapshot(
        HomeFeedModel(sections = listOf(HomeSection(title = id, items = listOf(HomeItem.Playlist(id, "", "", id))))),
        emptyList())

    @Test fun localHistoryCanRenderImmediatelyWhileFirstRemoteFetchRuns() = runBlocking {
        val scope = scope()
        try {
            val gate = CompletableDeferred<AutoHomeContent.Snapshot>()
            var calls = 0
            val cache = AutoHomeFeedCache(scope, { 0L }) { calls++; gate.await() }
            assertNull(cache.get(waitForFirst = false))
            val firstBrowse = async(start = CoroutineStart.UNDISPATCHED) { cache.get(waitForFirst = true) }
            assertFalse(firstBrowse.isCompleted)
            assertNull(cache.get(waitForFirst = false))
            assertEquals(1, calls)
            val loaded = snapshot("server")
            gate.complete(loaded)
            assertEquals(loaded, firstBrowse.await())
            assertEquals(loaded, cache.state.value)
        } finally { scope.cancel() }
    }

    @Test fun expiryUsesCompletionTimeAndReturnsCachedFeedWhileRefreshing() = runBlocking {
        val scope = scope()
        try {
            var now = 0L
            var calls = 0
            val first = CompletableDeferred<AutoHomeContent.Snapshot>()
            val second = CompletableDeferred<AutoHomeContent.Snapshot>()
            val cache = AutoHomeFeedCache(scope, { now }, ttlMs = 100) { if (++calls == 1) first.await() else second.await() }
            cache.get(false)
            now = 1_000; first.complete(snapshot("A"))
            now = 1_099
            assertEquals(snapshot("A"), cache.get(true))
            assertEquals(1, calls)
            now = 1_100
            assertEquals(snapshot("A"), cache.get(true))
            assertEquals(2, calls)
            second.complete(snapshot("B"))
            assertEquals(snapshot("B"), cache.state.value)
        } finally { scope.cancel() }
    }

    @Test fun offlineRefreshRetainsContentAndRetriesWithoutRequestStorms() = runBlocking {
        val scope = scope()
        try {
            var now = 0L
            var calls = 0
            val cache = AutoHomeFeedCache(scope, { now }, ttlMs = 100, retryMs = 10) {
                if (++calls == 2) throw IOException("offline")
                snapshot("$calls")
            }
            assertEquals(snapshot("1"), cache.get(true))
            now = 100
            assertEquals(snapshot("1"), cache.get(true))
            repeat(10) { cache.get(false) }
            assertEquals(2, calls)
            now = 110
            cache.get(false)
            assertEquals(snapshot("3"), cache.state.value)
        } finally { scope.cancel() }
    }

    @Test fun callbackCancellationDoesNotCancelTheSharedRemoteRefresh() = runBlocking {
        val scope = scope()
        try {
            val gate = CompletableDeferred<AutoHomeContent.Snapshot>()
            val cache = AutoHomeFeedCache(scope, { 0L }) { gate.await() }
            val callback = async(start = CoroutineStart.UNDISPATCHED) { cache.get(true) }
            callback.cancelAndJoin()
            gate.complete(snapshot("completed"))
            assertEquals(snapshot("completed"), cache.state.value)
        } finally { scope.cancel() }
    }

    @Test fun serviceTeardownCancelsFetchAndNeverPublishesLateContent() = runBlocking {
        val scope = scope()
        val gate = CompletableDeferred<AutoHomeContent.Snapshot>()
        val cache = AutoHomeFeedCache(scope, { 0L }) { gate.await() }
        cache.get(false)
        scope.cancel()
        gate.complete(snapshot("too late"))
        assertNull(cache.state.value)
    }

    @Test fun unavailableOrTimedOutFirstFeedDoesNotPreventLaterRetry() = runBlocking {
        val scope = scope()
        try {
            var now = 0L
            var calls = 0
            val cache = AutoHomeFeedCache(scope, { now }, timeoutMs = 40, retryMs = 10) {
                when (++calls) {
                    1 -> null
                    2 -> { awaitCancellation() }
                    else -> snapshot("online")
                }
            }
            assertNull(cache.get(true))
            now = 10
            assertNull(withTimeout(2_000) { cache.get(true) })
            now = 20
            assertEquals(snapshot("online"), cache.get(true))
        } finally { scope.cancel() }
    }
}
