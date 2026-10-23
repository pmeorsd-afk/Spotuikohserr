package com.music.spotui.ui.library

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.music.spotui.R

/** Draw outside the header without displacing the sort row or the scrolling content. */
@Composable
internal fun LibraryHeaderSurface(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().zIndex(1f).background(Color(0xFF121212)).drawWithContent {
        drawContent()
        val depth = 4.dp.toPx()
        drawRect(
            brush = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.48f), Color.Transparent),
                startY = size.height, endY = size.height + depth),
            topLeft = Offset(0f, size.height), size = Size(size.width, depth),
        )
    }, content = content)
}

@Composable
internal fun LibraryTopBar(
    searchActive: Boolean,
    onSearch: () -> Unit,
    onCreatePlaylist: () -> Unit,
    avatar: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        avatar()
        Spacer(Modifier.width(12.dp))
        Text("הספרייה", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        LibraryHeaderAction(R.drawable.ic_search_outline, "חיפוש",
            if (searchActive) Color(0xFF1ED760) else Color.White, onSearch)
        LibraryHeaderAction(R.drawable.ic_library_create, "יצירת פלייליסט", Color.White, onCreatePlaylist)
    }
}

@Composable
private fun LibraryHeaderAction(@DrawableRes icon: Int, label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(
        interactionSource = remember { MutableInteractionSource() }, indication = null,
        role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun NewEpisodesCover(modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF64397E)),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.ic_new_episodes), contentDescription = null,
            tint = Color(0xFF1ED760), modifier = Modifier.fillMaxSize(0.5f))
    }
}

/** Derived collection: navigation only, with no remove/playlist menu. */
@Composable
internal fun NewEpisodesLibraryItem(grid: Boolean, onClick: () -> Unit) {
    @Composable fun label() {
        Text("פרקים חדשים", color = Color.White, fontSize = if (grid) 14.sp else 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Do not claim "updated today" before the feed has actually been refreshed.
        Text("פודקאסטים במעקב", color = Color(0xFFA7A7A7), fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (grid) {
        Column(Modifier.clickable(role = Role.Button, onClick = onClick)) {
            NewEpisodesCover(Modifier.fillMaxWidth().aspectRatio(1f))
            Spacer(Modifier.height(6.dp))
            label()
        }
    } else {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            NewEpisodesCover(Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { label() }
        }
    }
}
