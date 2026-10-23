package com.music.spotui.ui.screens

import com.music.spotui.ui.search.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import com.music.spotui.ui.components.SavedInSheet
import com.music.spotui.data.preferences.likedSongsRevision
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
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
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.addLikedSongId
import com.music.spotui.data.preferences.isSavedInAnyDestination
import com.music.spotui.data.preferences.removeLikedSong
import com.music.spotui.data.preferences.removeLikedSongId
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.NoImagePlaceholder
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.components.SearchCategoryCoverMenuSheet
import com.music.spotui.util.SearchArtworkPermissions
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.categoryRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.podcastHubRoute
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

// Category artwork verified against Spotify’s public Hebrew browse grid (2026-10-19).
// No invented image IDs: unavailable regional categories keep the placeholder.
private val browseCategories: List<BrowseCategory> = listOf(
    BrowseCategory("פודקאסטים", Color(0xFF006450), "פודקאסטים", "https://i.scdn.co/image/ab6765630000ba8a81f07e1ead0317ee3c285bfa"),
    BrowseCategory("מוזיקה", Color(0xFFDC148C), "מוזיקה", "https://i.scdn.co/image/ab67fb8200005caf474a477debc822a3a45c5acb"),
    BrowseCategory("במיוחד בשבילכם", Color(0xFF8D67AB), "במיוחד בשבילכם", "https://misc.scdn.co/liked-songs/liked-songs-640.png"),
    BrowseCategory("אירועים חיים", Color(0xFF8400E7), "אירועים חיים", "https://concerts.spotifycdn.com/images/live-events_category-image.jpg"),
    BrowseCategory("מה חדש?", Color(0xFF608108), "מה חדש", "https://i.scdn.co/image/ab67706f000000027ea4d505212b9de1f72c5112"),
    BrowseCategory("ריליסים שצפויים בקרוב", Color(0xFF056952), "ריליסים", ""),
    BrowseCategory("היפ-הופ", Color(0xFF477D95), "היפ הופ", "https://i.scdn.co/image/ab676d630000e1f065710a92b40492f6e3dbb819"),
    BrowseCategory("פופ", Color(0xFF509BF5), "פופ", "https://i.scdn.co/image/ab67fb8200005caf66d545e6a69d0bfe8bd1e825"),
    BrowseCategory("לטיני", Color(0xFF1E3264), "לטיני", "https://i.scdn.co/image/ab67fb8200005caf3a44e7ae3d808c220898185c"),
    BrowseCategory("רוק", Color(0xFF006450), "רוק", "https://i.scdn.co/image/ab67fb8200005cafda4c849095796a9e5d2c4ddb"),
    BrowseCategory("ישראלי", Color(0xFFE1118C), "ישראלי", ""),
    BrowseCategory("מזרחית", Color(0xFFD84000), "מזרחית", ""),
    BrowseCategory("יהודי ודתי", Color(0xFFBA5D07), "חסידי יהודי", ""),
    BrowseCategory("אימון וכושר", Color(0xFF777777), "אימון", "https://i.scdn.co/image/ab67fb8200005caf6af6d83c78493644c9b0627b"),
    BrowseCategory("צ'יל ורגיעה", Color(0xFF1E3264), "צ'יל", "https://i.scdn.co/image/ab67fb8200005caf330ca3a3bfaf8b18407fb33e"),
    BrowseCategory("דאנס ואלקטרוני", Color(0xFF056952), "אלקטרוני", "https://i.scdn.co/image/ab67fb8200005caf26ada793217994216c79dad8"),
    BrowseCategory("אינדי", Color(0xFF608108), "אינדי", "https://i.scdn.co/image/ab67fb8200005cafa1a252e3a815b65778d8c2aa"),
    BrowseCategory("מצעדים ולהיטים", Color(0xFF8C1932), "מצעדים", "https://charts-images.scdn.co/assets/locale_en/regional/weekly/region_global_default.jpg"),
    BrowseCategory("ג'אז", Color(0xFF503750), "ג'אז", "https://i.scdn.co/image/ab67fb8200005cafa1bb187ec2f4606aa7101bad"),
    BrowseCategory("קלאסי", Color(0xFF7D4B98), "קלאסי", "https://i.scdn.co/image/ab67fb8200005caf4597370d1058e1ec3c1a56fa"),
    BrowseCategory("שינה ומנוחה", Color(0xFF1E3264), "שינה", "https://i.scdn.co/image/ab67fb8200005caf1cef0cee1e498abb8e74955f"),
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
    val whitelistVersion by com.music.spotui.util.KosherWhitelistManager.versionState
    SearchImageApprovalRefresh(androidx.compose.ui.platform.LocalLifecycleOwner.current)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var input by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val text = input.text
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var isSubmitted by rememberSaveable { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<SearchCategory?>(null) }
    var recents by remember { mutableStateOf(com.music.spotui.data.preferences.getRecentItems(context)) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    val suggestions by searchViewModel.suggestions.collectAsState()
    val searching by searchViewModel.searching.collectAsState()

    LaunchedEffect(Unit) { if (text.isNotBlank()) searchViewModel.search(text) }

    BackHandler(enabled = searchActive) {
        if (isSubmitted) {
            isSubmitted = false
        } else {
            searchActive = false
            input = TextFieldValue()
            isSubmitted = false
            searchViewModel.search("")
            keyboardController?.hide()
        }
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
            SongPlayer.prefetchSongs(unifiedResults.songs, context, count = 3)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(if (searchActive || text.isNotBlank()) SearchHeaderColor else Color(AppBackground.toArgb()))
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp)
                    ) {
                        SearchHeaderRow()
                        SearchIdleBar(
                            onClick = { searchActive = true }
                        )
                    }
                } else {
                    SearchActiveBar(
                        value = input,
                        focusRequester = focusRequester,
                        onBackClick = {
                            if (isSubmitted) {
                                isSubmitted = false
                            } else {
                                searchActive = false
                                input = TextFieldValue()
                                isSubmitted = false
                                searchViewModel.search("")
                                keyboardController?.hide()
                            }
                        },
                        onTextChange = {
                            input = it
                            isSubmitted = false
                            if (it.text.isBlank()) {
                                selectedCategory = null
                            }
                            searchViewModel.search(it.text)
                        },
                        onClearClick = {
                            input = TextFieldValue()
                            isSubmitted = false
                            selectedCategory = null
                            searchViewModel.search("")
                        },
                        onSearch = {
                            if (text.isNotBlank()) {
                                isSubmitted = true
                                keyboardController?.hide()
                                searchViewModel.search(text)
                            }
                        }
                    )
                }
            }

            AnimatedVisibility(
                visible = (searchActive || text.isNotBlank()) && text.isNotBlank() && isSubmitted,
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
                    .fillMaxWidth()
                    .background(if (searchActive || text.isNotBlank()) SearchBodyColor else Color(AppBackground.toArgb())),
                label = "SearchBodyTransition"
            ) { isSearching ->
                if (!isSearching) {
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 130.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        item {
                            SearchDiscoverySection()
                        }
                        item {
                            CategoryGridSection { genre, title ->
                                if (genre == "פודקאסטים" || title == "פודקאסטים") {
                                    navController.navigate(podcastHubRoute())
                                } else {
                                    navController.navigate(categoryRoute(genre, title))
                                }
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
                                    Text("לאחרונה", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.fillMaxWidth().padding(16.dp, 16.dp, 16.dp, 8.dp))
                                }
                                items(recents.size, key = { "${recents[it].type}:${recents[it].key}" }) { i ->
                                    val item = recents[i]
                                    RecentItemRow(
                                        item = item,
                                        onClick = {
                                            val isOldEpisode = item.type == "song" && (item.songUrl.startsWith("episode:") || item.key.startsWith("episode:"))
                                            val effectiveType = if (isOldEpisode) "episode" else item.type
                                            when (effectiveType) {
                                                "song", "episode" -> {
                                                    val song = item.toSongModel()
                                                    searchViewModel.startRadioFromSong(song)
                                                    SongPlayer.playSong(song.url, context)
                                                    searchViewModel.updateSongState(
                                                        coverUri = song.coverUri,
                                                        title = song.title,
                                                        singer = song.singer,
                                                        playingState = true,
                                                        songId = song.id,
                                                        songIndex = 0,
                                                        album = song.album,
                                                    )
                                                }
                                                "artist" -> navController.navigate(artistRoute(item.name, item.key.takeIf { it != item.name }.orEmpty()))
                                                "album" -> navController.navigate(albumRoute(item.name, item.singer, albumId = item.collectionId))
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
                                item(key = "clear-search-history") {
                                    ClearSearchHistoryButton {
                                        com.music.spotui.data.preferences.clearRecentItems(context)
                                        recents = emptyList()
                                    }
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
                        } else if (!isSubmitted) {
                            if (suggestions.isNotEmpty()) {
                                items(suggestions.size) { i ->
                                    val suggestion = suggestions[i]
                                    SearchSuggestionRow(
                                        suggestion = suggestion,
                                        query = text,
                                        onClick = {
                                            input = TextFieldValue(suggestion, TextRange(suggestion.length))
                                            isSubmitted = true
                                            keyboardController?.hide()
                                            searchViewModel.search(suggestion)
                                        },
                                        onInsert = {
                                            input = TextFieldValue(suggestion, TextRange(suggestion.length))
                                            isSubmitted = false
                                            searchViewModel.search(suggestion)
                                            focusRequester.requestFocus()
                                            keyboardController?.show()
                                        }
                                    )
                                }
                            }
                            if (mixed.isEmpty()) {
                                if (searching) item {
                                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF1ED760))
                                    }
                                } else if (unifiedResultsResp is Response.Error) item {
                                    androidx.compose.material3.TextButton(onClick = { searchViewModel.search(text, force = true) }) {
                                        Text("החיפוש לא נטען — ניסיון נוסף", color = SearchMuted)
                                    }
                                } else item { SearchEmptyMessage("לא נמצאו תוצאות עבור \"$text\"") }
                            }
                            if (mixed.isNotEmpty()) {
                                items(mixed.size) { i ->
                                    when (val row = mixed[i]) {
                                        is SearchRow.Song -> SearchSongRow(
                                            song = row.song,
                                            songList = unifiedResults.songs,
                                            searchViewModel = searchViewModel,
                                            onPlayed = { recordRecent(row.song.toRecentItem()) },
                                            onLongClick = { menuSong = row.song }
                                        )
                                        is SearchRow.Playlist -> SearchSpotifyPlaylistRow(row.playlist) {
                                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                                type = "playlist", key = row.playlist.spotifyId, name = row.playlist.name,
                                                singer = row.playlist.subtitle, image = row.playlist.coverUri))
                                            navController.navigate(playlistRoute(row.playlist.spotifyId, row.playlist.name))
                                        }
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
                                                key = row.album.spotifyId.ifBlank { row.album.name },
                                                collectionId = row.album.spotifyId,
                                                name = row.album.name,
                                                singer = row.album.artists,
                                                image = row.album.coverUri,
                                            ))
                                            navController.navigate(albumRoute(row.album.name, row.album.artists, albumId = row.album.spotifyId))
                                        }
                                        is SearchRow.Show -> SearchShowRow(row.show) {
                                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                                type = "show",
                                                key = row.show.id,
                                                name = row.show.name,
                                                singer = row.show.publisher,
                                                image = row.show.coverUri,
                                            ))
                                            navController.navigate(showRoute(row.show.id, row.show.name))
                                        }
                                        is SearchRow.Episode -> SearchSongRow(
                                            song = row.episode,
                                            songList = unifiedResults.episodes,
                                            searchViewModel = searchViewModel,
                                            onPlayed = { recordRecent(row.episode.toRecentItem()) },
                                            onLongClick = { menuSong = row.episode }
                                        )
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
                                            items(unifiedResults.spotifyPlaylists.size) { index ->
                                                val playlist = unifiedResults.spotifyPlaylists[index]
                                                SearchSpotifyPlaylistRow(playlist) {
                                                    recordRecent(com.music.spotui.data.preferences.RecentItem(
                                                        type = "playlist", key = playlist.spotifyId, name = playlist.name,
                                                        singer = playlist.subtitle, image = playlist.coverUri))
                                                    navController.navigate(playlistRoute(playlist.spotifyId, playlist.name))
                                                }
                                            }
                                            if (unifiedResults.playlists.isEmpty() && unifiedResults.spotifyPlaylists.isEmpty()) {
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
                                                                    key = album.spotifyId.ifBlank { album.name },
                                                                    collectionId = album.spotifyId,
                                                                    name = album.name,
                                                                    singer = album.artists,
                                                                    image = album.coverUri,
                                                                )
                                                            )
                                                            navController.navigate(albumRoute(album.name, album.artists, albumId = album.spotifyId))
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
                                                items(mixed.size) { i ->
                                                    when (val row = mixed[i]) {
                                                        is SearchRow.Song -> SearchSongRow(
                                                            song = row.song,
                                                            songList = unifiedResults.songs,
                                                            searchViewModel = searchViewModel,
                                                            onPlayed = { recordRecent(row.song.toRecentItem()) },
                                                            onLongClick = { menuSong = row.song }
                                                        )
                                                        is SearchRow.Playlist -> SearchSpotifyPlaylistRow(row.playlist) {
                                                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                                                type = "playlist", key = row.playlist.spotifyId, name = row.playlist.name,
                                                                singer = row.playlist.subtitle, image = row.playlist.coverUri))
                                                            navController.navigate(playlistRoute(row.playlist.spotifyId, row.playlist.name))
                                                        }
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
                                                                key = row.album.spotifyId.ifBlank { row.album.name },
                                                                collectionId = row.album.spotifyId,
                                                                name = row.album.name,
                                                                singer = row.album.artists,
                                                                image = row.album.coverUri,
                                                            ))
                                                            navController.navigate(albumRoute(row.album.name, row.album.artists, albumId = row.album.spotifyId))
                                                        }
                                                        is SearchRow.Show -> SearchShowRow(row.show) {
                                                            recordRecent(com.music.spotui.data.preferences.RecentItem(
                                                                type = "show",
                                                                key = row.show.id,
                                                                name = row.show.name,
                                                                singer = row.show.publisher,
                                                                image = row.show.coverUri,
                                                            ))
                                                            navController.navigate(showRoute(row.show.id, row.show.name))
                                                        }
                                                        is SearchRow.Episode -> SearchSongRow(
                                                            song = row.episode,
                                                            songList = unifiedResults.episodes,
                                                            searchViewModel = searchViewModel,
                                                            onPlayed = { recordRecent(row.episode.toRecentItem()) },
                                                            onLongClick = { menuSong = row.episode }
                                                        )
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
fun SearchHeaderRow() {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
    val initial = com.music.spotui.data.api.ProfileCache.name
        ?.trim()?.firstOrNull()?.uppercase()?.takeIf { it != "•" } ?: "D"

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp)
    ) {
        // User avatar (Right side in RTL) - non-clickable for now
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8622C))
        ) {
            if (avatarUrl != null && avatarUrl.isNotBlank()) {
                GlideImage(
                    model = avatarUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = "Profile",
                    modifier = Modifier.size(32.dp),
                )
            } else {
                Text(
                    text = initial,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Title "חיפוש"
        Text(
            text = "חיפוש",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Camera / image icon (Left side in RTL) - non-clickable for now
        Icon(
            painter = painterResource(id = R.drawable.ic_camera),
            contentDescription = "מצלמה",
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun SearchIdleBar(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .height(48.dp)
            .background(Color.White)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 14.dp)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_search_big),
            tint = Color(0xFF121212),
            contentDescription = "חיפוש",
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "לאיזה תוכן תרצו להאזין?",
            color = Color(0xFF535353),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

data class DiscoveryCardItem(
    val tag: String,
)

private val sampleDiscoveryCards: List<DiscoveryCardItem> = listOf(
    DiscoveryCardItem("#פופ ישראלי"),
    DiscoveryCardItem("#hebrew pop"),
    DiscoveryCardItem("#פופ אקוסטי"),
    DiscoveryCardItem("#מוזיקה יהודית"),
    DiscoveryCardItem("#להיטים"),
)

@Composable
fun SearchDiscoverySection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
    ) {
        Text(
            text = "רוצים לגלות משהו חדש?",
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(sampleDiscoveryCards.size) { index ->
                val card = sampleDiscoveryCards[index]
                DiscoveryCard(card = card)
            }
        }
    }
}

@Composable
fun DiscoveryCard(card: DiscoveryCardItem) {
    Box(
        modifier = Modifier
            .width(164.dp)
            .height(246.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF242424))
    ) {
        // Kosher placeholder image (clean green stripes, no network)
        NoImagePlaceholder(
            modifier = Modifier.fillMaxSize()
        )

        // Center "בקרוב!" overlay (Big Spotify-styled badge)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 24.dp)
                .background(
                    color = Color(0xDD121212),
                    shape = RoundedCornerShape(12.dp)
                )
                .border(
                    width = 1.5.dp,
                    color = Color(0xFF1ED760),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "בקרוב!",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }

        // Bottom gradient overlay
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(80.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000))
                    )
                )
        )

        // Bottom hashtag label
        Text(
            text = card.tag,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }
}

@Composable
fun CategoryGridSection(onCategoryClick: (genre: String, title: String) -> Unit) {
    var menuCategory by remember { mutableStateOf<BrowseCategory?>(null) }
    menuCategory?.let { category ->
        SearchCategoryCoverMenuSheet(category.title, category.coverUrl, onDismiss = { menuCategory = null })
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        browseCategories.chunked(3).forEach { rowItems ->
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
                        onLongClick = { menuCategory = cat },
                        modifier = Modifier.weight(1f)
                    ) {
                        onCategoryClick(cat.query, cat.title)
                    }
                }
                repeat(3 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BrowseCategoryTile(
    name: String,
    color: Color,
    coverUrl: String,
    modifier: Modifier = Modifier,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(100.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
                onLongClickLabel = "אפשרויות תמונת הקטגוריה",
                onLongClick = onLongClick,
            ),
    ) {
        if (coverUrl.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-6).dp, y = 6.dp)
                    .rotate(25f)
            ) {
                GlideImage(
                    model = coverUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = null,
                    isAllowed = SearchArtworkPermissions.isAllowedInSearchCategory(coverUrl),
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp),
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
    data class Show(val show: com.music.spotui.data.entity.PodcastModel) : SearchRow()
    data class Episode(val episode: SongsModel) : SearchRow()
    data class Playlist(val playlist: com.music.spotui.data.entity.LibraryEntry) : SearchRow()
}

private fun mixSearchResults(results: UnifiedSearchResults): List<SearchRow> {
    val songs = results.songs.iterator()
    val artists = results.artists.iterator()
    val albums = results.albums.iterator()
    val shows = results.shows.iterator()
    val episodes = results.episodes.iterator()
    val playlists = results.spotifyPlaylists.iterator()
    val out = ArrayList<SearchRow>()
    while (songs.hasNext() || artists.hasNext() || albums.hasNext() || shows.hasNext() || episodes.hasNext() || playlists.hasNext()) {
        if (shows.hasNext()) out += SearchRow.Show(shows.next())
        repeat(2) { if (songs.hasNext()) out += SearchRow.Song(songs.next()) }
        if (artists.hasNext()) out += SearchRow.Artist(artists.next())
        if (albums.hasNext()) out += SearchRow.Album(albums.next())
        if (episodes.hasNext()) out += SearchRow.Episode(episodes.next())
        if (playlists.hasNext()) out += SearchRow.Playlist(playlists.next())
    }
    return out
}

private fun SongsModel.toRecentItem() = com.music.spotui.data.preferences.RecentItem(
    type = if (mediaType == MediaType.PODCAST_EPISODE) "episode" else "song",
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
    podcastShowId = resolvePodcastShowId(),
)

private fun com.music.spotui.data.preferences.RecentItem.toSongModel(): SongsModel {
    val isEpisode = type == "episode" || songUrl.startsWith("episode:") || key.startsWith("episode:")
    val playUrl = when {
        songUrl.isNotBlank() -> songUrl
        isEpisode -> {
            val show = podcastShowId.ifBlank { "unknown" }
            "episode:$show:$key"
        }
        spotifyTrackId.isNotBlank() -> com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(spotifyTrackId, name, singer)
        else -> key.ifBlank { name }
    }
    val fallbackId = if (songId > 0) songId else (key.ifBlank { name }.hashCode() and 0x7fffffff)
    return SongsModel(
        id = fallbackId,
        title = name,
        album = songAlbum,
        singer = singer,
        coverUri = image,
        url = playUrl,
        spotifyTrackId = spotifyTrackId,
        explicit = explicit,
        durationMs = durationMs,
        mediaType = if (isEpisode) MediaType.PODCAST_EPISODE else MediaType.TRACK,
        podcastShowId = podcastShowId,
    )
}

@Composable
fun RecentItemRow(
    item: com.music.spotui.data.preferences.RecentItem, onClick: () -> Unit, onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val revision by likedSongsRevision.collectAsState()
    val episode = item.type == "episode" || item.songUrl.startsWith("episode:") || item.key.startsWith("episode:")
    val playable = item.type == "song" || episode
    val song = remember(item) { if (playable) item.toSongModel() else null }
    var saving by remember(item) { mutableStateOf(false) }
    val saved = remember(item, revision) { song?.let { isSavedInAnyDestination(context, it) } == true }
    val (collectionSaved, saveCollection) = rememberCollectionSave(item.searchLibraryEntry())
    if (saving && song != null) SavedInSheet(song, context, onDismiss = { saving = false })
    val subtitle = when {
        episode -> "פרק" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
        item.type == "song" -> "שיר • ${item.singer}"
        item.type == "artist" -> "אמן או אמנית"
        item.type == "album" -> "אלבום • ${item.singer}"
        item.type == "show" -> "פודקאסט" + (if (item.singer.isNotBlank()) " • ${item.singer}" else "")
        item.type == "playlist" -> "פלייליסט"
        else -> ""
    }
    SearchEntryRow(item.name, subtitle, item.image,
        com.music.spotui.util.KosherWhitelistManager.isRecentItemWhitelisted(item), artist = item.type == "artist",
        saved = if (playable) saved else collectionSaved, itemKey = "${item.type}:${item.key}", onClick = onClick,
        onSave = if (playable) ({ saving = true }) else saveCollection, onRemove = onRemove)
}

@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun SearchSongRow(
    song: SongsModel, songList: List<SongsModel>, searchViewModel: SearchViewModel,
    onPlayed: () -> Unit = {}, onLongClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val revision by likedSongsRevision.collectAsState()
    val liked = remember(song, revision) { isSavedInAnyDestination(context, song) }
    var saving by remember(song) { mutableStateOf(false) }
    if (saving) SavedInSheet(song, context, onDismiss = { saving = false })
    SearchEntryRow(song.title, "${if (song.isPodcast()) "פרק" else "שיר"} • ${song.singer}", song.coverUri,
        com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(song),
        nameColor = if (song.id == searchViewModel.currentSongId.value) Color(AppPalette.toArgb()) else Color.White,
        saved = liked, itemKey = song, onSave = { saving = true }, onLongClick = onLongClick,
        onClick = {
            onPlayed()
            searchViewModel.startRadioFromSong(song)
            SongPlayer.playSong(song.url, context)
            searchViewModel.updateSongState(song.coverUri, song.title, song.singer, true, song.id, 0, song.album)
        })
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

@Composable
fun SearchShowRow(show: com.music.spotui.data.entity.PodcastModel, onClick: () -> Unit) {
    SearchEntryRow(show.name, "פודקאסט" + (if (show.publisher.isNotBlank()) " • ${show.publisher}" else ""), show.coverUri,
        com.music.spotui.util.KosherWhitelistManager.isPodcastShowWhitelisted(show), onClick = onClick)
}

@Composable
fun SearchArtistRow(artist: com.music.spotui.data.entity.ArtistsModel, onClick: () -> Unit) {
    SearchEntryRow(artist.name, "אמן או אמנית", artist.coverUri,
        com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artist), artist = true, onClick = onClick)
}

@Composable
fun SearchAlbumRow(album: com.music.spotui.data.entity.AlbumsModel, onClick: () -> Unit) {
    val (saved, save) = rememberCollectionSave(album.searchLibraryEntry())
    SearchEntryRow(album.name, "${if (album.type == "single") "סינגל" else "אלבום"} • ${album.artists}", album.coverUri,
        com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(album), saved = saved,
        itemKey = "album:${album.spotifyId}", onSave = save, onClick = onClick)
}

@Composable
fun SearchSpotifyPlaylistRow(item: com.music.spotui.data.entity.LibraryEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val (saved, save) = rememberCollectionSave(item)
    SearchEntryRow(item.name, item.subtitle.replace("Playlist", "פלייליסט"), item.coverUri,
        com.music.spotui.util.KosherWhitelistManager.isLibraryEntryWhitelisted(item, context),
        saved = saved, itemKey = "playlist:${item.spotifyId}", onSave = save, onClick = onClick)
}

@Composable
fun SearchYTPlaylistRow(item: com.metrolist.innertube.models.PlaylistItem, onClick: () -> Unit) {
    val entry = com.music.spotui.data.entity.LibraryEntry("youtube:${item.id}", item.title, "פלייליסט", item.thumbnail.orEmpty(), true)
    val (saved, save) = rememberCollectionSave(entry)
    SearchEntryRow(item.title, "פלייליסט" + item.author?.name?.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty(),
        item.thumbnail.orEmpty(), false, saved = saved, itemKey = entry.spotifyId, onSave = save, onClick = onClick)
}
