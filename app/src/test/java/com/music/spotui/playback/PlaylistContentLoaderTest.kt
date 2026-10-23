package com.music.spotui.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistContentLoaderTest {
    @Test fun headerAndFirstSongsArriveBeforeLaterPageCompletes() = runBlocking {
        val nextPage = CompletableDeferred<Unit>()
        val offsets = mutableListOf<Int>()
        val values = mutableListOf<PlaylistContentLoader.Content<String, String>>()
        val loader = PlaylistContentLoader<String, String> { offset ->
            offsets += offset
            if (offset == 0) PlaylistContentLoader.Page("header", listOf("A"), 2, 1)
            else { nextPage.await(); PlaylistContentLoader.Page("header", listOf("B"), 2, 1) }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { loader.load().toList(values) }
        assertEquals(listOf("A"), values.single().items)
        assertEquals("header", values.single().header)
        nextPage.complete(Unit)
        job.join()
        assertEquals(listOf(0, 1), offsets)
        assertEquals(listOf("A", "B"), values.last().items)
    }

    @Test fun unavailableItemsDoNotCauseDuplicateOffsetsOrLoseOrdering() = runBlocking {
        val offsets = mutableListOf<Int>()
        val loader = PlaylistContentLoader<String, String> { offset ->
            offsets += offset
            if (offset == 0) PlaylistContentLoader.Page("header", listOf("A"), 4, 3)
            else PlaylistContentLoader.Page("header", listOf("B"), 4, 1)
        }
        assertEquals(listOf("A", "B"), loader.load().last().items)
        assertEquals(listOf(0, 3), offsets)
    }

    @Test fun emptyPlaylistStillPublishesHeaderWithOneRequest() = runBlocking {
        var requests = 0
        val loader = PlaylistContentLoader<String, String> {
            requests++; PlaylistContentLoader.Page("empty", emptyList(), 0, 0)
        }
        val result = loader.load().single()
        assertEquals("empty", result.header)
        assertTrue(result.items.isEmpty())
        assertEquals(1, requests)
    }

    @Test fun emptyServerPageStopsInsteadOfLoopingForever() = runBlocking {
        var requests = 0
        val loader = PlaylistContentLoader<String, String> {
            requests++; PlaylistContentLoader.Page("header", emptyList(), 100, 0)
        }
        loader.load().toList()
        assertEquals(1, requests)
    }

    @Test fun failedLoadCanRetrySamePlaylistFromOffsetZero() = runBlocking {
        var shouldFail = true
        val offsets = mutableListOf<Int>()
        val loader = PlaylistContentLoader<String, String> {
            offsets += it
            if (shouldFail) error("Network unavailable")
            PlaylistContentLoader.Page("header", listOf("A"), 1, 1)
        }
        assertTrue(runCatching { loader.load().toList() }.isFailure)
        shouldFail = false
        assertEquals(listOf("A"), loader.load().single().items)
        assertEquals(listOf(0, 0), offsets)
    }

    @Test fun cancelledLoadNeverPublishesLatePages() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val values = mutableListOf<PlaylistContentLoader.Content<String, String>>()
        val loader = PlaylistContentLoader<String, String> {
            gate.await()
            PlaylistContentLoader.Page("header", listOf("A"), 1, 1)
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { loader.load().toList(values) }
        job.cancelAndJoin()
        gate.complete(Unit)
        assertTrue(values.isEmpty())
    }
}
