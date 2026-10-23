package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Headless integration of production coordinators; the Android Player boundary is simulated. */
class PlaybackIntegrationTest {
    private val idA = "1234567890abcdefghijkl"
    private val idB = "abcdefghijkl1234567890"
    private val a = SongsModel(1, "A", "Show", "Publisher", "", "episode:$idA", idA,
        mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show")
    private val b = a.copy(id = 2, title = "B", url = "episode:$idB", spotifyTrackId = idB)
    private val music = SongsModel(9, "Music", "Album", "Artist", "", "spotify:track:$idA|Music Artist", idA)
    private val direct = PlaybackSource.DirectAudio("https://example.test/episode.mp3")

    private class Boundary(source: suspend (SongsModel) -> PlaybackSource?) {
        val owner = PlaybackOwnership()
        val requests = ExternalPlaybackRequests({ owner.current.token }, { song, id ->
            owner.begin(song.url, song.mediaType, song, external = true, mediaId = id)
        }, source)
        var actual: SongsModel? = null
        var ui: PlaybackQueueIdentity.Selection? = null
        fun begin(): ExternalPlaybackRequests.Request {
            owner.begin("", MediaType.TRACK, external = true)
            return requests.begin()
        }
        fun accept(p: ExternalPlaybackRequests.Prepared): Boolean = owner.runIfCurrent(p.ticket.token) {
            check(owner.activate(p.ticket.token, p.ticket.mediaId))
            actual = p.ticket.song!!
            ui = PlaybackQueueIdentity.select(actual!!)
        }
        fun invariant() {
            assertEquals(actual, owner.active?.song)
            assertEquals(actual, ui?.song)
            assertEquals(actual, ui?.queue?.get(ui!!.index))
        }
    }

    @Test fun externalPodcastCommitsExactQueueAndPosition() = runBlocking {
        val h = Boundary { direct }
        val request = h.begin()
        val exact = h.requests.identity(request) { a }
        val prepared = h.requests.podcast(request, exact, 43_000)
        assertTrue(h.accept(prepared))
        assertEquals(43_000L, prepared.positionMs)
        assertEquals("spotify:episode:$idA", h.owner.active?.mediaId)
        h.invariant()
    }

    @Test fun lateMusicResultCannotOverwriteWazePodcast() = runBlocking {
        val h = Boundary { direct }
        val old = h.owner.begin(music.url, music.mediaType, music)
        val saved = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(PlaybackSnapshot(a, 32_000)))!!
        val plan = WazeResumeResolver.resolve(WazeResumeResolver.Request(), saved, null)!!
        val prepared = h.requests.podcast(h.begin(), plan.playback.song, plan.playback.positionMs)
        h.accept(prepared)
        assertFalse(h.owner.runIfCurrent(old.token) { h.actual = music })
        assertEquals(a, h.actual)
        h.invariant()
    }

    @Test fun podcastAFinishesAfterBAndCannotCommit() = runBlocking {
        val gate = CompletableDeferred<PlaybackSource>()
        val h = Boundary { if (it == a) gate.await() else direct }
        val ra = h.begin()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.requests.podcast(ra, a, 0) } }
        h.accept(h.requests.podcast(h.begin(), b, 0))
        gate.complete(direct)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(b, h.actual)
        h.invariant()
    }

    @Test fun lateIdentityLookupCannotTakeOverNewerPodcast() = runBlocking {
        val gate = CompletableDeferred<SongsModel>()
        val h = Boundary { direct }
        val ra = h.begin()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.requests.identity(ra) { gate.await() } } }
        h.accept(h.requests.podcast(h.begin(), b, 0))
        gate.complete(a)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        h.invariant()
    }

    @Test fun preparedAIsRejectedAfterBWasAccepted() = runBlocking {
        val h = Boundary { direct }
        val pa = h.requests.podcast(h.begin(), a, 0)
        h.accept(h.requests.podcast(h.begin(), b, 0))
        assertFalse(h.accept(pa))
        assertEquals(b, h.actual)
        h.invariant()
    }

    @Test fun failedResolutionPreservesLastDisplayedIdentityButDisablesOldTransport() = runBlocking {
        val h = Boundary { if (it == b) null else direct }
        val pa = h.requests.podcast(h.begin(), a, 0)
        h.accept(pa)
        assertTrue(runCatching { h.requests.podcast(h.begin(), b, 0) }.isFailure)
        assertEquals(a, h.actual)
        assertEquals(a, h.ui?.song)
        assertNull(h.owner.active)
        assertFalse(h.owner.canControl(pa.ticket.token))
    }

    @Test fun missingIdentityCannotResumeOldMusic() = runBlocking {
        val h = Boundary { direct }
        val old = h.owner.begin(music.url, music.mediaType, music)
        val request = h.begin()
        assertTrue(runCatching { h.requests.identity(request) { null } }.isFailure)
        assertFalse(h.owner.canControl(old.token))
        assertNull(h.owner.active)
    }

    @Test fun podcastRejectsYoutubeSource() = runBlocking {
        val h = Boundary { PlaybackSource.YouTube("video") }
        assertTrue(runCatching { h.requests.podcast(h.begin(), a, 0) }.exceptionOrNull() is IllegalStateException)
        assertNull(h.ui)
    }

    @Test fun trackCannotEnterPodcastPreparation() = runBlocking {
        var calls = 0
        val h = Boundary { calls++; direct }
        assertTrue(runCatching { h.requests.podcast(h.begin(), music, 0) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun pauseResumePreservesActivePodcastIdentityAndLivePosition() = runBlocking {
        val h = Boundary { direct }
        val p = h.requests.podcast(h.begin(), a, 4_000)
        h.accept(p)
        val plan = WazeResumeResolver.resolve(WazeResumeResolver.Request(), PlaybackSnapshot(a, 4_000),
            PlaybackSnapshot(h.actual!!, 19_000))!!
        assertTrue(plan.resumeActive)
        assertEquals(19_000L, plan.playback.positionMs)
        assertTrue(h.owner.canControl(p.ticket.token))
        h.invariant()
    }

    @Test fun processRecreationRestoresEpisodeAndPositionWithFreshOwnership() = runBlocking {
        val old = Boundary { direct }
        val oldPrepared = old.requests.podcast(old.begin(), a, 123_456)
        old.accept(oldPrepared)
        val disk = PlaybackSnapshotCodec.encode(PlaybackSnapshot(a, 123_456))
        val h = Boundary { direct }
        val plan = WazeResumeResolver.resolve(WazeResumeResolver.Request(),
            PlaybackSnapshotCodec.decode(disk), null)!!
        assertFalse(plan.resumeActive)
        val prepared = h.requests.podcast(h.begin(), plan.playback.song, plan.playback.positionMs)
        h.accept(prepared)
        assertEquals(oldPrepared.ticket.mediaId, prepared.ticket.mediaId)
        assertNotEquals(oldPrepared.ticket.token, prepared.ticket.token)
        assertEquals(123_456L, prepared.positionMs)
        assertEquals(MediaType.PODCAST_EPISODE, h.actual?.mediaType)
        assertFalse(h.accept(oldPrepared))
        h.invariant()
    }

    @Test fun serviceDestructionInvalidatesPendingSource() = runBlocking {
        val gate = CompletableDeferred<PlaybackSource>()
        val h = Boundary { gate.await() }
        val request = h.begin()
        val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { h.requests.podcast(request, a, 0) } }
        h.requests.invalidate()
        gate.complete(direct)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertNull(h.ui)
    }

    @Test fun transitionUpdatesPlayerProjectionQueueAndActiveIdentityTogether() = runBlocking {
        val h = Boundary { direct }
        h.accept(h.requests.podcast(h.begin(), a, 0))
        h.accept(h.requests.podcast(h.begin(), b, 20))
        assertEquals(b, h.ui?.queue?.single())
        h.invariant()
    }

    @Test fun externalAddOrReplaceFailsWithoutChangingOwnership() = runBlocking {
        val h = Boundary { direct }
        h.accept(h.requests.podcast(h.begin(), a, 0))
        val token = h.owner.current.token
        assertTrue(runCatching { h.requests.rejectAdd() }.exceptionOrNull() is UnsupportedOperationException)
        assertEquals(token, h.owner.current.token)
        h.invariant()
    }

    @Test fun missingOrCollidingCurrentIdHasNoQueueIndex() {
        assertEquals(-1, PlaybackQueueIdentity.indexOf(listOf(a, b), 99))
        assertEquals(-1, PlaybackQueueIdentity.indexOf(listOf(a, b.copy(id = a.id)), a.id))
    }

    @Test fun numericCollisionCannotTurnPodcastIntoTrack() {
        val selection = PlaybackQueueIdentity.select(a.copy(id = music.id), listOf(music))
        assertEquals(MediaType.PODCAST_EPISODE, selection.queue[selection.index].mediaType)
        assertEquals(a.url, selection.queue[selection.index].url)
    }

    @Test fun initialOrProgrammaticPagerPositionCannotStartPlayback() {
        assertNull(PlaybackPagerGuard().settle("token", listOf(a, b), b.id, 0))
    }

    @Test fun missingCurrentIdCannotTriggerPlaySongAtZero() {
        val guard = PlaybackPagerGuard()
        guard.beginDrag("token")
        assertNull(guard.settle("token", listOf(a, b), 99, 0))
    }

    @Test fun gestureFromOldOwnershipCannotReplaceNewPodcast() {
        val guard = PlaybackPagerGuard()
        guard.beginDrag("old")
        assertNull(guard.settle("podcast", listOf(a, b), a.id, 1))
    }

    @Test fun validUserSwipeSelectsOneTargetOnce() {
        val guard = PlaybackPagerGuard()
        guard.beginDrag("token")
        assertEquals(1, guard.settle("token", listOf(a, b), a.id, 1))
        assertNull(guard.settle("token", listOf(a, b), a.id, 1))
    }

    @Test fun musicTransitionKeepsExactItemAndItsExistingQueue() {
        val next = music.copy(id = 10, title = "Next", url = "spotify:track:$idB|Next Artist", spotifyTrackId = idB)
        val queue = listOf(music, next)
        val owner = PlaybackOwnership()
        val ticket = owner.begin(next.url, next.mediaType, next)
        assertTrue(owner.activate(ticket.token))
        val selection = PlaybackQueueIdentity.select(owner.active!!.song!!, queue)
        assertEquals(next, selection.song)
        assertEquals(next, selection.queue[selection.index])
        assertEquals(queue, selection.queue)
        assertEquals(1, selection.index)
    }

    @Test fun serviceRecreationWithLiveOwnerPreservesMusicQueue() {
        val next = music.copy(id = 10, title = "Next", url = "spotify:track:$idB|Next Artist", spotifyTrackId = idB)
        val queue = listOf(music, next)
        val live = PlaybackSnapshot(next, 28_000)
        val restored = PlaybackQueueIdentity.select(live.song, queue)
        assertEquals(queue, restored.queue)
        assertEquals(live.song, restored.queue[restored.index])
    }
}