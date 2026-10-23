package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

/** Exercise the real preference adapter, including last-item updates and old on-disk bookmarks. */
class PlaybackPositionPolicyTest {
    private val a = SongsModel().copy(id = 1, title = "A", url = "spotify:track:" + "a".repeat(22), spotifyTrackId = "a".repeat(22))
    private val b = a.copy(id = 2, title = "B", url = "spotify:track:" + "b".repeat(22), spotifyTrackId = "b".repeat(22))
    private val episode = a.copy(mediaType = MediaType.PODCAST_EPISODE, url = "episode:" + a.spotifyTrackId,
        podcastShowId = "show", durationMs = 180_000)
    private val otherEpisode = episode.copy(id = 2, url = "episode:" + b.spotifyTrackId, spotifyTrackId = b.spotifyTrackId)
    private val files = mutableMapOf<String, MemoryPreferences>()
    private fun context() = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            files.getOrPut(name) { MemoryPreferences() }.prefs
    }

    @Test fun pausedCurrentTrackKeepsExactPositionAcrossRecreationAndMetadataRefresh() {
        val original = context()
        saveLastPlayback(original, a)
        savePlaybackPosition(original, a, 7_253)
        val reopened = context()
        assertEquals(a to 7_253L, loadLastPlayback(reopened))
        assertEquals(7_253L, loadPlaybackPosition(reopened, a))
        saveLastPlayback(reopened, a.copy(title = "Renamed"))
        assertEquals(7_253L, loadLastPlayback(reopened)!!.second)
    }

    @Test fun selectingAnotherTrackAndReturningStartsBothFromZero() {
        val ctx = context()
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 7_253)
        assertEquals(0L, loadPlaybackPosition(ctx, b)) // Prepared before publishing B.
        saveLastPlayback(ctx, b)
        assertEquals(0L, loadLastPlayback(ctx)!!.second)
        savePlaybackPosition(ctx, b, 18_500)
        assertEquals(0L, loadPlaybackPosition(ctx, a))
        saveLastPlayback(ctx, a)
        assertEquals(a to 0L, loadLastPlayback(ctx))
        assertEquals(0L, loadPlaybackPosition(ctx, a)) // Legacy UI publishes before preparation.
    }

    @Test fun trackPositionWritesDoNotCreatePerSongBookmarks() {
        val ctx = context()
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 5_000)
        saveLastPlayback(ctx, b)
        savePlaybackPosition(ctx, b, 9_000)
        assertTrue(files.getValue("PlaybackProgress").values.isEmpty())
        assertEquals(b to 9_000L, loadLastPlayback(ctx))
    }

    @Test fun oldMusicBookmarksCannotOverrideCurrentPositionOrReviveEarlierSongs() {
        val ctx = context()
        val old = files.getOrPut("PlaybackProgress") { MemoryPreferences() }.values
        old[PlaybackProgressStore.key(a)!!] = 80_000L
        old[PlaybackProgressStore.key(b)!!] = 50_000L
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 450)
        old[PlaybackProgressStore.key(a)!!] = 80_000L
        assertEquals(a to 450L, loadLastPlayback(context()))
        assertEquals(0L, loadPlaybackPosition(ctx, b))
        saveLastPlayback(ctx, b)
        assertEquals(0L, loadPlaybackPosition(ctx, a))
        assertEquals(b to 0L, loadLastPlayback(ctx))
    }

    @Test fun changingToPodcastAlsoForgetsThePreviousMusicPosition() {
        val ctx = context()
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 15_000)
        saveLastPlayback(ctx, episode)
        savePlaybackPosition(ctx, episode, 42_000)
        assertEquals(0L, loadPlaybackPosition(ctx, a))
        saveLastPlayback(ctx, a)
        assertEquals(a to 0L, loadLastPlayback(ctx))
        assertEquals(42_000L, loadPlaybackPosition(ctx, episode))
    }

    @Test fun everyEpisodeKeepsItsOwnPositionAcrossMusicAndRecreation() {
        val ctx = context()
        saveLastPlayback(ctx, episode)
        savePlaybackPosition(ctx, episode, 52_345, durationMs = 180_000)
        saveLastPlayback(ctx, otherEpisode)
        savePlaybackPosition(ctx, otherEpisode, 8_750, durationMs = 180_000)
        saveLastPlayback(ctx, b)
        savePlaybackPosition(ctx, b, 4_000)
        val reopened = context()
        assertEquals(52_345L, loadPlaybackPosition(reopened, episode))
        assertEquals(8_750L, loadPlaybackPosition(reopened, otherEpisode))
        assertEquals(52_345L, readPodcastProgress(reopened, episode)!!.positionMs)
        saveLastPlayback(reopened, episode)
        assertEquals(episode to 52_345L, loadLastPlayback(reopened))
        assertEquals(0L, loadPlaybackPosition(reopened, b))
    }

    @Test fun lateOutgoingTrackSaveCannotReplaceNewMiniPlayerPosition() {
        val ctx = context()
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 11_000)
        saveLastPlayback(ctx, b)
        savePlaybackPosition(ctx, b, 2_000)
        savePlaybackPosition(ctx, a, 12_000)
        assertEquals(b to 2_000L, loadLastPlayback(ctx))
        assertEquals(0L, loadPlaybackPosition(ctx, a))
    }

    @Test fun outgoingEpisodeStillSavesAfterMiniPlayerSelectionChanges() {
        val ctx = context()
        saveLastPlayback(ctx, episode)
        savePlaybackPosition(ctx, episode, 20_000)
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, episode, 23_750, durationMs = 180_000)
        assertEquals(a to 0L, loadLastPlayback(ctx))
        assertEquals(23_750L, loadPlaybackPosition(ctx, episode))
    }

    @Test fun seekToZeroAndCompletionPersistForCurrentTrackWithoutChangingEpisodes() {
        val ctx = context()
        savePlaybackPosition(ctx, episode, 30_000)
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 40_000)
        saveLastPosition(ctx, 0)
        assertEquals(a to 0L, loadLastPlayback(ctx))
        savePlaybackPosition(ctx, a, 50_000)
        savePlaybackPosition(ctx, a, 180_000, completed = true)
        assertEquals(a to 0L, loadLastPlayback(ctx))
        assertEquals(30_000L, loadPlaybackPosition(ctx, episode))
    }

    @Test fun explicitPositionsStillWinForSeekAndExternalResumption() {
        val ctx = context()
        saveLastPlayback(ctx, a)
        savePlaybackPosition(ctx, a, 7_253)
        savePlaybackPosition(ctx, episode, 42_000)
        assertEquals(0L, loadPlaybackPosition(ctx, a, requested = 0))
        assertEquals(2_000L, loadPlaybackPosition(ctx, b, requested = 2_000))
        assertEquals(0L, loadPlaybackPosition(ctx, episode, requested = 0))
        assertEquals(7_253L, loadPlaybackPosition(ctx, a, requested = Long.MIN_VALUE))
        assertEquals(42_000L, loadPlaybackPosition(ctx, episode, requested = Long.MIN_VALUE))
    }

    @Test fun legacyEpisodeSnapshotMigratesBeforeSwitchingToMusic() {
        val ctx = context()
        ctx.getSharedPreferences("PlaybackState", 0).edit()
            .putString("song", PlaybackSnapshotCodec.encode(PlaybackSnapshot(episode, 93_000)))
            .putLong("positionMs", 93_000).apply()
        saveLastPlayback(ctx, a)
        assertEquals(93_000L, loadPlaybackPosition(ctx, episode))
        assertEquals(a to 0L, loadLastPlayback(ctx))
    }

    /** Separate preference files survive context recreation; no Android Player is simulated here. */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getLong" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putLong" -> { pending[args!![0] as String] = args[1]; proxy }
                    "apply" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        null
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
