package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RadioPreviewSessionTest {
    private fun song(id: Int = 1) = SongsModel(id, "Song $id", "Album", "Artist", "", "spotify:$id|Song", durationMs = 180_000)
    private class Main : PreviewMainPlayback {
        var token = "podcast-owner"
        var playing = true
        var resumes = 0
        var pauses = 0
        var replaceTokenOnPause = false
        // Represents the main podcast position, metadata and queue: preview never writes these.
        val metadata = "Current podcast"
        val position = 72_123L
        val queue = listOf("podcast-one", "podcast-two")
        override fun identity() = token
        override fun wantsToPlay() = playing
        override fun pause() { pauses++; playing = false; if (replaceTokenOnPause) token = "paused-pending-request" }
        override fun resume() { resumes++; playing = true }
    }
    private class Output : PreviewOutput {
        var events: PreviewOutput.Events? = null
        var loaded = ""
        var position = -1L
        var stopped = 0
        var actualPosition: Long? = null
        override fun load(source: RadioPreviewSource, window: PreviewWindow, positionMs: Long, events: PreviewOutput.Events) {
            loaded = source.uri; position = positionMs; this.events = events
            events.playing(true)
        }
        override fun stop() { stopped++ }
        override fun positionMs() = actualPosition
    }
    private class Harness(resolve: suspend (SongsModel) -> RadioPreviewSource? = { RadioPreviewSource(it.url) }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val main = Main()
        val output = Output()
        val session = RadioPreviewSession(scope, main, output, resolve)
    }

    @Test fun centeredTenSecondsAndShortSongs() {
        assertEquals(PreviewWindow(85_000, 95_000), PreviewWindow.middle(180_000))
        assertEquals(PreviewWindow(0, 5_000), PreviewWindow.middle(5_000))
        assertNull(PreviewWindow.middle(0)); assertNull(PreviewWindow.middle(-1))
    }
    @Test fun completionRestoresPodcastWithoutChangingIdentityPositionOrQueue() {
        val h = Harness(); h.session.toggle(song())
        assertFalse(h.main.playing)
        h.output.events!!.position(10_000); h.output.events!!.ended()
        assertTrue(h.main.playing); assertEquals(1, h.main.resumes)
        assertEquals("Current podcast", h.main.metadata); assertEquals(72_123, h.main.position)
        assertEquals(listOf("podcast-one", "podcast-two"), h.main.queue)
        assertEquals(1f, h.session.state.value.progress)
        h.session.close(); assertEquals(1, h.main.resumes)
    }
    @Test fun pauseRestoresMainAndResumeContinuesRemainingPreview() {
        val h = Harness(); h.session.toggle(song()); h.output.events!!.position(3_200)
        h.session.toggle(song()); assertTrue(h.main.playing)
        h.session.toggle(song()); assertFalse(h.main.playing)
        assertEquals(3_200, h.output.position)
        h.output.events!!.ended(); assertEquals(2, h.main.resumes)
    }
    @Test fun previouslyPausedMainStaysPaused() {
        val h = Harness(); h.main.playing = false; h.session.toggle(song())
        h.output.events!!.ended(); assertFalse(h.main.playing); assertEquals(0, h.main.resumes)
    }
    @Test fun switchingRowsDoesNotBrieflyResumeMainAndIgnoresOldCallbacks() {
        val h = Harness(); h.session.toggle(song(1)); val old = h.output.events!!
        h.session.toggle(song(2)); old.ended(); old.failed(); old.position(9_000)
        assertFalse(h.main.playing); assertEquals(0, h.main.resumes)
        assertEquals(song(2).previewKey(), h.session.state.value.key)
        assertEquals(0, h.session.state.value.positionMs)
        h.output.events!!.ended(); assertEquals(1, h.main.resumes)
    }
    @Test fun lateUncancellableResolutionCannotReplaceNewPreview() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val h = Harness { s -> if (s.id == 1) withContext(NonCancellable) { slow.await() }; RadioPreviewSource(s.url) }
        h.session.toggle(song(1)); h.session.toggle(song(2)); slow.complete(Unit); yield()
        assertEquals(song(2).url, h.output.loaded)
        assertEquals(song(2).previewKey(), h.session.state.value.key)
    }
    @Test fun cancellingLoadingRestoresMainAndIgnoresLateResult() = runBlocking {
        val slow = CompletableDeferred<Unit>()
        val h = Harness { withContext(NonCancellable) { slow.await() }; RadioPreviewSource(it.url) }
        h.session.toggle(song()); h.session.toggle(song()); slow.complete(Unit); yield()
        assertEquals("", h.output.loaded); assertTrue(h.main.playing)
        assertEquals(PreviewPhase.PAUSED, h.session.state.value.phase)
    }
    @Test fun failureRestoresMainWithoutStartingAnotherSong() {
        val h = Harness { null }; h.session.toggle(song())
        assertEquals("", h.output.loaded); assertTrue(h.main.playing)
        assertEquals(PreviewPhase.FAILED, h.session.state.value.phase)
    }
    @Test fun focusLossOrNoisyOutputDoesNotRestartMain() {
        val h = Harness(); h.session.toggle(song()); h.output.events!!.interrupted()
        h.session.close(); assertEquals(0, h.main.resumes); assertFalse(h.main.playing)
    }
    @Test fun differentMainOwnerNeverResumesOldItem() {
        val h = Harness(); h.session.toggle(song()); h.main.token = "new-track-owner"
        h.output.events!!.ended(); assertEquals(0, h.main.resumes)
    }
    @Test fun externalPlayStopsPreviewAndKeepsMainPlaying() {
        val h = Harness(); h.session.toggle(song()); h.main.playing = true
        h.session.checkMainPlayback(); assertEquals(PreviewPhase.PAUSED, h.session.state.value.phase)
        assertEquals(0, h.main.resumes); assertTrue(h.main.playing)
    }
    @Test fun closingScreenRestoresMainAndInvalidatesCallbacks() {
        val h = Harness(); h.session.toggle(song()); val old = h.output.events!!
        h.session.close(); old.failed(); old.ended(); h.session.toggle(song(2))
        assertEquals(1, h.main.resumes); assertEquals(PreviewPhase.IDLE, h.session.state.value.phase)
    }
    @Test fun podcastsNeverEnterPreviewResolver() {
        var calls = 0
        val h = Harness { calls++; RadioPreviewSource(it.url) }
        h.session.toggle(song().copy(mediaType = MediaType.PODCAST_EPISODE))
        assertEquals(0, calls); assertEquals(0, h.main.pauses)
    }
    @Test fun unknownDurationDoesNotInventClipOrPauseMain() {
        val h = Harness(); h.session.toggle(song().copy(durationMs = 0))
        assertEquals(0, h.main.pauses); assertEquals("", h.output.loaded)
        assertEquals(PreviewPhase.FAILED, h.session.state.value.phase)
    }
    @Test fun transportInterruptionDistinguishesPreviewOwnedCommands() {
        var calls = 0
        RadioPreviewInterruption.listener = { calls++ }
        try {
            RadioPreviewInterruption.fromPreview { RadioPreviewInterruption.mainCommand() }
            assertEquals(0, calls)
            RadioPreviewInterruption.mainCommand(); assertEquals(1, calls)
        } finally { RadioPreviewInterruption.listener = null }
    }
    @Test fun externalPauseWhileMainAlreadyPausedRevokesAutomaticResume() {
        val h = Harness(); h.session.toggle(song())
        RadioPreviewInterruption.listener = h.session::interrupt
        try {
            RadioPreviewInterruption.mainCommand()
            h.output.events!!.ended(); h.session.close()
            assertFalse(h.main.playing); assertEquals(0, h.main.resumes)
        } finally { RadioPreviewInterruption.listener = null }
    }
    @Test fun pausedPendingMainRequestUsesNewResumeIdentity() {
        val h = Harness(); h.main.replaceTokenOnPause = true; h.session.toggle(song())
        h.output.events!!.ended(); assertTrue(h.main.playing); assertEquals(1, h.main.resumes)
    }
    @Test fun pauseReadsExactAudioPositionInsteadOfLastUiTick() {
        val h = Harness(); h.session.toggle(song()); h.output.events!!.position(3_200)
        h.output.actualPosition = 3_245
        h.session.toggle(song()); assertEquals(3_245, h.session.state.value.positionMs)
        h.session.toggle(song()); assertEquals(3_245, h.output.position)
    }
}
