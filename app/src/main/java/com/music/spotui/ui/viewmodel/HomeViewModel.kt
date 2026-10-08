package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import com.music.spotui.data.home.HomeFeedEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.music.spotui.data.local.LocalListeningTracker
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import android.content.Context
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import javax.inject.Inject

enum class HomeTabFilter {
    ALL,
    MUSIC,
    PODCASTS,
    FOLLOWING
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AppRepository,
    private val homeFeedEngine: HomeFeedEngine,
    private val listeningTracker: LocalListeningTracker,
    private val currentSongState: CurrentSongState
) : ViewModel() {

    private val _currentFilter = MutableStateFlow(HomeTabFilter.ALL)
    val currentFilter: StateFlow<HomeTabFilter> = _currentFilter

    fun setFilter(filter: HomeTabFilter) {
        _currentFilter.value = filter
    }

    private val _followedPodcasts = MutableStateFlow<List<com.music.spotui.data.preferences.FollowedPodcastShow>>(emptyList())
    val followedPodcasts: StateFlow<List<com.music.spotui.data.preferences.FollowedPodcastShow>> = _followedPodcasts

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home

    private val _albums : MutableStateFlow<Response<List<AlbumsModel>>> = MutableStateFlow(Response.Loading())
    val albums : StateFlow<Response<List<AlbumsModel>>> = _albums

    private val _artists : MutableStateFlow<Response<List<ArtistsModel>>> = MutableStateFlow(Response.Loading())
    val artists : StateFlow<Response<List<ArtistsModel>>> = _artists

    private var personalizedRefreshJob: kotlinx.coroutines.Job? = null

    init {
        refreshHome()
        viewModelScope.launch {
            listeningTracker.revision.drop(1).collect {
                // 1. Immediate local update for RECENTLY_PLAYED & Top Grid (0ms latency)
                val fastFeed = homeFeedEngine.updateRecentListeningOnly()
                if (fastFeed.sections.isNotEmpty()) {
                    _home.value = Response.Success(fastFeed)
                }

                // 2. Background: single-flight personalized update (similar artists & recommendations)
                personalizedRefreshJob?.cancel()
                personalizedRefreshJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val personalizedFeed = homeFeedEngine.updatePersonalizedRecommendations()
                        if (personalizedFeed.sections.isNotEmpty()) {
                            _home.value = Response.Success(personalizedFeed)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        viewModelScope.launch {
            HomeRefreshSignal.events.collect {
                homeFeedEngine.invalidate()
                refreshHome(force = true)
            }
        }
        viewModelScope.launch {
            com.music.spotui.data.preferences.likedSongsRevision.drop(1).collect {
                syncLikedSongsCount()
            }
        }
        _followedPodcasts.value = repository.getFollowedPodcasts()
        viewModelScope.launch {
            com.music.spotui.data.preferences.podcastFollowRevision.collect {
                _followedPodcasts.value = repository.getFollowedPodcasts()
            }
        }
    }

    fun syncLikedSongsCount() {
        val current = (_home.value as? Response.Success)?.data ?: return
        val patched = homeFeedEngine.patchLikedSongsCount(current)
        _home.value = Response.Success(patched)
    }

    /**
     * Safety net on ON_RESUME: refreshes recent listening from local cache (<5ms) without network calls.
     */
    fun syncRecentListening() = viewModelScope.launch(Dispatchers.IO) {
        try {
            val fastFeed = homeFeedEngine.updateRecentListeningOnly()
            if (fastFeed.sections.isNotEmpty()) {
                _home.value = Response.Success(fastFeed)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Direct Play-on-Tap contract for listening items (G2):
     * Directly plays the exact track or podcast episode via SongPlayer.
     * Sets queue, registers podcast episode if needed, and starts playback without opening Album/Show catalog.
     */
    fun playTrack(song: SongsModel, context: Context) {
        if (song.mediaType == MediaType.PODCAST_EPISODE) {
            SongPlayer.registerMediaType(song.url, MediaType.PODCAST_EPISODE)
            SongPlayer.registerEpisodeModel(song.url, song)
        }
        currentSongState.updateQueue(listOf(song))
        SongPlayer.playSong(song.url, context)
        currentSongState.updateSongState(
            song.coverUri,
            song.title,
            song.singer,
            true,
            song.id,
            0,
            song.album
        )
        val seed = song.spotifyTrackId
        if (song.mediaType != MediaType.PODCAST_EPISODE && seed.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val recs = repository.provideRecommendations(listOf(seed))
                    val current = currentSongState.queue.value
                    if (current.size == 1 && current.first().id == song.id) {
                        val fresh = recs.filter { it.id != song.id }
                        if (fresh.isNotEmpty()) currentSongState.updateQueue(current + fresh)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun refreshHome(force: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        if (_home.value !is Response.Success) {
            _home.value = Response.Loading()
        }
        try {
            val feed = homeFeedEngine.getHomeFeed(force)
            if (feed.sections.isNotEmpty()) {
                _home.value = Response.Success(feed)
                return@launch
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        repository.provideHomeFeed().collect { feed ->
            if (feed is Response.Success && feed.data?.sections?.isNotEmpty() == true) {
                _home.value = feed
            } else if (_home.value !is Response.Success) {
                _home.value = feed
            }
        }
    }
}