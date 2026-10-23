package com.music.spotui.playback

import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackResumptionTest {
    @Test fun onlyFreshPlayKeysCanWakeTheService() {
        assertTrue(ResumeMediaButtonPolicy.accepts(0, 126, 0))
        assertTrue(ResumeMediaButtonPolicy.accepts(0, 85, 0))
        for (key in listOf(126, 85)) {
            assertFalse(ResumeMediaButtonPolicy.accepts(1, key, 0))
            assertFalse(ResumeMediaButtonPolicy.accepts(0, key, 1))
        }
        for (key in listOf(127, 86, 87, 88, 0)) {
            assertFalse(ResumeMediaButtonPolicy.accepts(0, key, 0))
        }
    }

    @Test fun musicWaitsForTheActualPreparedItem() = runBlocking {
        val exactItem = Any()
        var complete: ((Any) -> Boolean)? = null
        val cancelled = mutableListOf<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitResumptionPreparation(2_000, { "owned" }, { cancelled += it }) {
                complete = it
                "owned"
            }
        }
        assertFalse(pending.isCompleted)
        assertTrue(complete!!(exactItem))
        assertFalse(complete!!(Any()))
        assertSame(exactItem, pending.await())
        assertTrue(cancelled.isEmpty())
    }

    @Test fun alreadyCachedMusicCanPrepareSynchronously() = runBlocking {
        val exactItem = Any()
        val result = awaitResumptionPreparation<Any>(2_000, { "owned" }, { fail("Unexpected cancel") }) {
            assertTrue(it(exactItem))
            "owned"
        }
        assertSame(exactItem, result)
    }

    @Test fun offlinePreparationHasABoundedDeadlineAndRejectsLateCompletion() = runBlocking {
        var complete: ((Any) -> Boolean)? = null
        val cancelled = mutableListOf<String>()
        val outcome = runCatching {
            awaitResumptionPreparation<Any>(120, { "owned" }, { cancelled += it }) {
                complete = it
                "owned"
            }
        }
        assertTrue(outcome.exceptionOrNull() is TimeoutCancellationException)
        assertEquals(listOf("owned"), cancelled)
        assertFalse(complete!!(Any()))
    }

    @Test fun pauseCancelsPendingPreparationAndCannotPlayFromALateCallback() = runBlocking {
        var complete: ((Any) -> Boolean)? = null
        val cancelled = mutableListOf<String>()
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            awaitResumptionPreparation<Any>(2_000, { "owned" }, { cancelled += it }) {
                complete = it
                "owned"
            }
        }
        pending.cancelAndJoin()
        assertEquals(listOf("owned"), cancelled)
        assertFalse(complete!!(Any()))
    }

    @Test fun newerSelectionSurvivesAnOlderResumptionCompletingOrTimingOut() = runBlocking {
        var currentToken = "old"
        var stopped = false
        var complete: ((Any) -> Boolean)? = null
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                awaitResumptionPreparation<Any>(2_000, { currentToken }, { if (it == currentToken) stopped = true }) {
                    complete = it
                    "old"
                }
            }
        }
        currentToken = "new"
        assertFalse(complete!!(Any()))
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertFalse(stopped)
        assertEquals("new", currentToken)
    }

    @Test fun aPreparedResultCannotCommitAfterTheOwnerChanges() = runBlocking {
        var currentToken = "old"
        val outcome = runCatching {
            awaitResumptionPreparation<Any>(2_000, { currentToken }, {}) {
                it(Any())
                currentToken = "new"
                "old"
            }
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
    }

    @Test fun onlyTheExactPreparedInstanceAndOwnerAvoidReapplyingMusic() {
        data class Item(val url: String)
        val item = Item("resolved source")
        val prepared = PreparedResumption<Item>()
        prepared.set(item, "owner")
        assertFalse(prepared.consume(emptyList(), item, "owner"))
        assertFalse(prepared.consume(listOf(item.copy()), item, "owner"))
        assertFalse(prepared.consume(listOf(item), item.copy(), "owner"))
        assertFalse(prepared.consume(listOf(item), item, "new-owner"))
        assertTrue(prepared.consume(listOf(item), item, "owner"))
        assertFalse(prepared.consume(listOf(item), item, "owner"))
    }

    @Test fun cancelAlsoRevokesAPreparedButUndispatchedItem() {
        val item = Any()
        val prepared = PreparedResumption<Any>()
        prepared.set(item, "owner")
        prepared.clear()
        assertFalse(prepared.consume(listOf(item), item, "owner"))
    }

    private fun media3Completion(future: SettableFuture<Any>, gate: ResumptionDispatchGate, play: () -> Unit) {
        // MediaSessionImpl 1.3.1 calls handlePlayButtonAction in BOTH completion branches,
        // using postOrRunOnApplicationHandler (inline when completed on the player thread).
        Futures.addCallback(future, object : FutureCallback<Any> {
            override fun onSuccess(result: Any?) { if (gate.allowed) play() }
            override fun onFailure(t: Throwable) { if (gate.allowed) play() }
        }, MoreExecutors.directExecutor())
    }

    @Test fun media3FailurePlayCannotResumeANewerPausedItem() {
        val gate = ResumptionDispatchGate()
        val future = SettableFuture.create<Any>()
        var playCalls = 0
        media3Completion(future, gate) { playCalls++ }
        gate.complete(false) { future.setException(IllegalStateException("offline")) }
        assertEquals(0, playCalls)
        assertTrue(gate.allowed) // Future explicit user Play is unaffected.
    }

    @Test fun media3CancellationPlayIsSuppressedButSuccessfulResumeCanPlay() {
        val gate = ResumptionDispatchGate()
        var playCalls = 0
        val cancelled = SettableFuture.create<Any>()
        media3Completion(cancelled, gate) { playCalls++ }
        gate.complete(false) { cancelled.cancel(true) }
        assertEquals(0, playCalls)
        val success = SettableFuture.create<Any>()
        media3Completion(success, gate) { playCalls++ }
        gate.complete(true) { success.set(Any()) }
        assertEquals(1, playCalls)
    }

    @Test fun aNestedCompletionCannotLiftAnOuterCancellationGuard() {
        val gate = ResumptionDispatchGate()
        runCatching {
            gate.complete(false) {
                gate.complete(true) { assertFalse(gate.allowed) }
                throw IllegalStateException("callback failure")
            }
        }
        assertTrue(gate.allowed)
    }

    private val episodeId = "1234567890abcdefghijkl"
    private val episode = SongsModel(1, "Episode", "Show", "Publisher", "", "episode:$episodeId", episodeId,
        mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show")

    @Test fun podcastResumptionRetainsTypedIdentityDirectAudioAndBookmark() = runBlocking {
        val saved = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(PlaybackSnapshot(episode, 51_000)))!!
        val plan = WazeResumeResolver.resolve(WazeResumeResolver.Request(), saved, null)!!
        val owner = PlaybackOwnership()
        val requests = ExternalPlaybackRequests({ owner.current.token }, { song, id ->
            owner.begin(song.url, song.mediaType, song, external = true, mediaId = id)
        }, { exact ->
            assertEquals(episode, exact)
            PlaybackSource.DirectAudio("https://example.test/episode.mp3")
        })
        var reserved: PlaybackOwnership.Ticket? = null
        val result = requests.podcast(requests.begin(), plan.playback.song, plan.playback.positionMs) { reserved = it }
        assertSame(reserved, result.ticket)
        assertEquals(episode, result.ticket.song)
        assertEquals(51_000L, result.positionMs)
        assertEquals("https://example.test/episode.mp3", result.source.url)
    }

    @Test fun podcastOwnershipIsAvailableForCancellationBeforeRssCompletes() = runBlocking {
        val owner = PlaybackOwnership()
        val source = CompletableDeferred<PlaybackSource>()
        var reserved: PlaybackOwnership.Ticket? = null
        val requests = ExternalPlaybackRequests({ owner.current.token }, { song, id ->
            owner.begin(song.url, song.mediaType, song, external = true, mediaId = id)
        }, { source.await() })
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { requests.podcast(requests.begin(), episode, 51_000) { reserved = it } }
        }
        assertNotNull(reserved)
        assertFalse(pending.isCompleted)
        assertTrue(owner.revoke(reserved!!.token))
        source.complete(PlaybackSource.DirectAudio("https://example.test/episode.mp3"))
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertNull(owner.active)
    }

    @Test fun noSavedIdentityDoesNotInventAResumeItem() {
        assertNull(WazeResumeResolver.resolve(WazeResumeResolver.Request(), null, null))
        val invalid = PlaybackSnapshot(episode.copy(url = "", spotifyTrackId = "", title = ""), 5_000)
        assertNull(WazeResumeResolver.resolve(WazeResumeResolver.Request(), invalid, null))
    }
}
