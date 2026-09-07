package com.music.spotui.ui.screens

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.music.spotui.ui.components.SongOptionsSheet
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.music.spotui.ui.components.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.SearchViewModel


@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchScreen(navController: NavController) {
    val searchViewModel: SearchViewModel = hiltViewModel()
    val results by searchViewModel.results.collectAsState()
    val ytResultsResp by searchViewModel.ytResults.collectAsState()
    val searchSource by searchViewModel.searchSource.collectAsState()
    val ytFilter by searchViewModel.ytFilter.collectAsState()

    // Results are live search hits (or empty); never gate the search UI on them.
    val searchResults = (results as? Response.Success)?.data ?: SearchResults()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        SumUpSearchScreen(
            navController = navController,
            results = searchResults,
            ytResultsResp = ytResultsResp,
            searchSource = searchSource,
            ytFilter = ytFilter,
            searchViewModel = searchViewModel,
        )
    }
}


@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun SumUpSearchScreen(
    navController: NavController,
    results: SearchResults,
    ytResultsResp: Response<List<com.metrolist.innertube.models.YTItem>>,
    searchSource: com.music.spotui.ui.viewmodel.SearchSource,
    ytFilter: com.music.spotui.ui.viewmodel.YouTubeFilter,
    searchViewModel: SearchViewModel,
) {
    val context = LocalContext.current

    var text by remember {
        mutableStateOf("")
    }
    // Recents are the *items opened from results* (songs/artists/albums), not the
    // typed queries, and only appear once the user taps into the search bar.
    var searchFocused by remember { mutableStateOf(false) }
    var recents by remember {
        mutableStateOf(com.music.spotui.data.preferences.getRecentItems(context))
    }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    val recordRecent: (com.music.spotui.data.preferences.RecentItem) -> Unit = { item ->
        com.music.spotui.data.preferences.addRecentItem(context, item)
        recents = com.music.spotui.data.preferences.getRecentItems(context)
    }
    // Live Spotify search results for the current query.
    val searchedList = results.songs
    // One relevance-mixed list (songs, artists, albums interleaved) instead of
    // separate type sections — matches how most music apps present search.
    val mixed = remember(results) { mixSearchResults(results) }

    // Warm the stream cache for the top search hits so tapping a result plays
    // (near-)instantly instead of resolving YouTube on the tap.
    LaunchedEffect(searchedList) {
        if (searchedList.isNotEmpty()) {
            SongPlayer.prefetchList(searchedList.map { it.url }, context, count = 3)
        }
    }

    // Warm stream cache for YouTube song hits too
    val ytSongs = (ytResultsResp as? Response.Success)?.data?.filterIsInstance<com.metrolist.innertube.models.SongItem>().orEmpty()
    LaunchedEffect(ytSongs) {
        if (ytSongs.isNotEmpty()) {
            val ytUrls = ytSongs.take(3).map { "youtube:${it.id}|${it.title}" }
            SongPlayer.prefetchList(ytUrls, context, count = 3)
        }
    }

    LazyColumn(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        item {
            SearchTopBar()
        }
        stickyHeader {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(AppBackground.toArgb()))
            ) {
                SearchStickyBar(
                    text,
                    onFocusChange = { searchFocused = it },
                ) {
                    text = it
                    searchViewModel.search(it)
                }

                // Dual Engine Search Tabs (Spotify vs YouTube Music)
                SearchEngineTabs(
                    selectedSource = searchSource,
                    onSelectSource = { searchViewModel.setSearchSource(it) },
                )

                // YouTube category filter chips (when in YouTube Music mode)
                if (searchSource == com.music.spotui.ui.viewmodel.SearchSource.YOUTUBE_MUSIC) {
                    YouTubeFilterChips(
                        selectedFilter = ytFilter,
                        onSelectFilter = { searchViewModel.setYouTubeFilter(it) },
                    )
                }
            }
        }

        if (text.isBlank()) {
            if (searchSource == com.music.spotui.ui.viewmodel.SearchSource.YOUTUBE_MUSIC) {
                // YouTube Music Quick Starters (popular searches & community playlists)
                item {
                    YouTubeQuickStarters { selectedQuery ->
                        text = selectedQuery
                        searchViewModel.search(selectedQuery)
                    }
                }
            } else if (searchFocused && recents.isNotEmpty()) {
                // ── Recent searches: the items the user opened (Spotify-style),
                // shown only once the search bar is focused ──
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp, 16.dp, 16.dp, 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Recent searches",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Clear",
                            color = Color(0xFFB3B3B3),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                com.music.spotui.data.preferences.clearRecentItems(context)
                                recents = emptyList()
                            },
                        )
                    }
                }
                items(recents.size) { i ->
                    val item = recents[i]
                    RecentItemRow(
                        item = item,
                        onClick = {
                            when (item.type) {
                                "song" -> {
                                    val songUrl = item.songUrl.ifBlank {
                                        SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                                    }.let { savedUrl ->
                                        if (
                                            item.spotifyTrackId.isNotBlank() &&
                                            !savedUrl.startsWith("spotify:track:") &&
                                            !savedUrl.startsWith("youtube:")
                                        ) {
                                            SongPlayer.buildSpotifyPlayQuery(item.spotifyTrackId, item.name, item.singer)
                                        } else {
                                            savedUrl
                                        }
                                    }
                                    val song = SongsModel(
                                        item.songId, item.name, item.songAlbum, item.singer,
                                        item.image, songUrl, item.spotifyTrackId,
                                        explicit = item.explicit,
                                        durationMs = item.durationMs,
                                    )
                                    searchViewModel.startRadioFromSong(song)
                                    SongPlayer.playSong(song.url, context)
                                    searchViewModel.updateSongState(
                                        song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
                                }
                                "artist" -> navController.navigate(artistRoute(item.name, item.key.takeIf { it != item.name }.orEmpty()))
                                "album" -> navController.navigate(albumRoute(item.name, item.singer))
                                "show" -> navController.navigate(showRoute(item.key, item.name))
                                "playlist" -> navController.navigate(playlistRoute(item.key, item.name))
                            }
                        },
                        onRemove = {
                            com.music.spotui.data.preferences.removeRecentItem(context, item)
                            recents = com.music.spotui.data.preferences.getRecentItems(context)
                        },
                    )
                }
            } else {
                // ── Spotify-style "Browse all" category grid ──
                item {
                    BrowseAllSection { genre, title ->
                        navController.navigate(categoryRoute(genre, title))
                    }
                }
            }
        } else {
            // Search has a query
            if (searchSource == com.music.spotui.ui.viewmodel.SearchSource.SPOTIFY) {
                // Spotify Results
                items(mixed.size) { i ->
                    when (val row = mixed[i]) {
                        is SearchRow.Song -> SearchSongRow(row.song, searchedList, searchViewModel, onPlayed = {
                            recordRecent(row.song.toRecentItem())
                        }, onLongClick = { menuSong = row.song })
                        is SearchRow.Artist -> SearchArtistRow(row.artist) {
                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                type = "artist",
                                key = row.artist.id.ifBlank { row.artist.name },
                                name = row.artist.name,
                                image = row.artist.coverUri,
                            ))
                            navController.navigate(artistRoute(row.artist.name, row.artist.id))
                        }
                        is SearchRow.Album -> SearchAlbumRow(row.album) {
                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                type = "album",
                                key = row.album.name,
                                name = row.album.name,
                                singer = row.album.artists,
                                image = row.album.coverUri,
                            ))
                            navController.navigate(albumRoute(row.album.name, row.album.artists))
                        }
                    }
                }
                // ── Podcasts: shows (→ detail) then individual episodes (→ play) ──
                if (results.shows.isNotEmpty()) {
                    item { SearchSectionHeader("Podcasts") }
                    items(results.shows.size) { i ->
                        val show = results.shows[i]
                        SearchShowRow(show) {
                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                type = "show",
                                key = show.id,
                                name = show.name,
                                singer = show.publisher,
                                image = show.coverUri,
                            ))
                            navController.navigate(showRoute(show.id, show.name))
                        }
                    }
                }
                if (results.episodes.isNotEmpty()) {
                    item { SearchSectionHeader("Episodes") }
                    items(results.episodes.size) { i ->
                        val ep = results.episodes[i]
                        SearchSongRow(ep, results.episodes, searchViewModel, onPlayed = {
                            recordRecent(ep.toRecentItem())
                        }, onLongClick = { menuSong = ep })
                    }
                }
            } else {
                // YouTube Music Results
                when (ytResultsResp) {
                    is Response.Loading -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Loader()
                            }
                        }
                    }
                    is Response.Error -> {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "שגיאה בטעינת תוצאות מיוטיוב",
                                    color = Color.Gray,
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                    is Response.Success -> {
                        val ytList = ytResultsResp.data
                        if (ytList.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "לא נמצאו תוצאות ב-YouTube Music",
                                        color = Color.Gray,
                                        fontSize = 14.sp,
                                    )
                                }
                            }
                        } else {
                            items(ytList.size) { i ->
                                when (val item = ytList[i]) {
                                    is com.metrolist.innertube.models.SongItem -> {
                                        SearchYTSongRow(
                                            item = item,
                                            searchViewModel = searchViewModel,
                                            onPlayed = {
                                                recordRecent(
                                                    com.music.spotui.data.preferences.RecentItem(
                                                        type = "song",
                                                        key = "youtube:${item.id}",
                                                        name = item.title,
                                                        singer = item.artists.joinToString(", ") { it.name },
                                                        image = item.thumbnail,
                                                        songId = (item.id.hashCode() and 0x7fffffff),
                                                        songAlbum = item.album?.name ?: "",
                                                        songUrl = "youtube:${item.id}|${item.title} ${item.artists.firstOrNull()?.name.orEmpty()}",
                                                        spotifyTrackId = "",
                                                        explicit = item.explicit,
                                                        durationMs = (item.duration ?: 0) * 1000,
                                                    )
                                                )
                                            }
                                        )
                                    }
                                    is com.metrolist.innertube.models.PlaylistItem -> {
                                        SearchYTPlaylistRow(
                                            item = item,
                                            onClick = {
                                                recordRecent(
                                                    com.music.spotui.data.preferences.RecentItem(
                                                        type = "playlist",
                                                        key = "youtube:${item.id}",
                                                        name = item.title,
                                                        singer = item.author?.name.orEmpty(),
                                                        image = item.thumbnail ?: "",
                                                    )
                                                )
                                                navController.navigate(playlistRoute("youtube:${item.id}", item.title))
                                            }
                                        )
                                    }
                                    is com.metrolist.innertube.models.AlbumItem -> {
                                        SearchYTAlbumRow(
                                            item = item,
                                            onClick = {
                                                val targetId = item.playlistId.ifBlank { item.browseId }
                                                recordRecent(
                                                    com.music.spotui.data.preferences.RecentItem(
                                                        type = "album",
                                                        key = "youtube:$targetId",
                                                        name = item.title,
                                                        singer = item.artists?.joinToString(", ") { it.name }.orEmpty(),
                                                        image = item.thumbnail,
                                                    )
                                                )
                                                navController.navigate(playlistRoute("youtube:$targetId", item.title))
                                            }
                                        )
                                    }
                                    is com.metrolist.innertube.models.ArtistItem -> {
                                        SearchYTArtistRow(
                                            item = item,
                                            onClick = {
                                                text = item.title
                                                searchViewModel.search(item.title)
                                            }
                                        )
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A single row in the search results: a track, an artist, or an album. */
sealed class SearchRow {
    data class Song(val song: SongsModel) : SearchRow()
    data class Artist(val artist: com.music.spotui.data.entity.ArtistsModel) : SearchRow()
    data class Album(val album: com.music.spotui.data.entity.AlbumsModel) : SearchRow()
}

/**
 * Interleaves the three result types into one list, weighted toward songs
 * (2 songs per artist+album cycle) so the list reads as mixed rather than
 * grouped, while songs — the most common search intent — stay prominent.
 */
private fun mixSearchResults(results: SearchResults): List<SearchRow> {
    val songs = results.songs.iterator()
    val artists = results.artists.iterator()
    val albums = results.albums.iterator()
    val out = ArrayList<SearchRow>()
    while (songs.hasNext() || artists.hasNext() || albums.hasNext()) {
        repeat(2) { if (songs.hasNext()) out += SearchRow.Song(songs.next()) }
        if (artists.hasNext()) out += SearchRow.Artist(artists.next())
        if (albums.hasNext()) out += SearchRow.Album(albums.next())
    }
    return out
}

/** Maps a tapped search-result song to a persisted recent item. */
private fun SongsModel.toRecentItem() = com.music.spotui.data.preferences.RecentItem(
    type = "song",
    key = spotifyTrackId.ifBlank { url },
    name = title,
    singer = singer,
    image = coverUri,
    songId = id,
    songAlbum = album,
    songUrl = url,
    spotifyTrackId = spotifyTrackId,
    explicit = explicit,
    durationMs = durationMs,
)

/** A recent item row (song/artist/album/show the user opened), with remove (x). */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RecentItemRow(
    item: com.music.spotui.data.preferences.RecentItem,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(16.dp, 8.dp),
    ) {
        GlideImage(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(if (item.type == "artist") 100.dp else 6.dp)),
            model = item.image,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp, end = 8.dp),
        ) {
            Text(text = item.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            val subtitle = when (item.type) {
                "song" -> "Song • ${item.singer}"
                "artist" -> "Artist"
                "album" -> "Album • ${item.singer}"
                "show" -> "Podcast" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
                else -> ""
            }
            Text(text = subtitle, color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        Icon(
            imageVector = Icons.Default.Close,
            tint = Color(0xFFB3B3B3),
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onRemove() },
            contentDescription = "Remove",
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun SearchSongRow(
    song: SongsModel,
    songList: List<SongsModel>,
    searchViewModel: SearchViewModel,
    onPlayed: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var isLiked by remember { mutableStateOf(isSongLiked(context, song.id.toString())) }
    val likeState = searchViewModel.likeState.value
    LaunchedEffect(likeState) { isLiked = isSongLiked(context, song.id.toString()) }
    val currentPlayingIndicatorColor =
        if (song.id == searchViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = {
                    onPlayed()
                    // Start a radio from the tapped track (queue = this song + Spotify
                    // recommendations) rather than queuing the whole search list.
                    searchViewModel.startRadioFromSong(song)
                    SongPlayer.playSong(song.url, context)
                    searchViewModel.updateSongState(
                        song.coverUri,
                        song.title,
                        song.singer,
                        true,
                        song.id,
                        0,
                        song.album,
                    )
                },
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        ) {
            GlideImage(
                modifier = Modifier
                    .padding(0.dp, 0.dp, 10.dp, 0.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                model = song.coverUri,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                contentDescription = "",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = song.title, color = currentPlayingIndicatorColor, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(text = "Song • ${song.singer}", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }

        Icon(
            modifier = Modifier
                .size(20.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {
                    if (isLiked) removeLikedSongId(context, song.id.toString())
                    else addLikedSongId(context, song.id.toString())
                    isLiked = isSongLiked(context, song.id.toString())
                    searchViewModel.updateLikeState(!searchViewModel.likeState.value)
                },
            painter = if (isLiked) painterResource(id = R.drawable.added) else painterResource(id = R.drawable.ic_add),
            tint = if (isLiked) Color.White else Color.Gray,
            contentDescription = "",
        )
    }
}

@Composable
fun SearchSectionHeader(title: String) {
    Text(
        text = title,
        color = Color.White,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(16.dp, 18.dp, 16.dp, 4.dp),
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchShowRow(show: com.music.spotui.data.entity.PodcastModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = show.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = show.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                text = "Podcast" + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""),
                color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchArtistRow(artist: com.music.spotui.data.entity.ArtistsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(100.dp)),
            model = artist.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = artist.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = "Artist", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchAlbumRow(album: com.music.spotui.data.entity.AlbumsModel, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp, 8.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        GlideImage(
            modifier = Modifier
                .padding(0.dp, 0.dp, 10.dp, 0.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = album.coverUri,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column {
            Text(text = album.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = "Album • ${album.artists}", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/** Spotify's "Browse all" categories: name + tile colour + the search query it runs. */
private val browseCategories: List<Triple<String, Color, String>> = listOf(
    Triple("Music", Color(0xFFDC148C), "Top hits"),
    Triple("Podcasts", Color(0xFF1E3264), "Podcast"),
    Triple("Made For You", Color(0xFF8768A8), "Discover weekly"),
    Triple("New Releases", Color(0xFFE8115B), "New releases"),
    Triple("Pop", Color(0xFF8D67AB), "Pop"),
    Triple("Hip-Hop", Color(0xFF477D95), "Hip hop"),
    Triple("Rock", Color(0xFFE61E32), "Rock"),
    Triple("Latin", Color(0xFFE1118C), "Latin"),
    Triple("Country", Color(0xFFD84000), "Country"),
    Triple("R&B", Color(0xFFBA5D07), "R&B"),
    Triple("K-Pop", Color(0xFF148A08), "K-pop"),
    Triple("Indie", Color(0xFF608108), "Indie"),
    Triple("Dance/Electronic", Color(0xFF056952), "Electronic dance"),
    Triple("Metal", Color(0xFF777777), "Metal"),
    Triple("Chill", Color(0xFF1E3264), "Chill"),
    Triple("Charts", Color(0xFF8C1932), "Top charts"),
    Triple("Workout", Color(0xFF777777), "Workout"),
    Triple("Jazz", Color(0xFF503750), "Jazz"),
)

@Composable
fun BrowseAllSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Browse all",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 8.dp),
        )
        browseCategories.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp, 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { (name, color, query) ->
                    BrowseCategoryTile(name, color, query, Modifier.weight(1f)) { onCategoryClick(query, name) }
                }
                // Keep a half-width spacer if the last row has a single tile.
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    query: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Cover of the category's top playlist, shown tilted in the corner like
    // Spotify web's browse tiles. Empty until resolved (cached per session).
    val cover by androidx.compose.runtime.produceState(initialValue = "", key1 = query) {
        value = com.music.spotui.data.api.BrowseTileImages.coverFor(context, query)
    }
    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        if (cover.isNotBlank()) {
            GlideImage(
                model = cover,
                contentScale = ContentScale.Crop,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(64.dp)
                    .offset(x = 16.dp, y = 10.dp)
                    .rotate(25f)
                    .clip(RoundedCornerShape(4.dp)),
            )
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
fun SearchTopBar() {
    Row(horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(text = "Search", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        //Icon(imageVector = Icons.Default.Person, contentDescription = "", tint = Color.White)
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchStickyBar(
    text: String,
    onFocusChange: (Boolean) -> Unit = {},
    onTextChange: (String) -> Unit,
) {

    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .height(55.dp)
            .background(Color.White)
            .padding(10.dp, 0.dp)
    ){
        Icon(
            painterResource(id = R.drawable.ic_search_big),
            tint = Color.Black,
            contentDescription = "")

        TextField(
            enabled = true,
            modifier = Modifier.onFocusChanged { onFocusChange(it.isFocused) },
            value = text,
            textStyle = TextStyle.Default.copy(fontSize = 16.sp, color = Color.Black, fontWeight = FontWeight(500)),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Color.Black

            ),
            singleLine = true,
            onValueChange = onTextChange,
            placeholder = {
                Text(
                     textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    text = "What do you want to listen to?"

                )
            }
        )
    }
}

@Composable
fun SearchEngineTabs(
    selectedSource: com.music.spotui.ui.viewmodel.SearchSource,
    onSelectSource: (com.music.spotui.ui.viewmodel.SearchSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val spotifySelected = selectedSource == com.music.spotui.ui.viewmodel.SearchSource.SPOTIFY
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (spotifySelected) Color(0xFF1DB954) else Color(0xFF282828))
                .clickable { onSelectSource(com.music.spotui.ui.viewmodel.SearchSource.SPOTIFY) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Spotify",
                color = if (spotifySelected) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        val ytSelected = selectedSource == com.music.spotui.ui.viewmodel.SearchSource.YOUTUBE_MUSIC
        Box(
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (ytSelected) Color(0xFFFF0000) else Color(0xFF282828))
                .clickable { onSelectSource(com.music.spotui.ui.viewmodel.SearchSource.YOUTUBE_MUSIC) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "YouTube Music",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun YouTubeFilterChips(
    selectedFilter: com.music.spotui.ui.viewmodel.YouTubeFilter,
    onSelectFilter: (com.music.spotui.ui.viewmodel.YouTubeFilter) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val filters = com.music.spotui.ui.viewmodel.YouTubeFilter.values()
        items(filters.size) { index ->
            val filter = filters[index]
            val isSelected = selectedFilter == filter
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isSelected) Color.White else Color(0xFF282828))
                    .clickable { onSelectFilter(filter) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    text = filter.title,
                    color = if (isSelected) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun YouTubeQuickStarters(onSelectQuery: (String) -> Unit) {
    val starters = listOf(
        "פלייליסט דתי",
        "שירי שבת",
        "מוזיקה יהודית",
        "מוזיקה חסידית",
        "שירי רגש ונשמה",
        "להיטים 2024",
        "סטים ורמיקסים",
        "ווקאלי ללא מוזיקה",
        "מוזיקה מזרחית",
        "שירים שקטים",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "חיפושים פופולריים ופלייליסטים קהילתיים",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        starters.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { query ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF282828))
                            .clickable { onSelectQuery(query) }
                            .padding(12.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = query,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchYTSongRow(
    item: com.metrolist.innertube.models.SongItem,
    searchViewModel: SearchViewModel,
    onPlayed: () -> Unit,
) {
    val context = LocalContext.current
    val songId = item.id.hashCode() and 0x7fffffff
    val isPlayingThis = searchViewModel.currentSongId.value == songId
    val indicatorColor = if (isPlayingThis) Color(AppPalette.toArgb()) else Color.White

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onPlayed()
                val song = searchViewModel.startRadioFromYouTubeSong(item)
                SongPlayer.playSong(song.url, context)
                searchViewModel.updateSongState(
                    song.coverUri,
                    song.title,
                    song.singer,
                    true,
                    song.id,
                    0,
                    song.album
                )
            }
            .padding(16.dp, 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(end = 8.dp)
        ) {
            GlideImage(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(48.dp)
                    .clip(RoundedCornerShape(6.dp)),
                model = item.thumbnail,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                contentDescription = "",
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    color = indicatorColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                val artistStr = item.artists.joinToString(", ") { it.name }
                Text(
                    text = "שיר • $artistStr",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchYTPlaylistRow(
    item: com.metrolist.innertube.models.PlaylistItem,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp, 8.dp)
    ) {
        GlideImage(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(52.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = item.thumbnail ?: "",
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            val sub = buildString {
                append("פלייליסט")
                item.author?.name?.let { if (it.isNotBlank()) append(" • $it") }
                item.songCountText?.let { if (it.isNotBlank()) append(" • $it") }
            }
            Text(
                text = sub,
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchYTAlbumRow(
    item: com.metrolist.innertube.models.AlbumItem,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp, 8.dp)
    ) {
        GlideImage(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp)),
            model = item.thumbnail,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            val artistStr = item.artists?.joinToString(", ") { it.name }.orEmpty()
            Text(
                text = "אלבום" + (if (artistStr.isNotBlank()) " • $artistStr" else ""),
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun SearchYTArtistRow(
    item: com.metrolist.innertube.models.ArtistItem,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp, 8.dp)
    ) {
        GlideImage(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(48.dp)
                .clip(RoundedCornerShape(100.dp)),
            model = item.thumbnail ?: "",
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentDescription = "",
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
            Text(
                text = "אמן",
                color = Color.Gray,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}
