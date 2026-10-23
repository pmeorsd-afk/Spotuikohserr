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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.State
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
import androidx.compose.ui.unit.Dp
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.TextUnit

private val HomeBackground = Color(0xFF121212)
private val HomeFilterSelected = Color(0xFF1ED760)
private val HomeFollowingSelected = Color(0xFF1ABC54)
private val HomeFilterUnselected = Color(0xFF333333)


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
    val inPodcastModes = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS ||
        currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING
    // Keep the same animation alive when switching between the feed/loading/fallback headers.
    val followingReveal = followingRevealState(inPodcastModes)
    androidx.activity.compose.BackHandler(
        currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS || currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING
    ) { homeViewModel.backFromPodcasts() }
    val followedPodcasts by homeViewModel.followedPodcasts.collectAsState()
    val whitelistVersion by com.music.spotui.util.KosherWhitelistManager.versionState

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    var menuCover by remember { mutableStateOf<HomeItem?>(null) }
    var showWhatsNewDialog by remember {
        mutableStateOf(com.music.spotui.data.preferences.WhatsNewPref.shouldShow(context))
    }
    var showWazeIntro by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(com.music.spotui.data.preferences.WhatsNewPref.shouldOfferWaze(context))
    }

    com.music.spotui.ui.components.WhatsNewDialog(
        showDialog = showWhatsNewDialog,
        onDismiss = {
            showWhatsNewDialog = false
            com.music.spotui.data.preferences.WhatsNewPref.markSeen(context)
        },
        onContinue = {
            showWhatsNewDialog = false
            val offer = !(com.music.spotui.utils.WazeDetector.hasScreenDetectorPermission(context) &&
                com.music.spotui.data.preferences.isWazeOverlayEnabled(context))
            com.music.spotui.data.preferences.WhatsNewPref.markSeen(context, offerWaze = offer)
            showWazeIntro = offer
        }
    )
    if (showWazeIntro && !showWhatsNewDialog) {
        WazeSetupDialog(onDismiss = {
            showWazeIntro = false
            com.music.spotui.data.preferences.WhatsNewPref.finishWazeOffer(context)
        })
    }

    menuCover?.let { item ->
        com.music.spotui.ui.components.HomeCoverMenuSheet(
            item = item,
            onOpen = { onHomeItemClick(context, navController, item, { homeViewModel.playTrack(it, context) }, HomeSurface.TOP_GRID) },
            onSongOptions = { if (item is HomeItem.Track) menuSong = item.song },
            onDismiss = { menuCover = null },
        )
    }

    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = { menuSong = null },
        )
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    HomeImageApprovalRefresh(lifecycleOwner)
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
        color = HomeBackground,
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBackground)
            .statusBarsPadding()
    ) {
        val feed = (home as? Response.Success)?.data
        // Each feed resolves independently — albums (new releases) often succeeds
        // while artists (personalized) gets rate-limited. Render whatever arrived
        // instead of casting blindly (which crashed when one feed was an Error).
        val albumsList = (albums as? Response.Success)?.data.orEmpty()
        val artistsList = (artists as? Response.Success)?.data.orEmpty()

        when {
            inPodcastModes || (feed != null && feed.sections.isNotEmpty()) -> {
                HomeFeedContent(
                    navController = navController,
                    feed = feed ?: com.music.spotui.data.entity.HomeFeedModel(topGrid = emptyList(), sections = emptyList()),
                    currentFilter = currentFilter,
                    onFilterSelected = homeViewModel::setFilter,
                    followedPodcasts = followedPodcasts,
                    onPlayTrack = { song -> homeViewModel.playTrack(song, context) },
                    onSongLongClick = { menuSong = it },
                    onCoverLongClick = { menuCover = it },
                    followingReveal = followingReveal,
                )
            }

            // Only the first local snapshot read needs this loader. Catalog network
            // requests run after cached/Recents content has already been published.
            home is Response.Loading -> {
                Column {
                    HomeHeaderRow(navController, currentFilter, homeViewModel::setFilter, followingReveal)
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(28.dp), color = HomeFilterSelected, strokeWidth = 2.dp,
                        )
                    }
                }
            }

            // Fallback: home feed errored but new-releases / artists came through.
            albumsList.isNotEmpty() || artistsList.isNotEmpty() -> {
                Column {
                    HomeHeaderRow(navController, currentFilter, homeViewModel::setFilter, followingReveal)
                    Box(Modifier.weight(1f)) { SumUpHomeScreen(navController = navController, albums = albumsList, artists = artistsList, onCoverLongClick = { menuCover = it }) }
                }
            }

            else -> {
                Column {
                    HomeHeaderRow(navController, currentFilter, homeViewModel::setFilter, followingReveal)
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
}

enum class HomeSurface {
    TOP_GRID,
    RECENTS,
    RECOMMENDED,
    CATALOG_SECTION
}

private fun onHomeItemClick(
    context: android.content.Context,
    navController: NavController,
    item: HomeItem,
    onPlayTrack: (SongsModel) -> Unit,
    surface: HomeSurface = HomeSurface.CATALOG_SECTION
) {
    requestHomeCoverOnTap(context, item)
    when (item) {
        is HomeItem.Album -> navController.navigate(albumRoute(item.name, item.artists.ifBlank { item.subtitle }, item.imageUrl))
        is HomeItem.Artist -> navController.navigate(artistRoute(item.name, item.id))
        is HomeItem.Playlist ->
            if (item.id.isNotBlank()) navController.navigate(playlistRoute(item.id, item.name, item.imageUrl))
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
    onSongLongClick: (SongsModel) -> Unit,
    onCoverLongClick: (HomeItem) -> Unit,
    followingReveal: State<Float>,
) {
    val topGridItems = feed.topGrid.take(4)
    val sections = visibleHomeSections(feed.sections)
    val inPodcasts = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS || currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING
    // Do not initialize the podcast disk catalog on the music Home startup path.
    val podcastVm: com.music.spotui.ui.viewmodel.PodcastHomeViewModel? = if (inPodcasts) hiltViewModel() else null
    val podcastState = podcastVm?.state?.collectAsState()?.value ?: com.music.spotui.ui.viewmodel.PodcastHomeState()
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(podcastVm, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) podcastVm?.enter()
        }
        podcastVm?.enter()
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); podcastVm?.leave() }
    }
    val allScroll = rememberLazyListState()
    val musicScroll = rememberLazyListState()
    val podcastScroll = rememberLazyListState()
    val followingScroll = rememberLazyListState()

    LazyColumn(
        state = when (currentFilter) {
            com.music.spotui.ui.viewmodel.HomeTabFilter.ALL -> allScroll
            com.music.spotui.ui.viewmodel.HomeTabFilter.MUSIC -> musicScroll
            com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS -> podcastScroll
            com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING -> followingScroll
        },
        contentPadding = PaddingValues(bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .background(HomeBackground)
    ) {
        stickyHeader {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HomeBackground)
            ) {
                HomeHeaderRow(
                    navController = navController,
                    currentFilter = currentFilter,
                    onFilterSelected = onFilterSelected,
                    followingReveal = followingReveal,
                )
            }
        }

        when (currentFilter) {
            com.music.spotui.ui.viewmodel.HomeTabFilter.ALL,
            com.music.spotui.ui.viewmodel.HomeTabFilter.MUSIC -> {
                if (topGridItems.isNotEmpty()) {
                    item(key = "top_grid") {
                        HomeTopGrid(navController, topGridItems, onPlayTrack, onCoverLongClick)
                    }
                }
                items(sections.size, key = { i -> sections[i].id.ifBlank { "sec_$i" } }) { i ->
                    HomeFeedSection(navController, sections[i], onPlayTrack, onCoverLongClick)
                }
            }
            com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS,
            com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING -> {
                if (currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS && followedPodcasts.isNotEmpty()) {
                    item(key = "podcast_shortcuts") {
                        FollowedShowsShortcutsRow(
                            shows = followedPodcasts,
                            onShowClick = { navController.navigate(showRoute(it.showId, it.name)) },
                            onAddClick = { navController.navigate(Routes.PodcastChooser.route) },
                        )
                    }
                }
                if (currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING && followedPodcasts.isEmpty()) {
                    item(key = "following_empty_state") {
                        FollowingEmptyState(onBrowsePodcasts = { navController.navigate(Routes.PodcastChooser.route) })
                    }
                } else {
                    podcastVm?.let { vm ->
                        podcastHomeItems(podcastState, currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING,
                            vm, navController, onSongLongClick)
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
    followingReveal: State<Float>,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.music.spotui.data.api.ProfileCache.ensure(context)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        val avatarUrl = com.music.spotui.data.api.ProfileCache.imageUrl
        val initial = com.music.spotui.data.api.ProfileCache.name
            ?.trim()?.firstOrNull()?.uppercase() ?: "•"
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(start = 16.dp)
                .size(32.dp)
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
                    modifier = Modifier.size(32.dp),
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
                onFilterSelected = onFilterSelected,
                revealState = followingReveal,
            )
        }
    }
}

@Composable
private fun FilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
) {
    Box(
        modifier = modifier
            .heightIn(min = 32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (isSelected) HomeFilterSelected else HomeFilterUnselected)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color.Black else Color.White,
            fontSize = fontSize,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun followingRevealState(inPodcastModes: Boolean): State<Float> =
    animateFloatAsState(
        targetValue = if (inPodcastModes) 1f else 0f,
        animationSpec = tween(220, easing = FastOutSlowInEasing), label = "following-reveal",
    )

@Composable
internal fun PodcastFilterGroup(
    currentFilter: com.music.spotui.ui.viewmodel.HomeTabFilter,
    inPodcastModes: Boolean,
    onFilterSelected: (com.music.spotui.ui.viewmodel.HomeTabFilter) -> Unit,
    modifier: Modifier = Modifier,
    revealState: State<Float>? = null,
) {
    val following = currentFilter == com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING
    // Read the animation in measurement below, so each frame does not recompose the whole Home.
    val reveal by revealState ?: followingRevealState(inPodcastModes)
    // Place the following segment behind the fixed Podcasts pill. Relative placement mirrors in RTL.
    Layout(modifier = modifier.clipToBounds().clip(RoundedCornerShape(50)), content = {
        // The inner edge is covered by Podcasts; keep it square to avoid a notch at the join.
        Box(Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(
            topStart = CornerSize(0), topEnd = CornerSize(50),
            bottomEnd = CornerSize(50), bottomStart = CornerSize(0)))
            .background(if (following) HomeFollowingSelected else HomeFilterUnselected)
            .then(if (!inPodcastModes) Modifier.clearAndSetSemantics {} else Modifier)
            .clickable(enabled = inPodcastModes) {
                onFilterSelected(if (following) com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS
                    else com.music.spotui.ui.viewmodel.HomeTabFilter.FOLLOWING)
            }
            .padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
            Text("במעקב", color = if (following) Color.Black else Color.White, fontSize = 13.5.sp,
                lineHeight = 20.sp, fontWeight = FontWeight.Normal, maxLines = 1)
        }
        FilterPill("פודקאסטים", inPodcastModes,
            { onFilterSelected(if (inPodcastModes) com.music.spotui.ui.viewmodel.HomeTabFilter.ALL
                else com.music.spotui.ui.viewmodel.HomeTabFilter.PODCASTS) }, fontSize = 13.5.sp)
    }) { measurables, constraints ->
        val followingPill = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val primary = measurables[1].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val overlap = 16.dp.roundToPx()
        val extra = ((followingPill.width - overlap).coerceAtLeast(0) * reveal).toInt()
        val height = maxOf(primary.height, followingPill.height)
        layout(primary.width + extra, height) {
            followingPill.placeRelative(primary.width + extra - followingPill.width, (height - followingPill.height) / 2)
            primary.placeRelative(0, (height - primary.height) / 2)
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val whitelistVersion by com.music.spotui.util.KosherWhitelistManager.versionState

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
                        onClick = {
                            requestHomeCoverOnTap(context, show.name, show.imageUrl) {
                                com.music.spotui.util.HomeArtworkPermissions.isApproved(show.imageUrl) ||
                                    com.music.spotui.util.KosherWhitelistManager.isImageAllowed(show.imageUrl)
                            }
                            onShowClick(show)
                        }
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
                            isAllowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(show.imageUrl) ||
                                remember(show.imageUrl, whitelistVersion) {
                                    com.music.spotui.util.KosherWhitelistManager.isImageAllowed(show.imageUrl)
                                },
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
    onCoverLongClick: (HomeItem) -> Unit
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
                        onCoverLongClick = onCoverLongClick
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
    onCoverLongClick: (HomeItem) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF282828))
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    onCoverLongClick(item)
                },
                onClick = { onHomeItemClick(context, navController, item, onPlayTrack, HomeSurface.TOP_GRID) }
            )
    ) {
        GlideImage(
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.HomeArtworkPermissions.isAllowedOnHome(item),
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
internal fun HomeFeedSection(
    navController: NavController,
    section: HomeSection,
    onPlayTrack: (SongsModel) -> Unit,
    onCoverLongClick: (HomeItem) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val surface = when (section.id) {
        HomeSectionIds.RECENTLY_PLAYED -> HomeSurface.RECENTS
        HomeSectionIds.RECOMMENDED_TODAY -> HomeSurface.RECOMMENDED
        else -> HomeSurface.CATALOG_SECTION
    }

    if (section.id == HomeSectionIds.SIMILAR_ARTISTS || section.headerArtist != null) {
        ArtistSectionHeader(section, onCoverLongClick) {
            section.headerArtist?.let { artist ->
                requestHomeCoverOnTap(context, HomeItem.Artist(artist.name, artist.coverUri, artist.id))
                navController.navigate(artistRoute(artist.name, artist.id))
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 8.dp)
        ) {
            Text(section.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            if (surface == HomeSurface.RECENTS) {
                Text("הצגת כל האפשרויות", color = Color(0xFFB3B3B3), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { navController.navigate(Routes.History.route) }
                        .padding(vertical = 12.dp, horizontal = 4.dp))
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        val isRecent = surface == HomeSurface.RECENTS
        // Reference covers are ~300px / 146px at 1.5x density. Constrain to the actual viewport.
        val coverSize = minOf(if (isRecent) 98.dp else 200.dp, (maxWidth - 32.dp).coerceAtLeast(1.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(section.items.size) { i ->
                val item = section.items[i]
                when {
                    item is HomeItem.LikedSongs -> {
                        LikedSongsCard(count = item.count, coverSize = coverSize) {
                            onHomeItemClick(context, navController, item, onPlayTrack, surface)
                        }
                    }
                    section.type == HomeSectionType.ARTISTS && item is HomeItem.Artist -> {
                        HomeArtistCircleCard(artist = item, coverSize = coverSize, onLongClick = { onCoverLongClick(item) }) {
                            onHomeItemClick(context, navController, item, onPlayTrack, surface)
                        }
                    }
                    else -> {
                        HomeFeedCard(
                            item = item,
                            coverSize = coverSize,
                            isRecent = isRecent,
                            onCoverLongClick = onCoverLongClick
                        ) {
                            onHomeItemClick(context, navController, item, onPlayTrack, surface)
                        }
                    }
                }
            }
        }
    }
}

/** Header with artist avatar next to "אמנים נוספים כמו" */
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun ArtistSectionHeader(
    section: HomeSection,
    onCoverLongClick: (HomeItem) -> Unit,
    onArtistClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 32.dp, bottom = 8.dp)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = { section.headerArtist?.let { onCoverLongClick(HomeItem.Artist(it.name, it.coverUri, it.id)) } },
                onClick = onArtistClick,
            )
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
                isAllowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(artist.coverUri) || com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artist),
                contentDescription = artist.name
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.subtitle ?: "אמנים נוספים כמו",
                color = Color(0xFFB3B3B3),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
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
private fun LikedSongsCard(count: Int, coverSize: Dp, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val revision by com.music.spotui.data.preferences.likedSongsRevision.collectAsState()
    val liveCount = remember(revision) {
        com.music.spotui.data.preferences.getLikedSongsCount(context)
    }
    val effectiveCount = liveCount
    Column(
        modifier = Modifier
            .width(coverSize)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth().aspectRatio(1f)
                .clip(RoundedCornerShape(4.dp))
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
                modifier = Modifier.size(coverSize * 0.34f)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
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
            com.music.spotui.ui.components.SavedCheckBadge()
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
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
private fun HomeArtistCircleCard(artist: HomeItem.Artist, coverSize: Dp, onLongClick: () -> Unit, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(coverSize)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
    ) {
        GlideImage(
            modifier = Modifier
                .fillMaxWidth().aspectRatio(1f)
                .clip(CircleShape),
            contentScale = ContentScale.Crop,
            model = artist.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.HomeArtworkPermissions.isAllowedOnHome(artist),
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
    coverSize: Dp,
    isRecent: Boolean,
    onCoverLongClick: (HomeItem) -> Unit,
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
            .width(coverSize)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = {
                    onCoverLongClick(item)
                },
                onClick = { onClick() },
            ),
    ) {
        GlideImage(
            modifier = Modifier
                .fillMaxWidth().aspectRatio(1f)
                .clip(if (isArtist) CircleShape else RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
            model = item.imageUrl,
            loading = placeholder(R.drawable.placeholder),
            failure = placeholder(R.drawable.placeholder),
            isAllowed = com.music.spotui.util.HomeArtworkPermissions.isAllowedOnHome(item),
            contentDescription = item.name,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = item.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        if (isRecent && item is HomeItem.Track && item.song.mediaType == MediaType.PODCAST_EPISODE) {
            RecentPodcastProgress(item.song)
        } else Text(
            text = subtitle,
            color = Color(0xFFB3B3B3),
            fontSize = 14.sp,
            // LazyRow measures only the visible cards. Reserve the full caption slot
            // so a two-line publisher entering/leaving view cannot move the next shelf.
            minLines = if (isRecent) 1 else 2,
            maxLines = if (isRecent) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
    }
}

/** Subscribes only while the card is composed; playback remains the sole writer of progress. */
@Composable
private fun RecentPodcastProgress(song: SongsModel) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val progress by remember(context, song) {
        com.music.spotui.data.preferences.observePodcastProgress(context, song)
    }.collectAsState(initial = null)
    val value = progress
    if (value == null) {
        Text(song.singer, color = Color(0xFFB3B3B3), fontSize = 14.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        return
    }
    com.music.spotui.ui.components.ListeningProgressBar(
        value.fraction,
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)
            .height(4.dp)
    )
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun SumUpHomeScreen(navController : NavController, albums: List<AlbumsModel>, artists: List<ArtistsModel>, onCoverLongClick: (HomeItem) -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .background(Color(AppBackground.toArgb()))
    ){

        GreetingSection()
        //ChipSection(chip = listOf(" All ", "Music", "Podcasts"))

        if (albums.isNotEmpty()) {
            HomePlaylistGrid(navController, albums, onCoverLongClick)
            HomeAlbums(album = albums, navController, onCoverLongClick)
        }
        //HomeRecentlyPlayed(navController, albums = listOf("karan aujla", "diljit", "fudfu", "frref", "frrf"))
        if (artists.isNotEmpty()) {
            HomeArtists(artists = artists, navController, onCoverLongClick)
        }
        if (albums.isNotEmpty()) {
            ImageCard(navController, albums, onCoverLongClick = onCoverLongClick)
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

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomePlaylistGrid(navController: NavController, albums: List<AlbumsModel>, onCoverLongClick: (HomeItem) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                onLongClick = { onCoverLongClick(chunkedAlbums[it][album].homeCover()) },
                                indication = null
                            ) {
                                val albumModel = chunkedAlbums[it][album]
                                Log.d("check", albumModel.name)
                                requestHomeCoverOnTap(context, albumModel.homeCover())
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
                            isAllowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(albItem.coverUri) || com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albItem),
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


@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeAlbums(
    album : List<AlbumsModel>,
    navController: NavController,
    onCoverLongClick: (HomeItem) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        onLongClick = { onCoverLongClick(reversedAlbum[album].homeCover()) },
                                indication = null
                    ) {
                        requestHomeCoverOnTap(context, reversedAlbum[album].homeCover())
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
                        isAllowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(albItem.coverUri) || com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albItem),
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

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun HomeArtists(
    artists : List<ArtistsModel>,
    navController: NavController,
    onCoverLongClick: (HomeItem) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

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
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    onLongClick = { onCoverLongClick(HomeItem.Artist(artists[artist].name, artists[artist].coverUri, artists[artist].id)) },
                                indication = null
                ) {
                    Log.d("check", artists[artist].name)
                    requestHomeCoverOnTap(context, HomeItem.Artist(artists[artist].name, artists[artist].coverUri, artists[artist].id))
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
                        isAllowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(artistItem.coverUri) || com.music.spotui.util.KosherWhitelistManager.isArtistModelWhitelisted(artistItem),
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


@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun ImageCard(
    navController: NavController,
    allAlbums: List<AlbumsModel>,
    modifier: Modifier = Modifier,
    onCoverLongClick: (HomeItem) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current


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
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        onLongClick = { onCoverLongClick(albums[album].homeCover()) },
                                indication = null
                    ) {
                        requestHomeCoverOnTap(context, albums[album].homeCover())
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
                        isAllowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(albumItem.coverUri) || com.music.spotui.util.KosherWhitelistManager.isAlbumWhitelisted(albumItem),
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






















private fun AlbumsModel.homeCover() = HomeItem.Album(name, coverUri, artists, artists, spotifyId)
