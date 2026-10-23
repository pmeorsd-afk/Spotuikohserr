package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AutoRadioStartTest {
    private fun track(n: Int) = SongsModel(n, "Track $n", "Album", "Artist", "",
        "spotify:track:${n.toString().padStart(22, '0')}|Track $n Artist", n.toString().padStart(22, '0'))

    @Test fun radioStartsTheHighestRankedNewTrackAndPublishesOnlyAfterSourcePreparation() = runBlocking {
        val anchor = track(1)
        var token = "anchor"
        var prepared: (() -> Unit)? = null
        var requested: SongsModel? = null
        var selection = PlaybackQueueIdentity.select(anchor)
        var commits = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            AutoRadioQueue.startStation(anchor, listOf(anchor, track(3), track(2)), { token }, {},
                prepare = { song, ready -> requested = song; prepared = ready; token = "new"; token },
                commit = { selection = it; commits++ })
        }
        assertEquals(track(3), requested)
        assertEquals(anchor, selection.song)
        assertFalse(pending.isCompleted)
        prepared!!()
        assertTrue(pending.await())
        assertEquals(track(3), selection.song)
        assertEquals(listOf(track(3), track(2)), selection.queue)
        assertEquals(0, selection.index)
        prepared!!()
        assertEquals(1, commits)
    }

    @Test fun noValidRecommendationLeavesCurrentAudioAndQueueAlone() = runBlocking {
        assertFalse(AutoRadioQueue.startStation(track(1), listOf(track(1), track(2).copy(url = "")),
            { "anchor" }, { fail("must not stop current playback") },
            prepare = { _, _ -> error("must not start a fallback") },
            commit = { fail("must not replace the queue") }))
    }

    @Test fun newerPlaybackWinsOverLateRadioPreparation() = runBlocking {
        var token = "radio"
        var complete: (() -> Unit)? = null
        var stopped = false
        var committed = false
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { AutoRadioQueue.startStation(track(1), listOf(track(2)), { token },
                cancel = { if (token == it) stopped = true },
                prepare = { _, ready -> complete = ready; "radio" },
                commit = { committed = true }) }
        }
        token = "new-user-choice"
        complete!!()
        assertTrue(result.await().exceptionOrNull() is CancellationException)
        assertFalse(stopped)
        assertFalse(committed)
    }

    @Test fun timedOutRadioCannotPublishFromALateCallback() = runBlocking {
        var complete: (() -> Unit)? = null
        var committed = false
        val cancelled = mutableListOf<String>()
        val result = runCatching { AutoRadioQueue.startStation(track(1), listOf(track(2)), { "radio" },
            cancel = { cancelled += it },
            prepare = { _, ready -> complete = ready; "radio" },
            commit = { committed = true }, timeoutMs = 20) }
        assertTrue(result.exceptionOrNull() is TimeoutCancellationException)
        complete!!()
        assertFalse(committed)
        assertEquals(listOf("radio"), cancelled)
    }

    @Test fun cachedPreparationCanCompleteImmediately() = runBlocking {
        var committed: SongsModel? = null
        assertTrue(AutoRadioQueue.startStation(track(1), listOf(track(2)), { "radio" }, {},
            prepare = { _, ready -> ready(); "radio" }, commit = { committed = it.song }))
        assertEquals(track(2), committed)
    }

    @Test fun cancellingTheCommandRevokesOnlyItsPendingPreparation() = runBlocking {
        var complete: (() -> Unit)? = null
        var committed = false
        val cancelled = mutableListOf<String>()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            AutoRadioQueue.startStation(track(1), listOf(track(2)), { "radio" },
                cancel = { cancelled += it }, prepare = { _, ready -> complete = ready; "radio" },
                commit = { committed = true })
        }
        pending.cancelAndJoin()
        complete!!()
        assertFalse(committed)
        assertEquals(listOf("radio"), cancelled)
    }

    @Test fun sourceFailureCannotPublishAnUnpreparedStation() = runBlocking {
        var committed = false
        val result = runCatching { AutoRadioQueue.startStation(track(1), listOf(track(2)), { "radio" }, {},
            prepare = { _, _ -> throw IllegalStateException("source unavailable") },
            commit = { committed = true }) }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertFalse(committed)
    }
}
