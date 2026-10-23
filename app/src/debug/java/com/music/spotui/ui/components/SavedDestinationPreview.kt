package com.music.spotui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.theme.SpotuiTheme

/** Production row/cover/button previews; gesture and Dialog-window checks require a device. */
@Preview(name = "Save destinations — normal", widthDp = 600, heightDp = 1067)
@Preview(name = "Save destinations — small", widthDp = 320, heightDp = 480)
@Preview(name = "Save destinations — narrow / large text", widthDp = 240, heightDp = 320, fontScale = 1.3f)
@Composable
private fun SavedDestinationPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1F1F1F)).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(24.dp))
                SavedInDestinationRow("הפרקים שלכם", "3 פרקים", true, true,
                    cover = { SavedCollectionCover(true, Modifier.size(48.dp)) }, onToggle = {})
                HorizontalDivider(color = Color(0xFF333333))
                SavedInDestinationRow("איזה מזל", "שיר 1", false, true,
                    cover = { SavedCollectionCover(false, Modifier.size(48.dp)) }, onToggle = {})
                SavedInDestinationRow("שם של פלייליסט ארוך לבדיקת גלישה במסך קטן", "12 שירים", false, true,
                    cover = { SavedCollectionCover(false, Modifier.size(48.dp)) }, onToggle = {})
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    SpotifyPlusButton(false, {}, size = 22.dp, touchTargetSize = 48.dp)
                    SpotifyPlusButton(true, {}, size = 22.dp, touchTargetSize = 48.dp)
                }
            }
        }
    }
}
