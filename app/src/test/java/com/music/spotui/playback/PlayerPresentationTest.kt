package com.music.spotui.playback

import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.ui.components.PlayerDismissPolicy
import com.music.spotui.ui.viewmodel.PlayerArtistLoader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class PlayerPresentationTest {
    @Test fun shortPullReturnsToPlayer() {
        assertFalse(PlayerDismissPolicy.shouldDismiss(30f, 0f, 850f))
    }

    @Test fun sufficientPullClosesOnBothViewportSizes() {
        assertTrue(PlayerDismissPolicy.shouldDismiss(96f, 0f, 850f))
        assertTrue(PlayerDismissPolicy.shouldDismiss(50f, 0f, 400f))
    }

    @Test fun deliberateFastPullClosesButTinyMovementDoesNot() {
        assertTrue(PlayerDismissPolicy.shouldDismiss(20f, 1100f, 850f))
        assertFalse(PlayerDismissPolicy.shouldDismiss(5f, 1100f, 850f))
    }

    @Test fun upwardReleaseReturnsEvenAfterLongPull() {
        assertFalse(PlayerDismissPolicy.shouldDismiss(110f, -700f, 850f))
    }

    @Test fun scrolledContentRetainsItsVerticalScroll() {
        assertEquals(0f, PlayerDismissPolicy.consume(0f, 40f, false, true, true))
        assertEquals(40f, PlayerDismissPolicy.consume(0f, 40f, true, true, true))
    }

    @Test fun inertialScrollingNeverStartsOrContinuesDismissal() {
        assertEquals(0f, PlayerDismissPolicy.consume(0f, 40f, true, false, true))
        assertEquals(0f, PlayerDismissPolicy.consume(20f, 40f, true, false, true))
    }

    @Test fun upwardDragReturnsOnlyExistingDisplacement() {
        assertEquals(-20f, PlayerDismissPolicy.consume(20f, -50f, true, true, true))
        assertEquals(0f, PlayerDismissPolicy.consume(0f, -50f, true, true, true))
    }

    @Test fun horizontalAndDisabledGesturesDoNotMovePlayer() {
        assertEquals(0f, PlayerDismissPolicy.consume(0f, 0f, true, true, true))
        assertEquals(0f, PlayerDismissPolicy.consume(0f, 40f, true, true, false))
    }

    @Test fun openingPlayerStartsArtistRequestWithoutAnyCardCollector() = runBlocking {
        val requested = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val loader = PlayerArtistLoader(scope) {
                requested += it
                flowOf(Response.Success(ArtistOverviewModel(name = it)))
            }
            loader.load("Akiva")
            assertEquals(listOf("Akiva"), requested)
            assertEquals("Akiva", (loader.overview.value.response as Response.Success).data.name)
            loader.load(" Akiva ")
            assertEquals(1, requested.size)
        } finally { scope.cancel() }
    }

    @Test fun artistChangeClearsPreviousCardWhileNewRequestLoads() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val loader = PlayerArtistLoader(scope) {
                if (it == "B") gate.await()
                flowOf(Response.Success(ArtistOverviewModel(name = it)))
            }
            loader.load("A")
            loader.load("B")
            assertEquals("B", loader.overview.value.artist)
            assertTrue(loader.overview.value.response is Response.Loading)
            gate.complete(Unit)
            assertEquals("B", (loader.overview.value.response as Response.Success).data.name)
        } finally { scope.cancel() }
    }

    @Test fun cancelledOldRequestCannotPublishLateResultOverNewArtist() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val oldFinished = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val loader = PlayerArtistLoader(scope) { name ->
                if (name == "A") {
                    withContext(NonCancellable) { gate.await() }
                    oldFinished.complete(Unit)
                }
                // flowOf does not suspend before publishing, even if its caller was cancelled.
                flowOf(Response.Success(ArtistOverviewModel(name = name)))
            }
            loader.load("A")
            loader.load("B")
            gate.complete(Unit)
            withTimeout(1000) { oldFinished.await() }
            assertEquals("B", loader.overview.value.artist)
            assertEquals("B", (loader.overview.value.response as Response.Success).data.name)
        } finally { scope.cancel() }
    }

    @Test fun podcastOrMissingArtistMakesNoRequestAndClearsMusicCard() = runBlocking {
        var requests = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val loader = PlayerArtistLoader(scope) {
                requests++
                flowOf(Response.Success(ArtistOverviewModel(name = it)))
            }
            loader.load(null)
            loader.load(" ")
            assertEquals(0, requests)
            loader.load("A")
            loader.load(null)
            assertEquals(1, requests)
            assertNull(loader.overview.value.artist)
            assertTrue(loader.overview.value.response is Response.Loading)
        } finally { scope.cancel() }
    }

    @Test fun failureIsContainedAndNextArtistCanStillLoad() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val loader = PlayerArtistLoader(scope) {
                if (it == "A") error("offline")
                flowOf(Response.Success(ArtistOverviewModel(name = it)))
            }
            loader.load("A")
            assertTrue(loader.overview.value.response is Response.Error)
            loader.load("B")
            assertEquals("B", (loader.overview.value.response as Response.Success).data.name)
        } finally { scope.cancel() }
    }
}
