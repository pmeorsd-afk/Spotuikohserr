package com.music.spotui.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.likedSongsRevision
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.components.SpotifyPlusButton
import com.music.spotui.ui.viewmodel.AddScreenView
import com.music.spotui.ui.viewmodel.AddSearchFilter
import com.music.spotui.ui.viewmodel.AddTab
import com.music.spotui.ui.viewmodel.AddToLikedSongsViewModel

private val SpotifyGreen = Color(0xFF1ED760)
private val SpotifyDarkBg = Color(0xFF121212)
private val CardBg = Color(0xFF282828)
private val TextSecondary = Color(0xFFB3B3B3)

@Composable
fun AddToLikedSongsScreen(
    navController: NavController,
    viewModel: AddToLikedSongsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val revision by likedSongsRevision.collectAsState()

    BackHandler(enabled = true) {
        when (viewModel.currentView) {
            AddScreenView.ARTIST_DETAIL -> viewModel.currentView = AddScreenView.SEARCH
            AddScreenView.SEARCH -> viewModel.currentView = AddScreenView.MAIN_FEED
            AddScreenView.MAIN_FEED -> navController.navigateUp()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SpotifyDarkBg)
        ) {
            AnimatedContent(
                targetState = viewModel.currentView,
                transitionSpec = {
                    fadeIn(animationSpec = spring()) togetherWith fadeOut(animationSpec = spring())
                },
                label = "AddScreenViewTransition"
            ) { targetView ->
                when (targetView) {
                    AddScreenView.MAIN_FEED -> {
                        MainFeedView(
                            viewModel = viewModel,
                            onBack = { navController.navigateUp() },
                            onOpenSearch = { viewModel.currentView = AddScreenView.SEARCH },
                            revision = revision,
                            context = context
                        )
                    }
                    AddScreenView.SEARCH -> {
                        SearchView(
                            viewModel = viewModel,
                            onBack = { viewModel.currentView = AddScreenView.MAIN_FEED },
                            revision = revision,
                            context = context
                        )
                    }
                    AddScreenView.ARTIST_DETAIL -> {
                        ArtistDetailView(
                            viewModel = viewModel,
                            onBack = { viewModel.currentView = AddScreenView.SEARCH },
                            revision = revision,
                            context = context
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1. Main Feed View: "הוספה לשירים שאהבתם"
 * Tabs: "שירים" & "הושמעו לאחרונה", Infinite Scroll, Bottom Search Pill
 */
@Composable
private fun MainFeedView(
    viewModel: AddToLikedSongsViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    revision: Long,
    context: Context
) {
    val recommendedSongs by viewModel.recommendedSongs.collectAsState()
    val recentSongs by viewModel.recentSongs.collectAsState()
    val isLoadingMore by viewModel.isLoadingMore.collectAsState()
    val listState = rememberLazyListState()

    // Detect infinite scroll reaching bottom
    val shouldLoadMore = remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && viewModel.activeTab == AddTab.SONGS) {
            viewModel.loadMoreRecommendations()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // TopBar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "חזרה",
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.CenterStart)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack
                        )
                )

                Text(
                    text = "הוספה לשירים שאהבתם",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Tab Pills Row (RTL)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pill 1: שירים
                TabPill(
                    text = "שירים",
                    isSelected = viewModel.activeTab == AddTab.SONGS,
                    onClick = { viewModel.activeTab = AddTab.SONGS }
                )

                Spacer(modifier = Modifier.width(8.dp))

                // Pill 2: הושמעו לאחרונה
                TabPill(
                    text = "הושמעו לאחרונה",
                    isSelected = viewModel.activeTab == AddTab.RECENT,
                    onClick = {
                        viewModel.activeTab = AddTab.RECENT
                        viewModel.loadRecentSongs(context)
                    }
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Songs List
            val displayList = if (viewModel.activeTab == AddTab.SONGS) recommendedSongs else recentSongs

            if (displayList.isEmpty() && viewModel.activeTab == AddTab.RECENT) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "אין שירים שהושמעו לאחרונה",
                        color = TextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp)
                ) {
                    itemsIndexed(
                        items = displayList,
                        key = { idx, song ->
                            if (song.spotifyTrackId.isNotBlank()) "sp_${song.spotifyTrackId}_$idx"
                            else if (song.id > 0) "id_${song.id}_$idx"
                            else "t_${song.title}_$idx"
                        }
                    ) { _, song ->
                        val isLiked = remember(song, revision) { isSongLiked(context, song) }
                        val isPlayingThis = viewModel.currentSongId.value == song.id
                        val isPlaying = viewModel.currentSongPlayingState.value

                        SongAddRow(
                            song = song,
                            isLiked = isLiked,
                            isPlayingThis = isPlayingThis && isPlaying,
                            onPlay = { viewModel.playSong(song, context) },
                            onToggleLike = { viewModel.toggleLike(song, context) }
                        )
                    }

                    if (viewModel.activeTab == AddTab.SONGS && isLoadingMore) {
                        item(key = "loading_more_item") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = SpotifyGreen,
                                    strokeWidth = 2.dp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Floating Bottom Search Pill ("מה תרצו להוסיף?")
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .shadow(elevation = 10.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF242424))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onOpenSearch
                    ),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "חיפוש",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "מה תרצו להוסיף?",
                        color = Color(0xFFB3B3B3),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

/**
 * 2. Search View: "איתור"
 * Recent searches, live search results, 4 filter pills, floating bottom search bar
 */
@Composable
private fun SearchView(
    viewModel: AddToLikedSongsViewModel,
    onBack: () -> Unit,
    revision: Long,
    context: Context
) {
    val searchResultsResp by viewModel.searchResults.collectAsState()
    val recentSearches by viewModel.recentSearches.collectAsState()
    val isQueryBlank = viewModel.searchQuery.isBlank()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // TopBar: "איתור"
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "חזרה",
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.CenterStart)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onBack
                        )
                )

                Text(
                    text = "איתור",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Filter Pills Row (When search query has results / not blank)
            if (!isQueryBlank) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (viewModel.selectedFilter != null) {
                        // Single active filter with dismiss 'X'
                        val active = viewModel.selectedFilter!!
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .height(32.dp)
                                .clip(CircleShape)
                                .background(SpotifyGreen)
                                .clickable { viewModel.selectedFilter = null }
                                .padding(start = 14.dp, end = 8.dp)
                        ) {
                            Text(
                                text = active.title,
                                color = Color.Black,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "נקה סינון",
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        // All 4 pills
                        AddSearchFilter.values().forEach { filter ->
                            FilterPill(
                                text = filter.title,
                                isSelected = false,
                                onClick = { viewModel.selectedFilter = filter }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Content: Recent searches or Search Results
            if (isQueryBlank) {
                // Empty query: Show "חיפושים אחרונים"
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)
                ) {
                    if (recentSearches.isNotEmpty()) {
                        item {
                            Text(
                                text = "חיפושים אחרונים",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        items(recentSearches) { term ->
                            RecentSearchRow(
                                title = term,
                                subtitle = "אמן",
                                onClick = {
                                    viewModel.openArtist(term)
                                }
                            )
                        }

                        item {
                            Spacer(modifier = Modifier.height(24.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .border(BorderStroke(1.dp, Color(0xFF555555)), CircleShape)
                                        .clickable { viewModel.clearRecentSearches() }
                                        .padding(horizontal = 24.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "ניקוי החיפושים האחרונים",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-empty query: Show Search Results
                when (val resp = searchResultsResp) {
                    is Response.Loading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = SpotifyGreen, modifier = Modifier.size(36.dp))
                        }
                    }
                    is Response.Error -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "שגיאה בחיפוש", color = TextSecondary, fontSize = 14.sp)
                        }
                    }
                    is Response.Success -> {
                        val results = resp.data
                        val filter = viewModel.selectedFilter

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentPadding = PaddingValues(top = 8.dp, bottom = 100.dp)
                        ) {
                            // 1. Songs (if filter is null or SONGS)
                            if (filter == null || filter == AddSearchFilter.SONGS) {
                                items(results.songs) { song ->
                                    val isLiked = remember(song, revision) { isSongLiked(context, song) }
                                    val isPlayingThis = viewModel.currentSongId.value == song.id
                                    val isPlaying = viewModel.currentSongPlayingState.value

                                    SongAddRow(
                                        song = song,
                                        isLiked = isLiked,
                                        isPlayingThis = isPlayingThis && isPlaying,
                                        onPlay = {
                                            viewModel.addRecentSearch(song.title)
                                            viewModel.playSong(song, context)
                                        },
                                        onToggleLike = { viewModel.toggleLike(song, context) }
                                    )
                                }
                            }

                            // 2. Artists (if filter is null or ARTISTS)
                            if (filter == null || filter == AddSearchFilter.ARTISTS) {
                                items(results.artists) { artist ->
                                    ArtistSearchRow(
                                        artist = artist,
                                        onClick = {
                                            viewModel.addRecentSearch(artist.name)
                                            viewModel.openArtist(artist.name, artist.id)
                                        }
                                    )
                                }
                            }

                            // 3. Playlists (if filter is null or PLAYLISTS)
                            if (filter == null || filter == AddSearchFilter.PLAYLISTS) {
                                items(results.shows) { show ->
                                    GenericMediaRow(
                                        title = show.name,
                                        subtitle = "פלייליסט",
                                        coverUri = show.coverUri
                                    )
                                }
                            }

                            // 4. Albums (if filter is null or ALBUMS)
                            if (filter == null || filter == AddSearchFilter.ALBUMS) {
                                items(results.albums) { album ->
                                    GenericMediaRow(
                                        title = album.name,
                                        subtitle = "אלבום • ${album.artists}",
                                        coverUri = album.coverUri
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Floating Bottom Search Input Pill
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .shadow(elevation = 10.dp, shape = CircleShape)
                    .clip(CircleShape)
                    .background(Color(0xFF242424))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), CircleShape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "חיפוש",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (viewModel.searchQuery.isEmpty()) {
                        Text(
                            text = "חיפוש",
                            color = Color(0xFFB3B3B3),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    BasicTextField(
                        value = viewModel.searchQuery,
                        onValueChange = { viewModel.search(it) },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(SpotifyGreen),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (viewModel.searchQuery.isNotEmpty()) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "נקה",
                        tint = Color(0xFFB3B3B3),
                        modifier = Modifier
                            .size(18.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { viewModel.search("") }
                            )
                    )
                }
            }
        }
    }
}

/**
 * 3. Artist Detail View: Overview of artist popular songs, albums, and singles
 */
@Composable
private fun ArtistDetailView(
    viewModel: AddToLikedSongsViewModel,
    onBack: () -> Unit,
    revision: Long,
    context: Context
) {
    val artistOverviewResp by viewModel.artistOverview.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyDarkBg)
    ) {
        // TopBar: Artist Name
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .padding(horizontal = 16.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "חזרה",
                tint = Color.White,
                modifier = Modifier
                    .size(24.dp)
                    .align(Alignment.CenterStart)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onBack
                    )
            )

            Text(
                text = viewModel.selectedArtistName,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        when (val resp = artistOverviewResp) {
            is Response.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = SpotifyGreen, modifier = Modifier.size(36.dp))
                }
            }
            is Response.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "שגיאה בטעינת אמן", color = TextSecondary, fontSize = 14.sp)
                }
            }
            is Response.Success -> {
                val overview = resp.data
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp)
                ) {
                    // Section: פופולריים
                    if (overview.topTracks.isNotEmpty()) {
                        item {
                            Text(
                                text = "פופולריים",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }

                        items(overview.topTracks) { trackUi ->
                            val song = trackUi.song
                            val isLiked = remember(song, revision) { isSongLiked(context, song) }
                            val isPlayingThis = viewModel.currentSongId.value == song.id
                            val isPlaying = viewModel.currentSongPlayingState.value

                            SongAddRow(
                                song = song,
                                isLiked = isLiked,
                                isPlayingThis = isPlayingThis && isPlaying,
                                onPlay = { viewModel.playSong(song, context) },
                                onToggleLike = { viewModel.toggleLike(song, context) }
                            )
                        }
                    }

                    // Section: אלבומים
                    if (overview.popularReleases.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "אלבומים",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }

                        items(overview.popularReleases) { album ->
                            GenericMediaRow(
                                title = album.name,
                                subtitle = "אלבום • ${album.artists}",
                                coverUri = album.coverUri
                            )
                        }
                    }

                    // Section: סינגלים ואי־פי
                    if (overview.appearsOn.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "סינגלים",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }

                        items(overview.appearsOn) { release ->
                            GenericMediaRow(
                                title = release.name,
                                subtitle = "סינגל • ${release.artists}",
                                coverUri = release.coverUri
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual Song Row with Cover (play overlay), Title, Artist, and SpotifyPlusButton
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun SongAddRow(
    song: SongsModel,
    isLiked: Boolean,
    isPlayingThis: Boolean,
    onPlay: () -> Unit,
    onToggleLike: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onPlay
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Right: Cover with subtle Play icon overlay
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
        ) {
            GlideImage(
                model = song.coverUri,
                contentDescription = song.title,
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                modifier = Modifier.fillMaxSize()
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.28f))
            ) {
                Icon(
                    painter = if (isPlayingThis) painterResource(R.drawable.ic_playing) else painterResource(R.drawable.play_svgrepo_com),
                    contentDescription = if (isPlayingThis) "השהה" else "נגן",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Center: Title & Singer
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = song.title,
                color = if (isPlayingThis) SpotifyGreen else Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = song.singer,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Left: Plus button with signature bouncy spring animation
        SpotifyPlusButton(
            isLiked = isLiked,
            onClick = onToggleLike,
            size = 26.dp
        )
    }
}

/**
 * Filter / Tab Pill with authentic Spotify styling
 */
@Composable
private fun TabPill(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (isSelected) SpotifyGreen else CardBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun FilterPill(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (isSelected) SpotifyGreen else CardBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

/**
 * Artist row with circular avatar and navigation arrow
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ArtistSearchRow(
    artist: ArtistsModel,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlideImage(
            model = artist.coverUri,
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = artist.name,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "אמן",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "פרטים",
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Recent search row in search view
 */
@Composable
private fun RecentSearchRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(CardBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Generic Media Row for Albums, Playlists
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun GenericMediaRow(
    title: String,
    subtitle: String,
    coverUri: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlideImage(
            model = coverUri,
            contentDescription = title,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
