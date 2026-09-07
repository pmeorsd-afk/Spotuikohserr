package com.music.spotui.ui.viewmodel

import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchSource {
    SPOTIFY,
    YOUTUBE_MUSIC,
}

enum class YouTubeFilter(val title: String, val filter: YouTube.SearchFilter) {
    ALL("הכל", YouTube.SearchFilter.FILTER_ALL),
    PLAYLISTS("פלייליסטים", YouTube.SearchFilter.FILTER_PLAYLIST),
    SONGS("שירים", YouTube.SearchFilter.FILTER_SONG),
    ALBUMS("אלבומים", YouTube.SearchFilter.FILTER_ALBUM),
    ARTISTS("אמנים", YouTube.SearchFilter.FILTER_ARTIST),
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
) : ViewModel() {

    private val _searchSource = MutableStateFlow(SearchSource.SPOTIFY)
    val searchSource: StateFlow<SearchSource> = _searchSource

    private val _ytFilter = MutableStateFlow(YouTubeFilter.ALL)
    val ytFilter: StateFlow<YouTubeFilter> = _ytFilter

    private val _songs: MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs: StateFlow<Response<List<SongsModel>>> = _songs

    private val _results: MutableStateFlow<Response<SearchResults>> = MutableStateFlow(Response.Success(SearchResults()))
    val results: StateFlow<Response<SearchResults>> = _results

    private val _ytResults: MutableStateFlow<Response<List<YTItem>>> = MutableStateFlow(Response.Success(emptyList()))
    val ytResults: StateFlow<Response<List<YTItem>>> = _ytResults

    val likeState = currentSongState.likeState

    val currentSongId: State<Int> get() = currentSongState.songId

    private var currentQuery: String = ""

    fun updateLikeState(likeState: Boolean) {
        currentSongState.updateLikeState(likeState)
    }

    private var searchJob: Job? = null

    init {
        _songs.value = Response.Success(emptyList())
        _ytResults.value = Response.Success(emptyList())
    }

    fun setSearchSource(source: SearchSource) {
        if (_searchSource.value == source) return
        _searchSource.value = source
        if (currentQuery.isNotBlank()) {
            search(currentQuery)
        }
    }

    fun setYouTubeFilter(filter: YouTubeFilter) {
        if (_ytFilter.value == filter) return
        _ytFilter.value = filter
        if (currentQuery.isNotBlank() && _searchSource.value == SearchSource.YOUTUBE_MUSIC) {
            search(currentQuery)
        }
    }

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    /**
     * Start playback of a single search result as a *radio*, the way Spotify does:
     * the queue becomes just this track, then Spotify-recommended tracks (seeded from
     * it) are appended as they load — instead of queuing the rest of the search list.
     */
    fun startRadioFromSong(song: SongsModel) {
        currentSongState.updateQueue(listOf(song))
        val seed = song.spotifyTrackId
        if (seed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val recs = repository.provideRecommendations(listOf(seed))
            val current = currentSongState.queue.value
            if (current.size == 1 && current.first().id == song.id) {
                val fresh = recs.filter { it.id != song.id }
                if (fresh.isNotEmpty()) currentSongState.updateQueue(current + fresh)
            }
        }
    }

    fun startRadioFromYouTubeSong(item: SongItem): SongsModel {
        val song = SongsModel(
            id = (item.id.hashCode() and 0x7fffffff),
            title = item.title,
            album = item.album?.name ?: "",
            singer = item.artists.joinToString(", ") { it.name },
            coverUri = item.thumbnail,
            url = "youtube:${item.id}|${item.title} ${item.artists.firstOrNull()?.name.orEmpty()}",
            spotifyTrackId = "",
            explicit = item.explicit,
            durationMs = (item.duration ?: 0) * 1000,
        )
        currentSongState.updateQueue(listOf(song))
        return song
    }

    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId: Int, songIndex: Int = 0, album: String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun search(query: String) {
        currentQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _results.value = Response.Success(SearchResults())
            _songs.value = Response.Success(emptyList())
            _ytResults.value = Response.Success(emptyList())
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150) // short debounce for snappy real-time results
            if (_searchSource.value == SearchSource.SPOTIFY) {
                repository.searchEverything(query).collect { result ->
                    _results.value = result
                    _songs.value = when (result) {
                        is Response.Success -> Response.Success(result.data.songs)
                        is Response.Error -> Response.Error(result.error)
                        is Response.Loading -> Response.Loading()
                    }
                }
            } else {
                _ytResults.value = Response.Loading()
                repository.searchYouTube(query, _ytFilter.value.filter).collect { result ->
                    _ytResults.value = result
                }
            }
        }
    }
}