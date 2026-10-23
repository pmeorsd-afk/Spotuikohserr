package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.music.spotui.ui.search.*
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Recent search 360", widthDp = 360, heightDp = 780)
@Composable private fun SearchRecentsPhone() = SearchSample(false)
@Preview(name = "Recent search 240 large text", widthDp = 240, heightDp = 480, fontScale = 1.3f)
@Composable private fun SearchRecentsSmall() = SearchSample(false)
@Preview(name = "Live search 360", widthDp = 360, heightDp = 780)
@Composable private fun SearchLivePhone() = SearchSample(true)
@Preview(name = "Live search 240 large text", widthDp = 240, heightDp = 480, fontScale = 1.3f)
@Composable private fun SearchLiveSmall() = SearchSample(true)

@Composable private fun SearchSample(typing: Boolean) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        SpotuiTheme(darkTheme = true, dynamicColor = false) {
            var text by remember { mutableStateOf(TextFieldValue(if (typing) "אייל " else "")) }
            Column(Modifier.fillMaxSize().background(SearchBodyColor)) {
                SearchActiveBar(text, remember { FocusRequester() }, {}, { text = it }, { text = TextFieldValue() }, {})
                if (typing) listOf("אייל גולן", "אייל גולן הלהיטים הגדולים", "אייל לוי").forEach { suggestion ->
                    SearchSuggestionRow(suggestion, text.text, {}, { text = TextFieldValue(suggestion, TextRange(suggestion.length)) })
                } else SearchSectionHeader("לאחרונה")
                repeat(4) { index ->
                    SearchEntryRow(if (index == 2) "אייל גולן" else "שם שיר או פרק ארוך לבדיקה $index",
                        if (index == 2) "אמן או אמנית" else "שיר • אייל גולן", "", false,
                        artist = index == 2, onClick = {}, onSave = if (index != 2) ({}) else null,
                        onRemove = if (!typing) ({}) else null)
                }
                if (!typing) ClearSearchHistoryButton {}
            }
        }
    }
}
