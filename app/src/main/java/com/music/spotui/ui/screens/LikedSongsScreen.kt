package com.music.spotui.ui.screens

import android.annotation.SuppressLint
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
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
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.Loader
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.viewmodel.LikedSongsViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class LikedSortOrder(val title: String) {
    RECENT("לאחרונה"),
    RECENT_ADDED("נוספו לאחרונה"),
    ALPHABETICAL("אלפביתי"),
    CREATOR("יוצר")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LikedSongsScreen(navController: NavController) {

    val likedSongsViewModel: LikedSongsViewModel = hiltViewModel()
    val songsResp by likedSongsViewModel.songs.collectAsState()
    val context = LocalContext.current

    val rawSongs = (songsResp as? Response.Success)?.data.orEmpty()

    LaunchedEffect(Unit) {
        likedSongsViewModel.refresh()
    }

    LaunchedEffect(rawSongs) {
        if (rawSongs.isNotEmpty()) {
            SongPlayer.prefetchList(rawSongs.map { it.url }, context)
        }
    }

    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { sel ->
        com.music.spotui.ui.components.SongOptionsSheet(
            song = sel,
            navController = navController,
            context = context,
            onDismiss = {
                if (!com.music.spotui.data.preferences.isSongLiked(context, sel)) {
                    likedSongsViewModel.removeLocally(sel)
                }
                menuSong = null
            },
        )
    }

    var sortOrder by remember { mutableStateOf(LikedSortOrder.RECENT) }
    var showSortSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val sortedSongs = remember(rawSongs, sortOrder) {
        when (sortOrder) {
            LikedSortOrder.RECENT -> rawSongs
            LikedSortOrder.RECENT_ADDED -> rawSongs.reversed()
            LikedSortOrder.ALPHABETICAL -> rawSongs.sortedBy { it.title.lowercase() }
            LikedSortOrder.CREATOR -> rawSongs.sortedBy { it.singer.lowercase() }
        }
    }

    val displaySongs = remember(sortedSongs, searchQuery) {
        if (searchQuery.isBlank()) sortedSongs
        else sortedSongs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.singer.contains(searchQuery, ignoreCase = true)
        }
    }

    if (showSortSheet) {
        LikedSortBottomSheet(
            currentSort = sortOrder,
            onSortSelected = { sortOrder = it },
            onDismiss = { showSortSheet = false }
        )
    }

    val spotifyBackground = Color(0xFF121212)
    val spotifyGreen = Color(0xFF1ED760)
    val cardBackground = Color(0xFF2A2A2A)
    val textSecondary = Color(0xFFB3B3B3)

    // Measured directly from original Spotify reference (576 x 1024):
    // Gradient transitions smoothly into #121212 by ~24% of screen height
    val rootGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0.00f to Color(0xFF23337D),
            0.06f to Color(0xFF1F2B63),
            0.12f to Color(0xFF1B2348),
            0.18f to Color(0xFF171B2E),
            0.24f to spotifyBackground,
            1.00f to spotifyBackground
        )
    )

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Interaction 1: Pull-to-Reveal Search Bar & Sort Button (own row below TopBar)
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
            if (searchQuery.isNotEmpty()) 1f
            else (pullDistancePx / pullThresholdPx).coerceIn(0f, 1f)
        }
    }

    val nestedScrollConnection = remember(pullThresholdPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && pullAnimJob?.isActive == true) {
                    pullAnimJob?.cancel()
                    pullAnimJob = null
                }

                // If search is open/pulled and user scrolls UP (available.y < 0):
                if (pullDistancePx > 0f && available.y < 0f && searchQuery.isEmpty()) {
                    val newPull = (pullDistancePx + available.y).coerceAtLeast(0f)
                    val consumedY = newPull - pullDistancePx
                    pullDistancePx = newPull
                    return Offset(0f, consumedY)
                }

                // If user drags DOWN at top of list:
                if ((pullDistancePx > 0f || (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)) &&
                    available.y > 0f &&
                    searchQuery.isEmpty()
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
                    available.y > 0f &&
                    searchQuery.isEmpty()
                ) {
                    val newPull = (pullDistancePx + available.y).coerceAtMost(pullThresholdPx)
                    val consumedY = newPull - pullDistancePx
                    pullDistancePx = newPull
                    return Offset(0f, consumedY)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (searchQuery.isEmpty() && pullDistancePx > 0f) {
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

    // Interaction 2: Sticky Header with Centered Title on Scroll Down
    val scrollY by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) 1000f
            else listState.firstVisibleItemScrollOffset.toFloat()
        }
    }

    val headerFadeStartPx = with(density) { 40.dp.toPx() }
    val headerFadeEndPx = with(density) { 80.dp.toPx() }
    val titleFadeStartPx = with(density) { 55.dp.toPx() }
    val titleFadeEndPx = with(density) { 95.dp.toPx() }

    val topBarAlpha by remember {
        derivedStateOf {
            if (searchProgress > 0f) searchProgress
            else ((scrollY - headerFadeStartPx) / (headerFadeEndPx - headerFadeStartPx)).coerceIn(0f, 1f)
        }
    }

    val titleAlpha by remember {
        derivedStateOf {
            if (searchProgress > 0f) 0f
            else ((scrollY - titleFadeStartPx) / (titleFadeEndPx - titleFadeStartPx)).coerceIn(0f, 1f)
        }
    }

    val titleTranslateY by remember {
        derivedStateOf {
            (1f - titleAlpha) * with(density) { 10.dp.toPx() }
        }
    }

    // Explicit RTL enforcement ensures correct layout on all devices
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = rootGradient)
        ) {
            val screenWidth = maxWidth

            // Proportional sizing derived directly from original Spotify reference:
            // Card height ratio: 68px / 576px = ~0.12 (12% of screen width)
            // Cover width: exactly matches cardHeight (square)
            // On standard 360dp phone: ~43dp (clamped to 48dp minimum for readability)
            // On 411dp phone: ~49.3dp
            // On 600dp tablet / 900px emulator: 68dp (matches original 106px!)
            val cardHeight = (screenWidth * 0.12f).coerceIn(48.dp, 72.dp)
            val cardHorizontalPadding = (screenWidth * 0.012f).coerceIn(4.dp, 8.dp)

            if (songsResp is Response.Loading && rawSongs.isEmpty()) {
                Loader()
                return@BoxWithConstraints
            }

            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    val topBarBrush = remember(searchProgress, topBarAlpha) {
                        if (searchProgress > 0f) {
                            SolidColor(Color(0xFF1B2348).copy(alpha = searchProgress))
                        } else {
                            Brush.verticalGradient(
                                listOf(
                                    Color(0xFF23337D).copy(alpha = topBarAlpha),
                                    Color(0xFF1F2B63).copy(alpha = topBarAlpha),
                                    Color(0xFF2C41A5).copy(alpha = topBarAlpha)
                                )
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(topBarBrush)
                            .statusBarsPadding()
                    ) {
                        // 1. TopBar Row: Standalone 64dp (Arrow right, Title center)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(64.dp)
                                .padding(horizontal = 16.dp)
                        ) {
                            // Back arrow on the right in RTL (Alignment.CenterStart)
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "חזרה",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(24.dp)
                                    .align(Alignment.CenterStart)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        if (searchQuery.isNotEmpty()) {
                                            searchQuery = ""
                                            animatePullTo(0f)
                                        } else if (pullDistancePx > 0f) {
                                            animatePullTo(0f)
                                        } else {
                                            navController.navigateUp()
                                        }
                                    }
                            )

                            // Title horizontally CENTERED in screen, vertically aligned with arrow
                            if (titleAlpha > 0.001f) {
                                Text(
                                    text = "שירים שאהבתם",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .align(Alignment.Center)
                                        .graphicsLayer {
                                            alpha = titleAlpha
                                            translationY = titleTranslateY
                                        }
                                )
                            }
                        }

                        // 2. Search & Sort Row: Dedicated row BELOW TopBar (Collapsible)
                        if (searchProgress > 0.001f || searchQuery.isNotEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp * searchProgress)
                                    .padding(horizontal = 16.dp)
                                    .graphicsLayer {
                                        alpha = searchProgress
                                        translationY = (1f - searchProgress) * with(density) { (-16.dp).toPx() }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                ) {
                                    // Search pill on right (RTL first child)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .padding(horizontal = 10.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "חיפוש",
                                            tint = textSecondary,
                                            modifier = Modifier.size(18.dp)
                                        )

                                        Spacer(modifier = Modifier.width(8.dp))

                                        Box(
                                            modifier = Modifier.weight(1f),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (searchQuery.isEmpty()) {
                                                Text(
                                                    text = "חיפוש בשירים שאהבתם",
                                                    color = textSecondary,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            BasicTextField(
                                                value = searchQuery,
                                                onValueChange = { searchQuery = it },
                                                textStyle = TextStyle(
                                                    color = Color.White,
                                                    fontSize = 13.sp
                                                ),
                                                cursorBrush = SolidColor(spotifyGreen),
                                                singleLine = true,
                                                enabled = searchProgress > 0.5f,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        if (searchQuery.isNotEmpty()) {
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "נקה",
                                                tint = textSecondary,
                                                modifier = Modifier
                                                    .size(16.dp)
                                                    .clickable(
                                                        interactionSource = remember { MutableInteractionSource() },
                                                        indication = null
                                                    ) {
                                                        searchQuery = ""
                                                    }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    // Sort button on left
                                    Box(
                                        modifier = Modifier
                                            .height(36.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.White.copy(alpha = 0.12f))
                                            .clickable(enabled = searchProgress > 0.5f) { showSortSheet = true }
                                            .padding(horizontal = 14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "מיון",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                LazyColumn(
                    state = listState,
                    contentPadding = innerPadding,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent)
                        .consumeWindowInsets(innerPadding)
                        .nestedScroll(nestedScrollConnection)
                ) {
                    // Header section - seamless with root gradient
                    item(key = "spotify_liked_header") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                        ) {
                            Text(
                                text = "שירים שאהבתם",
                                color = Color.White,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            val countText = when (displaySongs.size) {
                                0 -> "0 שירים"
                                1 -> "שיר אחד"
                                else -> "${displaySongs.size} שירים"
                            }
                            Text(
                                text = countText,
                                color = textSecondary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                            )

                            // Action controls: Download, Shuffle, Big Play
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                            ) {
                                // Download button (Right side in RTL)
                                var likedDownloaded by remember(rawSongs) {
                                    mutableStateOf(rawSongs.isNotEmpty() && SongPlayer.allDownloaded(rawSongs, context))
                                }
                                if (rawSongs.isNotEmpty()) {
                                    Icon(
                                        imageVector = if (likedDownloaded)
                                            Icons.Default.CheckCircle else ImageVector.vectorResource(R.drawable.ic_spotify_download),
                                        tint = if (likedDownloaded) spotifyGreen else textSecondary,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                if (!likedDownloaded) {
                                                    SongPlayer.downloadAll(rawSongs, context)
                                                    android.widget.Toast.makeText(
                                                        context,
                                                        "מוריד ${rawSongs.size} שירים...",
                                                        android.widget.Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            },
                                        contentDescription = "הורדת שירים"
                                    )
                                }

                                Spacer(modifier = Modifier.weight(1f))

                                // Shuffle-play
                                if (displaySongs.isNotEmpty()) {
                                    Icon(
                                        painter = painterResource(id = R.drawable.ic_player_shuffle),
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                likedSongsViewModel.startShuffled(displaySongs)?.let { first ->
                                                    SongPlayer.playSong(first.url, context)
                                                    likedSongsViewModel.updateSongState(
                                                        first.coverUri,
                                                        first.title,
                                                        first.singer,
                                                        true,
                                                        first.id,
                                                        0,
                                                        "Liked Songs"
                                                    )
                                                }
                                            },
                                        contentDescription = "השמעה אקראית"
                                    )
                                    Spacer(modifier = Modifier.width(18.dp))
                                }

                                // Big Spotify Green Play button (Left side in RTL)
                                // Operates consistently on displaySongs (Point 7 fix)
                                if (displaySongs.isNotEmpty()) {
                                    val playing = likedSongsViewModel.currentSongPlayingState.value
                                    val isPlayingCurrent = displaySongs.any { it.id == likedSongsViewModel.currentSongId.value }
                                    val headerPlayButtonAlpha by remember {
                                        derivedStateOf { (1f - titleAlpha).coerceIn(0f, 1f) }
                                    }
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .graphicsLayer { alpha = headerPlayButtonAlpha }
                                            .clip(CircleShape)
                                            .background(spotifyGreen)
                                            .clickable(
                                                enabled = headerPlayButtonAlpha > 0.1f,
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null
                                            ) {
                                                when {
                                                    playing -> likedSongsViewModel.setPlaying(false)
                                                    isPlayingCurrent -> likedSongsViewModel.setPlaying(true)
                                                    else -> {
                                                        likedSongsViewModel.updateQueue(displaySongs)
                                                        SongPlayer.playSong(displaySongs[0].url, context)
                                                        likedSongsViewModel.updateSongState(
                                                            displaySongs[0].coverUri,
                                                            displaySongs[0].title,
                                                            displaySongs[0].singer,
                                                            true,
                                                            displaySongs[0].id,
                                                            0,
                                                            "Liked Songs"
                                                        )
                                                    }
                                                }
                                            }
                                    ) {
                                        Icon(
                                            modifier = Modifier.size(22.dp),
                                            tint = Color.Black,
                                            painter = painterResource(
                                                id = if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com
                                            ),
                                            contentDescription = if (playing) "השהה" else "נגן"
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            // "הוספה לפלייליסט הזה" row matching Spotify: Sharp + on the FAR RIGHT, text to its left
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 6.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        navController.navigate(Routes.AddToLiked.route)
                                    }
                            ) {
                                // Square + button on the right matching Spotify: 44dp, #2A2A2A, 2dp radius (sharp)
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(cardBackground)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "הוספה",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Text(
                                    text = "הוספה לפלייליסט הזה",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Song items as Spotify cards with proportional dimensions
                    itemsIndexed(
                        displaySongs,
                        key = { index, song ->
                            when {
                                song.spotifyTrackId.isNotBlank() -> "sp_${song.spotifyTrackId}"
                                song.id != 0 -> "id_${song.id}"
                                song.url.isNotBlank() -> "url_${song.url}"
                                else -> "song_${index}_${song.title}"
                            }
                        }
                    ) { index, song ->
                        val isCurrent = song.id == likedSongsViewModel.currentSongId.value
                        val currentTitleColor = if (isCurrent) spotifyGreen else Color.White
                        val playing = likedSongsViewModel.currentSongPlayingState.value

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = cardHorizontalPadding, vertical = 2.5.dp)
                                .height(cardHeight)
                                .clip(RoundedCornerShape(8.dp))
                                .background(cardBackground)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onLongClick = { menuSong = song },
                                    onClick = {
                                        likedSongsViewModel.updateQueue(displaySongs)
                                        likedSongsViewModel.updateSongState(
                                            song.coverUri,
                                            song.title,
                                            song.singer,
                                            true,
                                            song.id,
                                            index,
                                            "Liked Songs"
                                        )
                                        SongPlayer.playSong(song.url, context)
                                    }
                                )
                        ) {
                            // Full height cover image on the right (RTL layout)
                            // Outer right corners (start in RTL) have 8dp radius, inner left corners are 0dp
                            Box(
                                modifier = Modifier
                                    .size(cardHeight)
                                    .clip(
                                        RoundedCornerShape(
                                            topStart = 8.dp,
                                            bottomStart = 8.dp,
                                            topEnd = 0.dp,
                                            bottomEnd = 0.dp
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                GlideImage(
                                    modifier = Modifier.fillMaxSize(),
                                    model = song.coverUri,
                                    failure = placeholder(R.drawable.placeholder),
                                    loading = placeholder(R.drawable.placeholder),
                                    contentScale = ContentScale.Crop,
                                    isAllowed = com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(song),
                                    contentDescription = song.title
                                )

                                // Subtle play circle overlay matching Spotify
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.4f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        painter = painterResource(
                                            id = if (isCurrent && playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com
                                        ),
                                        contentDescription = null,
                                        tint = if (isCurrent) spotifyGreen else Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }

                            // Title & singer
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                            ) {
                                Text(
                                    text = song.title,
                                    color = currentTitleColor,
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (song.explicit) {
                                        Box(
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                            .background(textSecondary),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "E",
                                                color = Color.Black,
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = song.singer,
                                        color = textSecondary,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // 3-dots more menu on the left (End in RTL)
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { menuSong = song },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "אפשרויות",
                                    tint = textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Space for bottom player bar
                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(130.dp))
                    }
                }
            }

            // Sticky Floating Green Play button pinned above the sticky header layer on the left (End in RTL)
            if (displaySongs.isNotEmpty() && titleAlpha > 0.001f && searchProgress <= 0.001f) {
                val playing = likedSongsViewModel.currentSongPlayingState.value
                val isPlayingCurrent = displaySongs.any { it.id == likedSongsViewModel.currentSongId.value }
                val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                // TopBar row height is 64dp. The play button radius is 24dp (half of 48dp).
                // Centering the button on the bottom edge of the 64dp sticky header:
                val playButtonTop = statusBarTop + 64.dp - 24.dp

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(top = playButtonTop)
                        .padding(end = 16.dp)
                        .align(Alignment.TopEnd) // In RTL, End is the LEFT edge
                        .size(48.dp)
                        .graphicsLayer {
                            alpha = titleAlpha
                            scaleX = 0.8f + 0.2f * titleAlpha
                            scaleY = 0.8f + 0.2f * titleAlpha
                        }
                        .shadow(elevation = 6.dp, shape = CircleShape)
                        .clip(CircleShape)
                        .background(spotifyGreen)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            when {
                                playing -> likedSongsViewModel.setPlaying(false)
                                isPlayingCurrent -> likedSongsViewModel.setPlaying(true)
                                else -> {
                                    likedSongsViewModel.updateQueue(displaySongs)
                                    SongPlayer.playSong(displaySongs[0].url, context)
                                    likedSongsViewModel.updateSongState(
                                        displaySongs[0].coverUri,
                                        displaySongs[0].title,
                                        displaySongs[0].singer,
                                        true,
                                        displaySongs[0].id,
                                        0,
                                        "Liked Songs"
                                    )
                                }
                            }
                        }
                ) {
                    Icon(
                        modifier = Modifier.size(22.dp),
                        tint = Color.Black,
                        painter = painterResource(
                            id = if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com
                        ),
                        contentDescription = if (playing) "השהה" else "נגן"
                    )
                }
            }
        }
    }
}

/**
 * BottomSheet for Liked Songs sorting options matching Spotify 1:1
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LikedSortBottomSheet(
    currentSort: LikedSortOrder,
    onSortSelected: (LikedSortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF242424),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF5A5A5A))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "מיון לפי",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )

            LikedSortOrder.values().forEach { sort ->
                val selected = sort == currentSort
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSortSelected(sort)
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = sort.title,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f)
                    )

                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "נבחר",
                            tint = Color(0xFF1ED760),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
