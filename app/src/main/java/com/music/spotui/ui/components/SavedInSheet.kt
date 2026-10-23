package com.music.spotui.ui.components

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.music.spotui.R
import com.music.spotui.data.api.SpotifySync
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.library.*
import com.music.spotui.ui.viewmodel.HomeRefreshSignal
import com.music.spotui.util.KosherWhitelistManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.roundToInt

private const val DEFAULT_DESTINATION = "__primary_saved_collection__"
private val SaveSheetBackground = Color(0xFF1F1F1F)

/** A selected media snapshot stays fixed while playback may advance behind this dialog. */
@Composable
fun SavedInSheet(song: SongsModel, context: Context, onDismiss: () -> Unit, onLikedChanged: (Boolean) -> Unit = {}) = key(song) {
    val draft = remember { SaveDestinationDraft().apply { load(DEFAULT_DESTINATION, isSongLiked(context, song)) } }
    var revision by remember { mutableIntStateOf(0) }
    val local = remember { localSavePlaylists(context) }
    var remote by remember { mutableStateOf(emptyList<SavePlaylist>()) }
    val pending = remember { mutableStateListOf<SavePlaylist>() }
    val createdDrafts = remember { mutableStateMapOf<String, String>() }
    val membershipErrors = remember { mutableStateMapOf<String, Boolean>() }
    val membershipLimit = remember { Semaphore(4) }
    var loadAttempt by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var commitFailed by remember { mutableStateOf(false) }
    var committing by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var closeRequested by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playlists = remember(local, remote, pending.toList()) { pending.toList() + mergeSavePlaylists(local, remote) }
    val primarySelected = remember(revision) { draft.selected(DEFAULT_DESTINATION) == true }
    val canUseSpotify = savedItemSpotifyUri(song) != null
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(loadAttempt) {
        loading = true; loadFailed = false
        try {
            withContext(Dispatchers.IO) {
                remoteSavePlaylists(context) { page -> withContext(Dispatchers.Main) { remote = page } }
            }
        } catch (_: TimeoutCancellationException) { loadFailed = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { loadFailed = true }
        finally { loading = false }
    }
    fun toggle(id: String) { draft.toggle(id); revision++ }
    fun done() {
        if (committing) return
        committing = true; commitFailed = false
        scope.launch {
            // Snapshot only edits, so unrelated concurrent Library changes survive.
            val edits = draft.changes() + pending.filter { it.id !in createdDrafts }
                .associate { it.id to (draft.selected(it.id) == true) }
            for ((id, selected) in edits) {
                val playlist = playlists.find { it.id == id }
                val ok = try { when {
                    id == DEFAULT_DESTINATION -> {
                        if (selected) addLikedSong(context, song) else removeLikedSong(context, song)
                        if (!song.isPodcast() && song.spotifyTrackId.isNotBlank()) SpotifySync.setTrackSaved(context, song.spotifyTrackId, selected)
                        isSongLiked(context, song) == selected
                    }
                    id.startsWith("draft:") -> {
                        val existing = createdDrafts[id]
                        if (existing == null) {
                            val created = CustomPlaylistStore.createPlaylist(context, requireNotNull(playlist).name, if (selected) song else null)
                            createdDrafts[id] = created.id
                            addCustomPlaylist(context, LibraryEntry(created.id, created.name,
                                "Playlist • ${CustomPlaylistStore.getSubtitle(created.songKeys.size)}", created.coverUri, true))
                            true
                        } else {
                            if (CustomPlaylistStore.isSongInPlaylist(context, existing, song) != selected) CustomPlaylistStore.toggleSong(context, existing, song)
                            CustomPlaylistStore.isSongInPlaylist(context, existing, song) == selected
                        }
                    }
                    playlist?.local == true -> {
                        if (CustomPlaylistStore.isSongInPlaylist(context, id, song) != selected) CustomPlaylistStore.toggleSong(context, id, song)
                        CustomPlaylistStore.isSongInPlaylist(context, id, song) == selected
                    }
                    playlist != null && canUseSpotify -> withContext(Dispatchers.IO) { setRemoteSaveMembership(context, id, song, selected) }
                    else -> false
                } } catch (_: TimeoutCancellationException) { false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                if (ok) draft.accept(id, selected) else commitFailed = true
            }
            notifyLikedSongsChanged()
            HomeRefreshSignal.trigger()
            onLikedChanged(isSongLiked(context, song))
            revision++
            committing = false
            if (!commitFailed) closeRequested = true
        }
    }

    if (showCreate) CreatePlaylistDialog(initialName = "", onDismiss = { showCreate = false }, onCreate = { name ->
        showCreate = false
        val item = SavePlaylist("draft:${java.util.UUID.randomUUID()}", name, 1, song.coverUri, true)
        pending.add(0, item); draft.load(item.id, false); draft.toggle(item.id); revision++
    })

    SaveDestinationSheetFrame(
        closeRequested = closeRequested, busy = committing, saveFailed = commitFailed,
        onDismiss = { onLikedChanged(isSongLiked(context, song)); currentOnDismiss() },
        onDone = ::done, onCancel = { closeRequested = true }, onNewPlaylist = { showCreate = true },
    ) { listModifier ->
        LazyColumn(modifier = listModifier, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = DEFAULT_DESTINATION) {
                SavedInDestinationRow(
                    name = if (song.isPodcast()) "הפרקים שלכם" else "שירים שאהבתם",
                    subtitle = if (song.isPodcast()) savedEpisodeCountLabel(getSavedEpisodes(context).size) else "${getLikedSongsCount(context)} שירים",
                    selected = primarySelected, enabled = !committing,
                    cover = { SavedCollectionCover(song.isPodcast(), Modifier.size(48.dp)) },
                    onToggle = { toggle(DEFAULT_DESTINATION) },
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
            }
            items(playlists, key = { it.id }) { item ->
                LaunchedEffect(item.id, loadAttempt) {
                    if (draft.selected(item.id) != null) return@LaunchedEffect
                    if (!item.local && !canUseSpotify) return@LaunchedEffect
                    try {
                        val saved = if (item.local) CustomPlaylistStore.isSongInPlaylist(context, item.id, song)
                            else withContext(Dispatchers.IO) { membershipLimit.withPermit { remoteSaveMembership(context, item.id, song) } }
                        draft.load(item.id, saved); membershipErrors.remove(item.id); revision++
                    } catch (_: TimeoutCancellationException) { membershipErrors[item.id] = true }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { membershipErrors[item.id] = true }
                }
                val selected = remember(item.id, revision) { draft.selected(item.id) }
                val unavailable = !item.local && !canUseSpotify
                SavedInDestinationRow(item.name,
                    subtitle = when {
                        unavailable -> "הפריט אינו זמין לשמירה ב־Spotify"
                        membershipErrors[item.id] == true -> "לא ניתן לבדוק שמירה — נסו שוב"
                        else -> if (item.id.startsWith("draft:") && song.isPodcast()) "פרק 1" else CustomPlaylistStore.getSubtitle(item.count)
                    },
                    selected = selected, enabled = !committing && !unavailable && selected != null,
                    cover = {
                        val exactCoverItem = if (item.id.startsWith("draft:")) song else
                            CustomPlaylistStore.getPlaylists(context).find { it.id == item.id }?.cachedTracks?.find { it.coverUri == item.cover }
                        val allowed = exactCoverItem?.let { KosherWhitelistManager.isSongWhitelisted(it) }
                            ?: KosherWhitelistManager.isUrlAllowed(item.cover)
                        if (item.cover.isNotBlank()) GlideImage(item.cover, null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)), isAllowed = allowed)
                        else Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_library_big), null, Modifier.size(24.dp), tint = Color(0xFFB3B3B3))
                        }
                    }, onToggle = { toggle(item.id) },
                )
            }
            item(key = "new") {
                SavedInDestinationRow("פלייליסט חדש", "", selected = false, enabled = !committing, showIndicator = false,
                    cover = { Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Add, null, Modifier.size(30.dp), tint = Color(0xFFB3B3B3))
                    } }, onToggle = { showCreate = true })
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = SpotifyGreen) } }
            if (loadFailed || membershipErrors.isNotEmpty()) item {
                TextButton(onClick = { loadAttempt++ }, enabled = !committing, modifier = Modifier.fillMaxWidth()) {
                    Text("טעינת הפלייליסטים מ־Spotify מחדש", color = SpotifyGreen)
                }
            }
        }
    }
}

@Composable
internal fun SaveDestinationSheetFrame(
    closeRequested: Boolean, busy: Boolean, saveFailed: Boolean, onDismiss: () -> Unit, onDone: () -> Unit,
    onCancel: () -> Unit, onNewPlaylist: () -> Unit, content: @Composable (Modifier) -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onCancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                val density = LocalDensity.current
                val heightPx = with(density) { maxHeight.toPx() }
                val peekPx = with(density) { minOf(232.dp, maxHeight * 0.7f).toPx() }
                val anchors = remember(heightPx, peekPx) { SaveSheetAnchors(heightPx, peekPx) }
                val threshold = with(density) { 24.dp.toPx() }
                val fling = with(density) { 400.dp.toPx() }
                var offset by remember { mutableFloatStateOf(heightPx) }
                var expanded by rememberSaveable { mutableStateOf(false) }
                var firstOpen by remember { mutableStateOf(true) }
                var dragStart by remember { mutableFloatStateOf(anchors.collapsed) }
                var nestedDragging by remember { mutableStateOf(false) }
                var motion by remember { mutableStateOf<Job?>(null) }
                val scope = rememberCoroutineScope()
                suspend fun move(target: Float) {
                    animate(offset, target, animationSpec = tween(300, easing = FastOutSlowInEasing)) { value, _ -> offset = value }
                    expanded = target == 0f
                    if (target == heightPx) onDismiss()
                }
                fun snap(target: Float) { motion?.cancel(); motion = scope.launch { move(target) } }
                fun drag(delta: Float): Float {
                    if (busy || closeRequested) return 0f
                    val before = offset; offset = anchors.clamp(offset + delta); return offset - before
                }
                fun settle(velocity: Float) { snap(anchors.settle(offset, dragStart, velocity, threshold, fling)) }
                LaunchedEffect(anchors) {
                    motion?.cancel()
                    if (firstOpen) { firstOpen = false; snap(anchors.collapsed) }
                    else offset = if (expanded) 0f else anchors.collapsed
                }
                LaunchedEffect(closeRequested) { if (closeRequested) snap(heightPx) }
                BackHandler { if (!busy) onCancel() }
                val scrollConnection = remember(anchors, busy, closeRequested) {
                    object : NestedScrollConnection {
                        fun start() { if (!nestedDragging) { motion?.cancel(); dragStart = offset; nestedDragging = true } }
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (source == NestedScrollSource.Drag && offset > 0f && available.y < 0f) { start(); return Offset(0f, drag(available.y)) }
                            return Offset.Zero
                        }
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source == NestedScrollSource.Drag && available.y > 0f) { start(); return Offset(0f, drag(available.y)) }
                            return Offset.Zero
                        }
                        override suspend fun onPreFling(available: Velocity): Velocity {
                            if (!nestedDragging) return Velocity.Zero
                            nestedDragging = false; settle(available.y); return available
                        }
                    }
                }
                val expandFraction = if (anchors.collapsed == 0f) 1f else (1f - offset / anchors.collapsed).coerceIn(0f, 1f)
                // The reference compact save sheet leaves the Show page at its original brightness.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f * expandFraction))
                    .clickable(enabled = !busy, onClick = onCancel))
                val showExpanded = expandFraction > 0.8f
                Column(Modifier.fillMaxWidth().fillMaxHeight().offset { IntOffset(0, offset.roundToInt()) }
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(SaveSheetBackground)
                    .pointerInput(Unit) { detectTapGestures { /* Blank sheet space is not the dismiss scrim. */ } }
                    .navigationBarsPadding()) {
                    Column(Modifier.draggable(rememberDraggableState { drag(it) }, Orientation.Vertical, enabled = !busy && !closeRequested,
                        onDragStarted = { motion?.cancel(); dragStart = offset }, onDragStopped = { settle(it) })) {
                        Box(Modifier.fillMaxWidth().height(24.dp).clickable(enabled = !busy, role = Role.Button) {
                            snap(if (showExpanded) anchors.collapsed else 0f)
                        }, contentAlignment = Alignment.Center) {
                            Box(Modifier.size(32.dp, 4.dp).clip(CircleShape).background(Color(0xFF777777)))
                        }
                        AnimatedVisibility(showExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = onCancel, enabled = !busy) { Text("ביטול", color = Color.White, fontWeight = FontWeight.Bold) }
                            TextButton(onClick = onDone, enabled = !busy) { Text("סיום", color = Color.White, fontWeight = FontWeight.Bold) }
                            }
                        }
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("נשמר בתיקייה", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            TextButton(onClick = onNewPlaylist, enabled = !busy) { Text("פלייליסט חדש", color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                    content(Modifier.fillMaxWidth().weight(1f).nestedScroll(scrollConnection))
                    AnimatedVisibility(showExpanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (saveFailed) Text("חלק מהשינויים לא נשמרו. לחצו על סיום כדי לנסות שוב.", color = Color(0xFFFFB4AB), fontSize = 12.sp)
                        Button(onClick = onDone, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) {
                            Text(if (busy) "שומר…" else "סיום", fontWeight = FontWeight.Bold)
                        }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedInDestinationRow(
    name: String, subtitle: String, selected: Boolean?, enabled: Boolean,
    cover: @Composable () -> Unit, onToggle: () -> Unit, modifier: Modifier = Modifier, showIndicator: Boolean = true,
) {
    Row(modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox, onClick = onToggle)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        cover(); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFFB3B3B3), fontSize = 13.sp, maxLines = 2, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(12.dp))
        if (showIndicator) {
            if (selected == null) Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Text("—", color = Color.Gray) }
            else SavedStateIcon(selected, Modifier.size(22.dp))
        }
    }
}
