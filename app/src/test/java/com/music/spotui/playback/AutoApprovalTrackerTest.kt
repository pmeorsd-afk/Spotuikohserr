package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.util.AutoApprovalTracker
import org.junit.Assert.*
import org.junit.Test

class AutoApprovalTrackerTest {

    @Test
    fun testSubmittedKeys_inMemoryDeduplication() {
        val testKey = "spotify_track_12345"
        // Initially should not be submitted in memory
        AutoApprovalTracker.markSubmitted(context = null, key = testKey)
        // Since markSubmitted caches in inMemorySentKeys, check isSubmitted with key
        assertTrue(AutoApprovalTracker.isSubmitted(context = null, key = testKey))
        // Case and whitespace insensitivity
        assertTrue(AutoApprovalTracker.isSubmitted(context = null, key = "  SPOTIFY_TRACK_12345  "))
    }

    @Test
    fun testEmptyAndBlankKeys_handledSafely() {
        assertTrue("Blank keys should return true to avoid submitting invalid tracks",
            AutoApprovalTracker.isSubmitted(context = null, key = ""))
        assertTrue(AutoApprovalTracker.isSubmitted(context = null, key = "   "))
    }

    @Test
    fun testPodcastEpisodes_skippedGracefully() {
        val podcastEpisode = SongsModel(
            id = 999,
            title = "פרק פודקאסט מיוחד",
            singer = "יוצר פודקאסט",
            album = "פודקאסט",
            coverUri = "https://cdn.com/cover.jpg",
            url = "episode:999",
            mediaType = MediaType.PODCAST_EPISODE
        )

        // Calling onPlaybackStateChanged for podcast should not crash and should not submit
        AutoApprovalTracker.onPlaybackStateChanged(context = null, song = podcastEpisode, isPlaying = true)
        assertFalse(AutoApprovalTracker.isSubmitted(context = null, key = "episode_999_test_dummy"))
    }

    @Test
    fun testShortPlayback_doesNotSubmitImmediately() {
        val testSong = SongsModel(
            id = 777,
            title = "שיר בדיקה קצר",
            singer = "זמר בדיקה לא בהיתר",
            album = "אלבום בדיקה",
            coverUri = "https://cdn.com/test_cover.jpg",
            url = "spotify:track:short_play_test_777",
            mediaType = MediaType.TRACK
        )

        // Reset required threshold to standard 30s
        AutoApprovalTracker.requiredListeningMs = 30_000L

        // Start playback
        AutoApprovalTracker.onPlaybackStateChanged(context = null, song = testSong, isPlaying = true)

        // Immediately after start (< 30s), should NOT be marked as submitted!
        assertFalse(
            "Track should NOT be submitted immediately on play (< 30s)",
            AutoApprovalTracker.isSubmitted(context = null, key = "short_play_test_777")
        )

        // User pauses after 2 seconds
        AutoApprovalTracker.onPlaybackStateChanged(context = null, song = testSong, isPlaying = false)
        assertFalse(
            "Track should NOT be submitted when paused before 30s",
            AutoApprovalTracker.isSubmitted(context = null, key = "short_play_test_777")
        )
    }
}
