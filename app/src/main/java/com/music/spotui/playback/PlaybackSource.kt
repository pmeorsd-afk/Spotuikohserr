package com.music.spotui.playback

sealed interface PlaybackSource {
    data class YouTube(
        val videoId: String
    ) : PlaybackSource

    data class DirectAudio(
        val url: String,
        val mimeType: String = "audio/mpeg"
    ) : PlaybackSource
}
