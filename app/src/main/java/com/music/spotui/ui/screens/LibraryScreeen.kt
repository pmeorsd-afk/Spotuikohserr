package com.music.spotui.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
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
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.entity.AccountModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.CustomPlaylistStore
import com.music.spotui.data.preferences.isLibraryGridView
import com.music.spotui.data.preferences.setLibraryGridView
import com.music.spotui.ui.components.CreatePlaylistDialog
import com.music.spotui.ui.components.PlaylistContextMenuSheet
import com.music.spotui.ui.components.PlaylistDeleteDialog
import com.music.spotui.ui.components.PlaylistEditDetailsSheet
import com.music.spotui.ui.components.Snackbar
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.library.LibraryFilter
import com.music.spotui.ui.library.LibraryItem
import com.music.spotui.ui.library.libraryItems
import com.music.spotui.ui.library.LibraryHeaderSurface
import com.music.spotui.ui.library.LibraryTopBar
import com.music.spotui.ui.library.NewEpisodesLibraryItem
import com.music.spotui.ui.viewmodel.HomeRefreshSignal
import com.music.spotui.ui.viewmodel.LibraryViewModel

private val SpotifyGreen = Color(0xFF1ED760)
private val SpotifyBackground = Color(0xFF121212)
private val TextWhite = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFA7A7A7)
private val ChipInactiveBg = Color(0xFF2A2A2A)

/**
 * Spotify-authentic "Your Library" ("הספרייה") screen.
 * Sampled 1:1 directly from official Spotify:
 * - Top bar in RTL:
 *   - Profile avatar on top right (32dp circle, orange with letter or profile image)
 *   - Bold title "הספרייה" (24sp)
 *   - Action icons on top left: Plus (+) to create playlist, Search to filter
 * - Content filter chips: "פלייליסטים", "פודקאסטים", "אלבומים", "אמנים"
 * - Sort and view row: "לאחרונה" with sort direction arrows + List/Grid view toggle
 * - Items with official dimensions (64dp cover, pinned indicator, subtitles)
 * - Long-press on any playlist opens the authentic Spotify Context Menu bottom sheet
 * - Functional "מחיקת הפלייליסט" (delete dialog) and "שם ופרטים" (edit sheet)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navController: NavController) {
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val entries by libraryViewModel.entries.collectAsState()
    val account by libraryViewModel.account.collectAsState()
    val followedArtists by libraryViewModel.followedArtists.collectAsState()
    val followedPodcasts by libraryViewModel.followedPodcasts.collectAsState()
    val savedRevision by com.music.spotui.data.preferences.likedSongsRevision.collectAsState()
    val context = LocalContext.current
    val savedEpisodeCount = remember(savedRevision) { com.music.spotui.data.preferences.getSavedEpisodes(context).size }
    val whitelistVersion by KosherWhitelistManager.versionState

    var showAccount by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(isLibraryGridView(context)) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(LibraryFilter.ALL) }

    // Playlist context menu & actions state
    var showContextMenu by remember { mutableStateOf(false) }
    var selectedPlaylistForMenu by remember { mutableStateOf<LibraryEntry?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDetailsSheet by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    // Auto-refresh when custom playlists are modified anywhere
    LaunchedEffect(Unit) {
        HomeRefreshSignal.events.collect {
            libraryViewModel.refreshSilently()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SpotifyBackground)
                .statusBarsPadding()
        ) {
            LibraryHeaderSurface {
                LibraryTopBar(
                    searchActive = searchActive,
                    onSearch = {
                        searchActive = !searchActive
                        if (!searchActive) searchQuery = ""
                    },
                    onCreatePlaylist = { showCreateDialog = true },
                ) {
                    // Profile Avatar (Right in RTL)
                    val accountData = (account as? Response.Success)?.data
                    val avatarUrl = accountData?.imageUrl.orEmpty()
                    val initialLetter = accountData?.name?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "D"

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFD85820))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { showAccount = true },
                        contentAlignment = Alignment.Center
                    ) {
                        if (avatarUrl.isNotBlank()) {
                            AccountAvatar(avatarUrl, 32.dp)
                        } else {
                            Text(
                                text = initialLetter,
                                color = TextWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // ── Search Input (Animated Visibility) ──
                AnimatedVisibility(visible = searchActive) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF252525))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextWhite, fontSize = 15.sp),
                            cursorBrush = SolidColor(SpotifyGreen),
                            modifier = Modifier.weight(1f),
                            decorationBox = { inner ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text("חיפוש בספרייה…", color = TextSecondary, fontSize = 15.sp)
                                    }
                                    inner()
                                }
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "נקה",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { searchQuery = "" }
                            )
                        }
                    }
                }

                // ── Filter Chips Row: פלייליסטים, אלבומים, אמנים ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LibraryChip(
                        text = "פלייליסטים",
                        isSelected = activeFilter == LibraryFilter.PLAYLISTS,
                        onClick = {
                            activeFilter = if (activeFilter == LibraryFilter.PLAYLISTS) LibraryFilter.ALL else LibraryFilter.PLAYLISTS
                        }
                    )
                    LibraryChip(
                        text = "פודקאסטים",
                        isSelected = activeFilter == LibraryFilter.PODCASTS,
                        onClick = {
                            activeFilter = if (activeFilter == LibraryFilter.PODCASTS) LibraryFilter.ALL else LibraryFilter.PODCASTS
                        }
                    )
                    LibraryChip(
                        text = "אלבומים",
                        isSelected = activeFilter == LibraryFilter.ALBUMS,
                        onClick = {
                            activeFilter = if (activeFilter == LibraryFilter.ALBUMS) LibraryFilter.ALL else LibraryFilter.ALBUMS
                        }
                    )
                    LibraryChip(
                        text = "אמנים",
                        isSelected = activeFilter == LibraryFilter.ARTISTS,
                        onClick = {
                            activeFilter = if (activeFilter == LibraryFilter.ARTISTS) LibraryFilter.ALL else LibraryFilter.ARTISTS
                        }
                    )
                }
            }
            // ── Sort & View Row: "לאחרונה" (Right) + Grid/List Icon (Left) ──
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                // Sort "לאחרונה" with arrows (Right in RTL)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Toggle sort order if desired */ }
                ) {
                    SortArrowsIcon()
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "לאחרונה",
                        color = TextWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // Grid/List toggle icon (Left in RTL)
                Icon(
                    painter = painterResource(if (gridView) R.drawable.ic_view_list else R.drawable.ic_view_grid),
                    contentDescription = if (gridView) "תצוגת רשימה" else "תצוגת רשת",
                    tint = TextWhite,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            gridView = !gridView
                            setLibraryGridView(context, gridView)
                        }
                )
            }

            // ── Content Area: List or Grid with Filter and Search ──
            val contentItems = remember(entries, followedPodcasts, followedArtists, activeFilter, searchQuery, savedEpisodeCount) {
                libraryItems((entries as? Response.Success)?.data.orEmpty(), followedPodcasts,
                    followedArtists, activeFilter, searchQuery, savedEpisodeCount)
            }
            when {
                entries is Response.Success || contentItems.isNotEmpty() || activeFilter == LibraryFilter.PODCASTS -> {
                    if (gridView) {
                        LibraryGridScreen(
                            padding = PaddingValues(0.dp),
                            contentItems = contentItems,
                            navController = navController,
                            onPlaylistLongClick = { entry ->
                                selectedPlaylistForMenu = entry
                                showContextMenu = true
                            }
                        )
                    } else {
                        SumUpLibraryScreen(
                            padding = PaddingValues(0.dp),
                            contentItems = contentItems,
                            navController = navController,
                            onPlaylistLongClick = { entry ->
                                selectedPlaylistForMenu = entry
                                showContextMenu = true
                            }
                        )
                    }
                }
                entries is Response.Loading -> LibrarySkeleton(PaddingValues(0.dp))
                else -> Box(modifier = Modifier.padding(20.dp, 100.dp)) {
                    Snackbar(showMessage = "לא ניתן לטעון את הספרייה")
                }
            }
        }

        // ── Dialogs & Sheets ──

        // Account sheet
        if (showAccount) {
            AccountSheet(
                account = (account as? Response.Success)?.data ?: AccountModel(),
                navController = navController,
                onDismiss = { showAccount = false },
            )
        }

        // Create Playlist Dialog
        if (showCreateDialog) {
            CreatePlaylistDialog(
                initialName = "",
                onDismiss = { showCreateDialog = false },
                onCreate = { newName ->
                    CustomPlaylistStore.createPlaylist(context, newName)
                    HomeRefreshSignal.trigger()
                    libraryViewModel.load()
                    showCreateDialog = false
                }
            )
        }

        // Playlist Context Menu Bottom Sheet
        if (showContextMenu && selectedPlaylistForMenu != null) {
            val playlist = selectedPlaylistForMenu!!
            PlaylistContextMenuSheet(
                entry = playlist,
                onDismiss = {
                    showContextMenu = false
                    selectedPlaylistForMenu = null
                },
                onDeleteClick = {
                    showContextMenu = false
                    showDeleteDialog = true
                },
                onEditDetailsClick = {
                    showContextMenu = false
                    showEditDetailsSheet = true
                }
            )
        }

        // Delete Playlist Confirmation Dialog
        if (showDeleteDialog && selectedPlaylistForMenu != null) {
            val playlist = selectedPlaylistForMenu!!
            PlaylistDeleteDialog(
                playlistName = playlist.name,
                onConfirm = {
                    libraryViewModel.deletePlaylist(playlist.spotifyId)
                    HomeRefreshSignal.trigger()
                    showDeleteDialog = false
                    showContextMenu = false
                    selectedPlaylistForMenu = null
                },
                onDismiss = {
                    showDeleteDialog = false
                }
            )
        }

        // Edit Playlist Details Sheet ("שם ופרטים")
        if (showEditDetailsSheet && selectedPlaylistForMenu != null) {
            val playlist = selectedPlaylistForMenu!!
            PlaylistEditDetailsSheet(
                entry = playlist,
                onDismiss = {
                    showEditDetailsSheet = false
                    showContextMenu = false
                    selectedPlaylistForMenu = null
                },
                onSave = { newName, newDescription ->
                    CustomPlaylistStore.updatePlaylist(context, playlist.spotifyId, newName, newDescription)
                    HomeRefreshSignal.trigger()
                    libraryViewModel.load()
                    showEditDetailsSheet = false
                    showContextMenu = false
                    selectedPlaylistForMenu = null
                },
                onDeleteClick = {
                    showEditDetailsSheet = false
                    showDeleteDialog = true
                }
            )
        }
    }
}

@Composable
internal fun LibraryChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .background(if (isSelected) SpotifyGreen else ChipInactiveBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp)
    ) {
        Text(
            text = text,
            color = if (isSelected) Color.Black else TextWhite,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
        )
    }
}

@Composable
private fun SortArrowsIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val w = size.width
        val h = size.height
        val c = TextWhite
        val stroke = 1.8.dp.toPx()
        // Left arrow pointing down
        drawLine(color = c, start = Offset(w * 0.3f, h * 0.15f), end = Offset(w * 0.3f, h * 0.85f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = c, start = Offset(w * 0.1f, h * 0.65f), end = Offset(w * 0.3f, h * 0.85f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = c, start = Offset(w * 0.5f, h * 0.65f), end = Offset(w * 0.3f, h * 0.85f), strokeWidth = stroke, cap = StrokeCap.Round)
        // Right arrow pointing up
        drawLine(color = c, start = Offset(w * 0.7f, h * 0.85f), end = Offset(w * 0.7f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = c, start = Offset(w * 0.5f, h * 0.35f), end = Offset(w * 0.7f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = c, start = Offset(w * 0.9f, h * 0.35f), end = Offset(w * 0.7f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
private fun SumUpLibraryScreen(
    padding: PaddingValues,
    contentItems: List<LibraryItem>,
    navController: NavController,
    onPlaylistLongClick: (LibraryEntry) -> Unit = {}
) {
    val context = LocalContext.current
    if (contentItems.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "הספרייה ריקה", color = TextSecondary, fontSize = 16.sp)
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(top = 4.dp, bottom = 130.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(SpotifyBackground)
    ) {
        items(contentItems, key = { it.key }) { item ->
            when (item) {
                is LibraryItem.Saved -> {
                    val entry = item.entry
                    val isLiked = entry.spotifyId == "liked" || entry.name == "שירים שאהבתם" || entry.name.equals("Liked Songs", ignoreCase = true)
                    val isSystemShortcut = isLiked || entry.spotifyId == "downloaded" || entry.name.equals("Downloaded", ignoreCase = true)
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isPressed) Color.Black else Color.Transparent)
                            .combinedClickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { openLibraryEntry(entry, navController) },
                                onLongClick = {
                                    if (entry.isPlaylist && !isSystemShortcut) {
                                        onPlaylistLongClick(entry)
                                    }
                                }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // Cover Art (64x64dp)
                        if (isLiked) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))
                                        )
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        } else if (entry.coverUri.isNotBlank()) {
                            val isEntryAllowed = KosherWhitelistManager.isLibraryEntryWhitelisted(entry, context)
                            GlideImage(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                model = entry.coverUri,
                                contentScale = ContentScale.Crop,
                                contentDescription = entry.name,
                                failure = placeholder(R.drawable.placeholder),
                                loading = placeholder(R.drawable.placeholder),
                                isAllowed = isEntryAllowed
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF2E2E2E))
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_library_big),
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(30.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // Text: Title + Subtitle
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isLiked) "שירים שאהבתם" else entry.name,
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isLiked) {
                                    SpotifyPinIcon()
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                val prefix = if (entry.isPlaylist) "פלייליסט • " else "אלבום • "
                                val subText = if (isLiked) "שירים שאהבתם"
                                else if (entry.subtitle.startsWith("Playlist • ") || entry.subtitle.startsWith("פלייליסט • ")) {
                                    entry.subtitle
                                } else {
                                    prefix + entry.subtitle.ifBlank { "SpotUI" }
                                }
                                Text(
                                    text = subText,
                                    color = TextSecondary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                is LibraryItem.Episodes -> SavedEpisodesLibraryItem(item.count, false) { navController.navigate(Routes.SavedEpisodes.route) }
                LibraryItem.NewEpisodes -> NewEpisodesLibraryItem(grid = false) { navController.navigate(Routes.NewEpisodes.route) }
                is LibraryItem.Podcast -> LibraryPodcastItem(item, grid = false) {
                    navController.navigate(showRoute(item.followed.showId, item.followed.name))
                }
                is LibraryItem.Artist -> {
                    val artist = item.artist
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { navController.navigate(artistRoute(artist.name, artist.id)) }
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        val isArtistAllowed = KosherWhitelistManager.isArtistModelWhitelisted(artist)
                        GlideImage(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            model = artist.coverUri,
                            contentScale = ContentScale.Crop,
                            contentDescription = artist.name,
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder),
                            isAllowed = isArtistAllowed
                        )

                        Spacer(modifier = Modifier.width(14.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                color = TextWhite,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "אמן",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedEpisodesLibraryItem(count: Int, grid: Boolean, onClick: () -> Unit) {
    @Composable fun label() {
        Text("הפרקים שלכם", color = TextWhite, fontSize = if (grid) 13.sp else 16.sp, fontWeight = FontWeight.SemiBold)
        Text(com.music.spotui.data.preferences.savedEpisodeCountLabel(count), color = TextSecondary, fontSize = if (grid) 11.sp else 13.sp)
    }
    if (grid) Column(Modifier.clickable(onClick = onClick)) {
        com.music.spotui.ui.components.SavedCollectionCover(true, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp)); label()
    } else Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        com.music.spotui.ui.components.SavedCollectionCover(true, Modifier.size(64.dp))
        Spacer(Modifier.width(14.dp)); Column { label() }
    }
}

private fun openLibraryEntry(entry: LibraryEntry, navController: NavController) {
    val isLiked = entry.spotifyId == Api.HomeCache.LIKED_SONGS_ID || entry.spotifyId == "liked"
        || entry.name == "שירים שאהבתם" || entry.name.equals("Liked Songs", ignoreCase = true)
    if (isLiked) {
        navController.navigate(Routes.Liked.route)
    } else if (entry.spotifyId == Api.HomeCache.DOWNLOADS_ID || entry.spotifyId == "downloaded"
        || entry.name.equals("Downloaded", ignoreCase = true) || entry.name == "הורדות") {
        navController.navigate(Routes.Downloads.route)
    } else if (entry.isPlaylist) {
        navController.navigate(playlistRoute(entry.spotifyId, entry.name))
    } else {
        navController.navigate(albumRoute(entry.name, entry.artists, albumId = entry.spotifyId))
    }
}

/**
 * Grid layout of the same library content: 3 columns of square covers.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalGlideComposeApi::class)
@Composable
private fun LibraryGridScreen(
    padding: PaddingValues,
    contentItems: List<LibraryItem>,
    navController: NavController,
    onPlaylistLongClick: (LibraryEntry) -> Unit = {}
) {
    val context = LocalContext.current
    if (contentItems.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "הספרייה ריקה", color = TextSecondary, fontSize = 16.sp)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(SpotifyBackground),
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 130.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        items(contentItems, key = { it.key }) { item ->
            when (item) {
                is LibraryItem.Saved -> {
                    val entry = item.entry
                    val isLiked = entry.spotifyId == "liked" || entry.name == "שירים שאהבתם" || entry.name.equals("Liked Songs", ignoreCase = true)
                    val isSystemShortcut = isLiked || entry.spotifyId == "downloaded" || entry.name.equals("Downloaded", ignoreCase = true)
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isPressed) Color.Black else Color.Transparent)
                            .combinedClickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { openLibraryEntry(entry, navController) },
                                onLongClick = {
                                    if (entry.isPlaylist && !isSystemShortcut) {
                                        onPlaylistLongClick(entry)
                                    }
                                }
                            )
                    ) {
                        if (isLiked) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF450AF5), Color(0xFF8E8EE5))
                                        )
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Favorite,
                                    contentDescription = null,
                                    tint = TextWhite,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        } else if (entry.coverUri.isNotBlank()) {
                            val isEntryAllowed = KosherWhitelistManager.isLibraryEntryWhitelisted(entry, context)
                            GlideImage(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp)),
                                model = entry.coverUri,
                                contentScale = ContentScale.Crop,
                                contentDescription = entry.name,
                                failure = placeholder(R.drawable.placeholder),
                                loading = placeholder(R.drawable.placeholder),
                                isAllowed = isEntryAllowed
                            )
                        } else {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF2E2E2E))
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_library_big),
                                    contentDescription = null,
                                    tint = TextSecondary,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (isLiked) "שירים שאהבתם" else entry.name,
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isLiked) "פלייליסט" else entry.subtitle,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                is LibraryItem.Episodes -> SavedEpisodesLibraryItem(item.count, true) { navController.navigate(Routes.SavedEpisodes.route) }
                LibraryItem.NewEpisodes -> NewEpisodesLibraryItem(grid = true) { navController.navigate(Routes.NewEpisodes.route) }
                is LibraryItem.Podcast -> LibraryPodcastItem(item, grid = true) {
                    navController.navigate(showRoute(item.followed.showId, item.followed.name))
                }
                is LibraryItem.Artist -> {
                    val artist = item.artist
                    Column(
                        modifier = Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { navController.navigate(artistRoute(artist.name, artist.id)) }
                        )
                    ) {
                        val isArtistAllowed = KosherWhitelistManager.isArtistModelWhitelisted(artist)
                        GlideImage(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(CircleShape),
                            model = artist.coverUri,
                            contentScale = ContentScale.Crop,
                            contentDescription = artist.name,
                            failure = placeholder(R.drawable.placeholder),
                            loading = placeholder(R.drawable.placeholder),
                            isAllowed = isArtistAllowed
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = artist.name,
                            color = TextWhite,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "אמן",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LibraryPodcastItem(item: LibraryItem.Podcast, grid: Boolean, onClick: () -> Unit) {
    val allowed = remember(item.show, KosherWhitelistManager.versionState.value) {
        KosherWhitelistManager.isPodcastShowWhitelisted(item.show)
    }
    @Composable fun cover(modifier: Modifier) {
        GlideImage(
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            model = item.followed.imageUrl,
            contentDescription = item.followed.name,
            contentScale = ContentScale.Crop,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            isAllowed = allowed,
        )
    }
    @Composable fun label() {
        Text(item.followed.name, color = TextWhite, fontSize = if (grid) 13.sp else 16.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!grid) Spacer(Modifier.height(3.dp))
        Text("פודקאסט", color = TextSecondary, fontSize = if (grid) 11.sp else 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val click = Modifier.clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick,
    )
    if (grid) {
        Column(modifier = click) {
            cover(Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(6.dp))
            label()
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth().then(click).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            cover(Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { label() }
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AccountAvatar(url: String, size: androidx.compose.ui.unit.Dp) {
    GlideImage(
        modifier = Modifier.size(size).clip(CircleShape),
        model = url,
        contentScale = ContentScale.Crop,
        contentDescription = "",
        isAllowed = true
    )
}

@Composable
fun LibrarySkeleton(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .background(SpotifyBackground)
    ) {
        Spacer(modifier = Modifier.height(10.dp))
        repeat(8) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF1E1E1E))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Box(
                        modifier = Modifier
                            .height(16.dp)
                            .width(160.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1E1E1E))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .height(12.dp)
                            .width(90.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xFF1E1E1E))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountSheet(
    account: AccountModel,
    navController: NavController,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1A1A1A),
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp, 16.dp, 16.dp)
            ) {
                Box(
                    modifier = Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF3A3A3A)),
                    contentAlignment = Alignment.Center
                ) {
                    if (account.imageUrl.isNotBlank()) AccountAvatar(account.imageUrl, 56.dp)
                    else Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(account.name.ifBlank { "Spotify user" }, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.email.isNotBlank()) Text(account.email, color = Color.Gray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (account.plan.isNotBlank()) Text("Spotify ${account.plan}", color = Color(0xFF1DB954), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))

            AccountRow("חשבון") { onDismiss() }
            AccountRow("היסטוריית האזנה") {
                onDismiss()
                navController.navigate(Routes.History.route)
            }
            HorizontalDivider(color = Color(0xFF2A2A2A))
            AccountRow("התנתקות", tint = Color(0xFFE57373)) {
                SpotifySession.setSpDc(context, "")
                Api.HomeCache.clear()
                onDismiss()
                navController.navigate(Routes.Login.route) {
                    popUpTo(0) { inclusive = true }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccountRow(label: String, tint: Color = Color.White, onClick: () -> Unit) {
    Text(
        text = label,
        color = tint,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(20.dp, 16.dp)
    )
}
