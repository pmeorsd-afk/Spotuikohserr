package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.ui.podcast.PodcastCoverStyle
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Podcast covers — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "Podcast covers — phone", widthDp = 360, heightDp = 800)
@Preview(name = "Podcast covers — small", widthDp = 320, heightDp = 480)
@Preview(name = "Podcast covers — narrow", widthDp = 240, heightDp = 480)
@Preview(name = "Podcast covers — narrow / large text", widthDp = 240, heightDp = 480, fontScale = 1.3f)
@Preview(name = "Podcast covers — accessibility", widthDp = 320, heightDp = 640, fontScale = 2f)
@Composable
private fun PodcastCoverPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize().background(Color(0xFF121212))
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Spacer(Modifier.height(4.dp))
                Text("המשך להאזין", color = Color.White)
                val episode = PodcastEpisodeUiModel("preview", 1,
                    "פרק 1: מנהלת גן (ולא רק גננת)", showName = "חדר מורים ירושלמי",
                    publisher = "חדר מורים ירושלמי", durationFormatted = "49 דק׳")
                PodcastEpisodeCover(episode, PodcastCoverStyle.fallback("show-1"))
                PodcastEpisodeCover(episode.copy(title = "90: למה להשתנות אם אפשר להתלונן?! | אורין ג׳ולי — A new perspective",
                    publisher = "אור החכמה | אורין ג׳ולי", durationFormatted = "1 שע׳ 14 דק׳"),
                    PodcastCoverStyle.fromArtwork(0xFFE7CE7B.toInt(), 0xFFF5AD78.toInt()))
                PodcastEpisodeCover(episode.copy(title = "סיפור חדש מתחיל כאן", publisher = "",
                    showName = "הפודקאסט שלנו", durationFormatted = ""),
                    PodcastCoverStyle.fromArtwork(0xFF376F96.toInt(), 0xFF7B4FA1.toInt()))
                EpisodeCardItem(episode, showDownloadAction = false,
                    coverContent = { PodcastCoverSwatch(PodcastCoverStyle.fallback("show-1"), it) })
            }
        }
    }
}
