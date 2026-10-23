package com.music.spotui.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.CustomPlaylistStore
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.toTrackKey
import com.music.spotui.di.Palette
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.EpisodeCardItem
import com.music.spotui.ui.components.PodcastShowHeader
import com.music.spotui.ui.components.PodcastShowMenuSheet
import com.music.spotui.ui.components.sharePodcastShow
import com.music.spotui.ui.podcast.PodcastShowMenuTarget
import com.music.spotui.ui.components.PodcastShowTabs
import com.music.spotui.ui.components.PodcastEpisodeListHeading
import com.music.spotui.ui.components.SavedInSheet
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.viewmodel.ShowViewModel
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.PodcastDateFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun isEpisodeSavedInLibrary(context: android.content.Context, song: SongsModel): Boolean {
    return com.music.spotui.data.preferences.isSavedInAnyDestination(context, song)
}

@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun ShowScreen(
    navController: NavController,
    showId: String,
    showName: String = "",
    singleEpisodeId: String = ""
) {
    val singleEpisodeRaw = remember(singleEpisodeId) {
        if (singleEpisodeId.isNotBlank()) com.music.spotui.ui.navigation.ItemDetailRegistry.get(singleEpisodeId) else null
    }
    val singleEpisode = remember(singleEpisodeRaw) {
        singleEpisodeRaw?.let { s ->
            val durationMs = s.durationMs.toLong()
            PodcastEpisodeUiModel(
                id = s.url.ifBlank { "episode:${s.id}" },
                numericId = s.id,
                title = s.title,
                description = null,
                pubDate = null,
                pubDateFormatted = "",
                durationMs = durationMs,
                durationFormatted = PodcastDateFormatter.formatDuration(durationMs),
                formattedMetadata = PodcastDateFormatter.formatDuration(durationMs),
                coverUri = s.coverUri,
                playUrl = s.url,
                showId = s.podcastShowId.ifBlank { showId },
                showName = s.album.ifBlank { showName },
                publisher = s.singer,
                spotifyEpisodeId = s.spotifyTrackId,
            )
        }
    }

    val vm: ShowViewModel = hiltViewModel()
    val context = LocalContext.current
    LaunchedEffect(showId, showName, singleEpisode) {
        if (singleEpisode == null) {
            vm.loadShow(showId, showName)
        }
    }

    val episodesState by vm.episodes.collectAsState()
    val show by vm.show.collectAsState()
    val episodes = if (singleEpisode != null) {
        listOf(singleEpisode)
    } else {
        (episodesState as? Response.Success)?.data.orEmpty()
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    val followRevision by com.music.spotui.data.preferences.podcastFollowRevision.collectAsState()
    val isFollowing = remember(showId, followRevision) { vm.isPodcastFollowed(showId) }
    var notificationsEnabled by remember { mutableStateOf(false) }

    val effectiveCoverUri = remember(singleEpisode, show, episodes) {
        singleEpisode?.coverUri
            ?: show?.coverUri
            ?: episodes.firstOrNull()?.coverUri
            ?: ""
    }
    var rawDominantColor by remember(effectiveCoverUri) { mutableStateOf(Color(0xFF141414)) }
    val dominantColor by animateColorAsState(
        targetValue = rawDominantColor,
        animationSpec = tween(durationMillis = 400),
        label = "ShowHeaderGradientColor"
    )

    LaunchedEffect(effectiveCoverUri) {
        if (effectiveCoverUri.isNotBlank()) {
            Palette().extractDominantColorForGradient(context, effectiveCoverUri) { color ->
                rawDominantColor = color
            }
        }
    }

    var savedInSheetSong by remember { mutableStateOf<SongsModel?>(null) }
    var refreshSavedTrigger by remember { mutableIntStateOf(0) }
    val savedRevision by com.music.spotui.data.preferences.likedSongsRevision.collectAsState()

    savedInSheetSong?.let { songToSave ->
        SavedInSheet(
            song = songToSave,
            context = context,
            onDismiss = {
                savedInSheetSong = null
                refreshSavedTrigger++
            },
            onLikedChanged = {
                refreshSavedTrigger++
            }
        )
    }

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = {
                menuSong = null
                refreshSavedTrigger++
            },
        )
    }

    var menuShow by remember(showId) { mutableStateOf<PodcastShowMenuTarget?>(null) }
    menuShow?.let { target ->
        PodcastShowMenuSheet(
            target = target,
            following = isFollowing,
            onToggleFollow = {
                if (isFollowing) vm.unfollowPodcast(target.show.id)
                else vm.followPodcast(target.show.id, target.show.name, target.show.coverUri)
            },
            onDismiss = { menuShow = null },
        )
    }

    // Pull-to-reveal Search Bar & In-Show Search States
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val pullThresholdPx = with(density) { 56.dp.toPx() }
    var pullDistancePx by remember { mutableFloatStateOf(0f) }
    var pullAnimJob by remember { mutableStateOf<Job?>(null) }

    fun animatePullTo(target: Float) {
        pullAnimJob?.cancel()
        pullAnimJob = coroutineScope.launch {
            Animatable(pullDistancePx).animateTo(
                targetValue = target,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) {
                pullDistancePx = this.value
            }
        }
    }

    val searchProgress by remember {
        derivedStateOf {
            (pullDistancePx / pullThresholdPx).coerceIn(0f, 1f)
        }
    }

    val nestedScrollConnection = remember(pullThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && pullAnimJob?.isActive == true) {
                    pullAnimJob?.cancel()
                    pullAnimJob = null
                }

                // If search is pulled/open and user scrolls down the list (available.y < 0):
                if (pullDistancePx > 0f && available.y < 0f) {
                    val newPull = (pullDistancePx + available.y).coerceAtLeast(0f)
                    val consumedY = newPull - pullDistancePx
                    pullDistancePx = newPull
                    return Offset(0f, consumedY)
                }

                // If user drags DOWN at top of list (available.y > 0):
                if ((pullDistancePx > 0f || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)) &&
                    available.y > 0f
                ) {
                    val newPull = (pullDistancePx + available.y).coerceAtMost(pullThresholdPx)
                    val consumedY = newPull - pullDistancePx
                    pullDistancePx = newPull
                    return Offset(0f, consumedY)
                }

                return Offset.Zero
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0 &&
                    available.y > 0f
                ) {
                    val newPull = (pullDistancePx + available.y).coerceAtMost(pullThresholdPx)
                    val consumedY = newPull - pullDistancePx
                    pullDistancePx = newPull
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullDistancePx > 0f) {
                    val shouldOpen = if (available.y > 150f) true
                    else if (available.y < -150f) false
                    else pullDistancePx >= pullThresholdPx * 0.4f
                    val target = if (shouldOpen) pullThresholdPx else 0f
                    Animatable(pullDistancePx).animateTo(
                        targetValue = target,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ) {
                        pullDistancePx = this.value
                    }
                }
                return Velocity.Zero
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF121212))
        ) {
            // Dynamic vertical gradient matching official Spotify show cover logo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                dominantColor,
                                androidx.compose.ui.graphics.lerp(dominantColor, Color(0xFF121212), 0.65f),
                                Color(0xFF121212)
                            )
                        )
                    )
            )

            AnimatedContent(
                targetState = isSearchActive,
                transitionSpec = {
                    if (targetState) {
                        (fadeIn(animationSpec = tween(260, easing = LinearOutSlowInEasing)) +
                                slideInVertically(animationSpec = tween(260, easing = LinearOutSlowInEasing)) { it / 8 })
                            .togetherWith(
                                fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing))
                            )
                    } else {
                        (fadeIn(animationSpec = tween(240, easing = LinearOutSlowInEasing)))
                            .togetherWith(
                                fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                                        slideOutVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) { it / 8 }
                            )
                    }
                },
                label = "ShowSearchTransition",
                modifier = Modifier.fillMaxSize()
            ) { active ->
                if (active) {
                    // ==========================================
                    // DEDICATED IN-SHOW SEARCH MODE (Matches media_1791233919228.png)
                    // ==========================================
                    BackHandler {
                        keyboardController?.hide()
                        isSearchActive = false
                        searchQuery = ""
                    }

                    val searchFocusRequester = remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        delay(120)
                        searchFocusRequester.requestFocus()
                        keyboardController?.show()
                    }

                    val filteredEpisodes = remember(episodes, searchQuery) {
                        val q = searchQuery.trim().lowercase()
                        if (q.isEmpty()) {
                            episodes
                        } else {
                            episodes.filter { ep ->
                                ep.title.lowercase().contains(q) ||
                                (ep.description?.lowercase()?.contains(q) == true)
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                    ) {
                        // Top Bar: [Search Box (Right in RTL)] [ "ביטול" (Left in RTL) ]
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            // Search input box (occupies remaining width)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.14f))
                                    .padding(horizontal = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = Color(0xFFDDDDDD),
                                    modifier = Modifier.size(18.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterStart
                                ) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "חפשו בתוכנית זו",
                                            color = Color(0xFFB3B3B3),
                                            fontSize = 14.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        textStyle = TextStyle(
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Normal
                                        ),
                                        cursorBrush = SolidColor(Color(0xFF1ED760)),
                                        singleLine = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(searchFocusRequester)
                                    )
                                }

                                if (searchQuery.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "נקה",
                                        tint = Color(0xFFB3B3B3),
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                searchQuery = ""
                                            }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(14.dp))

                            // "ביטול" button on left in RTL
                            Text(
                                text = "ביטול",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Normal,
                                modifier = Modifier
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        keyboardController?.hide()
                                        isSearchActive = false
                                        searchQuery = ""
                                    }
                                    .padding(vertical = 4.dp, horizontal = 2.dp)
                            )
                        }

                        if (filteredEpisodes.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 32.dp, vertical = 60.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = Color(0xFF666666),
                                        modifier = Modifier.size(56.dp)
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "לא נמצאו פרקים עבור \"$searchQuery\"",
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "בדקו את האיות או נסו לחפש מילות מפתח אחרות מתוך תוכנית זו.",
                                        color = Color(0xFFB3B3B3),
                                        fontSize = 13.sp,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            // Episodes List directly below search bar matching media_1791233919228.png
                            LazyColumn(
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 8.dp, bottom = 140.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(filteredEpisodes) { _, ep ->
                                    val isPlayingCurrent = (ep.numericId == vm.currentSongId.value)
                                    val isPlayingActive = (isPlayingCurrent && vm.currentSongPlayingState.value)
                                    val epSong = remember(ep) { ep.toSongModel() }
                                    val isSaved = remember(epSong, refreshSavedTrigger, savedRevision) {
                                        isEpisodeSavedInLibrary(context, epSong)
                                    }

                                    EpisodeCardItem(
                                        ep = ep,
                                        showPageStyle = true,
                                        showCoverUri = show?.coverUri,
                                        isPlayingCurrent = isPlayingCurrent,
                                        isPlayingActive = isPlayingActive,
                                        isSaved = isSaved,
                                        onEpisodeClick = {
                                            vm.playEpisode(ep, filteredEpisodes, context)
                                        },
                                        onTogglePlay = {
                                            vm.togglePlay(ep, filteredEpisodes, context)
                                        },
                                        onSaveClick = {
                                            if (!isSaved) {
                                                addLikedSong(context, epSong)
                                            }
                                            savedInSheetSong = epSong
                                        },
                                        onDownloadClick = {
                                            SongPlayer.downloadSong(epSong, context)
                                        },
                                        onShareClick = {
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, "${ep.title} - ${ep.showName}")
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, null))
                                        },
                                        onMoreClick = {
                                            menuSong = epSong
                                        }
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // ==========================================
                    // NORMAL SHOW SCREEN MODE WITH PULL SEARCH
                    // ==========================================
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Pinned Top Section: Back Button + Collapsible Search Bar
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        ) {
                            // Top Bar: Back Button
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .padding(horizontal = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    tint = Color.White,
                                    contentDescription = "חזרה",
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            if (pullDistancePx > 0f) {
                                                animatePullTo(0f)
                                            } else {
                                                navController.navigateUp()
                                            }
                                        }
                                        .padding(12.dp),
                                )
                            }

                            // Pull-to-reveal Search Bar (revealed by dragging down at top)
                            if (singleEpisode == null && searchProgress > 0.001f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp * searchProgress)
                                        .padding(horizontal = 16.dp, vertical = 4.dp)
                                        .graphicsLayer {
                                            alpha = searchProgress
                                            translationY = (1f - searchProgress) * with(density) { (-12.dp).toPx() }
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(38.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.14f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                isSearchActive = true
                                            }
                                            .padding(horizontal = 12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "חיפוש",
                                            tint = Color(0xFFDDDDDD),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = "חפשו בתוכנית זו",
                                            color = Color(0xFFDDDDDD),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        // LazyColumn with nestedScroll for Pull-to-Reveal
                        LazyColumn(
                            state = listState,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 140.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .nestedScroll(nestedScrollConnection),
                        ) {
                            // Show-specific presentation; existing callbacks keep their behavior.
                            item {
                                PodcastShowHeader(
                                    title = if (singleEpisode != null) singleEpisode.showName else (show?.name ?: showName),
                                    publisher = if (singleEpisode != null) singleEpisode.publisher else show?.publisher.orEmpty(),
                                    coverUri = effectiveCoverUri,
                                    artworkAllowed = if (singleEpisode != null) {
                                        KosherWhitelistManager.isSongWhitelisted(singleEpisode.toSongModel())
                                    } else {
                                        KosherWhitelistManager.isPodcastShowWhitelisted(show)
                                    },
                                    topics = show?.topics.orEmpty(),
                                    following = isFollowing,
                                    notificationsEnabled = notificationsEnabled,
                                    onFollow = {
                                        if (isFollowing) {
                                            vm.unfollowPodcast(showId)
                                        } else {
                                            val nameToSave = show?.name?.ifBlank { showName }?.ifBlank { "Podcast" } ?: showName.ifBlank { "Podcast" }
                                            val imageToSave = effectiveCoverUri.ifBlank { show?.coverUri.orEmpty() }
                                            vm.followPodcast(showId, nameToSave, imageToSave)
                                        }
                                    },
                                    onNotifications = { notificationsEnabled = !notificationsEnabled },
                                    onShare = {
                                        PodcastShowMenuTarget.fromRoute(showId, showName, show)?.let {
                                            sharePodcastShow(context, it)
                                        }
                                    },
                                    onMore = {
                                        menuShow = PodcastShowMenuTarget.fromRoute(showId, showName, show)
                                    },
                                )
                            }
                            if (singleEpisode == null) {
                                item { PodcastShowTabs(selectedTab) { selectedTab = it } }
                            }

                            // Tab 1: אודות View
                            if (selectedTab == 1 && singleEpisode == null) {
                                item {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 16.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFF1E1E1E))
                                            .padding(16.dp)
                                    ) {
                                        Text(
                                            text = "על התוכנית",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        val desc = show?.description?.ifBlank { "פודקאסט מאת ${show?.publisher ?: showName}" }
                                            ?: "פודקאסט מאת $showName"
                                        Text(
                                            text = desc,
                                            fontSize = 14.sp,
                                            color = Color(0xFFCCCCCC),
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }

                            // Tab 0: פרקים View
                            if (selectedTab == 0 || singleEpisode != null) {
                                if (singleEpisode == null) {
                                    item { PodcastEpisodeListHeading() }
                                }

                                if (singleEpisode == null && episodesState is Response.Loading) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 48.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(40.dp),
                                                color = Color(0xFF1ED760)
                                            )
                                        }
                                    }
                                }

                                if (episodesState is Response.Error) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = (episodesState as Response.Error).error.ifBlank { "לא ניתן לטעון פרקים" },
                                                color = Color.Gray,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }

                                if (episodesState is Response.Success && episodes.isEmpty()) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "אין פרקים זמינים",
                                                color = Color.Gray,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }

                                // 4-Layer Episode Cards matching official Spotify
                                itemsIndexed(episodes) { _, ep ->
                                    val isPlayingCurrent = (ep.numericId == vm.currentSongId.value)
                                    val isPlayingActive = (isPlayingCurrent && vm.currentSongPlayingState.value)
                                    val epSong = remember(ep) { ep.toSongModel() }
                                    val isSaved = remember(epSong, refreshSavedTrigger, savedRevision) {
                                        isEpisodeSavedInLibrary(context, epSong)
                                    }

                                    EpisodeCardItem(
                                        ep = ep,
                                        showPageStyle = true,
                                        showCoverUri = show?.coverUri,
                                        isPlayingCurrent = isPlayingCurrent,
                                        isPlayingActive = isPlayingActive,
                                        isSaved = isSaved,
                                        onEpisodeClick = {
                                            vm.playEpisode(ep, episodes, context)
                                        },
                                        onTogglePlay = {
                                            vm.togglePlay(ep, episodes, context)
                                        },
                                        onSaveClick = {
                                            if (!isSaved) {
                                                addLikedSong(context, epSong)
                                            }
                                            savedInSheetSong = epSong
                                        },
                                        onDownloadClick = {
                                            SongPlayer.downloadSong(epSong, context)
                                        },
                                        onShareClick = {
                                            val sendIntent = Intent().apply {
                                                action = Intent.ACTION_SEND
                                                putExtra(Intent.EXTRA_TEXT, "${ep.title} - ${ep.showName}")
                                                type = "text/plain"
                                            }
                                            context.startActivity(Intent.createChooser(sendIntent, null))
                                        },
                                        onMoreClick = {
                                            menuSong = epSong
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

