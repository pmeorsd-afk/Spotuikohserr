package com.music.spotui.ui.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import com.music.spotui.R
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.podcast.*
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.ui.components.*
import com.music.spotui.ui.navigation.ItemDetailRegistry
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.podcast.enqueueEpisodeDownload
import com.music.spotui.ui.viewmodel.NewEpisodesState
import com.music.spotui.ui.viewmodel.NewEpisodesViewModel
import com.music.spotui.ui.viewmodel.SavedEpisodesViewModel
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.PodcastDateFormatter
import kotlinx.coroutines.*

private val EpisodeMuted = Color(0xFFB3B3B3)

@Composable
fun NewEpisodesScreen(navController: NavController) {
    val vm: NewEpisodesViewModel = hiltViewModel()
    val playback: SavedEpisodesViewModel = hiltViewModel()
    val state by vm.state.collectAsState()
    val playing by playback.playing
    val currentId by playback.currentId
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val savedRevision by likedSongsRevision.collectAsState()
    var menu by remember { mutableStateOf<PodcastEpisodeUiModel?>(null) }
    var saving by remember { mutableStateOf<SongsModel?>(null) }
    val downloading = remember { mutableStateListOf<String>() }
    LaunchedEffect(owner, vm) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.enter()
            try { while (isActive) { delay(60_000); vm.refresh() } }
            finally { vm.leave() }
        }
    }
    LaunchedEffect(state.actionError) {
        state.actionError?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.clearError() }
    }
    fun detail(ep: PodcastEpisodeUiModel) {
        navController.navigate(showRoute(ep.showId, ep.showName, ItemDetailRegistry.register(ep.toSongModel())))
    }
    fun download(ep: PodcastEpisodeUiModel) {
        if (ep.id in downloading) return
        downloading.add(ep.id)
        scope.launch {
            try {
                val success = enqueueEpisodeDownload(context.applicationContext, ep)
                Toast.makeText(context, if (success) "ההורדה נוספה להורדות המכשיר" else "לא ניתן להוריד את הפרק כרגע",
                    Toast.LENGTH_LONG).show()
            } finally { downloading.remove(ep.id) }
        }
    }
    saving?.let { SavedInSheet(it, context, onDismiss = { saving = null }) }
    menu?.let { ep ->
        PodcastEpisodeMenuSheet(ep, saved = remember(ep, savedRevision) { isEpisodeSaved(context, ep.toSongModel()) },
            downloading = ep.id in downloading, onDismiss = { menu = null },
            onToggleSaved = { if (isEpisodeSaved(context, ep.toSongModel())) removeSavedEpisode(context, ep.toSongModel())
                else saveEpisode(context, ep.toSongModel()) },
            onPlaylist = { saving = ep.toSongModel() }, onDownload = { download(ep) },
            onComplete = { vm.complete(ep) },
            onShow = { navController.navigate(showRoute(ep.showId, ep.showName)) }, onEpisode = { detail(ep) })
    }
    NewEpisodesContent(state, onBack = { navController.popBackStack() }, onRefresh = { vm.refresh(force = true) },
        onUndo = vm::undo, onComplete = vm::complete, onMore = { menu = it }, onDetail = ::detail,
        onPlay = { playback.toggle(it.toSongModel(), context) }, onSave = { ep ->
            if (saveToDefaultIfNeeded(context, ep.toSongModel())) saving = ep.toSongModel()
        }, onDownload = ::download,
        saved = { ep -> savedRevision; isSavedInAnyDestination(context, ep.toSongModel()) },
        isPlaying = { ep -> currentId == ep.numericId && playing &&
            SongPlayer.ownedPlaybackSnapshot()?.song?.let(PlaybackProgressStore::key) == NewEpisodesProjection.key(ep) },
        downloading = { it.id in downloading })
}

@Composable
internal fun NewEpisodesContent(
    state: NewEpisodesState, onBack: () -> Unit, onRefresh: () -> Unit,
    onUndo: (EpisodeCompletionUndo) -> Unit, onComplete: (PodcastEpisodeUiModel) -> Unit,
    onMore: (PodcastEpisodeUiModel) -> Unit, onDetail: (PodcastEpisodeUiModel) -> Unit,
    onPlay: (PodcastEpisodeUiModel) -> Unit, onSave: (PodcastEpisodeUiModel) -> Unit,
    onDownload: (PodcastEpisodeUiModel) -> Unit, saved: (PodcastEpisodeUiModel) -> Boolean,
    isPlaying: (PodcastEpisodeUiModel) -> Boolean, downloading: (PodcastEpisodeUiModel) -> Boolean,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF282828)).statusBarsPadding().heightIn(min = 56.dp)) {
                Text("פרקים חדשים", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp, vertical = 16.dp))
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Box(Modifier.weight(1f)) {
                LazyColumn(contentPadding = PaddingValues(top = 16.dp, bottom = 148.dp)) {
                    if (state.feed.continuing.isNotEmpty()) {
                        item(key = "continue-heading") { NewEpisodesHeading("המשך האזנה") }
                        items(state.feed.continuing, key = { "continue:${it.episode.id}" }) { item ->
                            ContinuingEpisodeCard(item, onPlay = { onPlay(item.episode) },
                                onComplete = { onComplete(item.episode) }, onMore = { onMore(item.episode) })
                        }
                        item { Spacer(Modifier.height(22.dp)) }
                    }
                    state.feed.episodes.groupBy { NewEpisodesProjection.section(it.pubDate) }.forEach { (title, episodes) ->
                        item(key = "section:$title") { NewEpisodesHeading(title) }
                        items(episodes, key = { it.id }) { ep ->
                            NewEpisodeRow(ep, saved(ep), isPlaying(ep), downloading(ep),
                                onDetail = { onDetail(ep) }, onMore = { onMore(ep) }, onComplete = { onComplete(ep) },
                                onPlay = { onPlay(ep) }, onSave = { onSave(ep) }, onDownload = { onDownload(ep) })
                        }
                    }
                    if (state.loading) item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF1ED760), modifier = Modifier.size(24.dp))
                        }
                    }
                    if (state.partialError) item(key = "error") {
                        Text("חלק מהפרקים לא נטענו. הפרקים שכבר נטענו נשארו זמינים.", color = EpisodeMuted,
                            modifier = Modifier.padding(16.dp))
                    }
                    if (!state.loading && state.feed.episodes.isEmpty()) item(key = "empty") {
                        Text(if (state.followedCount == 0) "עקבו אחרי פודקאסטים כדי לראות כאן פרקים חדשים"
                            else "אין כרגע פרקים נוספים שלא סיימתם", color = EpisodeMuted, modifier = Modifier.padding(24.dp))
                    }
                    item(key = "refresh") {
                        TextButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                            Text("רענון פרקים", color = EpisodeMuted)
                        }
                    }
                }
                state.undo?.let { undo ->
                    EpisodeCompletionNotice(onUndo = { onUndo(undo) },
                        modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp).padding(bottom = 124.dp))
                }
            }
        }
    }
}

@Composable private fun NewEpisodesHeading(title: String) {
    Text(title, color = EpisodeMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp))
}

@Composable
internal fun NewEpisodeArtwork(ep: PodcastEpisodeUiModel, modifier: Modifier) {
    val version by KosherWhitelistManager.versionState
    val allowed = remember(ep, version) { KosherWhitelistManager.isSongWhitelisted(ep.toSongModel()) }
    GlideImage(ep.coverUri, isAllowed = allowed, contentDescription = null, modifier = modifier.clip(RoundedCornerShape(3.dp)))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinuingEpisodeCard(item: ContinuingEpisode, onPlay: () -> Unit, onComplete: () -> Unit, onMore: () -> Unit) {
    val ep = item.episode
    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth()
        .clip(RoundedCornerShape(8.dp)).background(Color(0xFF282828))
        .combinedClickable(onClick = onPlay, onLongClick = onMore).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        NewEpisodeArtwork(ep, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ep.title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val remaining = (item.listening.durationMs - item.listening.positionMs).coerceAtLeast(0)
            val metadata = listOf(PodcastDateFormatter.formatPubDate(ep.pubDate),
                if (remaining > 0) "הזמן שנותר: ${PodcastDateFormatter.formatDuration(remaining)}" else "המשך האזנה")
                .filter { it.isNotBlank() }.joinToString(" • ")
            Text(metadata, color = EpisodeMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (item.listening.durationMs > 0) {
                Spacer(Modifier.height(4.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    ListeningProgressBar(item.listening.fraction, Modifier.widthIn(max = 76.dp).fillMaxWidth().height(3.dp))
                }
            }
        }
        CompleteEpisodeButton(onComplete)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewEpisodeRow(ep: PodcastEpisodeUiModel, saved: Boolean, playing: Boolean, downloading: Boolean,
    onDetail: () -> Unit, onMore: () -> Unit, onComplete: () -> Unit, onPlay: () -> Unit,
    onSave: () -> Unit, onDownload: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onDetail, onLongClick = onMore)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NewEpisodeArtwork(ep, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, color = if (playing) Color(0xFF1ED760) else Color.White, fontSize = 16.sp,
                        lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(ep.showName, color = EpisodeMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            val description = remember(ep.description) {
                HtmlCompat.fromHtml(ep.description.orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
            }
            if (description.isNotBlank()) Text(description, color = EpisodeMuted, fontSize = 13.sp, lineHeight = 18.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Text(PodcastDateFormatter.formatMetadata(ep.pubDate, ep.durationMs), color = EpisodeMuted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        }
        @Composable fun actions() {
            Row {
                CompleteEpisodeButton(onComplete)
                SpotifyPlusButton(saved, onSave, size = 22.dp, touchTargetSize = 48.dp, itemKey = ep.id, unsavedTint = EpisodeMuted)
                IconButton(onClick = onDownload, enabled = !downloading) {
                    if (downloading) CircularProgressIndicator(Modifier.size(22.dp), color = EpisodeMuted, strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.ic_spotify_download), "הורדה", tint = EpisodeMuted, modifier = Modifier.size(24.dp))
                }
                IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "אפשרויות לפרק", tint = EpisodeMuted) }
            }
        }
        @Composable fun play() {
            Box(Modifier.size(48.dp).clickable(role = Role.Button, onClick = onPlay), contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                        if (playing) "השהיה" else "ניגון הפרק", tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            if (maxWidth < 240.dp) Column(Modifier.fillMaxWidth()) {
                actions(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { play() }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                actions(); play()
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), color = Color(0xFF333333), thickness = 1.dp)
    }
}

@Composable private fun CompleteEpisodeButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(Icons.Default.Check, "סימון שסיימתם לקרוא", tint = EpisodeMuted, modifier = Modifier.size(26.dp)) }
}

@Composable
internal fun EpisodeCompletionNotice(onUndo: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 480.dp).semantics { liveRegion = LiveRegionMode.Polite },
        color = Color.White, shape = RoundedCornerShape(6.dp), shadowElevation = 4.dp) {
        BoxWithConstraints {
            if (maxWidth < 340.dp) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text("מסומן שסיימתם לקרוא את הפרק.", color = Color.Black, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = onUndo) { Text("ביטול הפעולה", color = Color(0xFF008A32), fontWeight = FontWeight.Bold) }
            } else Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("מסומן שסיימתם לקרוא את הפרק.", color = Color.Black, fontSize = 14.sp,
                    modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp))
                TextButton(onClick = onUndo) { Text("ביטול הפעולה", color = Color(0xFF008A32), fontWeight = FontWeight.Bold) }
            }
        }
    }
}
