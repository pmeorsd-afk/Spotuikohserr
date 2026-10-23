package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.cacheFollowedArtists
import com.music.spotui.data.preferences.cacheLibraryEntries
import com.music.spotui.data.preferences.getCachedFollowedArtists
import com.music.spotui.data.preferences.getCachedLibraryEntries
import com.music.spotui.data.preferences.getFollowedShows
import com.music.spotui.data.preferences.observeFollowedShows
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: AppRepository,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _entries: MutableStateFlow<Response<List<LibraryEntry>>> = MutableStateFlow(Response.Loading())
    val entries: StateFlow<Response<List<LibraryEntry>>> = _entries

    private val _account: MutableStateFlow<Response<AccountModel>> = MutableStateFlow(Response.Loading())
    val account: StateFlow<Response<AccountModel>> = _account

    private val _followedArtists: MutableStateFlow<List<com.music.spotui.data.entity.ArtistsModel>> =
        MutableStateFlow(emptyList())
    val followedArtists: StateFlow<List<com.music.spotui.data.entity.ArtistsModel>> = _followedArtists

    // Independent of the remote music-library response, including loading/error/offline states.
    val followedPodcasts = observeFollowedShows(context)
        .stateIn(viewModelScope, SharingStarted.Eagerly, getFollowedShows(context))

    init {
        viewModelScope.launch {
            com.music.spotui.data.preferences.savedCollectionsRevision.collect {
                val current = (_entries.value as? Response.Success)?.data ?: getCachedLibraryEntries(context)
                val merged = com.music.spotui.data.preferences.withSavedCollections(context, current)
                if (merged.isNotEmpty() || _entries.value is Response.Success) _entries.value = Response.Success(merged)
            }
        }
        // Seed UI with the last-seen library immediately so it's visible offline.
        val cached = getCachedLibraryEntries(context)
        if (cached.isNotEmpty()) _entries.value = Response.Success(cached)
        val cachedArtists = getCachedFollowedArtists(context)
        if (cachedArtists.isNotEmpty()) _followedArtists.value = cachedArtists

        load()
        loadAccount()
        loadFollowedArtists()
    }

    fun load() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideLibrary().collect { response ->
            _entries.value = response
            if (response is Response.Success) cacheLibraryEntries(context, response.data)
        }
    }

    fun refreshSilently() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideLibrary().collect { response ->
            if (response is Response.Success) {
                _entries.value = response
                cacheLibraryEntries(context, response.data)
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        val current = (_entries.value as? Response.Success)?.data
        current?.firstOrNull { it.isPlaylist && it.spotifyId == playlistId }?.let { entry ->
            if (com.music.spotui.data.preferences.validCollection(entry)) {
                com.music.spotui.data.preferences.setCollectionSaved(context, entry, false)
                if (!playlistId.startsWith("youtube:")) com.music.spotui.data.api.SpotifySync.setPlaylistSaved(context, playlistId, false)
            }
        }
        if (current != null) {
            val updated = current.filterNot { it.spotifyId == playlistId }
            _entries.value = Response.Success(updated)
            cacheLibraryEntries(context, updated)
        }
        viewModelScope.launch(Dispatchers.IO) {
            com.music.spotui.data.preferences.CustomPlaylistStore.deletePlaylist(context, playlistId)
            com.music.spotui.data.api.Api.HomeCache.library = null
        }
    }

    private fun loadFollowedArtists() = viewModelScope.launch(Dispatchers.IO) {
        val artists = repository.provideFollowedArtists()
        _followedArtists.value = artists
        if (artists.isNotEmpty()) cacheFollowedArtists(context, artists)
    }

    private fun loadAccount() = viewModelScope.launch(Dispatchers.IO) {
        repository.provideAccount().collect { _account.value = it }
    }
}
