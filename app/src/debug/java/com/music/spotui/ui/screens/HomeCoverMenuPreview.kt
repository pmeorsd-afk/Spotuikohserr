package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.ui.components.HomeCoverMenuContent
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Home cover menu normal", widthDp = 360, heightDp = 420)
@Preview(name = "Home cover menu small", widthDp = 240, heightDp = 320)
@Preview(name = "Home cover menu large text", widthDp = 240, heightDp = 320, fontScale = 1.3f)
@Composable
private fun HomeCoverMenuPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1F1F1F))) {
                HomeCoverMenuContent(HomeItem.Playlist("מיקס תפילה", "", "Playlist • DJ Beckerman"),
                    allowed = false, enabled = true, hasImage = true, pending = false,
                    onShare = {}, onSave = {}, onOpen = {}, onSongOptions = null, onRequest = {}, onRefresh = {})
            }
        }
    }
}
