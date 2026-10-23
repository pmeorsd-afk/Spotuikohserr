package com.music.spotui.playback

import androidx.media3.common.Player
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.AutoPlaybackActions.Action
import com.music.spotui.playback.AutoPlaybackActions.SaveResult
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AutoPlaybackActionsTest {
    private val id = "1234567890123456789012"
    private val otherId = "abcdefghijklmnopqrstuv"
    private val song = SongsModel(7, "Same title", "Album", "Artist", "cover",
        "spotify:track:$id", id, durationMs = 90_000)
    // Same name and numeric UI hash must not make two versions the same saved track.
    private val other = song.copy(url = "spotify:track:$otherId", spotifyTrackId = otherId, explicit = true)
    private val episode = song.copy(url = "episode:show:guid", mediaType = MediaType.PODCAST_EPISODE,
        podcastShowId = "show")

    @Test fun carKeepsPlayAndNextAndGivesTheFreePreviousSlotToSave() {
        val commands = AutoPlaybackControls.commands(setOf(Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM), true)
        assertEquals(setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM), commands)
        val actions = AutoPlaybackActions.order(song, true)
        assertEquals(Action.SAVE, actions[0])
        assertEquals(setOf(Action.SAVE, Action.RADIO, Action.REPEAT), actions.toSet())
        assertEquals(3, actions.size) // Radio/Repeat still belong to the full player.
    }

    @Test fun disconnectAndReconnectRestoreTheCorrectStandaloneAndCarOrder() {
        assertEquals(Action.RADIO, AutoPlaybackActions.order(song, false)[0])
        assertEquals(Action.SAVE, AutoPlaybackActions.order(song, true)[0])
        assertEquals(Action.RADIO, AutoPlaybackActions.order(song, false)[0])
        assertEquals(Action.SAVE, AutoPlaybackActions.order(song, true)[0])
        assertEquals(AutoPlaybackActions.order(song, true).toSet(), AutoPlaybackActions.order(song, false).toSet())
    }

    @Test fun unavailableOrPodcastItemNeverAdvertisesMusicSaving() {
        assertTrue(AutoPlaybackActions.order(null, true).isEmpty())
        assertEquals(listOf(Action.REPEAT), AutoPlaybackActions.order(episode, true))
        val malformed = song.copy(url = "spotify:episode:$id")
        assertFalse(Action.SAVE in AutoPlaybackActions.order(malformed, true))
        for (item in listOf(episode, malformed)) {
            assertFalse(AutoPlaybackActions.isSaved(item, listOf(song)) { true })
            assertEquals(SaveResult.UNSUPPORTED, AutoPlaybackActions.save(item,
                isSaved = { error("Unsupported item queried likes") },
                saveLocal = { error("Unsupported item saved") }, syncSpotify = { error("Unsupported item synced") }))
        }
    }

    @Test fun savingTwoVersionsWithTheSameNameAndUiHashPreservesBothIdentities() {
        val likes = mutableListOf(other)
        val synced = mutableListOf<String>()
        assertFalse(AutoPlaybackActions.isSaved(song, likes))
        assertEquals(SaveResult.SAVED, AutoPlaybackActions.save(song,
            isSaved = { AutoPlaybackActions.isSaved(it, likes) },
            saveLocal = { likes += it }, syncSpotify = { synced += it }))
        assertEquals(listOf(other, song), likes)
        assertEquals(listOf(id), synced)
        assertTrue(AutoPlaybackActions.isSaved(other, likes))
        assertTrue(AutoPlaybackActions.isSaved(song, likes))
    }

    @Test fun doubleTapAndRecreatedSavedStateStayCheckedWithoutRemovingOrSyncingTwice() {
        var stored: String? = null
        var writes = 0
        val synced = mutableListOf<String>()
        fun local() = listOfNotNull(stored?.let(PlaybackSnapshotCodec::decode)?.song)
        fun tap() = AutoPlaybackActions.save(song, isSaved = { AutoPlaybackActions.isSaved(it, local()) },
            saveLocal = { stored = PlaybackSnapshotCodec.encode(PlaybackSnapshot(it, 0)); writes++ },
            syncSpotify = { synced += it })
        assertEquals(SaveResult.SAVED, tap())
        assertEquals(SaveResult.ALREADY_SAVED, tap())
        assertEquals(1, writes)
        assertEquals(listOf(id), synced)
        assertTrue(AutoPlaybackActions.isSaved(song.copy(id = 99, coverUri = "new"), local()))
        assertEquals(Action.SAVE, AutoPlaybackActions.order(local().single(), true)[0])
    }

    @Test fun exactLegacySpotifyLikeRemainsSavedButAnEpisodeOrDifferentIdDoesNot() {
        val queries = mutableListOf<String>()
        fun legacy(key: String): Boolean { queries += key; return key == id }
        assertTrue(AutoPlaybackActions.isSaved(song, emptyList(), ::legacy))
        assertFalse(AutoPlaybackActions.isSaved(other, emptyList(), ::legacy))
        assertEquals(listOf(id, otherId), queries)
        assertFalse(AutoPlaybackActions.isSaved(song, listOf(episode)))
        assertTrue(AutoPlaybackActions.isSaved(song.copy(url = "https://open.spotify.com/track/$id"), listOf(song)))
    }

    @Test fun knownLocalSourceCanSaveLocallyWithoutInventingASpotifySyncId() {
        val localSong = song.copy(url = "youtube:abcdefghijk", spotifyTrackId = "")
        val likes = mutableListOf<SongsModel>()
        assertEquals(SaveResult.SAVED, AutoPlaybackActions.save(localSong,
            isSaved = { AutoPlaybackActions.isSaved(it, likes) }, saveLocal = { likes += it },
            syncSpotify = { error("No Spotify track ID is known") }))
        assertTrue(AutoPlaybackActions.isSaved(localSong, likes))
        assertFalse(AutoPlaybackActions.isSaved(localSong.copy(url = "youtube:lmnopqrstuv"), likes))
    }

    @Test fun failedLocalWriteDoesNotReportSavedOrSubmitRemoteSync() {
        var syncs = 0
        val result = runCatching { AutoPlaybackActions.save(song, isSaved = { false },
            saveLocal = { throw IOException("write failed") }, syncSpotify = { syncs++ }) }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(0, syncs)
    }
}
