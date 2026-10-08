package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShowViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
) : ViewModel() {

    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId

    private val _episodes: MutableStateFlow<Response<List<PodcastEpisodeUiModel>>> = MutableStateFlow(Response.Loading())
    val episodes: StateFlow<Response<List<PodcastEpisodeUiModel>>> = _episodes

    private val _show: MutableStateFlow<PodcastModel?> = MutableStateFlow(null)
    val show: StateFlow<PodcastModel?> = _show

    val queue: State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex: Int = 0, album: String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun isPodcastFollowed(showId: String): Boolean = repository.isPodcastFollowed(showId)

    fun followPodcast(showId: String, name: String, imageUrl: String = "") {
        repository.followPodcast(showId, name, imageUrl)
    }

    fun unfollowPodcast(showId: String): Boolean = repository.unfollowPodcast(showId)

    private var showKey: String? = null

    fun loadShow(showId: String, showName: String = "") {
        if (showKey == showId && _episodes.value is Response.Success) return
        showKey = showId
        _episodes.value = Response.Loading()
        viewModelScope.launch(Dispatchers.IO) {
            _show.value = repository.provideShow(showId, showName)
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.providePodcastEpisodes(showId, showName).collect { res ->
                _episodes.value = res
                if (res is Response.Success) {
                    val updated = repository.provideShow(showId, showName)
                    if (updated != null) {
                        _show.value = updated
                    }
                }
            }
        }
    }

    fun playEpisode(episode: PodcastEpisodeUiModel, allEpisodes: List<PodcastEpisodeUiModel>, context: Context) {
        val songModels = allEpisodes.map { it.toSongModel() }
        updateQueue(songModels)
        val targetSong = episode.toSongModel()
        val index = allEpisodes.indexOfFirst { it.numericId == episode.numericId }.coerceAtLeast(0)
        SongPlayer.playSong(targetSong.url, context)
        updateSongState(targetSong.coverUri, targetSong.title, targetSong.singer, true, targetSong.id, index, targetSong.album)
    }

    fun togglePlay(episode: PodcastEpisodeUiModel, allEpisodes: List<PodcastEpisodeUiModel>, context: Context) {
        if (currentSongId.value == episode.numericId) {
            if (currentSongPlayingState.value) {
                SongPlayer.pause()
                currentSongState.updateSongState(
                    episode.coverUri,
                    episode.title,
                    episode.publisher.ifBlank { episode.showName },
                    false,
                    episode.numericId,
                    allEpisodes.indexOfFirst { it.numericId == episode.numericId }.coerceAtLeast(0),
                    episode.showName
                )
            } else {
                SongPlayer.play()
                currentSongState.updateSongState(
                    episode.coverUri,
                    episode.title,
                    episode.publisher.ifBlank { episode.showName },
                    true,
                    episode.numericId,
                    allEpisodes.indexOfFirst { it.numericId == episode.numericId }.coerceAtLeast(0),
                    episode.showName
                )
            }
        } else {
            playEpisode(episode, allEpisodes, context)
        }
    }
}
