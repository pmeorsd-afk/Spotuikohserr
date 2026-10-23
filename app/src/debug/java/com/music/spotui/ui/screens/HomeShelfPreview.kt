package com.music.spotui.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation.compose.rememberNavController
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.HomeSectionIds
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Home shelves — normal", widthDp = 360, heightDp = 800)
@Preview(name = "Home shelves — narrow", widthDp = 240, heightDp = 800)
@Preview(name = "Home shelves — large text", widthDp = 240, heightDp = 900, fontScale = 1.3f)
@Composable
private fun HomeShelfPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            val nav = rememberNavController()
            Column {
                HomeFeedSection(nav, HomeSection(HomeSectionIds.TOP_MIXES, "המיקסים המובילים שלכם",
                    items = (0..7).map { HomeItem.Playlist("מיקס $it", "",
                        if (it < 3) "Playlist • Spotify" else "תיאור ארוך של יוצר שמתפרס על שתי שורות", "mix-$it") }), {}, {})
                HomeFeedSection(nav, HomeSection(HomeSectionIds.POPULAR_RADIO, "רדיו פופולרי",
                    items = (0..3).map { HomeItem.Playlist("רדיו $it", "", "Playlist • Spotify", "radio-$it") }), {}, {})
            }
        }
    }
}
