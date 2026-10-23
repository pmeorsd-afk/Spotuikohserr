package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.components.ArtworkSwipeController
import com.music.spotui.ui.components.PlayerDismissPolicy
import org.junit.Assert.*
import org.junit.Test

class ArtworkSwipeControllerTest {
    private val queue = listOf(
        SongsModel(id = 1, title = "A", singer = "Artist", album = "Album", coverUri = "", url = "a"),
        SongsModel(id = 2, title = "B", singer = "Artist", album = "Album", coverUri = "", url = "b"),
        SongsModel(id = 3, title = "C", singer = "Artist", album = "Album", coverUri = "", url = "c"),
    )

    @Test fun nextAndPreviousSwipesCommitAtFlingCompletionOnce() {
        for (target in listOf(0, 2)) {
            val controller = ArtworkSwipeController()
            controller.begin("music")
            val ticket = controller.gesture
            // Finger-up/intermediate idle snapshots do not consume the drag authorization.
            assertNotNull(ticket)
            assertEquals(target, controller.finish(ticket, "music", queue, 2, target))
            assertNull(controller.finish(ticket, "music", queue, 2, target))
        }
    }

    @Test fun smallDragThatSnapsBackDoesNotPlayAnotherItem() {
        val controller = ArtworkSwipeController()
        controller.begin("music")
        assertNull(controller.finish(controller.gesture, "music", queue, 2, 1))
        assertNull(controller.gesture)
    }

    @Test fun programmaticPagerMovementDoesNotStartPlayback() {
        val controller = ArtworkSwipeController()
        assertNull(controller.finish(null, "music", queue, 2, 0))
    }

    @Test fun cancellationOrQueueDisposalRevokesSwipe() {
        val controller = ArtworkSwipeController()
        controller.begin("music")
        val ticket = controller.gesture
        controller.cancel()
        assertNull(controller.finish(ticket, "music", queue, 2, 0))
    }

    @Test fun externalOwnershipChangeWhileFlingingCannotStartOldMusic() {
        val controller = ArtworkSwipeController()
        controller.begin("old music")
        assertNull(controller.finish(controller.gesture, "external podcast", queue, 2, 0))
    }

    @Test fun oldFlingCannotConsumeNewDragAuthorization() {
        val controller = ArtworkSwipeController()
        controller.begin("music")
        val oldTicket = controller.gesture
        controller.begin("music")
        val newTicket = controller.gesture
        assertNull(controller.finish(oldTicket, "music", queue, 2, 0))
        assertEquals(newTicket, controller.gesture)
        assertEquals(2, controller.finish(newTicket, "music", queue, 2, 2))
    }

    @Test fun missingOrAmbiguousCurrentIdentityNeverSelectsPageZero() {
        for (currentQueue in listOf(queue, queue + queue[1])) {
            val controller = ArtworkSwipeController()
            controller.begin("music")
            val currentId = if (currentQueue.size == 3) 99 else 2
            assertNull(controller.finish(controller.gesture, "music", currentQueue, currentId, 0))
        }
    }

    @Test fun horizontalSwipeOwnsDiagonalMovementUntilItEnds() {
        assertEquals(0f, PlayerDismissPolicy.consume(0f, 14f, true, true, true, horizontalGesture = true))
        assertEquals(14f, PlayerDismissPolicy.consume(0f, 14f, true, true, true, horizontalGesture = false))
    }

    @Test fun alreadyStartedVerticalDismissalRetainsItsGesture() {
        assertEquals(14f, PlayerDismissPolicy.consume(30f, 14f, true, true, true, horizontalGesture = true))
    }
}
