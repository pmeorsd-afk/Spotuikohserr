package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.theme.SpotuiTheme
import com.music.spotui.ui.viewmodel.HomeTabFilter

@Preview(name = "Podcast filters — normal", widthDp = 360, heightDp = 280)
@Preview(name = "Podcast filters — narrow", widthDp = 240, heightDp = 280)
@Preview(name = "Podcast filters — large text", widthDp = 240, heightDp = 320, fontScale = 1.3f)
@Composable
private fun PodcastFilterPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(HomeTabFilter.ALL, HomeTabFilter.PODCASTS, HomeTabFilter.FOLLOWING).forEach { initialFilter ->
                    var filter by remember(initialFilter) { mutableStateOf(initialFilter) }
                    Text(filter.name, color = Color.LightGray)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        PodcastFilterGroup(filter, filter != HomeTabFilter.ALL, { filter = it })
                    }
                }
            }
        }
    }
}
