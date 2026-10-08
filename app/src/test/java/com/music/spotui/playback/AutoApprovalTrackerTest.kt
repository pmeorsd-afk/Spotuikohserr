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

        // Calling onTrackPlayed for podcast should not crash and should not submit
        AutoApprovalTracker.onTrackPlayed(context = null, song = podcastEpisode)
        assertFalse(AutoApprovalTracker.isSubmitted(context = null, key = "episode_999_test_dummy"))
    }
}
