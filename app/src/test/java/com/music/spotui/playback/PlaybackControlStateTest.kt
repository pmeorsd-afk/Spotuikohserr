package com.music.spotui.playback

import androidx.media3.common.Player
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

/** Exercises the projection used by SongPlayer.onEvents, bindState and player promotion. */
class PlaybackControlStateTest {
    private val ownership = PlaybackOwnership()
    private var owner = ownership.begin("spotify:track:music", MediaType.TRACK)
    private var buttonShowsPause = true

    private fun snapshot(
        playWhenReady: Boolean,
        state: Int = Player.STATE_READY,
        token: String? = owner.token,
        currentPlayer: Boolean = true,
    ) {
        PlaybackControlState.playing(ownership, token, currentPlayer, state, playWhenReady)
            ?.let { buttonShowsPause = it }
    }

    @Test fun lockScreenPauseThenResumeUpdatesSharedButtonState() {
        snapshot(false)
        assertFalse(buttonShowsPause)
        snapshot(true)
        assertTrue(buttonShowsPause)
    }

    @Test fun notificationPauseDuringBufferingDoesNotNeedIsPlayingChange() {
        snapshot(true, Player.STATE_BUFFERING)
        assertTrue(buttonShowsPause)
        snapshot(false, Player.STATE_BUFFERING)
        assertFalse(buttonShowsPause)
        snapshot(false, Player.STATE_READY)
        assertFalse(buttonShowsPause)
    }

    @Test fun externalResumeWhileBufferingShowsPauseBeforeAudioStarts() {
        snapshot(false, Player.STATE_BUFFERING)
        snapshot(true, Player.STATE_BUFFERING)
        assertTrue(buttonShowsPause)
    }

    @Test fun temporaryBufferingDoesNotFlashPlay() {
        snapshot(true)
        snapshot(true, Player.STATE_BUFFERING)
        assertTrue(buttonShowsPause)
        snapshot(true)
        assertTrue(buttonShowsPause)
    }

    @Test fun bindingToAlreadyPausedPlayerCorrectsStaleState() {
        assertTrue(buttonShowsPause)
        snapshot(false)
        assertFalse(buttonShowsPause)
    }

    @Test fun idleOrEndedShowsPlayDespiteRetainedPlayWhenReady() {
        snapshot(true, Player.STATE_IDLE)
        assertFalse(buttonShowsPause)
        snapshot(true)
        assertTrue(buttonShowsPause)
        ownership.deactivate(owner.token)
        snapshot(true, Player.STATE_ENDED)
        assertFalse(buttonShowsPause)
    }

    @Test fun podcastPauseResumePreservesExactOwnership() {
        val episode = SongsModel().copy(
            url = "episode:show:guid", mediaType = MediaType.PODCAST_EPISODE,
            spotifyTrackId = "episode-id", podcastShowId = "show",
        )
        owner = ownership.begin(episode.url, episode.mediaType, episode, true, "episode/42")
        assertTrue(ownership.activate(owner.token, "episode/42"))
        snapshot(false)
        assertFalse(buttonShowsPause)
        snapshot(true)
        assertTrue(buttonShowsPause)
        assertEquals(owner, ownership.active)
        assertEquals(episode, ownership.active?.song)
    }

    @Test fun lateOldItemEventCannotOverwritePausedPodcast() {
        val oldToken = owner.token
        owner = ownership.begin("episode:show:guid", MediaType.PODCAST_EPISODE)
        snapshot(false)
        snapshot(true, token = oldToken)
        assertFalse(buttonShowsPause)
    }

    @Test fun oldPauseWhileNewRequestResolvesDoesNotOverwriteNewState() {
        val oldToken = owner.token
        owner = ownership.begin("new track", MediaType.TRACK)
        snapshot(false, token = oldToken)
        assertTrue(buttonShowsPause)
    }

    @Test fun untaggedPlayerCannotOverwriteCurrentState() {
        snapshot(false, token = null)
        assertTrue(buttonShowsPause)
    }

    @Test fun secondaryOrReleasedPlayerCannotOverwritePrimaryEvenWithSameToken() {
        snapshot(false, currentPlayer = false)
        assertTrue(buttonShowsPause)
        snapshot(false)
        snapshot(true, currentPlayer = false)
        assertFalse(buttonShowsPause)
    }

    @Test fun promotedPlayerSnapshotIsAppliedWithoutWaitingForNewEvent() {
        val oldToken = owner.token
        owner = ownership.begin("next track", MediaType.TRACK)
        snapshot(false) // Promoted player may already be paused; binding reads its state.
        assertFalse(buttonShowsPause)
        snapshot(true, token = oldToken, currentPlayer = false)
        assertFalse(buttonShowsPause)
    }
}
