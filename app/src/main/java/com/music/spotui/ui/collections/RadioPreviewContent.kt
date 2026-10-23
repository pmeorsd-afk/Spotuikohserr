package com.music.spotui.ui.collections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.*
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.util.KosherWhitelistManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Draw-only scaling: pressing never remeasures the row or shifts its neighbors. */
@Composable
internal fun radioPressModifier(label: String, onClick: () -> Unit, content: @Composable (Modifier, Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val held by interaction.collectIsPressedAsState()
    var pulse by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val action by rememberUpdatedState(onClick)
    val down = held || pulse
    val scale by animateFloatAsState(if (down) .97f else 1f, tween(80), label = "RadioPress")
    content(Modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = label) {
            if (!pulse) scope.launch {
                pulse = true
                delay(80)
                pulse = false
                delay(80)
                action()
            }
        }, down)
}

@Composable
internal fun RadioPreviewContent(
    title: String, color: Color, songs: List<SongsModel>, state: RadioPreviewState,
    onBack: () -> Unit, onPreview: (SongsModel) -> Unit, onMore: (SongsModel) -> Unit,
) {
    val list = rememberLazyListState()
    val background = Color(0xFF121212)
    val muted = Color(0xFFB3B3B3)
    val headerColor = lerp(color, background, .48f)
    val titleThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    val pinned by remember(list, titleThreshold) { derivedStateOf {
        list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset >= titleThreshold
    } }
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Box(Modifier.fillMaxSize().background(background)) {
            LazyColumn(state = list, contentPadding = PaddingValues(bottom = 148.dp),
                modifier = Modifier.fillMaxSize().testTag("radio-preview-list")) {
                item(key = "preview-header") {
                    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(headerColor, background)))
                        .padding(top = status + 108.dp, bottom = 24.dp).padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text("כאן ניתן להאזין לתצוגות מקדימות. נגנו גרסאות מלאות בהשמעה אקראית.",
                            color = muted, fontSize = 16.sp, lineHeight = 21.sp, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
                item(key = "preview-with") {
                    Text("עם...", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp))
                }
                itemsIndexed(songs, key = { index, song -> "${song.previewKey()}|$index" }) { _, song ->
                    val selected = state.key == song.previewKey()
                    val active = selected && state.active
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp).padding(bottom = 6.dp)) {
                        radioPressModifier(if (active) "השהיית טעימה" else "השמעת טעימה של 10 שניות", { onPreview(song) }) { press, _ ->
                            Row(Modifier.fillMaxWidth().then(press).clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF292929)).testTag("preview-row:${song.id}"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(72.dp), contentAlignment = Alignment.Center) {
                                    if (!active) {
                                        GlideImage(song.coverUri, null, Modifier.fillMaxSize(), isAllowed = KosherWhitelistManager.isSongWhitelisted(song))
                                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .12f)))
                                        Icon(painterResource(R.drawable.play_svgrepo_com), null, Modifier.size(24.dp), tint = Color.White)
                                    } else {
                                        if (state.phase == PreviewPhase.LOADING) CircularProgressIndicator(
                                            Modifier.size(34.dp), color = Color.White, strokeWidth = 2.dp)
                                        else CircularProgressIndicator(progress = { state.progress }, modifier = Modifier.size(34.dp)
                                            .semantics { contentDescription = "התקדמות הטעימה" },
                                            color = Color.White, trackColor = Color.Transparent, strokeWidth = 2.dp)
                                        Icon(painterResource(R.drawable.ic_playing), null, Modifier.size(18.dp), tint = Color.White)
                                    }
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(song.title, color = Color.White, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(song.singer, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 3.dp))
                                }
                                IconButton(onClick = { onMore(song) }) { Icon(Icons.Default.MoreVert, "אפשרויות לשיר", tint = muted) }
                            }
                        }
                        if (selected && state.phase == PreviewPhase.FAILED) Text("הטעימה אינה זמינה כרגע. אפשר לנסות שוב.",
                            color = muted, fontSize = 13.sp, modifier = Modifier.padding(8.dp))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(if (pinned) Brush.verticalGradient(listOf(headerColor, background))
                else Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))).statusBarsPadding().height(64.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה לרדיו", Modifier.size(28.dp), tint = Color.White)
                }
                if (pinned) Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp).testTag("preview-pinned-title"))
            }
        }
    }
}
