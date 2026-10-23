package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.data.preferences.episodeSaveKey
import com.music.spotui.playback.PlaybackQueueIdentity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SavedEpisodesViewModel @Inject constructor(private val state: CurrentSongState) : ViewModel() {
    val playing get() = state.playingState
    val currentId get() = state.songId

    fun toggle(song: SongsModel, context: Context) {
        if (!song.isPodcast()) return
        val key = episodeSaveKey(song) ?: return
        val owner = SongPlayer.ownedPlaybackSnapshot()?.song
        if (owner != null && episodeSaveKey(owner) == key) {
            if (state.playingState.value) SongPlayer.pause() else SongPlayer.play()
        } else SongPlayer.resumeResolvedPlayback(song, null, context) {
            state.commitPlayback(PlaybackQueueIdentity.select(song, listOf(song)))
        }
    }
}
