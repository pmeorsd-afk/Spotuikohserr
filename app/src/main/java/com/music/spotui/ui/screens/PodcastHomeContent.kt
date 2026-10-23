package com.music.spotui.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import com.music.spotui.di.SongPlayer
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.ui.components.EpisodeCardItem
import com.music.spotui.ui.components.PodcastEpisodeCover
import com.music.spotui.ui.components.PodcastCoverSwatch
import com.music.spotui.ui.components.rememberPodcastCoverColors
import com.music.spotui.ui.navigation.ItemDetailRegistry
import com.music.spotui.ui.navigation.Routes
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.ui.viewmodel.PodcastHomeState
import com.music.spotui.ui.viewmodel.PodcastHomeViewModel
import com.music.spotui.ui.viewmodel.PlayerViewModel
import com.music.spotui.util.KosherWhitelistManager

internal fun LazyListScope.podcastHomeItems(
    state: PodcastHomeState,
    following: Boolean,
    vm: PodcastHomeViewModel,
    navController: NavController,
    onMore: (SongsModel) -> Unit,
) {
    item(key = "podcast_actions") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { navController.navigate(Routes.PodcastChooser.route) }) { Text("בחירת פודקאסטים", color = Color.White) }
            TextButton(onClick = { vm.refresh(force = true) }, enabled = !state.loading) { Text("רענון", color = Color(0xFF1ED760)) }
        }
    }
    if (state.partialError) item(key = "podcast_error") {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("חלק מהפרקים לא נטענו. התוכן שכבר נטען זמין כאן.", color = Color.LightGray)
            TextButton(onClick = { vm.refresh(force = true) }, enabled = !state.loading) { Text("ניסיון נוסף", color = Color(0xFF1ED760)) }
        }
    }
    if (following) {
        if (state.sections.following.isNotEmpty()) {
            item(key = "following_title") { PodcastSectionTitle("הפרקים האחרונים") }
            items(state.sections.following, key = { "following:${it.id}" }) { ep ->
                PodcastHomeEpisode(ep, compact = true, vm, navController, onMore)
            }
        } else if (!state.loading) item(key = "following_empty") {
            PodcastEmptyMessage(if (state.followedCount == 0) "בחרו פודקאסטים למעקב כדי לראות כאן את הפרקים שלהם" else "עדיין אין פרקים זמינים מהתוכניות במעקב")
        }
    } else {
        val sections = listOf(
            Triple("continue", "המשך להאזין", state.sections.continuing),
            Triple("recommended", "פרקים שאולי תאהבו", state.sections.recommended),
            Triple("latest", if (state.followedCount > 0) "חדש מפודקאסטים במעקב" else "פרקים חדשים", state.sections.latest),
        )
        sections.filter { it.third.isNotEmpty() }.forEach { (key, title, episodes) ->
            item(key = "$key-title") { PodcastSectionTitle(title) }
            items(episodes, key = { "$key:${it.id}" }) { ep -> PodcastHomeEpisode(ep, false, vm, navController, onMore) }
        }
        if (!state.loading && sections.all { it.third.isEmpty() }) item(key = "podcast_empty") {
            PodcastEmptyMessage("אין כרגע פרקים להצגה. אפשר לבחור תוכניות למעקב או לנסות לרענן.")
        }
    }
    if (state.loading) item(key = "podcast_loading") {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFF1ED760), modifier = Modifier.size(28.dp))
        }
    }
    if (state.hasMore && !state.loading) item(key = "podcast_more") {
        TextButton(onClick = vm::loadMore, modifier = Modifier.fillMaxWidth()) {
            Text("טעינת פרקים מתוכניות נוספות", color = Color.White)
        }
    }
}

@Composable private fun PodcastSectionTitle(title: String) {
    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp))
}
@Composable private fun PodcastEmptyMessage(message: String) {
    Text(message, color = Color.LightGray, modifier = Modifier.fillMaxWidth().padding(24.dp))
}

@Composable
private fun PodcastHomeEpisode(
    episode: PodcastEpisodeUiModel, compact: Boolean, vm: PodcastHomeViewModel,
    navController: NavController, onMore: (SongsModel) -> Unit,
) {
    val context = LocalContext.current
    val song = remember(episode) { episode.toSongModel() }
    val whitelist by KosherWhitelistManager.versionState
    val likedRevision by likedSongsRevision.collectAsState()
    val saved = remember(song, likedRevision) { isSavedInAnyDestination(context, song) }
    val homeItem = remember(song) { com.music.spotui.data.entity.HomeItem.Track(song) }
    val globallyAllowed = remember(song, whitelist) { KosherWhitelistManager.isSongWhitelisted(song) }
    val allowed = com.music.spotui.util.HomeArtworkPermissions.isApproved(homeItem.imageUrl) || globallyAllowed
    val coverColors = rememberPodcastCoverColors(episode, allowed)
    val player: PlayerViewModel = hiltViewModel()
    val playing by player.currentSongPlayingState
    val currentId by player.currentSongId
    val title by player.currentSongTitle
    val owner = SongPlayer.ownedPlaybackSnapshot()?.song
    val isCurrent = currentId == song.id && title == song.title && owner != null &&
        PlaybackProgressStore.key(owner) == PlaybackProgressStore.key(song)
    val progress by remember(song) { observePodcastProgress(context, song) }.collectAsState(initial = null)
    val detail = {
        requestHomeCoverOnTap(context, homeItem)
        navController.navigate(showRoute(episode.showId, episode.showName, ItemDetailRegistry.register(song)))
    }
    var savingTarget by remember(episode.id) { mutableStateOf<SongsModel?>(null) }
    savingTarget?.let { target ->
        com.music.spotui.ui.components.SavedInSheet(target, context, onDismiss = { savingTarget = null })
    }
    val save: () -> Unit = {
        if (saveToDefaultIfNeeded(context, song)) savingTarget = song
    }

    if (compact) {
        EpisodeCardItem(
            ep = episode, isPlayingCurrent = isCurrent, isPlayingActive = isCurrent && playing, isSaved = saved,
            onEpisodeClick = detail, onTogglePlay = { vm.togglePlay(episode, context) }, onSaveClick = save,
            // The legacy downloader resolves music sources; expose no podcast download action here.
            showDownloadAction = false,
            onShareClick = {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${episode.title} — ${episode.showName}")
                }, null))
            },
            onMoreClick = { onMore(song) },
            coverContent = { modifier -> PodcastCoverSwatch(coverColors, modifier) },
        )
        return
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        PodcastEpisodeCover(episode, coverColors,
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = detail))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
            PodcastCoverSwatch(coverColors, Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))
                .clickable {
                    requestHomeCoverOnTap(context, homeItem)
                    navController.navigate(showRoute(episode.showId, episode.showName))
                })
            Column(Modifier.weight(1f).padding(horizontal = 8.dp).clickable(onClick = detail)) {
                Text(episode.title, color = if (isCurrent) Color(0xFF1ED760) else Color.White,
                    fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOf(episode.showName, episode.pubDateFormatted).filter { it.isNotBlank() }.joinToString(" • "),
                    color = Color.LightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { onMore(song) }) { Icon(Icons.Default.MoreVert, "אפשרויות לפרק", tint = Color.LightGray) }
        }
        val description = remember(episode.description) {
            HtmlCompat.fromHtml(episode.description.orEmpty(), HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()
        }
        if (description.isNotBlank()) Text(description, color = Color(0xFFB3B3B3), fontSize = 13.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        progress?.takeIf { it.positionMs > 0 || it.completed }?.let { value ->
            LinearProgressIndicator(progress = { value.fraction }, color = Color(0xFF1ED760), trackColor = Color.DarkGray,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(3.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            com.music.spotui.ui.components.SpotifyPlusButton(saved, save,
                touchTargetSize = 48.dp, itemKey = episode.id, unsavedTint = Color.LightGray)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.togglePlay(episode, context) }, modifier = Modifier.size(44.dp).background(Color.White, CircleShape)) {
                Icon(painterResource(if (isCurrent && playing) R.drawable.ic_playing else R.drawable.ic_paused),
                    if (isCurrent && playing) "השהיה" else "ניגון הפרק", tint = Color.Black)
            }
        }
    }
}
