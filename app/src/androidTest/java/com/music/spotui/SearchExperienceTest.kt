package com.music.spotui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.*
import com.music.spotui.ui.search.*
import com.music.spotui.ui.theme.SpotuiTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production Compose checks; requires a connected emulator/device to execute. */
class SearchExperienceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun normalViewport() = verify(360, 1f)
    @Test fun smallViewportWithLargeText() = verify(240, 1.3f)

    private fun verify(width: Int, fontScale: Float) {
        var value by mutableStateOf(TextFieldValue("אייל", TextRange(4)))
        var opened = 0
        var saved = 0
        var removed = 0
        var submitted = 0
        var clearedHistory = 0
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                SpotuiTheme(darkTheme = true, dynamicColor = false) {
                    val focus = remember { FocusRequester() }
                    Column(Modifier.width(width.dp)) {
                        SearchActiveBar(value, focus, {}, { value = it }, { value = TextFieldValue() }, { submitted++ })
                        SearchSuggestionRow("אייל גולן", value.text, { submitted++ }, {
                            value = TextFieldValue("אייל גולן", TextRange("אייל גולן".length))
                            focus.requestFocus()
                        })
                        SearchEntryRow("פרק בדיקה", "פרק • תוכנית", "", false, itemKey = "episode:exact",
                            onClick = { opened++ }, onSave = { saved++ }, onRemove = { removed++ })
                        ClearSearchHistoryButton { clearedHistory++ }
                    }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }
        val field = compose.onNodeWithTag("active-search-input")
        field.assertIsFocused()
        fun cursorX(): Float {
            val results = mutableListOf<TextLayoutResult>()
            field.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            return results.single().getCursorRect(value.text.length).left
        }
        val startX = cursorX()
        field.performTextInput(" ")
        val firstSpaceX = cursorX()
        field.performTextInput(" ")
        val secondSpaceX = cursorX()
        assertEquals("אייל  ", value.text)
        assertTrue(firstSpaceX < startX)
        assertTrue(secondSpaceX < firstSpaceX)
        compose.onNodeWithTag("insert-suggestion:אייל גולן").performClick()
        field.assertIsFocused()
        assertEquals(TextRange("אייל גולן".length), value.selection)
        assertEquals("אייל גולן", value.text)
        assertEquals(0, submitted)
        compose.onNodeWithContentDescription("מחיקת טקסט החיפוש").performClick()
        assertEquals("", value.text)
        compose.onNodeWithTag("search-save:episode:exact").performClick()
        compose.onNodeWithContentDescription("הסרת פרק בדיקה מהחיפושים האחרונים").performClick()
        assertEquals(1, saved); assertEquals(1, removed); assertEquals(0, opened)
        compose.onNodeWithText("ניקוי החיפושים האחרונים").performClick()
        compose.mainClock.advanceTimeBy(250)
        compose.waitUntil(2_000) { clearedHistory == 1 }
        assertEquals(1, clearedHistory)
    }
}
