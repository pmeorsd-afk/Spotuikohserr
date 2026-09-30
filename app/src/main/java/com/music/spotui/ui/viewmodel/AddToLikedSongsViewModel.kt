package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.getListeningHistory
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.notifyLikedSongsChanged
import com.music.spotui.data.preferences.removeLikedSong
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AddScreenView {
    MAIN_FEED,
    SEARCH,
    ARTIST_DETAIL
}

enum class AddTab {
    SONGS,
    RECENT
}

enum class AddSearchFilter(val title: String) {
    SONGS("שירים"),
    ARTISTS("אמנים"),
    PLAYLISTS("פלייליסטים"),
    ALBUMS("אלבומים")
}

@HiltViewModel
class AddToLikedSongsViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
) : ViewModel() {

    var currentView by mutableStateOf(AddScreenView.MAIN_FEED)
    var activeTab by mutableStateOf(AddTab.SONGS)

    // Recommended & infinite scroll
    private val _recommendedSongs = MutableStateFlow<List<SongsModel>>(emptyList())
    val recommendedSongs: StateFlow<List<SongsModel>> = _recommendedSongs

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    // Recently played songs
    private val _recentSongs = MutableStateFlow<List<SongsModel>>(emptyList())
    val recentSongs: StateFlow<List<SongsModel>> = _recentSongs

    // Search state
    var searchQuery by mutableStateOf("")
    private val _searchResults = MutableStateFlow<Response<SearchResults>>(Response.Success(SearchResults()))
    val searchResults: StateFlow<Response<SearchResults>> = _searchResults

    var selectedFilter by mutableStateOf<AddSearchFilter?>(null)

    // Recent searches list
    private val _recentSearches = MutableStateFlow<List<String>>(listOf("ישי ריבו", "חנן בן ארי", "עומר אדם", "אביב בכר"))
    val recentSearches: StateFlow<List<String>> = _recentSearches

    // Selected artist for detail view
    var selectedArtistName by mutableStateOf("")
    var selectedArtistId by mutableStateOf("")
    private val _artistOverview = MutableStateFlow<Response<ArtistOverviewModel>>(Response.Loading())
    val artistOverview: StateFlow<Response<ArtistOverviewModel>> = _artistOverview

    // Player state
    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongId: State<Int> get() = currentSongState.songId

    private var searchJob: Job? = null
    private val allRecPool: MutableList<SongsModel> = mutableListOf()
    private var currentPage = 0

    init {
        loadInitialRecommendations()
    }

    fun loadRecentSongs(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val history = getListeningHistory(context)
            val songs = history.map { entry ->
                val stable = if (entry.songId > 0) entry.songId else (entry.title + entry.singer).hashCode() and 0x7fffffff
                SongsModel(
                    id = stable,
                    title = entry.title,
                    singer = entry.singer,
                    album = entry.album,
                    coverUri = entry.image,
                    url = SongPlayer.buildSpotifyPlayQuery("", entry.title, entry.singer)
                )
            }.distinctBy { it.title.trim().lowercase() + "|" + it.singer.trim().lowercase() }
            _recentSongs.value = songs
        }
    }

    private fun loadInitialRecommendations() {
        viewModelScope.launch(Dispatchers.IO) {
            val generalSongs = mutableListOf<SongsModel>()
            runCatching {
                repository.provideSongs().collect { resp ->
                    if (resp is Response.Success) {
                        generalSongs.addAll(resp.data)
                    }
                }
            }

            // Get liked seeds
            val likedSeeds = mutableListOf<String>()
            runCatching {
                repository.provideLikedSongs().collect { resp ->
                    if (resp is Response.Success) {
                        likedSeeds.addAll(resp.data.mapNotNull { it.spotifyTrackId.takeIf { s -> s.isNotBlank() } })
                    }
                }
            }

            var recommendations: List<SongsModel> = emptyList()
            if (likedSeeds.isNotEmpty()) {
                runCatching {
                    recommendations = repository.provideRecommendations(likedSeeds.take(5))
                }
            }

            // Combine and shuffle
            val combined = (recommendations + generalSongs).distinctBy {
                it.title.trim().lowercase() + "|" + it.singer.trim().lowercase()
            }.shuffled()

            allRecPool.clear()
            allRecPool.addAll(combined)

            currentPage = 1
            _recommendedSongs.value = allRecPool.take(15)
        }
    }

    fun loadMoreRecommendations() {
        if (_isLoadingMore.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingMore.value = true
            delay(300)
            val nextSize = (currentPage + 1) * 15
            if (allRecPool.size > _recommendedSongs.value.size) {
                currentPage++
                _recommendedSongs.value = allRecPool.take(nextSize)
            } else {
                val fresh = mutableListOf<SongsModel>()
                runCatching {
                    repository.searchSongs("ישראלי 2026").collect { resp ->
                        if (resp is Response.Success) fresh.addAll(resp.data)
                    }
                }
                allRecPool.addAll(fresh.filterNot { f -> allRecPool.any { it.title == f.title } })
                currentPage++
                _recommendedSongs.value = allRecPool.take(nextSize)
            }
            _isLoadingMore.value = false
        }
    }

    fun search(query: String) {
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = Response.Success(SearchResults())
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(200)
            _searchResults.value = Response.Loading()
            runCatching {
                repository.searchEverything(query).collect { resp ->
                    _searchResults.value = resp
                }
            }
        }
    }

    fun addRecentSearch(query: String) {
        if (query.isBlank()) return
        val current = _recentSearches.value.toMutableList()
        current.remove(query)
        current.add(0, query)
        _recentSearches.value = current.take(10)
    }

    fun clearRecentSearches() {
        _recentSearches.value = emptyList()
    }

    fun openArtist(artistName: String, artistId: String = "") {
        selectedArtistName = artistName
        selectedArtistId = artistId
        currentView = AddScreenView.ARTIST_DETAIL
        viewModelScope.launch(Dispatchers.IO) {
            _artistOverview.value = Response.Loading()
            runCatching {
                repository.provideArtistOverview(artistName, artistId).collect { resp ->
                    _artistOverview.value = resp
                }
            }
        }
    }

    fun playSong(song: SongsModel, context: Context) {
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
    }

    fun toggleLike(song: SongsModel, context: Context) {
        val liked = isSongLiked(context, song)
        if (liked) {
            removeLikedSong(context, song)
            if (song.spotifyTrackId.isNotBlank()) {
                com.music.spotui.data.api.SpotifySync.setTrackSaved(context, song.spotifyTrackId, false)
            }
        } else {
            addLikedSong(context, song)
            if (song.spotifyTrackId.isNotBlank()) {
                com.music.spotui.data.api.SpotifySync.setTrackSaved(context, song.spotifyTrackId, true)
            }
        }
        notifyLikedSongsChanged()
    }
}
