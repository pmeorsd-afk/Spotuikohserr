package com.music.spotui.ui.screens

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
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
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.podcastHubRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.data.entity.MediaType
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.theme.GridBackground
import com.music.spotui.ui.viewmodel.HomeViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import com.music.spotui.data.entity.HomeSectionIds
import com.music.spotui.data.entity.HomeSectionType
import java.time.LocalTime
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.rotate



@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeScreen(navController: NavController){

    val homeViewModel : HomeViewModel = hiltViewModel()
    val playerViewModel : PlayerViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val home by homeViewModel.home.collectAsState()
    val albums by homeViewModel.albums.collectAsState()
    val artists by homeViewModel.artists.collectAsState()
    val currentFilter by homeViewModel.currentFilter.collectAsState()
    val followedPodcasts by homeViewModel.followedPodcasts.collectAsState()
    val whitelistVersion by com.music.spotui.util.KosherWhitelistManager.versionState

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    var showPodcastsComingSoonDialog by remember { mutableStateOf(false) }

    com.music.spotui.ui.components.PodcastsComingSoonDialog(
        showDialog = showPodcastsComingSoonDialog,
        onDismiss = { showPodcastsComingSoonDialog = false }
    )

    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                com.music.spotui.data.preferences.notifyLikedSongsChanged()
                homeViewModel.syncLikedSongsCount()
                homeViewModel.syncRecentListening()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
            .statusBarsPadding()
    ) {
        val feed = (home as? Response.Success)?.data
        // Each feed resolves independently — albums (new releases) often succeeds
        // while artists (personalized) gets rate-limited. Render whatever arrived
        // instead of casting blindly (which crashed when one feed was an Error).
        val albumsList = (albums as? Response.Success)?.data.orEmpty()
        val artistsList = (artists as? Response.Success)?.data.orEmpty()

        val inPodcastModes = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS ||
            currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING

        when {
            inPodcastModes -> {
                HomeFeedContent(
                    navController = navController,
                    feed = feed ?: com.music.spotui.data.entity.HomeFeedModel(topGrid = emptyList(), sections = emptyList()),
                    currentFilter = currentFilter,
                    onFilterSelected = { filter ->
                        if (filter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS) {
                            showPodcastsComingSoonDialog = true
                        } else {
                            homeViewModel.setFilter(filter)
                        }
                    },
                    followedPodcasts = followedPodcasts,
                    onPlayTrack = { song -> homeViewModel.playTrack(song, context) },
                    onSongLongClick = { menuSong = it }
                )
            }

            // Preferred: the real personalized Spotify home feed.
            feed != null && feed.sections.isNotEmpty() -> {
                HomeFeedContent(
                    navController = navController,
                    feed = feed,
                    currentFilter = currentFilter,
                    onFilterSelected = { filter ->
                        if (filter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS) {
                            showPodcastsComingSoonDialog = true
                        } else {
                            homeViewModel.setFilter(filter)
                        }
                    },
                    followedPodcasts = followedPodcasts,
                    onPlayTrack = { song -> homeViewModel.playTrack(song, context) },
                    onSongLongClick = { menuSong = it }
                )
            }

            // Still resolving the real personalized home feed. Show the loader even
            // if the new-releases/artists fallbacks already arrived from cache —
            // otherwise the old "Sum up" layout flashes for a beat before the real
            // Spotify-style feed swaps in.
            home is Response.Loading -> {
                Loader()
            }

            // Fallback: home feed errored but new-releases / artists came through.
            albumsList.isNotEmpty() || artistsList.isNotEmpty() -> {
                SumUpHomeScreen(navController = navController, albums = albumsList, artists = artistsList)
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Couldn't load music.\nCheck your connection and try again.",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

enum class HomeSurface {
    TOP_GRID,
    RECENTS,
    RECOMMENDED,
    CATALOG_SECTION
}

private fun onHomeItemClick(
    navController: NavController,
    item: HomeItem,
    onPlayTrack: (SongsModel) -> Unit,
    surface: HomeSurface = HomeSurface.CATALOG_SECTION
) {
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.imageUrl))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name))
            else navController.navigate(albumRoute(item.name, cover = item.imageUrl))
        is HomeItem.LikedSongs -> navController.navigate(Routes.Liked.route)
        is HomeItem.Track -> {
            when (surface) {
                HomeSurface.TOP_GRID, HomeSurface.RECENTS -> {
                    // History and Top Grid represent specific listening items.
                    // Tapping them opens AlbumScreen / ShowScreen displaying ONLY that exact item ("לאותו שיר בלבד").
                    if (item.song.mediaType == MediaType.PODCAST_EPISODE) {
                        val key = com.music.spotui.ui.navigation.ItemDetailRegistry.register(item.song)
                        val showId = item.song.resolvePodcastShowId().ifBlank { item.song.podcastShowId }.ifBlank { item.song.singer }
                        val showTitle = item.song.album.ifBlank { item.song.singer }
                        navController.navigate(showRoute(id = showId, name = showTitle, singleEpisodeId = key))
                    } else {
                        val key = com.music.spotui.ui.navigation.ItemDetailRegistry.register(item.song)
                        val albumOrTitle = item.song.album.ifBlank { item.song.title }
                        navController.navigate(
                            albumRoute(
                                name = albumOrTitle,
                                artist = item.song.singer,
                                cover = item.song.coverUri,
                                singleTrackId = key
                            )
                        )
                    }
                }
                HomeSurface.RECOMMENDED, HomeSurface.CATALOG_SECTION -> {
                    // Contract C (Phase G.1):
                    // "מומלץ להיום" and Catalog sections are NOT history.
                    // Preserve existing navigation behavior to AlbumScreen / ShowScreen.
                    if (item.song.mediaType == MediaType.PODCAST_EPISODE) {
                        val showId = item.song.resolvePodcastShowId().ifBlank { item.song.podcastShowId }.ifBlank { item.song.singer }
                        val showTitle = item.song.album.ifBlank { item.song.singer }
                        navController.navigate(showRoute(showId, showTitle))
                    } else {
                        val albumOrTitle = item.song.album.ifBlank { item.song.title }
                        navController.navigate(albumRoute(albumOrTitle, item.song.singer, item.song.coverUri))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun HomeFeedContent(
    navController: NavController,
    feed: HomeFeedModel,
    currentFilter: com.music.spotui.ui.viewmodel.HomeTabFilter,
    onFilterSelected: (com.music.spotui.ui.viewmodel.HomeTabFilter) -> Unit,
    followedPodcasts: List<com.music.spotui.data.preferences.FollowedPodcastShow>,
    onPlayTrack: (SongsModel) -> Unit,
    onSongLongClick: (SongsModel) -> Unit
) {
    val topGridItems = feed.topGrid.take(4)
    val sections = feed.sections

    LazyColumn(
        contentPadding = PaddingValues(bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(Color(AppBackground.toArgb()))
    ) {
        stickyHeader {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(AppBackground.toArgb()))
            ) {
                HomeHeaderRow(
                    navController = navController,
                    currentFilter = currentFilter,
                    onFilterSelected = onFilterSelected
                )
            }
        }

        when (currentFilter) {
            com.music.spotui.ui.viewmodel.HomeTabFilter.ALL,
            com.music.spotui.ui.viewmodel.HomeTabFilter.MUSIC -> {
                if (topGridItems.isNotEmpty()) {
                    item(key = "top_grid") {
                        HomeTopGrid(navController, topGridItems, onPlayTrack, onSongLongClick)
                    }
                }
                items(sections.size, key = { i -> sections[i].id.ifBlank { "sec_$i" } }) { i ->
                    HomeFeedSection(navController, sections[i], onPlayTrack, onSongLongClick)
                }
            }
            com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS -> {
                if (followedPodcasts.isNotEmpty()) {
                    item(key = "podcast_shortcuts") {
                        FollowedShowsShortcutsRow(
                            shows = followedPodcasts,
                            onShowClick = { show ->
                                navController.navigate(showRoute(show.showId, show.name))
                            },
                            onAddClick = {
                                navController.navigate(podcastHubRoute())
                            }
                        )
                    }
                }
                item(key = "podcast_mode_content") {
                    PodcastModeContent(
                        followedPodcasts = followedPodcasts,
                        onShowClick = { show ->
                            navController.navigate(showRoute(show.showId, show.name))
                        },
                        onBrowsePodcasts = {
                            navController.navigate(podcastHubRoute())
                        }
                    )
                }
            }
            com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING -> {
                if (followedPodcasts.isEmpty()) {
                    item(key = "following_empty_state") {
                        FollowingEmptyState(
                            onBrowsePodcasts = {
                                navController.navigate(podcastHubRoute())
                            }
                        )
                    }
                } else {
                    item(key = "following_shows_header") {
                        FollowedShowsListContent(
                            shows = followedPodcasts,
                            onShowClick = { show ->
                                navController.navigate(showRoute(show.showId, show.name))
                            }
                        )
                    }
                }
            }
        }
    }
}

/** Spotify-style top row: profile avatar on the left, filter pills on the right. */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeHeaderRow(
    navController: NavController,
    currentFilter: com.music.spotui.ui.viewmodel.HomeTabFilter,
    onFilterSelected: (com.music.spotui.ui.viewmodel.HomeTabFilter) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 10.dp),
    ) {
        val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
        val initial = com.music.spotui.data.api.ProfileCache.name
            ?.trim()?.firstOrNull()?.uppercase() ?: "•"
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(34.dp)
                .clip(CircleShape)
                .background(Color(0xFFE8622C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { navController.navigate(Routes.Settings.route) },
        ) {
            if (avatarUrl != null) {
                GlideImage(
                    model = avatarUrl,
                    contentScale = ContentScale.Crop,
                    contentDescription = "Profile",
                    modifier = Modifier.size(34.dp),
                )
            } else {
                Text(
                    text = initial,
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        val inPodcastModes = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS ||
                currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            FilterPill(
                label = "הכול",
                isSelected = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.ALL,
                onClick = { onFilterSelected(com.music.spotui.ui.viewmodel.HomeTabFilter.ALL) }
            )

            FilterPill(
                label = "מוזיקה",
                isSelected = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.MUSIC,
                onClick = { onFilterSelected(com.music.spotui.ui.viewmodel.HomeTabFilter.MUSIC) }
            )

            PodcastFilterGroup(
                currentFilter = currentFilter,
                inPodcastModes = inPodcastModes,
                onFilterSelected = onFilterSelected
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (isSelected) Color(0xFF1ED760) else Color(0xFF2A2A2A))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = 14.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}

@Composable
private fun PodcastFilterGroup(
    currentFilter: com.music.spotui.ui.viewmodel.HomeTabFilter,
    inPodcastModes: Boolean,
    onFilterSelected: (com.music.spotui.ui.viewmodel.HomeTabFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val isPodcastsActive = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS
    val isFollowingActive = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        // Primary Podcast Pill: stays stable in place
        Box(
            modifier = Modifier
                .clip(
                    if (inPodcastModes) {
                        RoundedCornerShape(
                            topStart = 16.dp,
                            bottomStart = 16.dp,
                            topEnd = 0.dp,
                            bottomEnd = 0.dp
                        )
                    } else {
                        RoundedCornerShape(16.dp)
                    }
                )
                .background(
                    when {
                        isPodcastsActive -> Color(0xFF1ED760)
                        isFollowingActive -> Color(0xFF1AB252)
                        else -> Color(0xFF2A2A2A)
                    }
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { onFilterSelected(com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS) }
                )
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "פודקאסטים",
                color = if (inPodcastModes) Color.Black else Color.White,
                fontSize = 14.sp,
                fontWeight = if (inPodcastModes) FontWeight.Bold else FontWeight.Medium,
            )
        }

        // Animated Sub-Pill "במעקב": Slides in smoothly from Right to Left adjacent to "פודקאסטים"
        AnimatedVisibility(
            visible = inPodcastModes,
            enter = fadeIn(animationSpec = tween(200)) +
                    expandHorizontally(
                        expandFrom = Alignment.Start,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) +
                    slideInHorizontally(
                        initialOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ),
            exit = fadeOut(animationSpec = tween(150)) +
                    shrinkHorizontally(
                        shrinkTowards = Alignment.Start,
                        animationSpec = tween(180, easing = FastOutSlowInEasing)
                    ) +
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> fullWidth },
                        animationSpec = tween(180, easing = FastOutSlowInEasing)
                    )
        ) {
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topEnd = 16.dp,
                            bottomEnd = 16.dp,
                            topStart = 0.dp,
                            bottomStart = 0.dp
                        )
                    )
                    .background(
                        if (isFollowingActive) Color(0xFF1ED760)
                        else Color(0xFF333333)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onFilterSelected(com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING) }
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "במעקב",
                    color = if (isFollowingActive) Color.Black else Color.White,
                    fontSize = 14.sp,
                    fontWeight = if (isFollowingActive) FontWeight.Bold else FontWeight.Medium,
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FollowedShowsShortcutsRow(
    shows: List<com.music.spotui.data.preferences.FollowedPodcastShow>,
    onShowClick: (com.music.spotui.data.preferences.FollowedPodcastShow) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
        modifier = modifier.fillMaxWidth()
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(58.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onAddClick
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1F1F1F))
                        .border(1.dp, Color(0xFF2A2A2A), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "הוסף פודקאסטים",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "הוספה",
                    color = Color(0xFFB3B3B3),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        items(shows.size, key = { i -> shows[i].showId }) { i ->
            val show = shows[i]
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(58.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onShowClick(show) }
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF282828))
                ) {
                    if (show.imageUrl.isNotBlank()) {
                        GlideImage(
                            model = show.imageUrl,
                            contentDescription = show.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = show.name.take(1).uppercase(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = show.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun PodcastModeContent(
    followedPodcasts: List<com.music.spotui.data.preferences.FollowedPodcastShow>,
    onShowClick: (com.music.spotui.data.preferences.FollowedPodcastShow) -> Unit,
    onBrowsePodcasts: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        if (followedPodcasts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
                    .border(1.dp, Color(0xFF2C2C2C), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Column(horizontalAlignment = Alignment.Start) {
                    Text(
                        text = "פודקאסטים עבורכם",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "עקבו אחרי הפודקאסטים המועדפים עליכם כדי לקבל גישה מהירה ועדכונים שוטפים ישירות לכאן.",
                        color = Color(0xFFB3B3B3),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onBrowsePodcasts,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color.Black
                        ),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "עיון בפודקאסטים",
                            color = Color.Black,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        } else {
            Text(
                text = "הפודקאסטים שלכם",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                followedPodcasts.forEach { show ->
                    FollowedShowCardRow(
                        show = show,
                        onClick = { onShowClick(show) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FollowedShowsListContent(
    shows: List<com.music.spotui.data.preferences.FollowedPodcastShow>,
    onShowClick: (com.music.spotui.data.preferences.FollowedPodcastShow) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Text(
            text = "הפרקים האחרונים",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Text(
            text = "פודקאסטים במעקב (${shows.size})",
            color = Color(0xFFB3B3B3),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            shows.forEach { show ->
                FollowedShowCardRow(
                    show = show,
                    onClick = { onShowClick(show) }
                )
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun FollowedShowCardRow(
    show: com.music.spotui.data.preferences.FollowedPodcastShow,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF181818))
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF282828))
        ) {
            if (show.imageUrl.isNotBlank()) {
                GlideImage(
                    model = show.imageUrl,
                    contentDescription = show.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = show.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "פודקאסט • במעקב",
                color = Color(0xFF1ED760),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun FollowingEmptyState(
    modifier: Modifier = Modifier,
    onBrowsePodcasts: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Spotify XML: android.widget.TextView txt="הפרקים האחרונים" bounds=[654,121][876,162]
        Text(
            text = "הפרקים האחרונים",
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 14.dp),
            textAlign = TextAlign.Start
        )

        // Spotify XML: android.view.ViewGroup id=com.spotify.music:id/onboarding_card_root bounds=[24,174][876,547]
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF181818))
                .border(1.dp, Color(0xFF262626), RoundedCornerShape(12.dp))
                .padding(horizontal = 20.dp, vertical = 26.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Spotify XML: android.view.ViewGroup id=com.spotify.music:id/graphic bounds=[291,186][609,342]
                FollowingFannedStackGraphic(
                    modifier = Modifier
                        .size(width = 180.dp, height = 90.dp)
                        .padding(bottom = 4.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Spotify XML: android.widget.TextView id=com.spotify.music:id/title txt="עוד לא הוספתם פודקאסטים למעקב" bounds=[231,342][669,383]
                Text(
                    text = "עוד לא הוספתם פודקאסטים למעקב",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Spotify XML: android.widget.TextView id=com.spotify.music:id/explanation txt="עקבו אחרי הפודקאסטים המועדפים עליכם כדי להישאר מעודכנים." bounds=[194,389][706,417]
                Text(
                    text = "עקבו אחרי הפודקאסטים המועדפים עליכם כדי להישאר מעודכנים.",
                    color = Color(0xFFB3B3B3),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(22.dp))

                // Spotify XML: android.widget.Button id=com.spotify.music:id/positive_inverted txt="עיון בפודקסטים" bounds=[360,435][539,511]
                Button(
                    onClick = onBrowsePodcasts,
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black
                    ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 0.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(
                        text = "עיון בפודקאסטים",
                        color = Color.Black,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/** Fanned stack of 5 podcast album covers matching Spotify's empirical graphic in Following Empty State */
@Composable
private fun FollowingFannedStackGraphic(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // 1. Far Left (Outer teal cover)
        Box(
            modifier = Modifier
                .offset(x = (-46).dp, y = 4.dp)
                .rotate(-14f)
                .size(50.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF005F73), Color(0xFF0A9396))))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
        )

        // 2. Mid Left (Portrait coral cover)
        Box(
            modifier = Modifier
                .offset(x = (-24).dp, y = 2.dp)
                .rotate(-7f)
                .size(58.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF9B2226), Color(0xFFAE2012))))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
        )

        // 3. Far Right (Dark noir cover)
        Box(
            modifier = Modifier
                .offset(x = 46.dp, y = 4.dp)
                .rotate(14f)
                .size(50.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF262626), Color(0xFF171717))))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
        )

        // 4. Mid Right (Warm amber cover)
        Box(
            modifier = Modifier
                .offset(x = 24.dp, y = 2.dp)
                .rotate(7f)
                .size(58.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFCA6702), Color(0xFFBB3E03))))
                .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
        )

        // 5. Center (Foreground - Dissect vibrant purple / magenta cover)
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFE056FD), Color(0xFF6807F9), Color(0xFF130F40))
                    )
                )
                .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val centerPt = this.center
                drawCircle(
                    color = Color(0x44FFAA00),
                    radius = size.minDimension * 0.38f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
                )
                drawCircle(
                    color = Color(0x5500FFFF),
                    radius = size.minDimension * 0.26f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f)
                )
            }
            Text(
                text = "DISSECT",
                color = Color.White,
                fontSize = 8.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }
    }
}

/** Exactly 4 shortcut buttons (2x2 grid) for recently played items */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeTopGrid(
    navController: NavController,
    items: List<HomeItem>,
    onPlayTrack: (SongsModel) -> Unit,
    onSongLongClick: (SongsModel) -> Unit
) {
    val four = items.take(4)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        four.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { item ->
                    HomeGridCard(
                        navController = navController,
                        item = item,
                        modifier = Modifier.weight(1f),
                        onPlayTrack = onPlayTrack,
                        onSongLongClick = onSongLongClick
                    )
                }
                if (rowItems.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeGridCard(
    navController: NavController,
    item: HomeItem,
    modifier: Modifier = Modifier,
    onPlayTrack: (SongsModel) -> Unit,
    onSongLongClick: (SongsModel) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF282828))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    if (item is HomeItem.Track) {
                        onSongLongClick(item.song)
                    }
                },
                onClick = { onHomeItemClick(navController, item, onPlayTrack, HomeSurface.TOP_GRID) }
            )
    ) {
        GlideImage(
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.KosherWhitelistManager.isHomeItemWhitelisted(item),
            contentDescription = item.name
        )
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun HomeFeedSection(
    navController: NavController,
    section: HomeSection,
    onPlayTrack: (SongsModel) -> Unit,
    onSongLongClick: (SongsModel) -> Unit
) {
    val surface = when (section.id) {
        HomeSectionIds.RECENTLY_PLAYED -> HomeSurface.RECENTS
        HomeSectionIds.RECOMMENDED_TODAY -> HomeSurface.RECOMMENDED
        else -> HomeSurface.CATALOG_SECTION
    }

    if (section.id == HomeSectionIds.SIMILAR_ARTISTS || section.headerArtist != null) {
        ArtistSectionHeader(section) {
            section.headerArtist?.let { artist ->
                navController.navigate(artistRoute(artist.name, artist.id))
            }
        }
    } else {
        Text(
            text = section.title,
            color = Color.White,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
        )
    }

    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(section.items.size) { i ->
            val item = section.items[i]
            when {
                item is HomeItem.LikedSongs -> {
                    LikedSongsCard(count = item.count) {
                        onHomeItemClick(navController, item, onPlayTrack, surface)
                    }
                }
                section.type == HomeSectionType.ARTISTS && item is HomeItem.Artist -> {
                    HomeArtistCircleCard(artist = item) {
                        onHomeItemClick(navController, item, onPlayTrack, surface)
                    }
                }
                else -> {
                    HomeFeedCard(
                        item = item,
                        onSongLongClick = onSongLongClick
                    ) {
                        onHomeItemClick(navController, item, onPlayTrack, surface)
                    }
                }
            }
        }
    }
}

/** Header with artist avatar next to "אמנים נוספים כמו" */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ArtistSectionHeader(
    section: HomeSection,
    onArtistClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onArtistClick() }
    ) {
        val artist = section.headerArtist
        if (artist != null && artist.coverUri.isNotBlank()) {
            GlideImage(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                model = artist.coverUri,
                loading = placeholder(R.drawable.placeholder),
                failure = placeholder(R.drawable.placeholder),
                isAllowed = com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artist),
                contentDescription = artist.name
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.subtitle ?: "אמנים נוספים כמו",
                color = Color(0xFFB3B3B3),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = section.title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Pinned card for "שירים שאהבתם" in Recently Played */
@Composable
private fun LikedSongsCard(count: Int, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by com.music.spotui.data.preferences.likedSongsRevision.collectAsState()
    val liveCount = remember(revision) {
        com.music.spotui.data.preferences.getLikedSongsCount(context)
    }
    val effectiveCount = liveCount
    Column(
        modifier = Modifier
            .width(148.dp)
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF450AF5),
                            Color(0xFF8E8EE5),
                            Color(0xFFC4B5FD)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Default.Favorite,
                contentDescription = "Liked Songs",
                tint = Color.White,
                modifier = Modifier.size(54.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "שירים שאהבתם",
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 2.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1ED760))
            ) {
                Text(
                    text = "✓",
                    color = Color.Black,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (effectiveCount == 1) "נוסף שיר 1" else "$effectiveCount שירים",
                color = Color(0xFFB3B3B3),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Circular card for "אמנים פופולריים" */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HomeArtistCircleCard(artist: HomeItem.Artist, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(140.dp)
            .padding(6.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        GlideImage(
            modifier = Modifier
                .size(130.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            model = artist.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.KosherWhitelistManager.isHomeItemWhitelisted(artist),
            contentDescription = artist.name
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeFeedCard(
    item: HomeItem,
    onSongLongClick: ((SongsModel) -> Unit)? = null,
    onClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by com.music.spotui.data.preferences.likedSongsRevision.collectAsState()
    val liveCount = remember(revision) {
        com.music.spotui.data.preferences.getLikedSongsCount(context)
    }
    val isArtist = item is HomeItem.Artist
    val subtitle = when (item) {
        is HomeItem.Album -> item.subtitle
        is HomeItem.Playlist -> item.subtitle
        is HomeItem.Artist -> "Artist"
        is HomeItem.Track -> item.subtitle
        is HomeItem.LikedSongs -> if (liveCount == 1) "נוסף שיר 1" else "$liveCount שירים"
    }
    Column(
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .width(148.dp)
            .padding(6.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    if (item is HomeItem.Track && onSongLongClick != null) {
                        onSongLongClick(item.song)
                    }
                },
                onClick = { onClick() },
            ),
    ) {
        GlideImage(
            modifier = Modifier
                .size(148.dp)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.KosherWhitelistManager.isHomeItemWhitelisted(item),
            contentDescription = item.name,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        Text(
            text = subtitle,
            color = Color(0xFFB3B3B3),
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SumUpHomeScreen(navController : NavController, albums: List<AlbumsModel>, artists: List<ArtistsModel>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(AppBackground.toArgb()))
    ){

        GreetingSection()
        //ChipSection(chip = listOf(" All ", "Music", "Podcasts"))

        if (albums.isNotEmpty()) {
            HomePlaylistGrid(navController, albums)
            HomeAlbums(album = albums, navController)
        }
        //HomeRecentlyPlayed(navController, albums = listOf("karan aujla", "diljit", "fudfu", "frref", "frrf"))
        if (artists.isNotEmpty()) {
            HomeArtists(artists = artists, navController)
        }
        if (albums.isNotEmpty()) {
            ImageCard(navController, albums)
        }
    }
}



@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun GreetingSection(name : String = "User") {
    val currentHour = LocalTime.now().hour
    val greeting = when {
        currentHour < 12 -> "Good Morning"
        currentHour < 17 -> "Good Afternoon"
        else -> "Good Evening"
    }
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.Center) {
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
                )
            Text(
                text = "Have a Nice Day",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontSize = 13.sp
                )
        }
//        Icon(imageVector = Icons.Outlined.Person, contentDescription = "Profile", tint = Color.White)
    }
}

//@Composable
//fun ChipSection(
//    chip : List<String>
//) {
//    var selectedChip by remember {
//        mutableStateOf(0)
//    }
//    LazyRow{
//        items(chip.size){
//            Box(contentAlignment = Alignment.Center,
//                modifier = Modifier
//                    .padding(15.dp, 0.dp, 0.dp, 0.dp)
//                    .clickable {
//                        selectedChip = it
//                    }
//                    .clip(RoundedCornerShape(50.dp))
//                    .background(
//                        if (selectedChip == it) Color.Green
//                        else Color.Gray
//                    )
//                    .padding(10.dp, 5.dp)
//
//            ){
//                Text(text = chip[it], color = Color.White)
//            }
//        }
//    }
//}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomePlaylistGrid(navController: NavController, albums: List<AlbumsModel>) {
    // Use up to 8 albums, but don't assume there are at least 8 (rate-limited /
    // small feeds can return fewer) — that previously caused IndexOutOfBounds.
    val gridAlbums = albums.take(8)

    val chunkedAlbums = gridAlbums.chunked(2)
    Log.d("giveme", chunkedAlbums.toString())
    Column(
        modifier = Modifier
            .padding(0.dp, 10.dp)
    ){
        repeat(chunkedAlbums.size){
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .padding(15.dp, 5.dp, 7.dp, 0.dp)
                    .fillMaxWidth()
            )
            {
                repeat(chunkedAlbums[it].size){ album ->
                    Row(
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(GridBackground.toArgb()))
                            .width(180.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                val albumModel = chunkedAlbums[it][album]
                                Log.d("check", albumModel.name)
                                navController.navigate(albumRoute(albumModel.name, albumModel.artists, albumModel.coverUri))
                            }
                    ) {
                        val albItem = chunkedAlbums[it][album]
                        GlideImage(modifier = Modifier
                            .size(55.dp),
                            contentScale = ContentScale.Crop,
                            model = albItem.coverUri,
                            loading = placeholder(R.drawable.placeholder),
                            failure = placeholder(R.drawable.placeholder),
                            isAllowed = com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albItem),
                            contentDescription = "Profile")
                        Text(modifier = Modifier.padding(5.dp),
                            text = chunkedAlbums[it][album].name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                    }
                }

            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomeAlbums(
    album : List<AlbumsModel>,
    navController: NavController
) {
    val reversedAlbum = album.reversed().dropLast(1)
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Albums",
        color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold)
        LazyRow(modifier = Modifier.padding(6.dp)){
            items(reversedAlbum.size){ album ->
                Box(modifier = Modifier
                    .padding(10.dp)
                    .width(150.dp)
                    .height(195.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        navController.navigate(albumRoute(reversedAlbum[album].name, reversedAlbum[album].artists, reversedAlbum[album].coverUri))
                    }
            ){
                Column(
                    horizontalAlignment = Alignment.Start,
                    ) {

                    val albItem = reversedAlbum[album]
                    GlideImage(modifier = Modifier
                        .size(150.dp),
                        contentScale = ContentScale.Crop,
                        model = albItem.coverUri,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albItem),
                        contentDescription = "Albums")
                    Text(
                        fontSize = 13.sp,
                        text = reversedAlbum[album].name,
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontWeight = FontWeight.Bold)
                    Text(
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        text = reversedAlbum[album].artists,
                        color = Color.LightGray)
                }

            }
        }
    }
}

@Composable
fun HomeRecentlyPlayed(
    navController: NavController,
    albums : List<String>
) {
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Recently Played",
        color = Color.White,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold)
    LazyRow(modifier = Modifier.padding(6.dp)){
        items(albums.size){
            Box(modifier = Modifier
                .padding(10.dp)
                .width(130.dp)
                .height(140.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    navController.navigate(Routes.Player.route)
                }
            ){
                Column(horizontalAlignment = Alignment.Start) {
                    Image(modifier = Modifier
                        .size(120.dp)
                        .background(Color.Green),
                        contentScale = ContentScale.Crop,
                        painter = painterResource(id = R.drawable.album),
                        contentDescription = "Albums")
                    Text(modifier = Modifier.padding(2.dp),
                        text = "Album name",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp)
                }

            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun HomeArtists(
    artists : List<ArtistsModel>,
    navController: NavController
) {
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Best of Artists",
        color = Color.White,
        fontSize = 23.sp,
        fontWeight = FontWeight.Bold)
    LazyRow(modifier = Modifier.padding(6.dp)){
        items(artists.size){artist ->
            Box(modifier = Modifier
                .padding(10.dp)
                .width(150.dp)
                .height(200.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    Log.d("check", artists[artist].name)
                    navController.navigate(artistRoute(artists[artist].name, artists[artist].id))
                }
            ){
                Column(horizontalAlignment = Alignment.Start) {



                    val artistItem = artists[artist]
                    GlideImage(modifier = Modifier
                        .size(150.dp),
                        contentScale = ContentScale.Crop,
                        model = artistItem.coverUri,
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artistItem),
                        contentDescription = "Albums")
                    Text(modifier = Modifier.padding(2.dp),
                        text = "This is ${artists[artist].name}",
                        color = Color.LightGray,
                        fontSize = 11.sp)
                }

            }
        }
    }
}


@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun ImageCard(
    navController: NavController,
    allAlbums: List<AlbumsModel>,
    modifier: Modifier = Modifier
) {

    val albums = allAlbums.takeLast(3)
    Text(modifier = Modifier
        .padding(20.dp, 10.dp, 0.dp, 0.dp),
        text = "Discover",
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold)
    Column(
        modifier = Modifier.padding(0.dp, 10.dp, 0.dp, 50.dp)
    ) {
        repeat(albums.size) { album ->
            Card(
                shape = RoundedCornerShape(15.dp),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = 5.dp
                ),
                modifier = Modifier
                    .padding(15.dp)
                    .fillMaxWidth()
                    .height(380.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        navController.navigate(albumRoute(albums[album].name, albums[album].artists, albums[album].coverUri))
                    }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    val albumItem = albums[album]
                    GlideImage(
                        modifier = Modifier.fillMaxSize(),
                        model = albumItem.coverUri,
                        contentDescription = "artists",
                        loading = placeholder(R.drawable.placeholder),
                        failure = placeholder(R.drawable.placeholder),
                        isAllowed = com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albumItem),
                        contentScale = ContentScale.Crop
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color(AppBackground.toArgb())
                                    ),
                                    startY = 150f
                                )
                            )
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(0.dp, 0.dp, 0.dp, 30.dp),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = "Album : ${albums[album].name}",
                            style = TextStyle(color = Color.White, fontSize = 20.sp),
                            textAlign = TextAlign.Center
                        )

                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(100.dp))
    }
}





















