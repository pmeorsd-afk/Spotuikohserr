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
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: AppRepository,
    private val homeFeedEngine: HomeFeedEngine,
    private val listeningTracker: LocalListeningTracker
) : ViewModel() {

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