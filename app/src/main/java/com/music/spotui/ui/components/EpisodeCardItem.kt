package com.music.spotui.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.util.KosherWhitelistManager

/**
 * 4-Layer Episode Card matching official Spotify:
 * 1. Thumbnail + Title (in green if currently playing)
 * 2. Episode Description (max 2 lines)
 * 3. Hebrew Date • Duration metadata
 * 4. Secondary actions (Save +, Download, Share, More) + Circular Play Button
 */
@OptIn(ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun EpisodeCardItem(
    ep: PodcastEpisodeUiModel,
    showCoverUri: String? = null,
    isPlayingCurrent: Boolean = false,
    isPlayingActive: Boolean = false,
    isSaved: Boolean = false,
    onEpisodeClick: () -> Unit = {},
    onTogglePlay: () -> Unit = {},
    onSaveClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onShareClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = onMoreClick,
                onClick = onEpisodeClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // LAYER 1: Thumbnail (right) + Title (left in RTL)
        Row(
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth()
        ) {
            GlideImage(
                model = ep.coverUri.ifBlank { showCoverUri.orEmpty() },
                contentScale = ContentScale.Crop,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp)),
                isAllowed = KosherWhitelistManager.isSongWhitelisted(ep.toSongModel()),
                contentDescription = ep.title,
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = ep.title,
                color = if (isPlayingCurrent) Color(0xFF1ED760) else Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp,
                modifier = Modifier.weight(1f)
            )
        }

        // LAYER 2: Clean Episode Description (max 2 lines, soft grey #B3B3B3)
        if (!ep.description.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ep.description,
                color = Color(0xFFB3B3B3),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
        }

        // LAYER 3: Formatted Metadata: Hebrew Date • Duration (e.g. שבת • 2שע' 17 דק')
        if (ep.formattedMetadata.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = ep.formattedMetadata,
                color = Color(0xFFB3B3B3),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal
            )
        }

        // LAYER 4: Action row: Secondary icons on right, White circular Play button on left (in RTL)
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Secondary Actions (Right side in RTL): Save, Download, Share, More
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Save (+) -> Opens SavedInSheet exactly like Spotify
                Icon(
                    painter = painterResource(if (isSaved) R.drawable.added else R.drawable.ic_add),
                    contentDescription = if (isSaved) "נשמר" else "הוסף",
                    tint = if (isSaved) Color(0xFF1ED760) else Color(0xFFB3B3B3),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            onSaveClick()
                        }
                )

                // 2. Download (↓)
                Icon(
                    painter = painterResource(R.drawable.ic_spotify_download),
                    contentDescription = "הורדה",
                    tint = Color(0xFFB3B3B3),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable {
                            onDownloadClick()
                        }
                )

                // 3. Share
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "שתף",
                    tint = Color(0xFFB3B3B3),
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            onShareClick()
                        }
                )

                // 4. More (⋮)
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "אפשרויות נוספות",
                    tint = Color(0xFFB3B3B3),
                    modifier = Modifier
                        .size(24.dp)
                        .clickable { onMoreClick() }
                )
            }

            // Primary Action (Left side in RTL): Circular White Play/Pause Button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable {
                        onTogglePlay()
                    }
            ) {
                Icon(
                    painter = painterResource(
                        id = if (isPlayingActive) R.drawable.ic_playing else R.drawable.play_svgrepo_com
                    ),
                    contentDescription = if (isPlayingActive) "השהה" else "נגן",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        // Subtle separator line
        Spacer(modifier = Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color(0xFF282828))
        )
    }
}
