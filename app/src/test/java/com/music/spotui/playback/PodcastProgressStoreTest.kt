package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class PodcastProgressStoreTest {
    private val episode = SongsModel().copy(
        id = 1, title = "Episode", url = "episode:show:guid", podcastShowId = "show",
        mediaType = MediaType.PODCAST_EPISODE, durationMs = 100_000,
    )
    private val values = mutableMapOf<String, Long>()
    private fun progress() = PodcastProgressStore(values::get) { values.putAll(it) }
    private fun bookmark() = PlaybackProgressStore(values::get) { key, value -> values[key] = value }
    private fun save(song: SongsModel, position: Long, completed: Boolean = false, duration: Long = song.durationMs.toLong()) {
        bookmark().save(song, position, completed)
        progress().record(song, duration, completed)
    }

    @Test fun savedBookmarkWorksBeforeDisplayMetadataExists() {
        bookmark().save(episode, 25_000)
        assertEquals(0.25f, progress().progress(episode)!!.fraction, 0f)
    }

    @Test fun playingPauseAndSeekUseSameStoredPosition() {
        save(episode, 10_000)
        assertEquals(0.1f, progress().progress(episode)!!.fraction, 0f)
        save(episode, 30_000)
        assertEquals(0.3f, progress().progress(episode)!!.fraction, 0f)
        // Pausing leaves the value intact; seeking backwards updates immediately.
        assertEquals(0.3f, progress().progress(episode)!!.fraction, 0f)
        save(episode, 5_000)
        assertEquals(0.05f, progress().progress(episode)!!.fraction, 0f)
    }

    @Test fun differentEpisodesWithSameTitleAndUiIdKeepSeparateProgress() {
        val other = episode.copy(url = "episode:show:other")
        save(episode, 25_000)
        save(other, 75_000)
        assertEquals(0.25f, progress().progress(episode)!!.fraction, 0f)
        assertEquals(0.75f, progress().progress(other)!!.fraction, 0f)
    }

    @Test fun completionIsFullButResumeBookmarkStillResetsToZero() {
        save(episode, 100_000, completed = true)
        assertEquals(1f, progress().progress(episode)!!.fraction, 0f)
        assertTrue(progress().progress(episode)!!.completed)
        assertEquals(0L, bookmark().position(episode))
    }

    @Test fun replayClearsCompletionAndUsesNewPosition() {
        save(episode, 100_000, completed = true)
        save(episode, 1_000)
        assertFalse(progress().progress(episode)!!.completed)
        assertEquals(0.01f, progress().progress(episode)!!.fraction, 0f)
    }

    @Test fun learnedPlayerDurationSurvivesStoreRecreationAndUnknownDurationUpdate() {
        val unknown = episode.copy(durationMs = 0)
        save(unknown, 50_000, duration = 200_000)
        val recreated = progress()
        recreated.record(unknown, -9223372036854775807L, false)
        assertEquals(0.25f, recreated.progress(unknown)!!.fraction, 0f)
        assertEquals(200_000L, recreated.progress(unknown)!!.durationMs)
    }

    @Test fun unknownDurationDoesNotInventProgress() {
        save(episode.copy(durationMs = 0), 1000)
        assertNull(progress().progress(episode.copy(durationMs = 0)))
    }

    @Test fun musicNeverGetsPodcastProgressEvenWithSameNumericId() {
        val track = episode.copy(mediaType = MediaType.TRACK, url = "youtube:abcdefghijk", podcastShowId = "")
        save(episode, 20_000)
        progress().record(track, 100_000, false)
        assertNull(progress().progress(track))
    }

    @Test fun invalidOrConflictingIdentityNeverGetsProgress() {
        assertNull(progress().progress(episode.copy(url = "episode:other:guid")))
        assertNull(progress().progress(episode.copy(url = "ambiguous title")))
    }

    @Test fun spotifyIdentitySurvivesMetadataAndSignedUrlChanges() {
        val id = "1234567890abcdefghijkl"
        val spotify = episode.copy(url = "episode:$id", spotifyTrackId = id)
        save(spotify, 30_000)
        val changed = spotify.copy(title = "Renamed", url = "https://audio.test/new.mp3?token=new")
        assertEquals(0.3f, progress().progress(changed)!!.fraction, 0f)
    }

    @Test fun oversizedBookmarkIsClamped() {
        bookmark().save(episode, 200_000)
        assertEquals(1f, progress().progress(episode)!!.fraction, 0f)
    }

    @Test fun observerIgnoresAnotherEpisodeAndHandlesPreferenceClear() {
        val key = PlaybackProgressStore.key(episode)!!
        assertTrue(progress().observes(episode, key))
        assertTrue(progress().observes(episode, "display-duration:$key"))
        assertTrue(progress().observes(episode, "display-completed:$key"))
        assertTrue(progress().observes(episode, null))
        assertFalse(progress().observes(episode, PlaybackProgressStore.key(episode.copy(url = "episode:show:other"))))
    }
}
