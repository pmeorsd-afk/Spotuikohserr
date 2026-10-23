package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.ui.theme.SpotuiTheme

/** Uses production composables without Hilt, network, playback or a signed-in account. */
@Preview(name = "Show — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "Show — small viewport", widthDp = 320, heightDp = 480)
@Preview(name = "Show — narrow viewport", widthDp = 240, heightDp = 480)
@Preview(name = "Show — small / large text", widthDp = 320, heightDp = 480, fontScale = 1.3f)
@Composable
private fun PodcastShowPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
                Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(
                    listOf(Color(0xFF850412), Color(0xFF390D12), Color(0xFF121212)))))
                Column {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White,
                        modifier = Modifier.padding(top = 24.dp, start = 8.dp).size(48.dp).padding(12.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        PodcastShowHeader("הקצבייה The Butcher Shop", "כאן | Kan", "", false,
                            listOf("קומדיה", "טלוויזיה"), true, false, {}, {}, {}, {})
                        PodcastShowTabs(0) {}
                        PodcastEpisodeListHeading()
                        repeat(4) { index ->
                            EpisodeCardItem(
                                ep = PodcastEpisodeUiModel(id = "preview-$index", numericId = index,
                                    title = if (index == 0) "פרק חירום שלישי: הרומן של אנטולי" else "כותרת ארוכה לפרק עם עברית וגם English כדי לבדוק שבירת שורות",
                                    description = "תיאור לדוגמה של הפרק. הטקסט נפרס על שתי שורות לכל היותר, ומאפשר לבדוק את הריווח בין הכותרת לפרטי הפרק ולכפתורים שמתחתיהם.",
                                    formattedMetadata = "21 דצמ׳ 2023 • 14 דק׳"),
                                showPageStyle = true, isSaved = index == 1,
                            )
                        }
                        Spacer(Modifier.height(140.dp))
                    }
                }
            }
        }
    }
}
