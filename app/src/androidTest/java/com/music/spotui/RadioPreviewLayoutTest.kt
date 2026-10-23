package com.music.spotui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.RadioPreviewState
import com.music.spotui.ui.collections.RadioPreviewContent
import com.music.spotui.ui.theme.SpotuiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Device checks: same production surface, stable row geometry, pinned heading and exact click. */
class RadioPreviewLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun phone() = verify(360, 1f)
    @Test fun narrowLargeText() = verify(240, 1.3f)
    private fun verify(width: Int, fontScale: Float) {
        var previewed: SongsModel? = null
        var menu: SongsModel? = null
        val songs = (1..30).map { SongsModel(it, "שיר עם שם ארוך — $it", "", "יסמין מועלם, בן צור", "", "song:$it", durationMs = 180_000) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SpotuiTheme(darkTheme = true, dynamicColor = false) {
                    Box(Modifier.width(width.dp)) {
                        RadioPreviewContent("רדיו לפי אודיה", Color(0xFF94E8B3), songs, RadioPreviewState(), {},
                            { previewed = it }, { menu = it })
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("radio-preview-list")
        list.performScrollToIndex(2)
        compose.onNodeWithTag("preview-pinned-title").assertIsDisplayed()
        val row = compose.onNodeWithTag("preview-row:1")
        val before = row.fetchSemanticsNode().boundsInRoot
        row.performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.waitUntil(1_000) { previewed != null }
        compose.waitForIdle()
        assertEquals(songs[0], previewed)
        val after = row.fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        previewed = null
        compose.onAllNodesWithContentDescription("אפשרויות לשיר").onFirst().performClick()
        compose.waitForIdle()
        assertEquals(songs[0], menu); assertNull(previewed)
        list.performScrollToIndex(20)
        compose.onNodeWithTag("preview-pinned-title").assertIsDisplayed()
    }
}
