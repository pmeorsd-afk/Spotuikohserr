package com.music.spotui.ui.overlay

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.MainActivity
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.getListeningHistory
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.GlideImage
import kotlinx.coroutines.delay

@Composable
fun WazeOverlayView(
    currentSongState: CurrentSongState,
    onExpandChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isExpanded by remember { mutableStateOf(false) }
    var isShowList by remember { mutableStateOf(false) }

    val isPlayingState by rememberUpdatedState(currentSongState.playingState.value)
    var isPlayingLive by remember { mutableStateOf(SongPlayer.isPlaying()) }
    val isPlaying = isPlayingState || isPlayingLive

    val title = currentSongState.title.value
    val singer = currentSongState.singer.value
    val coverUri = currentSongState.coverUri.value
    val currentSongId = currentSongState.songId.value

    var progress by remember { mutableFloatStateOf(0f) }

    // Real-time position & play state watcher
    LaunchedEffect(Unit) {
        while (true) {
            isPlayingLive = SongPlayer.isPlaying()
            val dur = SongPlayer.getDuration()
            val pos = SongPlayer.getCurrentPosition()
            if (dur > 0) {
                progress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            }
            delay(400)
        }
    }

    if (!isExpanded) {
        // ── 1. Collapsed Floating Spotify Button ──
        Surface(
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    isExpanded = true
                    onExpandChanged(true)
                }
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_spotify_waze),
                    contentDescription = "Spotify",
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    } else {
        // ── 2. Expanded Mode: Full screen overlay with backdrop & top player bar ──
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Backdrop: tapping outside closes the player
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isExpanded = false
                        isShowList = false
                        onExpandChanged(false)
                    }
            )

            // Top Bar with slide-in animation
            AnimatedVisibility(
                visible = isExpanded,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(
                            Color(0xFF222426),
                            RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        )
                        .padding(top = 28.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header Row (Forced LTR for Spotify 1:1 look)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    val intent = Intent(context, MainActivity::class.java).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_spotify_waze),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "פתח את Spotify",
                                    color = Color(0xFF1ED760),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            if (!isShowList) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = Color(0xFF000000)
                                ) {
                                    Text(
                                        text = "Audio apps",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF383B3E), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (!isShowList) {
                        // Song Information Row (Cover on Left, Title/Artist next to it)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                GlideImage(
                                    model = coverUri.ifBlank { null },
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF333333)),
                                    contentScale = ContentScale.Crop
                                )

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = title.ifBlank { "אין שיר מנוגן" },
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = singer.ifBlank { "Spotify" },
                                        color = Color(0xFFA6A8AA),
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Controls Area
                        if (!isPlaying && title.isNotBlank()) {
                            // Paused state: Big green "המשך ניגון" button with white text
                            Button(
                                onClick = {
                                    SongPlayer.play()
                                    currentSongState.updatePlayingState(true)
                                    isPlayingLive = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                            ) {
                                Text(
                                    text = "המשך ניגון",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            // Playing / Active state: Full control buttons (Forced LTR order)
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Like / Add button (+)
                                    IconButton(
                                        onClick = {
                                            if (currentSongId != 0) {
                                                com.music.spotui.data.preferences.addLikedSongId(context, currentSongId.toString())
                                            }
                                        }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .border(2.dp, Color.White, CircleShape)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }

                                    // Skip Previous (|<<)
                                    IconButton(
                                        onClick = {
                                            skipPrevious(context, currentSongState)
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_player_back),
                                            contentDescription = "Previous",
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }

                                    // Pause button with green progress ring (||)
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.size(56.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            progress = { progress },
                                            color = Color(0xFF1ED760),
                                            trackColor = Color.White,
                                            strokeWidth = 3.dp,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        IconButton(
                                            onClick = {
                                                if (isPlaying) {
                                                    SongPlayer.pause()
                                                    currentSongState.updatePlayingState(false)
                                                    isPlayingLive = false
                                                } else {
                                                    SongPlayer.play()
                                                    currentSongState.updatePlayingState(true)
                                                    isPlayingLive = true
                                                }
                                            },
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(
                                                    id = if (isPlaying) R.drawable.ic_playing else R.drawable.play_svgrepo_com
                                                ),
                                                contentDescription = if (isPlaying) "Pause" else "Play",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }

                                    // Skip Next (>>|)
                                    IconButton(
                                        onClick = {
                                            skipNext(context, currentSongState)
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_player_skip),
                                            contentDescription = "Next",
                                            tint = Color.White,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color(0xFF383B3E), thickness = 1.dp)
                        Spacer(modifier = Modifier.height(8.dp))

                        // Bottom Row: Show list on left, Blue Chevron collapse button on right
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { isShowList = true }
                                        .padding(vertical = 4.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = Color(0xFF1ED760),
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowDown,
                                                contentDescription = "Show list",
                                                tint = Color.Black,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Show list",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                // Collapse Button (White circle with blue chevron)
                                Surface(
                                    shape = CircleShape,
                                    color = Color.White,
                                    shadowElevation = 4.dp,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clickable {
                                            isExpanded = false
                                            isShowList = false
                                            onExpandChanged(false)
                                        }
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_chevron_up_blue),
                                            contentDescription = "Collapse",
                                            tint = Color(0xFF0077FF),
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // ── 3. Show List View (3 Columns Grid) ──
                        val showListSongs = remember(currentSongState.queue.value) {
                            val q = currentSongState.queue.value
                            if (q.isNotEmpty()) {
                                q.take(18)
                            } else {
                                val hist = getListeningHistory(context)
                                if (hist.isNotEmpty()) {
                                    hist.take(18).map {
                                        SongsModel(
                                            id = it.songId,
                                            title = it.title,
                                            singer = it.singer,
                                            coverUri = it.image,
                                            album = it.album,
                                            url = SongPlayer.buildSpotifyPlayQuery(it.songId.toString(), it.title, it.singer)
                                        )
                                    }
                                } else {
                                    emptyList()
                                }
                            }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(270.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(showListSongs) { song ->
                                val isSelected = song.id == currentSongId
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable {
                                            currentSongState.updateSongState(
                                                coverUri = song.coverUri,
                                                title = song.title,
                                                singer = song.singer,
                                                playingState = true,
                                                songId = song.id,
                                                songIndex = showListSongs.indexOf(song),
                                                album = song.album
                                            )
                                            SongPlayer.playSong(song.url, context)
                                            isShowList = false
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(86.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .then(
                                                if (isSelected) Modifier.border(2.dp, Color(0xFF1ED760), RoundedCornerShape(6.dp))
                                                else Modifier
                                            )
                                    ) {
                                        GlideImage(
                                            model = song.coverUri.ifBlank { null },
                                            contentDescription = song.title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = song.title,
                                        color = if (isSelected) Color(0xFF1ED760) else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Close List button ("סגירה")
                        Text(
                            text = "סגירה",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isShowList = false }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun skipNext(context: Context, state: CurrentSongState) {
    val q = state.queue.value
    if (q.isEmpty()) return
    val curId = state.songId.value
    val curIdx = q.indexOfFirst { it.id == curId }.takeIf { it >= 0 } ?: state.songIndex.value.coerceIn(0, q.size - 1)
    val nextIdx = if (curIdx < q.size - 1) curIdx + 1 else 0
    val nextSong = q[nextIdx]
    state.updateSongState(
        nextSong.coverUri,
        nextSong.title,
        nextSong.singer,
        true,
        nextSong.id,
        nextIdx,
        nextSong.album
    )
    SongPlayer.playSong(nextSong.url, context)
}

private fun skipPrevious(context: Context, state: CurrentSongState) {
    val q = state.queue.value
    if (q.isEmpty()) return
    val curId = state.songId.value
    val curIdx = q.indexOfFirst { it.id == curId }.takeIf { it >= 0 } ?: state.songIndex.value.coerceIn(0, q.size - 1)
    val prevIdx = if (curIdx > 0) curIdx - 1 else q.size - 1
    val prevSong = q[prevIdx]
    state.updateSongState(
        prevSong.coverUri,
        prevSong.title,
        prevSong.singer,
        true,
        prevSong.id,
        prevIdx,
        prevSong.album
    )
    SongPlayer.playSong(prevSong.url, context)
}
