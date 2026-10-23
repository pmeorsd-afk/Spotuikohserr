package com.music.spotui.ui.search

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.R
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.SpotifyPlusButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val SearchBodyColor = Color(0xFF121212)
internal val SearchHeaderColor = Color(0xFF1F1F1F)
internal val SearchMuted = Color(0xFFB3B3B3)

@Composable
internal fun SearchActiveBar(
    value: TextFieldValue, focusRequester: FocusRequester, onBackClick: () -> Unit,
    onTextChange: (TextFieldValue) -> Unit, onClearClick: () -> Unit, onSearch: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(48.dp).background(SearchHeaderColor), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBackClick, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", Modifier.size(24.dp), tint = Color.White)
        }
        Row(Modifier.weight(1f).padding(end = 16.dp).height(32.dp).clip(RoundedCornerShape(50))
            .background(Color(0xFF363636)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(value, onTextChange, Modifier.weight(1f).focusRequester(focusRequester).testTag("active-search-input"),
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Normal,
                    textDirection = TextDirection.ContentOrRtl, textAlign = TextAlign.Right),
                cursorBrush = SolidColor(Color(0xFF1ED760)), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { input ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) Text("לאיזה תוכן תרצו להאזין?", color = SearchMuted, fontSize = 16.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        input()
                    }
                })
            // Keep text width stable when clear appears; the icon is inside the rounded field.
            Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                if (value.text.isNotEmpty()) IconButton(onClick = onClearClick, modifier = Modifier.size(32.dp)) {
                    Box(Modifier.size(16.dp).background(SearchMuted, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, "מחיקת טקסט החיפוש", Modifier.size(12.dp), tint = Color(0xFF363636))
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchSuggestionRow(suggestion: String, query: String, onClick: () -> Unit, onInsert: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onClick).padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_search_big), null, Modifier.size(18.dp), tint = Color.White)
        val prefix = searchRequestText(query)
        Text(buildAnnotatedString {
            if (prefix.isNotBlank() && suggestion.startsWith(prefix, ignoreCase = true)) {
                withStyle(SpanStyle(color = SearchMuted)) { append(suggestion.take(prefix.length)) }
                append(suggestion.drop(prefix.length))
            } else append(suggestion)
        }, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 20.dp))
        IconButton(onClick = onInsert, modifier = Modifier.size(48.dp).testTag("insert-suggestion:$suggestion")) {
            // Physical up-right arrow, as in the Hebrew reference; do not auto-mirror it.
            Canvas(Modifier.size(18.dp)) {
                val a = Offset(size.width * .25f, size.height * .75f)
                val b = Offset(size.width * .8f, size.height * .2f)
                drawLine(SearchMuted, a, b, 1.5.dp.toPx(), StrokeCap.Round)
                drawLine(SearchMuted, b, Offset(size.width * .3f, b.y), 1.5.dp.toPx(), StrokeCap.Round)
                drawLine(SearchMuted, b, Offset(b.x, size.height * .7f), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchEntryRow(
    name: String, subtitle: String, image: String, allowed: Boolean, artist: Boolean = false,
    nameColor: Color = Color.White, saved: Boolean = false, itemKey: Any? = null,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null, onSave: (() -> Unit)? = null, onRemove: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlideImage(image, null, Modifier.size(48.dp).clip(if (artist) CircleShape else RoundedCornerShape(4.dp)), isAllowed = allowed)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
            Text(name, color = nameColor, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = SearchMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp))
        }
        if (onSave != null) SpotifyPlusButton(saved, onSave, modifier = Modifier.testTag("search-save:$itemKey"), size = 16.dp, touchTargetSize = 48.dp,
            unsavedTint = SearchMuted, itemKey = itemKey)
        if (onRemove != null) IconButton(onClick = onRemove, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.Close, "הסרת $name מהחיפושים האחרונים", Modifier.size(20.dp), tint = SearchMuted)
        }
        if (onRemove == null && onSave == null) Spacer(Modifier.width(16.dp))
    }
}

@Composable
internal fun ClearSearchHistoryButton(onClear: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val held by interaction.collectIsPressedAsState()
    var pulse by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scale by animateFloatAsState(if (held || pulse) .95f else 1f, tween(80), label = "ClearRecentPress")
    Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(50))
            .border(1.dp, Color(0xFF777777), RoundedCornerShape(50))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
                if (!pulse) scope.launch { pulse = true; delay(80); pulse = false; delay(80); onClear() }
            }.padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text("ניקוי החיפושים האחרונים", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}
