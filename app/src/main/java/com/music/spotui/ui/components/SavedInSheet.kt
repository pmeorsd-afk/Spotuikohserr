package com.music.spotui.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.placeholder
import com.metrolist.spotify.Spotify
import com.music.spotui.R
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.CustomPlaylistStore
import com.music.spotui.data.preferences.addCustomPlaylist
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.getCachedPlaylists
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.removeLikedSong
import com.music.spotui.ui.viewmodel.HomeRefreshSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedInPlaylistItem(
    val id: String,
    val name: String,
    val trackCount: Int,
    val coverUrl: String,
)

/**
 * Spotify-authentic "Saved in" / "נשמר בתיקייה" bottom sheet.
 * Sampled directly 1:1 from official Spotify:
 * - Content-driven height (no arbitrary magic heights; wraps small lists cleanly, peeks naturally on larger lists)
 * - Continuous real-time drag physics tracking user gesture via sheet offset
 * - Expanded header: "ביטול" (right) and "סיום" (left)
 * - Header row: "נשמר בתיקייה" (right, 20sp bold) and "פלייליסט חדש" (left, SpotifyGreen 14sp bold)
 * - Row 1: "שירים שאהבתם" (Purple gradient heart, green pushpin indicator, green checkmark / plus outline)
 * - Playlists: Name, track count ("2 שירים" / "שיר 1" / "ריק"), cover art, green checkmark / plus outline
 * - Bottom playlist item: "פלייליסט חדש" (Dark grey + icon, 48x48dp)
 * - Floating bottom action pill: "סיום" in SpotifyGreen (#1ED760)
 * - Persistent CustomPlaylistStore for local playlists + Spotify API for server playlists
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class)
@Composable
fun SavedInSheet(
    song: SongsModel,
    context: Context,
    onDismiss: () -> Unit,
    onLikedChanged: (Boolean) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()
    var liked by remember { mutableStateOf(isSongLiked(context, song)) }
    val membership = remember { mutableStateMapOf<String, Boolean>() }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Load playlists: local custom playlists + cached server playlists (excluding system shortcuts)
    val cachedPlaylists = remember {
        getCachedPlaylists(context).map { entry ->
            val localCount = if (entry.spotifyId.startsWith("custom_")) {
                CustomPlaylistStore.getPlaylists(context).firstOrNull { it.id == entry.spotifyId }?.songKeys?.size ?: 0
            } else 0
            SavedInPlaylistItem(
                id = entry.spotifyId,
                name = entry.name,
                trackCount = localCount,
                coverUrl = entry.coverUri
            )
        }
    }
    val playlistItems = remember { mutableStateListOf<SavedInPlaylistItem>().apply { addAll(cachedPlaylists) } }

    // Check membership for local playlists immediately
    LaunchedEffect(Unit) {
        playlistItems.forEach { item ->
            if (item.id.startsWith("custom_")) {
                membership[item.id] = CustomPlaylistStore.isSongInPlaylist(context, item.id, song)
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val maxSheetHeight = screenHeight * 0.92f
    val screenHeightPx = with(density) { screenHeight.toPx() }
    val maxSheetHeightPx = with(density) { maxSheetHeight.toPx() }
    val targetExpandedOffset = screenHeightPx - maxSheetHeightPx



    val dismissSheet: () -> Unit = {
        coroutineScope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    // Async sync with Spotify server playlists
    LaunchedEffect(Unit) {
        val tokenOk = withContext(Dispatchers.IO) {
            SpotifyTokenProvider.ensureToken(context.applicationContext)
        }
        if (tokenOk) {
            val serverPlaylists = withContext(Dispatchers.IO) {
                Spotify.myPlaylists(limit = 50).getOrNull()?.items.orEmpty()
            }

            val existingIds = playlistItems.map { it.id }.toSet()
            val existingNames = playlistItems.map { it.name.trim().lowercase() }.toSet()

            val newItems = serverPlaylists
                .filter { it.id.isNotBlank() && it.id !in existingIds && it.name.trim().lowercase() !in existingNames }
                .map { sp ->
                    SavedInPlaylistItem(
                        id = sp.id,
                        name = sp.name,
                        trackCount = sp.tracks?.total ?: 0,
                        coverUrl = sp.images.firstOrNull()?.url ?: ""
                    )
                }

            playlistItems.forEachIndexed { idx, item ->
                serverPlaylists.firstOrNull { it.id == item.id }?.let { sp ->
                    playlistItems[idx] = item.copy(
                        name = sp.name,
                        trackCount = sp.tracks?.total ?: item.trackCount,
                        coverUrl = sp.images.firstOrNull()?.url ?: item.coverUrl
                    )
                }
            }
            playlistItems.addAll(newItems)

            newItems.forEach { item ->
                addCustomPlaylist(
                    context,
                    LibraryEntry(
                        spotifyId = item.id,
                        name = item.name,
                        subtitle = "Playlist • " + CustomPlaylistStore.getSubtitle(item.trackCount),
                        coverUri = item.coverUrl,
                        isPlaylist = true
                    )
                )
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            initialName = song.title.ifBlank { "הפלייליסט שלי" },
            onDismiss = { showCreateDialog = false },
            onCreate = { newName ->
                showCreateDialog = false
                val newPl = CustomPlaylistStore.createPlaylist(context, newName, song)
                HomeRefreshSignal.trigger()
                val newItem = SavedInPlaylistItem(
                    id = newPl.id,
                    name = newPl.name,
                    trackCount = 1,
                    coverUrl = newPl.coverUri
                )
                playlistItems.add(0, newItem)
                membership[newPl.id] = true

                addCustomPlaylist(
                    context,
                    LibraryEntry(
                        spotifyId = newPl.id,
                        name = newPl.name,
                        subtitle = "Playlist • שיר 1",
                        coverUri = newPl.coverUri,
                        isPlaylist = true
                    )
                )

                // Async Spotify sync if online
                if (song.spotifyTrackId.isNotBlank()) {
                    SpotifySync.createPlaylistWithTrack(context, newName, song.spotifyTrackId) { createdPlaylist ->
                        if (createdPlaylist != null && createdPlaylist.id.isNotBlank()) {
                            val idx = playlistItems.indexOfFirst { it.id == newPl.id }
                            val realCover = createdPlaylist.images.firstOrNull()?.url ?: newPl.coverUri
                            if (idx >= 0) {
                                playlistItems[idx] = playlistItems[idx].copy(
                                    coverUrl = realCover
                                )
                            }
                        }
                    }
                }
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF242424),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF5A5A5A))
                )
            }
        }
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                ) {
                    // ── Header: "נשמר בתיקייה" (Right) and "פלייליסט חדש" (Left) ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "נשמר בתיקייה",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "פלייליסט חדש",
                            color = SpotifyGreen,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showCreateDialog = true },
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = maxSheetHeight - 60.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        // ── 1. שירים שאהבתם (Liked Songs - Pinned Row 1) ──
                        item(key = "liked_songs_header") {
                            SavedInDestinationRow(
                                name = "שירים שאהבתם",
                                subtitle = "",
                                isLikedSongs = true,
                                isChecked = liked,
                                cover = {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                Brush.linearGradient(
                                                    listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))
                                                )
                                            ),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Favorite,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                },
                                onToggle = {
                                    liked = !liked
                                    if (liked) {
                                        addLikedSong(context, song)
                                    } else {
                                        removeLikedSong(context, song)
                                    }
                                    if (song.spotifyTrackId.isNotBlank()) {
                                        SpotifySync.setTrackSaved(context, song.spotifyTrackId, liked)
                                    }
                                    onLikedChanged(liked)
                                }
                            )
                        }

                        // ── 2. User Playlists ──
                        items(playlistItems, key = { it.id }) { item ->
                            LaunchedEffect(item.id, song.spotifyTrackId) {
                                if (membership[item.id] == null) {
                                    if (item.id.startsWith("custom_")) {
                                        membership[item.id] = CustomPlaylistStore.isSongInPlaylist(context, item.id, song)
                                    } else if (song.spotifyTrackId.isNotBlank()) {
                                        membership[item.id] = withContext(Dispatchers.IO) {
                                            SpotifySync.playlistTrackIds(context, item.id).contains(song.spotifyTrackId)
                                        }
                                    }
                                }
                            }
                            val isSaved = membership[item.id] == true
                            val subtitle = CustomPlaylistStore.getSubtitle(item.trackCount)

                            SavedInDestinationRow(
                                name = item.name,
                                subtitle = subtitle,
                                isChecked = isSaved,
                                cover = {
                                    if (item.coverUrl.isNotBlank()) {
                                        val isAllowed = if (item.id.startsWith("custom_")) {
                                            val cp = CustomPlaylistStore.getPlaylists(context).find { it.id == item.id }
                                            val matchingTrack = cp?.cachedTracks?.find { it.coverUri == item.coverUrl } ?: cp?.cachedTracks?.firstOrNull()
                                            matchingTrack?.let { com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(it) }
                                                ?: com.music.spotui.util.KosherWhitelistManager.isUrlAllowed(item.coverUrl)
                                        } else {
                                            com.music.spotui.util.KosherWhitelistManager.isUrlAllowed(item.coverUrl)
                                        }
                                        GlideImage(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(6.dp)),
                                            model = item.coverUrl,
                                            contentScale = ContentScale.Crop,
                                            failure = placeholder(R.drawable.placeholder),
                                            loading = placeholder(R.drawable.placeholder),
                                            isAllowed = isAllowed,
                                            contentDescription = "",
                                        )
                                    } else {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Color(0xFF2E2E2E))
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_library_big),
                                                contentDescription = null,
                                                tint = Color(0xFF888888),
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                    }
                                },
                                onToggle = {
                                    val newSaved = !isSaved
                                    membership[item.id] = newSaved

                                    if (item.id.startsWith("custom_")) {
                                        CustomPlaylistStore.toggleSong(context, item.id, song)
                                        HomeRefreshSignal.trigger()
                                        val updatedPl = CustomPlaylistStore.getPlaylists(context).firstOrNull { it.id == item.id }
                                        val newCount = updatedPl?.songKeys?.size ?: (if (newSaved) item.trackCount + 1 else (item.trackCount - 1).coerceAtLeast(0))
                                        val newCover = updatedPl?.coverUri?.ifBlank { item.coverUrl } ?: item.coverUrl
                                        val idx = playlistItems.indexOfFirst { it.id == item.id }
                                        if (idx >= 0) {
                                            playlistItems[idx] = item.copy(trackCount = newCount, coverUrl = newCover)
                                        }
                                    } else {
                                        val newCount = if (newSaved) item.trackCount + 1 else (item.trackCount - 1).coerceAtLeast(0)
                                        val idx = playlistItems.indexOfFirst { it.id == item.id }
                                        if (idx >= 0) {
                                            playlistItems[idx] = item.copy(trackCount = newCount)
                                        }
                                        if (song.spotifyTrackId.isNotBlank()) {
                                            if (newSaved) {
                                                SpotifySync.addTrackToPlaylist(context, item.id, song.spotifyTrackId)
                                            } else {
                                                SpotifySync.removeTrackFromPlaylist(context, item.id, song.spotifyTrackId)
                                            }
                                        }
                                    }
                                }
                            )
                        }

                        // ── 3. פלייליסט חדש (New Playlist row at bottom) ──
                        item(key = "new_playlist_row") {
                            SavedInDestinationRow(
                                name = "פלייליסט חדש",
                                subtitle = "",
                                isChecked = false,
                                showCheckIndicator = false,
                                cover = {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF2E2E2E)),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = "New playlist",
                                            tint = Color.White,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                },
                                onToggle = { showCreateDialog = true }
                            )
                        }
                    }
                }


            }
        }
    }
}

@Composable
private fun SpotifyPinIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(11.dp)) {
        val w = size.width
        val h = size.height
        val green = Color(0xFF1ED760)
        drawCircle(color = green, radius = w * 0.28f, center = center.copy(y = h * 0.35f, x = w * 0.6f))
        drawLine(
            color = green,
            start = Offset(w * 0.5f, h * 0.45f),
            end = Offset(w * 0.15f, h * 0.9f),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun SavedInDestinationRow(
    name: String,
    subtitle: String,
    isChecked: Boolean,
    isLikedSongs: Boolean = false,
    showCheckIndicator: Boolean = true,
    cover: @Composable () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle
            )
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            cover()
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isLikedSongs) {
                    SpotifyPinIcon(modifier = Modifier.padding(top = 2.dp))
                } else if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        color = Color(0xFFA7A7A7),
                        fontSize = 13.sp,
                        maxLines = 1
                    )
                }
            }
        }

        if (showCheckIndicator) {
            if (isChecked) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(SpotifyGreen)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Saved",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(24.dp)
                        .border(1.5.dp, Color(0xFF888888), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
