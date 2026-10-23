package com.music.spotui.playback

import com.music.spotui.util.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class HomeTapApprovalTest {
    @Test fun approvedAndPendingCoversDoNotSendOrSyncAgain() = runBlocking {
        val gate = HomeImageRequestCoordinator()
        assertEquals(HomeRequestResult.APPROVED, gate.request("cover", { true }, { false }, { error("sync") }, { error("send") }))
        assertEquals(HomeRequestResult.PENDING, gate.request("cover", { false }, { true }, { error("sync") }, { error("send") }))
    }
    @Test fun approvalOnAnotherPhoneIsRecheckedBeforeSending() = runBlocking {
        val gate = HomeImageRequestCoordinator(); var approved = false
        assertEquals(HomeRequestResult.APPROVED, gate.request("cover", { approved }, { false }, { approved = true; true }, { error("send") }))
    }
    @Test fun repeatedTapAndManualRequestShareOneInFlightSend() = runBlocking {
        val gate = HomeImageRequestCoordinator(); val complete = CompletableDeferred<Unit>(); var sends = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request("same-cover", { false }, { false }, { true }, { sends++; complete.await(); true })
        }
        assertEquals(HomeRequestResult.IN_FLIGHT, gate.request("same-cover", { false }, { false }, { error("duplicate sync") }, { error("duplicate send") }))
        complete.complete(Unit)
        assertEquals(HomeRequestResult.SENT, first.await()); assertEquals(1, sends)
    }
    @Test fun differentLocaleArtworkRemainsASeparateRequest() = runBlocking {
        val gate = HomeImageRequestCoordinator(); val hold = CompletableDeferred<Unit>()
        val base = "https://pickasso.spotifycdn.com/image/radio/artist/abc/"
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.request(HomeArtworkPermissions.key(base + "en"), { false }, { false }, { hold.await(); true }, { true })
        }
        assertEquals(HomeRequestResult.SENT, gate.request(HomeArtworkPermissions.key(base + "en-GB"), { false }, { false }, { true }, { true }))
        hold.complete(Unit); assertEquals(HomeRequestResult.SENT, first.await())
    }
    @Test fun failedSyncDoesNotSendAndNextTapCanRetry() = runBlocking {
        val gate = HomeImageRequestCoordinator()
        assertEquals(HomeRequestResult.SYNC_FAILED, gate.request("cover", { false }, { false }, { false }, { error("send") }))
        assertEquals(HomeRequestResult.SENT, gate.request("cover", { false }, { false }, { true }, { true }))
    }
    @Test fun failedSendDoesNotLockOutTheNextTap() = runBlocking {
        val gate = HomeImageRequestCoordinator()
        assertEquals(HomeRequestResult.FAILED, gate.request("cover", { false }, { false }, { true }, { false }))
        assertEquals(HomeRequestResult.FAILED, gate.request("cover", { false }, { false }, { true }, { error("network") }))
        assertEquals(HomeRequestResult.SENT, gate.request("cover", { false }, { false }, { true }, { true }))
    }
    @Test fun cancellationReleasesOnlyItsOwnImage() = runBlocking {
        val gate = HomeImageRequestCoordinator()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.request("cover", { false }, { false }, { awaitCancellation() }, { true })
        }
        first.cancelAndJoin()
        assertEquals(HomeRequestResult.SENT, gate.request("cover", { false }, { false }, { true }, { true }))
    }
    @Test fun builtInAndInvalidImagesHaveNoRequestPayload() {
        listOf("", "file:///cover.jpg", "content://cover", "http://example.com/a.jpg").forEach {
            assertNull(HomeImageApprovalRequest.create("cover", it))
        }
    }
}
