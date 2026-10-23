package com.music.spotui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.rememberNavController
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSection
import com.music.spotui.data.entity.HomeSectionIds
import com.music.spotui.ui.screens.HomeFeedSection
import com.music.spotui.ui.theme.SpotuiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Regression: removing the only two-line caption from LazyRow's measured window moved Radio. */
class HomeShelfStabilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun normalPhone() = checkShelf(360, 1f)
    @Test fun narrowPhone() = checkShelf(240, 1f)
    @Test fun narrowLargeText() = checkShelf(240, 1.3f)

    private fun checkShelf(width: Int, scale: Float) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density.density, scale)) {
                SpotuiTheme(darkTheme = true, dynamicColor = false) {
                    val nav = rememberNavController()
                    Column(Modifier.width(width.dp)) {
                        HomeFeedSection(nav, HomeSection(HomeSectionIds.TOP_MIXES, "המיקסים המובילים שלכם",
                            items = (0..7).map { index -> HomeItem.Playlist("מיקס $index", "",
                                if (index < 3) "Playlist • Spotify" else "יוצר עם שם ארוך מאוד שדורש שתי שורות בכרטיס המיקס",
                                "playlist-$index") }), {}, {})
                        Box(Modifier.fillMaxWidth().height(1.dp).testTag("radio-start"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val shelf = compose.onAllNodes(hasScrollAction()).onFirst()
        val initial = shelf.fetchSemanticsNode().boundsInRoot
        val anchor = compose.onNodeWithTag("radio-start").fetchSemanticsNode().boundsInRoot.top
        fun unchanged() {
            compose.waitForIdle()
            val actual = shelf.fetchSemanticsNode().boundsInRoot
            assertEquals(initial.top, actual.top, .5f)
            assertEquals(initial.height, actual.height, .5f)
            assertEquals(anchor, compose.onNodeWithTag("radio-start").fetchSemanticsNode().boundsInRoot.top, .5f)
        }
        shelf.performScrollToIndex(4)
        unchanged()
        shelf.performTouchInput { swipeLeft() }
        unchanged()
        shelf.performTouchInput { swipeRight() }
        unchanged()
        shelf.performScrollToIndex(0)
        unchanged()
    }
}
