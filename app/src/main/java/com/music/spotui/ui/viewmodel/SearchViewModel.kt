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

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions

    private val httpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private var suggestionJob: Job? = null

    fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()
        if (query.isBlank()) {
            _suggestions.value = emptyList()
            return
        }
        suggestionJob = viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<String>()
            runCatching {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=firefox&ds=yt&q=$encoded&hl=iw&gl=IL"
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val jsonArray = org.json.JSONArray(body)
                    val array = jsonArray.optJSONArray(1)
                    if (array != null) {
                        for (i in 0 until minOf(array.length(), 6)) {
                            val item = array.optString(i)
                            if (item.isNotBlank() && !list.contains(item)) {
                                list.add(item)
                            }
                        }
                    }
                }
            }
            _suggestions.value = list
        }
    }

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
            _suggestions.value = emptyList()
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

            // Extract YouTube albums
            val ytAlbums = ytGeneral.filterIsInstance<com.metrolist.innertube.models.AlbumItem>().map { item ->
                val targetId = item.playlistId.ifBlank { item.browseId }
                AlbumsModel(
                    id = (targetId.hashCode() and 0x7fffffff),
                    name = item.title,
                    artists = item.artists?.joinToString(", ") { it.name }.orEmpty(),
                    coverUri = item.thumbnail,
                    time = item.year?.toString() ?: "",
                )
            }

            // Extract YouTube artists
            val ytArtists = ytGeneral.filterIsInstance<com.metrolist.innertube.models.ArtistItem>().map { item ->
                ArtistsModel(
                    id = item.id,
                    name = item.title,
                    coverUri = item.thumbnail ?: "",
                )
            }

            // Combine Playlists
            val allYtPlaylists = (ytGeneral.filterIsInstance<PlaylistItem>() + ytPlaylists).distinctBy { it.id }

            // Combine Songs (Spotify hits + YouTube hits, deduped)
            val combinedSongs = (spotifyRes.songs + ytSongs).distinctBy { "${it.title.lowercase().trim()}_${it.singer.lowercase().trim()}" }

            // Combine Albums (Spotify hits + YouTube hits, deduped)
            val combinedAlbums = (spotifyRes.albums + ytAlbums).distinctBy { "${it.name.lowercase().trim()}_${it.artists.lowercase().trim()}" }

            // Combine Artists (Spotify hits + YouTube hits, deduped)
            val combinedArtists = (spotifyRes.artists + ytArtists).distinctBy { it.name.lowercase().trim() }

            val unified = UnifiedSearchResults(
                songs = combinedSongs,
                playlists = allYtPlaylists,
                albums = combinedAlbums,
                artists = combinedArtists,
                shows = spotifyRes.shows,
                episodes = spotifyRes.episodes,
            )

            _unifiedResults.value = Response.Success(unified)
            _songs.value = Response.Success(combinedSongs)
        }
    }
}