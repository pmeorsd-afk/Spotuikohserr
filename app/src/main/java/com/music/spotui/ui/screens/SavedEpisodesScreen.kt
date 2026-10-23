package com.music.spotui.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.ui.components.*
import com.music.spotui.ui.viewmodel.SavedEpisodesViewModel
import com.music.spotui.util.PodcastDateFormatter

@Composable
fun SavedEpisodesScreen(navController: NavController) {
    val context = LocalContext.current
    val revision by likedSongsRevision.collectAsState()
    val episodes = remember(revision) { getSavedEpisodes(context) }
    val vm: SavedEpisodesViewModel = hiltViewModel()
    val playing by vm.playing
    val currentId by vm.currentId
    var saveTarget by remember { mutableStateOf<SongsModel?>(null) }
    var menuTarget by remember { mutableStateOf<SongsModel?>(null) }
    saveTarget?.let { SavedInSheet(it, context, onDismiss = { saveTarget = null }) }
    menuTarget?.let { SongOptionsSheet(it, navController, context, onDismiss = { menuTarget = null }) }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Column(Modifier.fillMaxSize().background(Color(0xFF121212)).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White) }
                Text("הפרקים שלכם", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        SavedCollectionCover(true, Modifier.size(72.dp)); Spacer(Modifier.width(16.dp))
                        Text(savedEpisodeCountLabel(episodes.size), color = Color.LightGray)
                    }
                }
                if (episodes.isEmpty()) item { Text("פרקים שתשמרו יופיעו כאן", color = Color.LightGray, modifier = Modifier.padding(24.dp)) }
                items(episodes, key = { requireNotNull(episodeSaveKey(it)) }) { song ->
                    val owner = SongPlayer.ownedPlaybackSnapshot()?.song
                    val current = currentId == song.id && owner != null && episodeSaveKey(owner) == episodeSaveKey(song)
                    val ep = PodcastEpisodeUiModel(id = requireNotNull(episodeSaveKey(song)), numericId = song.id,
                        title = song.title, showName = song.album, publisher = song.singer, coverUri = song.coverUri,
                        durationMs = song.durationMs.toLong(), formattedMetadata = PodcastDateFormatter.formatDuration(song.durationMs.toLong()),
                        showId = song.podcastShowId, playUrl = song.url, spotifyEpisodeId = song.spotifyTrackId)
                    EpisodeCardItem(ep, isPlayingCurrent = current, isPlayingActive = current && playing,
                        isSaved = true, showPageStyle = true, showDownloadAction = false,
                        onEpisodeClick = { vm.toggle(song, context) }, onTogglePlay = { vm.toggle(song, context) },
                        onSaveClick = { saveTarget = song }, onMoreClick = { menuTarget = song },
                        onShareClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${song.title} — ${song.album}")
                        }, null)) })
                }
            }
        }
    }
}
