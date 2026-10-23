package com.music.spotui.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class PlaybackPerformanceTest {
    @Test fun pauseDuringRecoveryBlocksLateRestartAndKeepsExplicitRetry() = runBlocking {
        val ownership = PlaybackOwnership()
        val retry = PlaybackRetry()
        val song = com.music.spotui.data.entity.SongsModel().copy(url = "spotify:track:exact")
        val owner = ownership.begin(song.url, song.mediaType, song)
        ownership.activate(owner.token)
        val result = CompletableDeferred<Unit>()
        var restarted = false
        val recovery = launch(start = CoroutineStart.UNDISPATCHED) {
            result.await()
            ownership.runIfCurrent(owner.token) { restarted = true }
        }
        retry.set(owner.token) { restarted = true }
        val action = retry.get(owner.token)
        ownership.revoke(owner.token)
        retry.clear()
        retry.set(ownership.current.token, action)
        result.complete(Unit)
        recovery.join()
        assertFalse(restarted)
        assertEquals(song, ownership.current.song)
        retry.get(ownership.current.token)!!.invoke()
        assertTrue(restarted)
    }

    @Test fun retryCannotUsePreviousTrackAfterPromotion() {
        val retry = PlaybackRetry()
        val played = mutableListOf<String>()
        retry.set("A") { played += "A" }
        assertNull(retry.get("B"))
        retry.set("B") { played += "B" }
        assertNull(retry.get("A"))
        retry.get("B")!!.invoke()
        assertEquals(listOf("B"), played)
    }

    @Test fun retryCanTransferExactActionToRevokedGeneration() {
        val retry = PlaybackRetry()
        var played = ""
        retry.set("pending") { played = "exact-track" }
        val action = retry.get("pending")
        retry.clear()
        retry.set("revoked", action)
        assertNull(retry.get("pending"))
        retry.get("revoked")!!.invoke()
        assertEquals("exact-track", played)
    }

    @Test fun externalTakeoverClearsMusicRetry() {
        val retry = PlaybackRetry()
        retry.set("music") { fail("Old music restarted") }
        retry.clear()
        assertNull(retry.get("music"))
        assertNull(retry.get("podcast"))
    }

    @Test fun staleCacheReaderCannotEvictNewNetworkEntry() {
        val cache = PlaybackSourceCache<String, String>({ 0 })
        cache.put("A", "fresh", "new-network", 1000)
        assertNull(cache.get("A", "old-network"))
        assertEquals("fresh", cache.get("A", "new-network"))
    }

    @Test fun timedOutOwnerCannotCommitLateResultOrControlOldMedia() {
        val ownership = PlaybackOwnership()
        val song = com.music.spotui.data.entity.SongsModel().copy(url = "spotify:track:exact")
        val ticket = ownership.begin(song.url, song.mediaType, song)
        assertTrue(ownership.revoke(ticket.token))
        assertFalse(ownership.canControl(ticket.token))
        assertFalse(ownership.runIfCurrent(ticket.token) { fail("Late playback committed") })
        assertNull(ownership.active)
        assertEquals(song, ownership.current.song)
        assertEquals(song.url, ownership.current.request)
    }

    @Test fun oldErrorCannotRevokeNewPodcastOwner() {
        val ownership = PlaybackOwnership()
        val old = ownership.begin("music", com.music.spotui.data.entity.MediaType.TRACK)
        val podcast = ownership.begin("episode:show:guid", com.music.spotui.data.entity.MediaType.PODCAST_EPISODE)
        assertFalse(ownership.revoke(old.token))
        assertEquals(podcast, ownership.current)
    }

    @Test fun retryAfterFailureGetsNewGenerationAndSameIdentity() {
        val ownership = PlaybackOwnership()
        val song = com.music.spotui.data.entity.SongsModel().copy(url = "spotify:track:exact")
        val first = ownership.begin(song.url, song.mediaType, song)
        ownership.revoke(first.token)
        val retry = ownership.begin(song.url, song.mediaType, song)
        assertTrue(retry.generation > first.generation)
        assertTrue(ownership.activate(retry.token))
        assertEquals(song, ownership.active?.song)
    }

    @Test fun expiredReplacementCannotLeaveOlderCachedUrlBehind() {
        val cache = PlaybackSourceCache<String, String>({ 0 })
        cache.put("A", "old", "scope", 1000)
        cache.put("A", "already-expired", "scope", -1)
        assertNull(cache.get("A", "scope"))
    }

    @Test fun prefetchAndTapShareOneRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = CompletableDeferred<String>()
            val shared = SharedResolution<String, String>(scope)
            var requests = 0
            val prefetch = async(start = CoroutineStart.UNDISPATCHED) { shared.get("track/high/network1") { requests++; source.await() } }
            val tap = async(start = CoroutineStart.UNDISPATCHED) { shared.get("track/high/network1") { error("Duplicate source lookup") } }
            source.complete("audio")
            assertEquals("audio", prefetch.await())
            assertEquals("audio", tap.await())
            assertEquals(1, requests)
        } finally { scope.cancel() }
    }

    @Test fun cancelledWaiterDoesNotRemoveRunningRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val source = CompletableDeferred<String>()
            val shared = SharedResolution<String, String>(scope)
            val prefetch = async(start = CoroutineStart.UNDISPATCHED) { shared.get("A") { source.await() } }
            prefetch.cancelAndJoin()
            val tap = async(start = CoroutineStart.UNDISPATCHED) { shared.get("A") { error("Work was duplicated") } }
            source.complete("audio")
            assertEquals("audio", tap.await())
        } finally { scope.cancel() }
    }

    @Test fun completedFailureCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val shared = SharedResolution<String, String>(scope)
            assertTrue(runCatching { shared.get("A") { throw UnknownHostException() } }.isFailure)
            assertEquals("retry", shared.get("A") { "retry" })
        } finally { scope.cancel() }
    }

    @Test fun independentKeysNeverShareSource() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val shared = SharedResolution<String, String>(scope)
            val pending = CompletableDeferred<String>()
            val first = async(start = CoroutineStart.UNDISPATCHED) { shared.get("track/high/network1") { pending.await() } }
            assertEquals("new", shared.get("track/low/network2") { "new" })
            pending.complete("old")
            assertEquals("old", first.await())
        } finally { scope.cancel() }
    }

    @Test fun cacheExpiresBeforeItCanBeReused() {
        var now = 100L
        val cache = PlaybackSourceCache<String, String>({ now })
        cache.put("track", "url", "network1/high", 1000)
        now = 1099
        assertEquals("url", cache.get("track", "network1/high"))
        now = 1100
        assertNull(cache.get("track", "network1/high"))
    }

    @Test fun cacheRejectsNetworkQualityAccountAndRevisionChanges() {
        val cache = PlaybackSourceCache<String, String>({ 0 })
        for (scope in listOf("network2/high/account1/1", "network1/low/account1/1", "network1/high/account2/1", "network1/high/account1/2")) {
            cache.put("track", "old-url", "network1/high/account1/1", 1000)
            assertNull(cache.get("track", scope))
        }
    }

    @Test fun cacheIsBoundedAndExpiredValuesAreNotStored() {
        val cache = PlaybackSourceCache<String, String>({ 0 }, capacity = 2)
        cache.put("A", "a", "scope", 100)
        cache.put("B", "b", "scope", 100)
        cache.put("C", "c", "scope", 100)
        cache.put("expired", "x", "scope", 0)
        assertNull(cache.get("A", "scope"))
        assertNull(cache.get("expired", "scope"))
        assertEquals("b", cache.get("B", "scope"))
    }

    @Test fun invalidationPreventsCachedRetryOfBadUrl() {
        val cache = PlaybackSourceCache<String, String>({ 0 })
        cache.put("A", "refused-url", "scope", 1000)
        cache.remove("A")
        assertNull(cache.get("A", "scope"))
    }

    @Test fun startupMeasuresFirstPlayingOnlyOnceAndIgnoresOldOwner() {
        var now = 100L
        val events = mutableListOf<String>()
        val startup = PlaybackStartup({ now }, events::add)
        startup.begin("old", 1)
        startup.begin("new", 2)
        now = 600
        startup.stage("old", "first_playing", true)
        assertTrue(startup.pending)
        startup.stage("new", "prepare")
        assertTrue(startup.pending)
        startup.stage("new", "first_playing", true)
        startup.stage("new", "first_playing", true)
        assertFalse(startup.pending)
        assertEquals(1, events.count { it.contains("first_playing") })
        assertTrue(events.last().contains("elapsedMs=500"))
        assertFalse(events.any { it.contains("old") || it.contains("new") })
    }

    @Test fun cancelledStartupCannotReportLateFailure() {
        val events = mutableListOf<String>()
        val startup = PlaybackStartup({ 0 }, events::add)
        startup.begin("owner", 1)
        startup.cancel()
        startup.stage("owner", "failed", true)
        assertEquals(1, events.size)
        assertFalse(startup.pending)
    }

    @Test fun errorMessagesAreClassifiedWithoutLeakingExceptionText() {
        assertEquals(PlaybackFailure.DNS, PlaybackFailure.from(RuntimeException(UnknownHostException("secret-url"))))
        assertEquals(PlaybackFailure.TLS, PlaybackFailure.from(SSLHandshakeException("secret-token")))
        assertEquals(PlaybackFailure.TIMEOUT, PlaybackFailure.from(SocketTimeoutException()))
        assertEquals(PlaybackFailure.UNAVAILABLE, PlaybackFailure.from(null))
        assertFalse(PlaybackFailure.from(UnknownHostException("secret-url")).message.contains("secret-url"))
    }

    @Test fun backgroundQueueHasOneWorkerAndNewListReplacesPendingItems() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val turns = Channel<Unit>(Channel.UNLIMITED)
            val finishA = CompletableDeferred<Unit>()
            val loaded = mutableListOf<String>()
            var activeCanRun = false
            val loader = BackgroundPreloader<String>(scope, { activeCanRun }, {
                loaded += it
                if (it == "A") finishA.await()
            }, { turns.receive() })
            loader.offer(listOf("A", "old"))
            turns.send(Unit)
            assertTrue(loaded.isEmpty())
            activeCanRun = true
            turns.send(Unit)
            assertEquals(listOf("A"), loaded)
            loader.offer(listOf("B", "C"), replace = true)
            assertEquals(listOf("A"), loaded)
            finishA.complete(Unit)
            turns.send(Unit)
            turns.send(Unit)
            assertEquals(listOf("A", "B", "C"), loaded)
            loader.clearQueued()
        } finally { scope.cancel() }
    }
}
