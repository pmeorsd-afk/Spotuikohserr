package com.music.spotui.ui.overlay

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import com.music.spotui.data.preferences.getWazeButtonSize
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.GlideImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WazeOverlayView(
    currentSongState: CurrentSongState,
    isExpanded: Boolean = false,
    onExpandChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var isShowList by remember { mutableStateOf(false) }

    val isPlayingState by rememberUpdatedState(currentSongState.playingState.value)
    var isPlayingLive by remember { mutableStateOf(SongPlayer.isPlaying()) }
    val isPlaying = isPlayingState || isPlayingLive

    val liveTitle = currentSongState.title.value
    val liveSinger = currentSongState.singer.value
    val liveCoverUri = currentSongState.coverUri.value
    val liveSongId = currentSongState.songId.value

    val lastSavedTrack = remember(liveTitle, isExpanded) {
        if (liveTitle.isBlank()) com.music.spotui.data.preferences.getLastPlayedTrack(context) else null
    }

    val title = if (liveTitle.isNotBlank()) liveTitle else lastSavedTrack?.title.orEmpty()
    val singer = if (liveSinger.isNotBlank()) liveSinger else lastSavedTrack?.singer.orEmpty()
    val coverUri = if (liveCoverUri.isNotBlank()) liveCoverUri else lastSavedTrack?.coverUri.orEmpty()
    val currentSongId = if (liveSongId > 0) liveSongId else lastSavedTrack?.songId ?: 0

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
        val savedSize = remember { getWazeButtonSize(context, 54) }
        Surface(
            shape = CircleShape,
            color = Color.White,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_spotify_waze),
                    contentDescription = "Spotify",
                    modifier = Modifier.size((savedSize * 0.65f).dp)
                )
            }
        }
    } else {
        // ── 2. Expanded Mode: Full screen overlay with backdrop & top player bar ──
        val visibleState = remember {
            MutableTransitionState(false).apply {
                targetState = true
            }
        }
        val coroutineScope = rememberCoroutineScope()
        val closePlayer: () -> Unit = {
            coroutineScope.launch {
                isShowList = false
                visibleState.targetState = false
                delay(320)
                onExpandChanged(false)
            }
        }

        val backdropAlpha by animateFloatAsState(
            targetValue = if (visibleState.targetState && visibleState.currentState) 0.35f else 0f,
            animationSpec = tween(300),
            label = "backdropAlpha"
        )

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Backdrop: tapping outside closes the player with smooth exit
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = backdropAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        closePlayer()
                    }
            )

            // Animated Top Bar with FastOutSlowInEasing (380ms smooth slide down from top)
            AnimatedVisibility(
                visibleState = visibleState,
                enter = slideInVertically(
                    initialOffsetY = { -it },
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(380)),
                exit = slideOutVertically(
                    targetOffsetY = { -it },
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing)
                ) + fadeOut(animationSpec = tween(300)),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                    color = Color(0xFF222426),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        if (!isShowList) {
                            // ── Top Header Row (LTR forced to match original Spotify) ──
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Left: Mini App Icon + "פתח את Spotify"
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                closePlayer()
                                                val launchIntent = Intent(context, MainActivity::class.java).apply {
                                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                }
                                                context.startActivity(launchIntent)
                                            }
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_spotify_waze),
                                            contentDescription = "Open Spotify",
                                            modifier = Modifier.size(24.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "פתח את Spotify",
                                            color = Color(0xFF1ED760),
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Right: Track Source Indicator
                                    Text(
                                        text = "השירים שאהבת",
                                        color = Color(0xFF9E9E9E),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Normal
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Divider(color = Color(0xFF383B3E), thickness = 0.8.dp)
                            Spacer(modifier = Modifier.height(10.dp))

                            // ── Middle Row: Cover Art + Title/Artist ──
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Album / Song Cover
                                    Box(
                                        modifier = Modifier
                                            .size(54.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF2C2C2C))
                                    ) {
                                        GlideImage(
                                            model = coverUri.ifBlank { null },
                                            contentDescription = title,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    // Song Title & Artist
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Text(
                                            text = title.ifBlank { "אין שיר מנוגן" },
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = singer.ifBlank { "Spotify" },
                                            color = Color(0xFFB3B3B3),
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // ── Controls Row ──
                            if (!isPlaying) {
                                // Paused State: Big Green "המשך ניגון" Pill Button
                                Button(
                                    onClick = {
                                        if (liveTitle.isNotBlank() && liveSongId > 0 && com.music.spotui.MainActivity.isAlive) {
                                            // נסיון ראשון: המשך במקום. אם זה באמת לא מתחיל לנגן תוך 700ms
                                            // (הנגן האמיתי מאחורי CurrentSongState אופס) - נופלים למסלול הכבד.
                                            SongPlayer.play()
                                            currentSongState.updatePlayingState(true)
                                            isPlayingLive = true
                                            coroutineScope.launch {
                                                delay(700)
                                                if (!SongPlayer.isPlaying()) {
                                                    currentSongState.updatePlayingState(false)
                                                    isPlayingLive = false
                                                    closePlayer()
                                                    resumeFromPersistedTrack(context)
                                                }
                                            }
                                        } else {
                                            closePlayer()
                                            resumeFromPersistedTrack(context)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1ED760)),
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(42.dp)
                                ) {
                                    Text(
                                        text = "המשך ניגון",
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                // Playing State: 4 Control Icons (Add, Prev, Pause with Green Progress Ring, Next)
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Add to Liked (+)
                                        IconButton(
                                            onClick = {
                                                addToLiked(context, currentSongState)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "Add to Liked",
                                                tint = Color.White,
                                                modifier = Modifier.size(24.dp)
                                            )
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
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }

                                        // Center Pause with Green Progress Circle
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.size(50.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                progress = { progress },
                                                color = Color(0xFF1ED760),
                                                trackColor = Color(0xFF3E4246),
                                                strokeWidth = 3.dp,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            IconButton(
                                                onClick = {
                                                    if (isPlaying) {
                                                        SongPlayer.pause()
                                                        currentSongState.updatePlayingState(false)
                                                        isPlayingLive = false
                                                        // ההשהיה סוגרת את המיני-נגן וחוזרת לכפתור בלבד
                                                        closePlayer()
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
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Divider(color = Color(0xFF383B3E), thickness = 0.8.dp)
                            Spacer(modifier = Modifier.height(6.dp))

                            // ── Bottom Bar Row: Show list button & Collapse Chevron ──
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    // Show List Button (Left side)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                isShowList = true
                                            }
                                            .padding(vertical = 4.dp, horizontal = 2.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.KeyboardArrowDown,
                                            contentDescription = "Show list",
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Show list",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    Spacer(modifier = Modifier.weight(1f))

                                    // Right: Collapse Chevron (Blue arrow pointing up)
                                    IconButton(
                                        onClick = {
                                            closePlayer()
                                        },
                                        modifier = Modifier.size(32.dp)
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
                        } else {
                            // ── 3. Show List View (3 Columns Grid) ──
                            val showListSongs = remember(currentSongState.queue.value, currentSongId) {
                                val result = mutableListOf<SongsModel>()
                                val seenArtists = mutableSetOf<String>()

                                // 1. Current track
                                val currentSong = currentSongState.queue.value.firstOrNull { it.id == currentSongId }
                                if (currentSong != null) {
                                    result.add(currentSong)
                                    seenArtists.add(currentSong.singer.lowercase().trim())
                                }

                                // 2. Different tracks from history
                                val history = getListeningHistory(context)
                                for (entry in history) {
                                    val aKey = entry.singer.lowercase().trim()
                                    if (aKey !in seenArtists && entry.title.isNotBlank()) {
                                        val sId = if (entry.songId > 0) entry.songId else (entry.title + entry.singer).hashCode() and 0x7fffffff
                                        result.add(
                                            SongsModel(
                                                id = sId,
                                                title = entry.title,
                                                singer = entry.singer,
                                                coverUri = entry.image,
                                                album = entry.album,
                                                url = SongPlayer.buildSpotifyPlayQuery(sId.toString(), entry.title, entry.singer)
                                            )
                                        )
                                        seenArtists.add(aKey)
                                    }
                                    if (result.size >= 18) break
                                }

                                // 3. Fallback diverse curated tracks
                                if (result.size < 9) {
                                    val fallbacks = listOf(
                                        Triple("מלאך של כבוד", "Omer Adam", "https://i.scdn.co/image/ab67616d0000b27387f3b7b203c9454ee689f029"),
                                        Triple("אמא אם הייתי", "חנן בן ארי", "https://i.scdn.co/image/ab67616d0000b273f5ba3bfa2c5d19f564757c91"),
                                        Triple("במה קהל אהבה", "ישי ריבו", "https://i.scdn.co/image/ab67616d0000b2731872df0d00f7d54b455cb783"),
                                        Triple("ניגוני הינוקא", "הינוקא", "https://i.scdn.co/image/ab67616d0000b27341857ba0b6d214a1a5b6c813"),
                                        Triple("אלף מנעולים", "עקיבא", "https://i.scdn.co/image/ab67616d0000b273b067a9994c6bc312e737c355"),
                                        Triple("ניגונים", "יובל דיין", "https://i.scdn.co/image/ab67616d0000b273a21644ce63b06a45749f7833"),
                                        Triple("לוחות הלב", "עולמות", "https://i.scdn.co/image/ab67616d0000b273184d1264c78d5218d6a782b5"),
                                        Triple("צמאה 5", "אברהם פריד", "https://i.scdn.co/image/ab67616d0000b273fdfbcf6a17b075b6d9e03d7c"),
                                        Triple("דלתי תשובה", "שולי רנד", "https://i.scdn.co/image/ab67616d0000b27362a98f1fbe147b2c0db3b429"),
                                        Triple("נפשי בשאלתי", "נתן גושן", "https://i.scdn.co/image/ab67616d0000b273b7d159a6eaebcb64e8e19572"),
                                        Triple("ניגון הבעל שם טוב", "חיליק פרנק", "https://i.scdn.co/image/ab67616d0000b27303d7d7b1b5e5ebfc34563a69"),
                                        Triple("מיקס ישי ריבו", "ישי ריבו", "https://i.scdn.co/image/ab67616d0000b273c3327d9ba6d781b0f1625d97")
                                    )
                                    for ((fTitle, fSinger, fCover) in fallbacks) {
                                        if (result.none { it.title == fTitle }) {
                                            val fId = (fTitle + fSinger).hashCode() and 0x7fffffff
                                            result.add(
                                                SongsModel(
                                                    id = fId,
                                                    title = fTitle,
                                                    singer = fSinger,
                                                    coverUri = fCover,
                                                    album = fTitle,
                                                    url = SongPlayer.buildSpotifyPlayQuery(fId.toString(), fTitle, fSinger)
                                                )
                                            )
                                        }
                                        if (result.size >= 18) break
                                    }
                                }

                                // 4. Fill with remaining queue items
                                for (qSong in currentSongState.queue.value) {
                                    if (result.none { it.id == qSong.id || it.title == qSong.title }) {
                                        result.add(qSong)
                                    }
                                    if (result.size >= 18) break
                                }

                                result
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
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        isShowList = false
                                    }
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun skipNext(context: Context, currentSongState: CurrentSongState) {
    val q = currentSongState.queue.value
    val curIndex = currentSongState.songIndex.value
    if (q.isNotEmpty() && curIndex >= 0 && curIndex + 1 < q.size) {
        val nextSong = q[curIndex + 1]
        currentSongState.updateSongState(
            coverUri = nextSong.coverUri,
            title = nextSong.title,
            singer = nextSong.singer,
            playingState = true,
            songId = nextSong.id,
            songIndex = curIndex + 1,
            album = nextSong.album
        )
        SongPlayer.playSong(nextSong.url, context)
    }
}

private fun skipPrevious(context: Context, currentSongState: CurrentSongState) {
    val q = currentSongState.queue.value
    val curIndex = currentSongState.songIndex.value
    if (q.isNotEmpty() && curIndex > 0) {
        val prevSong = q[curIndex - 1]
        currentSongState.updateSongState(
            coverUri = prevSong.coverUri,
            title = prevSong.title,
            singer = prevSong.singer,
            playingState = true,
            songId = prevSong.id,
            songIndex = curIndex - 1,
            album = prevSong.album
        )
        SongPlayer.playSong(prevSong.url, context)
    }
}

private fun addToLiked(context: Context, currentSongState: CurrentSongState) {
    // Add song to local liked playlist
}

/**
 * "המשך ניגון" נלחץ אבל CurrentSongState ריק - פותחים את MainActivity עם השיר האחרון שנשמר,
 * כדי שהיא תטען אותו ותנגן, ואז תחזור לבד לוויז.
 */
private fun resumeFromPersistedTrack(context: Context) {
    val track = com.music.spotui.data.preferences.getLastPlayedTrack(context)
    android.util.Log.d("WazeResume", "track from prefs: $track")
    if (track == null) {
        android.util.Log.w("WazeResume", "no persisted track - nothing to resume")
        return
    }
    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(com.music.spotui.data.preferences.WazeResumeContract.EXTRA_SONG_ID, track.songId)
        putExtra(com.music.spotui.data.preferences.WazeResumeContract.EXTRA_TITLE, track.title)
        putExtra(com.music.spotui.data.preferences.WazeResumeContract.EXTRA_SINGER, track.singer)
        putExtra(com.music.spotui.data.preferences.WazeResumeContract.EXTRA_ALBUM, track.album)
        putExtra(com.music.spotui.data.preferences.WazeResumeContract.EXTRA_COVER_URI, track.coverUri)
        putExtra(com.music.spotui.data.preferences.WazeResumeContract.EXTRA_RETURN_TO_WAZE, true)
    }
    try {
        context.startActivity(intent)
        android.util.Log.d("WazeResume", "startActivity() returned normally")
    } catch (e: Exception) {
        android.util.Log.e("WazeResume", "startActivity() threw", e)
    }
}
