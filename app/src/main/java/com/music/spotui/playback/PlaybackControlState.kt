package com.music.spotui.playback

import androidx.media3.common.Player

/** Shared play/pause-button state, independent of screen lifecycle and media type. */
internal object PlaybackControlState {
    /** Null means an unrelated/unowned Player must not overwrite the current UI. */
    fun playing(
        ownership: PlaybackOwnership,
        itemToken: String?,
        isCurrentPlayer: Boolean,
        playbackState: Int,
        playWhenReady: Boolean,
    ): Boolean? {
        if (!isCurrentPlayer || !ownership.canControl(itemToken)) return null
        // Keep Pause available during buffering, but an external pause must show Play
        // even when isPlaying was already false. Idle/ended media cannot be playing.
        return playWhenReady &&
            (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
    }
}
