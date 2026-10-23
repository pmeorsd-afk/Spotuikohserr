package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel

/** Observes the owned primary Player; never starts, resolves or changes playback. */
internal class PlaybackHistoryRecorder {
    private var recordedToken: String? = null

    fun onPlayingSnapshot(
        ownership: PlaybackOwnership, token: String, isPrimaryItem: Boolean, isPlaying: Boolean,
        record: (SongsModel) -> Unit,
    ) {
        if (!isPrimaryItem || !isPlaying || recordedToken == token) return
        ownership.runIfCurrent(token) {
            val song = ownership.positionOwner(token)?.song ?: return@runIfCurrent
            record(song)
            recordedToken = token
        }
    }
}
