package com.music.spotui.playback

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AutoSearchCacheTest {
    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Test fun simultaneousSearchAndResultsShareTheSameSnapshot() = runBlocking {
        val scope = scope()
        try {
            val gate = CompletableDeferred<List<String>>()
            val requests = mutableListOf<String>()
            val cache = AutoSearchCache(scope, { 0L }) { query ->
                requests += query
                gate.await()
            }
            val search = async(start = CoroutineStart.UNDISPATCHED) { cache.get("  ישי ריבו  ") }
            val results = async(start = CoroutineStart.UNDISPATCHED) { cache.get("ישי ריבו") }
            val snapshot = listOf("artist:1", "playlist:2", "track:3")
            gate.complete(snapshot)
            assertSame(snapshot, search.await())
            assertSame(snapshot, results.await())
            assertSame(snapshot, cache.get("ישי ריבו"))
            assertEquals(listOf("ישי ריבו"), requests)
        } finally { scope.cancel() }
    }

    @Test fun distinctQueriesNeverSharePendingOrCompletedResults() = runBlocking {
        val scope = scope()
        try {
            val firstGate = CompletableDeferred<String>()
            val cache = AutoSearchCache(scope, { 0L }) { query ->
                if (query == "artist A") firstGate.await() else "results for $query"
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get("artist A") }
            assertEquals("results for artist B", cache.get("artist B"))
            firstGate.complete("results for artist A")
            assertEquals("results for artist A", first.await())
            assertEquals("results for artist B", cache.get("artist B"))
        } finally { scope.cancel() }
    }

    @Test fun failedLookupCanRetryWithoutCancellingTheServiceScope() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        try {
            var requests = 0
            val cache = AutoSearchCache(scope, { 0L }) { _: String ->
                if (++requests == 1) throw IOException("offline")
                "online result"
            }
            assertTrue(runCatching { cache.get("artist") }.exceptionOrNull() is IOException)
            assertTrue(scope.isActive)
            assertEquals("online result", cache.get("artist"))
            assertEquals(2, requests)
        } finally { scope.cancel() }
    }

    @Test fun ttlStartsAtCompletionAndReadsDoNotExtendIt() = runBlocking {
        val scope = scope()
        try {
            var now = 0L
            var requests = 0
            val firstGate = CompletableDeferred<String>()
            val cache = AutoSearchCache(scope, { now }, ttlMs = 100) { _: String ->
                if (++requests == 1) firstGate.await() else "second"
            }
            val first = async(start = CoroutineStart.UNDISPATCHED) { cache.get("artist") }
            now = 1_000
            firstGate.complete("first")
            assertEquals("first", first.await())
            now = 1_099
            assertEquals("first", cache.get("artist"))
            now = 1_100
            assertEquals("second", cache.get("artist"))
            assertEquals(2, requests)
        } finally { scope.cancel() }
    }

    @Test fun capacityEvictsLeastRecentlyReadCompletedQuery() = runBlocking {
        val scope = scope()
        try {
            val requests = mutableMapOf<String, Int>()
            val cache = AutoSearchCache(scope, { 0L }, maxEntries = 2) { query ->
                val count = (requests[query] ?: 0) + 1
                requests[query] = count
                "$query:$count"
            }
            assertEquals("A:1", cache.get("A"))
            assertEquals("B:1", cache.get("B"))
            assertEquals("A:1", cache.get("A"))
            assertEquals("C:1", cache.get("C"))
            assertEquals("A:1", cache.get("A"))
            assertEquals("B:2", cache.get("B"))
        } finally { scope.cancel() }
    }

    @Test fun cancellingOneCallbackDoesNotCancelSharedLookup() = runBlocking {
        val scope = scope()
        try {
            var requests = 0
            val gate = CompletableDeferred<String>()
            val cache = AutoSearchCache(scope, { 0L }) { _: String -> requests++; gate.await() }
            val search = async(start = CoroutineStart.UNDISPATCHED) { cache.get("artist") }
            search.cancelAndJoin()
            val results = async(start = CoroutineStart.UNDISPATCHED) { cache.get("artist") }
            gate.complete("same snapshot")
            assertEquals("same snapshot", results.await())
            assertEquals("same snapshot", cache.get("artist"))
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }

    @Test fun serviceCancellationCancelsPendingLookups() = runBlocking {
        val scope = scope()
        val gate = CompletableDeferred<String>()
        val cache = AutoSearchCache(scope, { 0L }) { _: String -> gate.await() }
        val request = async(start = CoroutineStart.UNDISPATCHED) { runCatching { cache.get("artist") } }
        scope.cancel()
        assertTrue(request.await().exceptionOrNull() is CancellationException)
    }

    @Test fun invalidQueriesNeverReachTheLoader() = runBlocking {
        val scope = scope()
        try {
            var requests = 0
            val cache = AutoSearchCache(scope, { 0L }) { _: String -> requests++; "result" }
            for (query in listOf("", " \t\n ", "x".repeat(201))) {
                assertTrue(runCatching { cache.get(query) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0, requests)
            assertEquals("result", cache.get("x".repeat(200)))
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }

    @Test fun invalidCacheBoundsAreRejected() {
        val scope = scope()
        try {
            assertTrue(runCatching {
                AutoSearchCache(scope, { 0L }, ttlMs = 0) { "result" }
            }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching {
                AutoSearchCache(scope, { 0L }, maxEntries = 0) { "result" }
            }.exceptionOrNull() is IllegalArgumentException)
        } finally { scope.cancel() }
    }
}
