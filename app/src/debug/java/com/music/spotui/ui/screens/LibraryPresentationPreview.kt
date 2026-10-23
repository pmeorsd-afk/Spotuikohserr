package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.library.LibraryHeaderSurface
import com.music.spotui.ui.library.LibraryTopBar
import com.music.spotui.ui.library.NewEpisodesLibraryItem
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Library — reference width", widthDp = 600, heightDp = 640)
@Preview(name = "Library — normal", widthDp = 360, heightDp = 640)
@Preview(name = "Library — narrow", widthDp = 240, heightDp = 480)
@Preview(name = "Library — large text", widthDp = 240, heightDp = 540, fontScale = 1.3f)
@Composable
private fun LibraryPresentationPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            var search by remember { mutableStateOf(false) }
            var creates by remember { mutableStateOf(0) }
            var opens by remember { mutableStateOf(0) }
            var filter by remember { mutableStateOf("") }
            Column(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                LibraryHeaderSurface {
                    LibraryTopBar(search, { search = !search }, { creates++ }) {
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(0xFFD85820)),
                            contentAlignment = Alignment.Center) { Text("D", color = Color.White) }
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("פלייליסטים", "פודקאסטים", "אלבומים", "אמנים").forEach {
                            LibraryChip(it, filter == it) { filter = if (filter == it) "" else it }
                        }
                    }
                }
                Text("לאחרונה", color = Color.White, modifier = Modifier.padding(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) { NewEpisodesLibraryItem(grid = true) { opens++ } }
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                NewEpisodesLibraryItem(grid = false) { opens++ }
                Text("Search: $search · Create taps: $creates · Open: $opens", color = Color.LightGray,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
}
