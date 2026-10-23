package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Show-page presentation only. Callers retain following, sharing and playback ownership. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PodcastShowHeader(
    title: String,
    publisher: String,
    coverUri: String,
    artworkAllowed: Boolean,
    topics: List<String>,
    following: Boolean,
    notificationsEnabled: Boolean,
    onFollow: () -> Unit,
    onNotifications: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        BoxWithConstraints {
            // 112dp at the reference width; leave room for names on narrow viewports.
            val coverSize = (maxWidth * 0.25f).coerceIn(72.dp, 112.dp)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                GlideImage(
                    model = coverUri,
                    isAllowed = artworkAllowed,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(coverSize).clip(RoundedCornerShape(4.dp)),
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 24.sp, lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (publisher.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(publisher, color = Color.White, fontSize = 13.sp, lineHeight = 18.sp,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // The model has no rating/count. Display real topics instead of a fabricated 4.8.
        Text(topics.filter { it.isNotBlank() }.distinct().joinToString(" • ").ifBlank { "פודקאסט" },
            color = Color(0xFFB3B3B3), fontSize = 14.sp, lineHeight = 20.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        Spacer(Modifier.height(12.dp))
        FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.Start) {
            Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onFollow),
                contentAlignment = Alignment.Center) {
                Text(if (following) "במעקב" else "מעקב", color = Color.White,
                    fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.border(1.dp, Color(0xFF7F7F7F), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
            Spacer(Modifier.width(8.dp))
            ShowHeaderAction(onNotifications) {
                Icon(painterResource(R.drawable.ic_podcast_bell), "התראות",
                    tint = if (notificationsEnabled) Color(0xFF1ED760) else Color(0xFFB3B3B3),
                    modifier = Modifier.size(24.dp))
            }
            ShowHeaderAction(onShare) {
                Icon(painterResource(R.drawable.ic_share), "שתף", tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
            }
            ShowHeaderAction(onMore) {
                Icon(Icons.Default.MoreVert, "אפשרויות נוספות", tint = Color(0xFFB3B3B3), modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun ShowHeaderAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun PodcastShowTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        listOf("פרקים", "אודות").forEachIndexed { index, title ->
            Column(Modifier.width(IntrinsicSize.Max).heightIn(min = 48.dp)
                .selectable(selected = selectedTab == index, role = Role.Tab, onClick = { onSelect(index) })) {
                Text(title, color = if (selectedTab == index) Color.White else Color(0xFFB3B3B3),
                    fontSize = 16.sp, lineHeight = 22.sp,
                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 10.dp, bottom = 14.dp))
                Box(Modifier.fillMaxWidth().height(2.dp)
                    .background(if (selectedTab == index) Color(0xFF1ED760) else Color.Transparent))
            }
        }
    }
}

@Composable
internal fun PodcastEpisodeListHeading() {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // This existing heading describes the loaded ordering; no new sort action is implied.
        Icon(painterResource(R.drawable.ic_podcast_filters), null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Text("כל הפרקים • הפריטים החדשים ביותר", color = Color.White,
            fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
    }
}
