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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.MainActivity
import com.music.spotui.R
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

    val isPlaying = currentSongState.playingState.value
    val title = currentSongState.title.value
    val singer = currentSongState.singer.value
    val coverUri = currentSongState.coverUri.value
    val queue = currentSongState.queue.value
    val currentSongId = currentSongState.songId.value

    var progress by remember { mutableFloatStateOf(0f) }

    // Live progress tracker for the green ring
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            val dur = SongPlayer.getDuration()
            val pos = SongPlayer.getCurrentPosition()
            if (dur > 0) {
                progress = (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
            }
            delay(500)
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
                            Color(0xFF161616),
                            RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                        )
                        .padding(top = 28.dp, bottom = 12.dp, start = 16.dp, end = 16.dp)
                ) {
                    // Header Row
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
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "פתח את Spotify",
                                color = Color(0xFF1ED760),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF262626)
                        ) {
                            Text(
                                text = "Audio apps",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (!isShowList) {
                        // Song Information Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GlideImage(
                                model = coverUri.ifBlank { null },
                                contentDescription = null,
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(6.dp)),
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
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = singer.ifBlank { "SpotUI" },
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Controls Area
                        if (!isPlaying && title.isNotBlank()) {
                            // Paused state: Big green "המשך ניגון" button
                            Button(
                                onClick = {
                                    SongPlayer.play()
                                    currentSongState.updatePlayingState(true)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = "המשך ניגון",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                        } else {
                            // Playing / Active state: Full control buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Like / Add button
                                IconButton(
                                    onClick = {
                                        if (currentSongId != 0) {
                                            com.music.spotui.data.preferences.addLikedSongId(context, currentSongId.toString())
                                        }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Add",
                                        tint = Color.White,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                // Skip Previous
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

                                // Play / Pause with circular progress ring
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(54.dp)
                                ) {
                                    CircularProgressIndicator(
                                        progress = { progress },
                                        color = Color(0xFF1ED760),
                                        trackColor = Color(0xFF333333),
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    IconButton(
                                        onClick = {
                                            if (isPlaying) {
                                                SongPlayer.pause()
                                                currentSongState.updatePlayingState(false)
                                            } else {
                                                SongPlayer.play()
                                                currentSongState.updatePlayingState(true)
                                            }
                                        },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(
                                                id = if (isPlaying) R.drawable.ic_paused else R.drawable.play_svgrepo_com
                                            ),
                                            contentDescription = if (isPlaying) "Pause" else "Play",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }

                                // Skip Next
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

                        Spacer(modifier = Modifier.height(10.dp))

                        // Bottom Row: Show list on left, Blue Chevron collapse button on right
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
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Show list",
                                    tint = Color(0xFF1ED760),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
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
                                    .size(38.dp)
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
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // ── 3. Show List View (3 Columns Grid) ──
                        Text(
                            text = "רשימת שירים לבחירה",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(queue.take(18)) { song ->
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
                                                songIndex = queue.indexOf(song),
                                                album = song.album
                                            )
                                            SongPlayer.playSong(song.url, context)
                                            isShowList = false
                                        }
                                ) {
                                    GlideImage(
                                        model = song.coverUri.ifBlank { null },
                                        contentDescription = song.title,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = song.title,
                                        color = if (song.id == currentSongId) Color(0xFF1ED760) else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Close List button
                        Button(
                            onClick = { isShowList = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(
                                text = "סגירה",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
