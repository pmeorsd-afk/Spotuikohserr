package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.music.spotui.ui.components.SearchCategoryCoverMenuContent
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Search cover menu normal", widthDp = 360, heightDp = 280)
@Preview(name = "Search cover menu small", widthDp = 240, heightDp = 280)
@Preview(name = "Search cover menu large text", widthDp = 240, heightDp = 320, fontScale = 1.3f)
@Composable
private fun SearchCategoryCoverPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF1F1F1F))) {
                SearchCategoryCoverMenuContent("ריליסים שצפויים בקרוב", "", allowed = false,
                    enabled = true, hasImage = true, pending = false, onRequest = {}, onRefresh = {})
            }
        }
    }
}
