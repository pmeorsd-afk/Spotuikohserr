package com.music.spotui.ui.viewmodel

import androidx.compose.runtime.State
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UnifiedSearchResults(
    val songs: List<SongsModel> = emptyList(),
    val playlists: List<PlaylistItem> = emptyList(),
    val albums: List<AlbumsModel> = emptyList(),
    val artists: List<ArtistsModel> = emptyList(),
    val shows: List<PodcastModel> = emptyList(),
    val episodes: List<SongsModel> = emptyList(),
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: AppRepository,
    private val currentSongState: CurrentSongState,
) : ViewModel() {

    private val _unifiedResults = MutableStateFlow<Response<UnifiedSearchResults>>(Response.Success(UnifiedSearchResults()))
    val unifiedResults: StateFlow<Response<UnifiedSearchResults>> = _unifiedResults

    private val _songs = MutableStateFlow<Response<List<SongsModel>>>(Response.Success(emptyList()))
    val songs: StateFlow<Response<List<SongsModel>>> = _songs

    val likeState = currentSongState.likeState
    val currentSongId: State<Int> get() = currentSongState.songId

    fun updateLikeState(likeState: Boolean) {
        currentSongState.updateLikeState(likeState)
    }

    private var searchJob: Job? = null

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

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
        searchJob?.cancel()
        if (query.isBlank()) {
            _unifiedResults.value = Response.Success(UnifiedSearchResults())
            _songs.value = Response.Success(emptyList())
            return
        }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(150) // debounce for real-time typing
            _unifiedResults.value = Response.Loading()

            // Query Spotify and YouTube Music in parallel
            val spotifyDeferred = async {
                runCatching {
                    var last: Response<SearchResults> = Response.Loading()
                    repository.searchEverything(query).collect { if (it !is Response.Loading) last = it }
                    last
                }.getOrNull()
            }

            val ytDeferred = async {
                runCatching {
                    var last: Response<List<YTItem>> = Response.Loading()
                    repository.searchYouTube(query, YouTube.SearchFilter.FILTER_ALL).collect { if (it !is Response.Loading) last = it }
                    last
                }.getOrNull()
            }

            val ytPlaylistsDeferred = async {
                runCatching {
                    var last: Response<List<YTItem>> = Response.Loading()
                    repository.searchYouTube(query, YouTube.SearchFilter.FILTER_PLAYLIST).collect { if (it !is Response.Loading) last = it }
                    last
                }.getOrNull()
            }

            val spotifyRes = (spotifyDeferred.await() as? Response.Success)?.data ?: SearchResults()
            val ytGeneral = (ytDeferred.await() as? Response.Success)?.data.orEmpty()
            val ytPlaylists = (ytPlaylistsDeferred.await() as? Response.Success)?.data.orEmpty().filterIsInstance<PlaylistItem>()

            // Extract YouTube songs
            val ytSongs = ytGeneral.filterIsInstance<SongItem>().map { item ->
                SongsModel(
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
            }

            // Combine Playlists
            val allYtPlaylists = (ytGeneral.filterIsInstance<PlaylistItem>() + ytPlaylists).distinctBy { it.id }

            // Combine Songs (Spotify hits + YouTube hits, deduped)
            val combinedSongs = (spotifyRes.songs + ytSongs).distinctBy { "${it.title.lowercase().trim()}_${it.singer.lowercase().trim()}" }

            val unified = UnifiedSearchResults(
                songs = combinedSongs,
                playlists = allYtPlaylists,
                albums = spotifyRes.albums,
                artists = spotifyRes.artists,
                shows = spotifyRes.shows,
                episodes = spotifyRes.episodes,
            )

            _unifiedResults.value = Response.Success(unified)
            _songs.value = Response.Success(combinedSongs)
        }
    }
}