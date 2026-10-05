package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastHubViewModel @Inject constructor(private val repository: AppRepository) : ViewModel() {

    private val cachedInitial = repository.peekCachedPodcastHubShows()
    private val _shows: MutableStateFlow<Response<List<PodcastModel>>> = MutableStateFlow(
        if (!cachedInitial.isNullOrEmpty()) Response.Success(cachedInitial) else Response.Loading()
    )
    val shows: StateFlow<Response<List<PodcastModel>>> = _shows

    init {
        load(forceRefresh = false)
    }

    fun load(forceRefresh: Boolean = false) {
        if (forceRefresh || _shows.value !is Response.Success) {
            _shows.value = Response.Loading()
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.providePodcastHubShows(forceRefresh).collect { _shows.value = it }
        }
    }
}
