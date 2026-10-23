package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.ui.podcast.PodcastShowMenuTarget
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Show menu — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "Show menu — small", widthDp = 320, heightDp = 480)
@Preview(name = "Show menu — narrow / large text", widthDp = 240, heightDp = 320, fontScale = 1.3f)
@Composable
private fun PodcastShowMenuPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
                Column(Modifier.fillMaxWidth().background(Color(0xFF1F1F1F)).verticalScroll(rememberScrollState())) {
                    Spacer(Modifier.height(32.dp))
                    PodcastShowMenuContent(
                        target = PodcastShowMenuTarget(PodcastModel("38bS44xjbVVZ3No3ByF1dJ", "הקצבייה The Butcher Shop", "כאן | Kan", "")),
                        following = true, onShare = {}, onToggleFollow = {}, onRating = {}, onCode = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "Personal rating — narrow", widthDp = 240, heightDp = 120)
@Composable
private fun PodcastRatingPreview() {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1F1F1F)).padding(16.dp)) {
            PodcastRatingStars(selected = 4, enabled = true, onSelect = {})
        }
    }
}
