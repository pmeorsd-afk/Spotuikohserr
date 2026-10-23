package com.music.spotui.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.R
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.util.KosherWhitelistManager

/** Denser Show rows. Home's existing EpisodeCardItem presentation stays unchanged. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PodcastShowEpisodeCard(
    ep: PodcastEpisodeUiModel,
    showCoverUri: String?,
    isPlayingCurrent: Boolean,
    isPlayingActive: Boolean,
    isSaved: Boolean,
    onEpisodeClick: () -> Unit,
    onTogglePlay: () -> Unit,
    onSaveClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onShareClick: () -> Unit,
    onMoreClick: () -> Unit,
    showDownloadAction: Boolean,
) {
    val whitelistVersion by KosherWhitelistManager.versionState
    val allowed = remember(ep, whitelistVersion) { KosherWhitelistManager.isSongWhitelisted(ep.toSongModel()) }
    Column(Modifier.fillMaxWidth().combinedClickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        onClick = onEpisodeClick, onLongClick = onMoreClick,
    ).padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                GlideImage(model = ep.coverUri.ifBlank { showCoverUri.orEmpty() }, isAllowed = allowed,
                    contentDescription = ep.title, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                Spacer(Modifier.width(12.dp))
                Text(ep.title, color = if (isPlayingCurrent) Color(0xFF1ED760) else Color.White,
                    fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (!ep.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(ep.description, color = Color(0xFFB3B3B3), fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (ep.formattedMetadata.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(ep.formattedMetadata, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        @Composable
        fun secondaryActions() {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpotifyPlusButton(isSaved, onSaveClick, size = 22.dp, touchTargetSize = 48.dp,
                    unsavedTint = Color(0xFFB3B3B3), itemKey = ep.id)
                if (showDownloadAction) EpisodeAction(onDownloadClick) {
                    Icon(painterResource(R.drawable.ic_spotify_download), "הורדה",
                        tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
                }
                EpisodeAction(onShareClick) {
                    Icon(painterResource(R.drawable.ic_share), "שתף",
                        tint = Color(0xFFB3B3B3), modifier = Modifier.size(22.dp))
                }
                EpisodeAction(onMoreClick) {
                    Icon(Icons.Default.MoreVert, "אפשרויות נוספות",
                        tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
                }
            }
        }
        @Composable
        fun playAction() {
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onTogglePlay),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(32.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (isPlayingActive) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
                        if (isPlayingActive) "השהה" else "נגן", tint = Color.Black, modifier = Modifier.size(16.dp))
                }
            }
        }
        // Keep 48dp targets separate even at 240dp viewport width; only very narrow layouts wrap.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            val requiredWidth = if (showDownloadAction) 240.dp else 192.dp
            if (maxWidth < requiredWidth) {
                Column(Modifier.fillMaxWidth()) {
                    secondaryActions()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { playAction() }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    secondaryActions()
                    playAction()
                }
            }
        }
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(0.5.dp).background(Color(0xFF282828)))
    }
}

@Composable
private fun EpisodeAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}
