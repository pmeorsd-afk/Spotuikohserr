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

    init {
        refreshHome()
        viewModelScope.launch {
            listeningTracker.revision.drop(1).collect {
                homeFeedEngine.invalidate()
                refreshHome(force = true)
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
            _home.value = feed
        }
    }
}