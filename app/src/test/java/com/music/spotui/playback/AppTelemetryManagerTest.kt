package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.util.AppTelemetryManager
import org.junit.Assert.assertNotNull
import org.junit.Test

class AppTelemetryManagerTest {

    @Test
    fun testTelemetry_handlesNullContextGracefully() {
        val dummySong = SongsModel(
            id = 123,
            title = "Test Song",
            singer = "Test Artist",
            album = "Test Album",
            coverUri = "https://cdn.example.com/cover.jpg",
            url = "spotify:track:123",
            mediaType = MediaType.TRACK
        )

        // Null context should never throw an exception
        AppTelemetryManager.onPlaybackStateChanged(null, dummySong, isPlaying = true)
        AppTelemetryManager.onPlaybackStateChanged(null, dummySong, isPlaying = false)
        AppTelemetryManager.onPlaybackStateChanged(null, null, isPlaying = false)
    }
}
