package com.music.spotui.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.SearchViewModel
import com.music.spotui.ui.viewmodel.UnifiedSearchResults

enum class SearchCategory(val title: String) {
    SONGS("שירים"),
    PLAYLISTS("פלייליסטים"),
    ALBUMS("אלבומים"),
    ARTISTS("אמנים"),
    PODCASTS("פודקאסטים"),
}

data class BrowseCategory(
    val title: String,
    val color: Color,
    val query: String,
    val coverUrl: String,
)

private val browseCategories: List<BrowseCategory> = listOf(
    BrowseCategory("פודקאסטים", Color(0xFF006450), "פודקאסטים", "https://i.scdn.co/image/ab6765630000ba8a7e025b4408d66dfa99908cf8"),
    BrowseCategory("מוזיקה", Color(0xFFDC148C), "מוזיקה", "https://i.scdn.co/image/ab67706f00000002b8d003e62f559ccaa19fa00c"),
    BrowseCategory("במיוחד בשבילכם", Color(0xFF8D67AB), "במיוחד בשבילכם", "https://misc.scdn.co/liked-songs/liked-songs-640.png"),
    BrowseCategory("אירועים חיים", Color(0xFF8400E7), "אירועים חיים", "https://concerts.spotifycdn.com/images/live-events_500.jpg"),
    BrowseCategory("מה חדש?", Color(0xFF608108), "מה חדש", "https://i.scdn.co/image/ab67706f000000027ea4d505212b9de1f72c5112"),
    BrowseCategory("ריליסים שצפויים בקרוב", Color(0xFF056952), "ריליסים", "https://i.scdn.co/image/ab67706f00000002fe24d7084be4aa2288d6cdc3"),
    BrowseCategory("היפ-הופ", Color(0xFF477D95), "היפ הופ", "https://i.scdn.co/image/ab67706f000000029bb7d9ab80004944d650058b"),
    BrowseCategory("פופ", Color(0xFF509BF5), "פופ", "https://i.scdn.co/image/ab67706f0000000282b243023b937c336ae3533f"),
    BrowseCategory("לטיני", Color(0xFF1E3264), "לטיני", "https://i.scdn.co/image/ab67706f000000027f30215c0e11894a4ae7339d"),
    BrowseCategory("רוק", Color(0xFF006450), "רוק", "https://i.scdn.co/image/ab67706f00000002fe24d7084be4aa2288d6cdc3"),
    BrowseCategory("ישראלי", Color(0xFFE1118C), "ישראלי", "https://i.scdn.co/image/ab67706f00000002078aa27cf5c49740e53a270f"),
    BrowseCategory("מזרחית", Color(0xFFD84000), "מזרחית", "https://i.scdn.co/image/ab67706f00000002b1f862db80bf38f828a2a537"),
    BrowseCategory("יהודי ודתי", Color(0xFFBA5D07), "חסידי יהודי", "https://i.scdn.co/image/ab67706f0000000236a28723c316279f15037d04"),
    BrowseCategory("אימון וכושר", Color(0xFF777777), "אימון", "https://i.scdn.co/image/ab67706f000000029249b35d23e07d9b0f4eb4c6"),
    BrowseCategory("צ'יל ורגיעה", Color(0xFF1E3264), "צ'יל", "https://i.scdn.co/image/ab67706f000000026e515187c071e45918e9f8de"),
    BrowseCategory("דאנס ואלקטרוני", Color(0xFF056952), "אלקטרוני", "https://i.scdn.co/image/ab67706f00000002037149a4f4d2f831bfaeb034"),
    BrowseCategory("אינדי", Color(0xFF608108), "אינדי", "https://i.scdn.co/image/ab67706f0000000224bf1694f4a3bfec6a66a70e"),
    BrowseCategory("מצעדים ולהיטים", Color(0xFF8C1932), "מצעדים", "https://charts-images.scdn.co/REGULAR/overview-default.jpg"),
    BrowseCategory("ג'אז", Color(0xFF503750), "ג'אז", "https://i.scdn.co/image/ab67706f000000025a1768c78b4081c7ffcc0872"),
    BrowseCategory("קלאסי", Color(0xFF7D4B98), "קלאסי", "https://i.scdn.co/image/ab67706f000000021670415982183ba89832a792"),
    BrowseCategory("שינה ומנוחה", Color(0xFF1E3264), "שינה", "https://i.scdn.co/image/ab67706f00000002b70502d6174a7846f4ab5118"),
)

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchScreen(navController: NavController) {
    val searchViewModel: SearchViewModel = hiltViewModel()
    val unifiedResultsResp by searchViewModel.unifiedResults.collectAsState()

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        SumUpSearchScreen(
            navController = navController,
            unifiedResultsResp = unifiedResultsResp,
            searchViewModel = searchViewModel,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.S)
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
fun SumUpSearchScreen(
    navController: NavController,
    unifiedResultsResp: Response<UnifiedSearchResults>,
    searchViewModel: SearchViewModel,
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var text by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<SearchCategory?>(null) }
    var recents by remember { mutableStateOf(com.music.spotui.data.preferences.getRecentItems(context)) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }



    BackHandler(enabled = searchActive) {
        searchActive = false
        text = ""
        searchViewModel.search("")
        keyboardController?.hide()
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

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

    val unifiedResults = (unifiedResultsResp as? Response.Success)?.data ?: UnifiedSearchResults()
    val mixed = remember(unifiedResults) { mixSearchResults(unifiedResults) }

    LaunchedEffect(unifiedResults.songs) {
        if (unifiedResults.songs.isNotEmpty()) {
            SongPlayer.prefetchList(unifiedResults.songs.map { it.url }, context, count = 3)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(AppBackground.toArgb()))
                .statusBarsPadding()
        ) {
            AnimatedContent(
                targetState = searchActive || text.isNotBlank(),
                transitionSpec = {
                    (fadeIn(animationSpec = tween(220, easing = LinearOutSlowInEasing)) +
                            slideInVertically(animationSpec = tween(220, easing = LinearOutSlowInEasing)) { -it / 4 })
                        .togetherWith(
                            fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                    slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { -it / 4 }
                        )
                },
                label = "SearchBarTransition"
            ) { isSearching ->
                if (!isSearching) {
                    SearchIdleBar(
                        onClick = { searchActive = true }
                    )
                } else {
                    SearchActiveBar(
                        text = text,
                        focusRequester = focusRequester,
                        onBackClick = {
                            searchActive = false
                            text = ""
                            searchViewModel.search("")
                            keyboardController?.hide()
                        },
                        onTextChange = {
                            text = it
                            if (it.isBlank()) {
                                selectedCategory = null
                            }
                            searchViewModel.search(it)
                        },
                        onClearClick = {
                            text = ""
                            selectedCategory = null
                            searchViewModel.search("")
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = (searchActive || text.isNotBlank()) && text.isNotBlank(),
                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
                exit = fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 2 }
            ) {
                SearchCategoryFilterChips(
                    selectedCategory = selectedCategory,
                    onSelectCategory = { cat ->
                        selectedCategory = if (selectedCategory == cat) null else cat
                    }
                )
            }

            AnimatedContent(
                targetState = searchActive || text.isNotBlank(),
                transitionSpec = {
                    if (targetState) {
                        (fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing)) +
                                slideInVertically(animationSpec = tween(260, easing = LinearOutSlowInEasing)) { it / 8 })
                            .togetherWith(
                                fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                            )
                    } else {
                        (fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                        slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { it / 8 }
                            )
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                label = "SearchBodyTransition"
            ) { isSearching ->
                if (!isSearching) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 130.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            CategoryGridSection { genre, title ->
                                navController.navigate(categoryRoute(genre, title))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 130.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (text.isBlank()) {
                            if (recents.isNotEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp, 16.dp, 16.dp, 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                    ) {
                                        Text(
                                            "חיפושים אחרונים",
                                            color = Color.White,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            "נקה הכל",
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
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 180.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(horizontal = 32.dp)
                                        ) {
                                            Text(
                                                text = "נגנו מוזיקה שאתם אוהבים",
                                                color = Color.White,
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "חפשו אמנים, שירים, פלייליסטים, פודקאסטים ועוד.",
                                                color = Color(0xFFB3B3B3),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Normal,
                                                textAlign = TextAlign.Center
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            when (unifiedResultsResp) {
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
                                                text = "שגיאה בחיפוש",
                                                color = Color.Gray,
                                                fontSize = 14.sp,
                                            )
                                        }
                                    }
                                }
                                is Response.Success -> {
                                    when (selectedCategory) {
                                        SearchCategory.SONGS -> {
                                            if (unifiedResults.songs.isEmpty()) {
                                                item { SearchEmptyMessage("לא נמצאו שירים עבור \"$text\"") }
                                            } else {
                                                items(unifiedResults.songs.size) { i ->
                                                    val song = unifiedResults.songs[i]
                                                    SearchSongRow(
                                                        song = song,
                                                        songList = unifiedResults.songs,
                                                        searchViewModel = searchViewModel,
                                                        onPlayed = { recordRecent(song.toRecentItem()) },
                                                        onLongClick = { menuSong = song }
                                                    )
                                                }
                                            }
                                        }
                                        SearchCategory.PLAYLISTS -> {
                                            if (unifiedResults.playlists.isEmpty()) {
                                                item { SearchEmptyMessage("לא נמצאו פלייליסטים עבור \"$text\"") }
                                            } else {
                                                items(unifiedResults.playlists.size) { i ->
                                                    val playlistItem = unifiedResults.playlists[i]
                                                    SearchYTPlaylistRow(
                                                        item = playlistItem,
                                                        onClick = {
                                                            recordRecent(
                                                                com.music.spotui.data.preferences.RecentItem(
                                                                    type = "playlist",
                                                                    key = "youtube:${playlistItem.id}",
                                                                    name = playlistItem.title,
                                                                    singer = playlistItem.author?.name.orEmpty(),
                                                                    image = playlistItem.thumbnail ?: "",
                                                                )
                                                            )
                                                            navController.navigate(playlistRoute("youtube:${playlistItem.id}", playlistItem.title))
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        SearchCategory.ALBUMS -> {
                                            if (unifiedResults.albums.isEmpty()) {
                                                item { SearchEmptyMessage("לא נמצאו אלבומים עבור \"$text\"") }
                                            } else {
                                                items(unifiedResults.albums.size) { i ->
                                                    val album = unifiedResults.albums[i]
                                                    SearchAlbumRow(
                                                        album = album,
                                                        onClick = {
                                                            recordRecent(
                                                                com.music.spotui.data.preferences.RecentItem(
                                                                    type = "album",
                                                                    key = album.name,
                                                                    name = album.name,
                                                                    singer = album.artists,
                                                                    image = album.coverUri,
                                                                )
                                                            )
                                                            navController.navigate(albumRoute(album.name, album.artists))
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        SearchCategory.ARTISTS -> {
                                            if (unifiedResults.artists.isEmpty()) {
                                                item { SearchEmptyMessage("לא נמצאו אמנים עבור \"$text\"") }
                                            } else {
                                                items(unifiedResults.artists.size) { i ->
                                                    val artist = unifiedResults.artists[i]
                                                    SearchArtistRow(
                                                        artist = artist,
                                                        onClick = {
                                                            recordRecent(
                                                                com.music.spotui.data.preferences.RecentItem(
                                                                    type = "artist",
                                                                    key = artist.id.ifBlank { artist.name },
                                                                    name = artist.name,
                                                                    image = artist.coverUri,
                                                                )
                                                            )
                                                            navController.navigate(artistRoute(artist.name, artist.id))
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                        SearchCategory.PODCASTS -> {
                                            val hasPodcasts = unifiedResults.shows.isNotEmpty() || unifiedResults.episodes.isNotEmpty()
                                            if (!hasPodcasts) {
                                                item { SearchEmptyMessage("לא נמצאו פודקאסטים עבור \"$text\"") }
                                            } else {
                                                if (unifiedResults.shows.isNotEmpty()) {
                                                    item { SearchSectionHeader("פודקאסטים") }
                                                    items(unifiedResults.shows.size) { i ->
                                                        val show = unifiedResults.shows[i]
                                                        SearchShowRow(show) {
                                                            recordRecent(
                                                                com.music.spotui.data.preferences.RecentItem(
                                                                    type = "show",
                                                                    key = show.id,
                                                                    name = show.name,
                                                                    singer = show.publisher,
                                                                    image = show.coverUri,
                                                                )
                                                            )
                                                            navController.navigate(showRoute(show.id, show.name))
                                                        }
                                                    }
                                                }
                                                if (unifiedResults.episodes.isNotEmpty()) {
                                                    item { SearchSectionHeader("פרקים") }
                                                    items(unifiedResults.episodes.size) { i ->
                                                        val ep = unifiedResults.episodes[i]
                                                        SearchSongRow(
                                                            song = ep,
                                                            songList = unifiedResults.episodes,
                                                            searchViewModel = searchViewModel,
                                                            onPlayed = { recordRecent(ep.toRecentItem()) },
                                                            onLongClick = { menuSong = ep }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        null -> {
                                            val isEmpty = unifiedResults.songs.isEmpty() &&
                                                    unifiedResults.playlists.isEmpty() &&
                                                    unifiedResults.artists.isEmpty() &&
                                                    unifiedResults.albums.isEmpty() &&
                                                    unifiedResults.shows.isEmpty() &&
                                                    unifiedResults.episodes.isEmpty()

                                            if (isEmpty) {
                                                item { SearchEmptyMessage("לא נמצאו תוצאות עבור \"$text\"") }
                                            } else {
                                                if (unifiedResults.playlists.isNotEmpty()) {
                                                    item { SearchSectionHeader("פלייליסטים") }
                                                    items(unifiedResults.playlists.size) { i ->
                                                        val playlistItem = unifiedResults.playlists[i]
                                                        SearchYTPlaylistRow(
                                                            item = playlistItem,
                                                            onClick = {
                                                                recordRecent(
                                                                    com.music.spotui.data.preferences.RecentItem(
                                                                        type = "playlist",
                                                                        key = "youtube:${playlistItem.id}",
                                                                        name = playlistItem.title,
                                                                        singer = playlistItem.author?.name.orEmpty(),
                                                                        image = playlistItem.thumbnail ?: "",
                                                                    )
                                                                )
                                                                navController.navigate(playlistRoute("youtube:${playlistItem.id}", playlistItem.title))
                                                            }
                                                        )
                                                    }
                                                }
                                                items(mixed.size) { i ->
                                                    when (val row = mixed[i]) {
                                                        is SearchRow.Song -> SearchSongRow(
                                                            song = row.song,
                                                            songList = unifiedResults.songs,
                                                            searchViewModel = searchViewModel,
                                                            onPlayed = { recordRecent(row.song.toRecentItem()) },
                                                            onLongClick = { menuSong = row.song }
                                                        )
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
                                                if (unifiedResults.shows.isNotEmpty()) {
                                                    item { SearchSectionHeader("פודקאסטים") }
                                                    items(unifiedResults.shows.size) { i ->
                                                        val show = unifiedResults.shows[i]
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
                                                if (unifiedResults.episodes.isNotEmpty()) {
                                                    item { SearchSectionHeader("פרקים") }
                                                    items(unifiedResults.episodes.size) { i ->
                                                        val ep = unifiedResults.episodes[i]
                                                        SearchSongRow(
                                                            ep,
                                                            unifiedResults.episodes,
                                                            searchViewModel,
                                                            onPlayed = {
                                                                recordRecent(ep.toRecentItem())
                                                            },
                                                            onLongClick = { menuSong = ep }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchIdleBar(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(8.dp))
            .height(48.dp)
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 12.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_search_big),
            tint = Color(0xFF121212),
            contentDescription = "חיפוש",
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "לאיזה תוכן תרצו להאזין?",
            color = Color(0xFF242424),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun SearchActiveBar(
    text: String,
    focusRequester: FocusRequester,
    onBackClick: () -> Unit,
    onTextChange: (String) -> Unit,
    onClearClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF282828))
            .padding(horizontal = 12.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "חזרה",
            tint = Color.White,
            modifier = Modifier
                .size(24.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onBackClick() }
        )

        Spacer(modifier = Modifier.width(12.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 1.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                textStyle = TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    textDirection = TextDirection.ContentOrRtl
                ),
                cursorBrush = SolidColor(Color(0xFF1ED760)),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = "לאיזה תוכן תרצו להאזין?",
                                color = Color(0xFFB3B3B3),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier.padding(start = 3.dp)
                            )
                        }
                        innerTextField()
                    }
                }
            )
        }

        if (text.isNotBlank()) {
            Icon(
                imageVector = Icons.Default.Close,
                tint = Color.White,
                contentDescription = "נקה",
                modifier = Modifier
                    .size(20.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClearClick() }
            )
        }
    }
}

@Composable
fun CategoryGridSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        browseCategories.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { cat ->
                    BrowseCategoryTile(
                        name = cat.title,
                        color = cat.color,
                        coverUrl = cat.coverUrl,
                        modifier = Modifier.weight(1f)
                    ) {
                        onCategoryClick(cat.query, cat.title)
                    }
                }
                if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    coverUrl: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        if (coverUrl.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-10).dp, y = 10.dp)
                    .rotate(-22f)
            ) {
                GlideImage(
                    model = coverUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
    }
}

@Composable
fun SearchEmptyMessage(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = Color.Gray,
            fontSize = 14.sp,
        )
    }
}

@Composable
fun SearchCategoryFilterChips(
    selectedCategory: SearchCategory?,
    onSelectCategory: (SearchCategory) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val categories = SearchCategory.values()
        items(categories.size) { index ->
            val category = categories[index]
            val isSelected = selectedCategory == category
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (isSelected) Color(0xFF1ED760) else Color(0xFF2A2A2A))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelectCategory(category) }
                    .padding(horizontal = 15.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category.title,
                    color = if (isSelected) Color.Black else Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

sealed class SearchRow {
    data class Song(val song: SongsModel) : SearchRow()
    data class Artist(val artist: com.music.spotui.data.entity.ArtistsModel) : SearchRow()
    data class Album(val album: com.music.spotui.data.entity.AlbumsModel) : SearchRow()
}

private fun mixSearchResults(results: UnifiedSearchResults): List<SearchRow> {
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
                "song" -> "שיר • ${item.singer}"
                "artist" -> "אמן"
                "album" -> "אלבום • ${item.singer}"
                "show" -> "פודקאסט" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
                "playlist" -> "פלייליסט" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
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
            contentDescription = "הסר",
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
                Text(text = "שיר • ${song.singer}", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
                text = "פודקאסט" + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""),
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
            Text(text = "אמן", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
            Text(text = "אלבום • ${album.artists}", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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


