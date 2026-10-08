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
class PodcastCategoryViewModel @Inject constructor(
    private val repository: AppRepository
) : ViewModel() {

    private val _shows = MutableStateFlow<Response<List<PodcastModel>>>(Response.Loading())
    val shows: StateFlow<Response<List<PodcastModel>>> = _shows

    private val allShowsList = mutableListOf<PodcastModel>()
    private var currentCategoryId: String = ""
    private var currentOffset: Int = 0
    private var isLoadingMore: Boolean = false
    private var canLoadMore: Boolean = true

    fun load(categoryId: String, forceRefresh: Boolean = false) {
        if (!forceRefresh && categoryId == currentCategoryId && _shows.value is Response.Success) {
            return
        }
        currentCategoryId = categoryId
        currentOffset = 0
        canLoadMore = true

        // Synchronous 0ms cache check to avoid any flash of Loading state
        val cached = com.music.spotui.data.api.Api.podcastCategoryCache[categoryId]
        if (!forceRefresh && !cached.isNullOrEmpty()) {
            allShowsList.clear()
            allShowsList.addAll(cached)
            _shows.value = Response.Success(allShowsList.toList())
        } else {
            allShowsList.clear()
            _shows.value = Response.Loading()
        }

        viewModelScope.launch(Dispatchers.IO) {
            repository.providePodcastCategoryShows(categoryId, offset = 0, forceRefresh = forceRefresh).collect { resp ->
                if (resp is Response.Success) {
                    allShowsList.clear()
                    allShowsList.addAll(resp.data)
                    _shows.value = Response.Success(allShowsList.toList())
                } else if (resp is Response.Error) {
                    if (allShowsList.isEmpty()) {
                        _shows.value = resp
                    }
                }
            }
        }
    }

    fun loadMore() {
        if (isLoadingMore || !canLoadMore || currentCategoryId.isBlank()) return
        isLoadingMore = true
        val nextOffset = currentOffset + 20

        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.providePodcastCategoryShows(currentCategoryId, offset = nextOffset, forceRefresh = false).collect { resp ->
                    if (resp is Response.Success) {
                        val newItems = resp.data.filter { newShow -> allShowsList.none { it.id == newShow.id } }
                        if (newItems.isEmpty()) {
                            canLoadMore = false
                        } else {
                            currentOffset = nextOffset
                            allShowsList.addAll(newItems)
                            _shows.value = Response.Success(allShowsList.toList())
                        }
                    } else if (resp is Response.Error) {
                        canLoadMore = false
                    }
                }
            } finally {
                isLoadingMore = false
            }
        }
    }
}
