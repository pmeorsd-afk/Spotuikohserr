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
        AppTelemetryManager.onAppForegrounded(null)
        AppTelemetryManager.onAppBackgrounded(null)
    }

    @Test
    fun testLiveWorkerConnectivity() {
        val url = java.net.URL("https://lingering-brook-93f6.orelgame156.workers.dev/api/telemetry")
        val conn = url.openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        conn.setRequestProperty("User-Agent", "SpotUI-Client/1.0")

        val payload = """{"userId":"usr_junit_test","event":"play","isPlaying":true,"secondsDelta":0,"totalSecondsListened":100,"track":{"title":"JUnit Test Track","artist":"JUnit Artist"}}"""
        java.io.OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
            writer.write(payload)
            writer.flush()
        }

        val code = conn.responseCode
        val responseBody = if (code in 200..299) {
            conn.inputStream.bufferedReader().use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "no error stream"
        }
        conn.disconnect()

        println("Live Worker Test: code=$code, body=$responseBody")
        org.junit.Assert.assertEquals(200, code)
    }
}
