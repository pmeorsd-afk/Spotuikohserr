package com.music.spotui.ui.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Matches LikedSongsScreen: 36dp controls, 4dp corners, 13sp text, 18dp search icon. */
@Composable
internal fun CollectionSearchRow(query: String, onQuery: (String) -> Unit, enabled: Boolean, onSort: () -> Unit) {
    val secondary = Color(0xFFB3B3B3)
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = .12f)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, "חיפוש", Modifier.size(18.dp), tint = secondary)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text("חיפוש בפלייליסט", color = secondary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(query, onQuery, textStyle = TextStyle(color = Color.White, fontSize = 13.sp),
                    cursorBrush = SolidColor(Color(0xFF1ED760)), singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.Close, "נקה", Modifier.size(16.dp).clickable { onQuery("") }, tint = secondary)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.height(36.dp).clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .12f))
            .clickable(enabled = enabled, onClick = onSort).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text("מיון", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
