package com.music.spotui.ui.collections

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.spotui.R
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.SpotifyPlusButton
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.PodcastDateFormatter

internal data class CollectionPresentation(
    val title: String, val owner: String, val description: String, val cover: String, val artworkAllowed: Boolean,
    val radio: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CollectionContent(
    presentation: CollectionPresentation, songs: List<SongsModel>, playing: Boolean, currentId: Int, saved: Boolean,
    loading: Boolean, error: String?, onBack: () -> Unit, onRetry: () -> Unit,
    onSave: () -> Unit, onMore: () -> Unit, onSongMore: (SongsModel) -> Unit, onDownload: () -> Unit,
    onShuffle: (List<SongsModel>) -> Unit, onPlay: (List<SongsModel>) -> Unit,
    onSong: (SongsModel, Int, List<SongsModel>) -> Unit,
    scrollReset: Int = 0, recommendations: List<HomeItem.Playlist> = emptyList(), recommendationsLoading: Boolean = false,
    recommendationsFailed: Boolean = false, onRecommendationsRetry: () -> Unit = {},
    onRecommendation: (HomeItem.Playlist) -> Unit = {}, onRecommendationMore: (HomeItem.Playlist) -> Unit = {},
    onRadioPreview: () -> Unit = {},
) {
    val density = LocalDensity.current
    val list = rememberLazyListState()
    LaunchedEffect(scrollReset) { if (scrollReset > 0) list.animateScrollToItem(0) }
    var query by rememberSaveable(presentation.title) { mutableStateOf("") }
    var order by rememberSaveable(presentation.title) { mutableIntStateOf(0) }
    var sorting by remember { mutableStateOf(false) }
    val color = rememberCollectionColor(presentation.cover, presentation.artworkAllowed, presentation.radio)
    val pullThresholdPx = with(density) { 56.dp.toPx() }
    var pullDistance by remember { mutableFloatStateOf(0f) }
    val searchProgress = if (presentation.radio) 0f else if (query.isNotEmpty()) 1f else (pullDistance / pullThresholdPx).coerceIn(0f, 1f)
    // Like Liked Songs, reveal only after a deliberate pull at the top. Loading a short
    // list must not scroll an initially hidden search item back into view.
    val revealSearch = remember(list, pullThresholdPx, presentation.radio) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (presentation.radio || query.isNotEmpty() || source != NestedScrollSource.Drag) return Offset.Zero
                val atTop = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
                if ((available.y > 0 && atTop) || (available.y < 0 && pullDistance > 0)) {
                    val next = (pullDistance + available.y).coerceIn(0f, pullThresholdPx)
                    val consumed = next - pullDistance
                    pullDistance = next
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = onPreScroll(available, source)
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!presentation.radio && query.isEmpty() && pullDistance > 0) {
                    val open = if (available.y > 150f) true else if (available.y < -150f) false else pullDistance >= pullThresholdPx * .4f
                    Animatable(pullDistance).animateTo(if (open) pullThresholdPx else 0f,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)) { pullDistance = value }
                }
                return Velocity.Zero
            }
        }
    }
    val background = Color(0xFF121212)
    val headerColor = androidx.compose.ui.graphics.lerp(color, background, .5f)
    val heroBase = androidx.compose.ui.graphics.lerp(color, background, .78f)
    val display = remember(songs, query, order) {
        val matches = songs.filter { query.isBlank() || it.title.contains(query, true) || it.singer.contains(query, true) }
        when (order) { 1 -> matches.sortedBy { it.title.lowercase() }; 2 -> matches.sortedByDescending { it.title.lowercase() }
            3 -> matches.sortedBy { it.singer.lowercase() }; else -> matches }
    }
    val muted = Color(0xFFB3B3B3)
    val statusHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
            val viewportWidth = maxWidth
            val coverSize = minOf(250.dp, maxWidth - 64.dp)
            val heroHeight = if (presentation.radio) maxWidth * .72f else coverSize + 48.dp
            val coverPx = with(density) { (if (presentation.radio) heroHeight else coverSize).toPx() }
            val recommendationColumns = if (maxWidth >= 480.dp) 3 else 2
            val radioTitleSize = (maxWidth.value * .085f).coerceIn(28f, 56f).sp
            val toolbarPx = with(density) { (56.dp + statusHeight).toPx() }
            val scroll by remember(list, coverPx) { derivedStateOf {
                if (list.firstVisibleItemIndex > 0) coverPx * 2 else list.firstVisibleItemScrollOffset.toFloat()
            } }
            val coverScroll = (scroll - with(density) { (114.dp * searchProgress).toPx() }).coerceAtLeast(0f)
            val collapse = (coverScroll / coverPx).coerceIn(0f, 1f)
            val pinnedTitle = collapse >= .9f
            val pinnedPlay by remember(list, toolbarPx) { derivedStateOf {
                val actions = list.layoutInfo.visibleItemsInfo.find { it.key == "actions" }
                if (actions != null) actions.offset + actions.size <= toolbarPx else list.firstVisibleItemIndex > 1
            } }
            val backdropAlpha by animateFloatAsState(if (pinnedPlay) 0f else 1f, tween(180), label = "CollectionBackdrop")
            val gradient = if (presentation.radio) Brush.verticalGradient(0f to color, .68f to heroBase, 1f to background)
                else Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(color, Color.Black, .15f), background))
            Box(Modifier.fillMaxWidth().height(if (presentation.radio) heroHeight + 200.dp else 620.dp)
                .graphicsLayer { alpha = backdropAlpha }.background(gradient))
            LazyColumn(state = list, contentPadding = PaddingValues(top = if (presentation.radio) 0.dp else statusHeight, bottom = 148.dp),
                modifier = Modifier.fillMaxSize().then(if (presentation.radio) Modifier else Modifier.nestedScroll(revealSearch))) {
                item(key = "header") {
                    Column(Modifier.fillMaxWidth()) {
                        if (!presentation.radio && searchProgress > 0f) Box(Modifier.fillMaxWidth().height(114.dp * searchProgress).clipToBounds()
                            .graphicsLayer { alpha = searchProgress }) {
                            Column(Modifier.fillMaxWidth().height(114.dp)) {
                                Spacer(Modifier.height(56.dp))
                                CollectionSearchRow(query, { query = it }, searchProgress > .5f) { sorting = true }
                                DropdownMenu(sorting, onDismissRequest = { sorting = false }) {
                                    listOf("סדר הפלייליסט", "שם השיר: א–ת", "שם השיר: ת–א", "שם האמן").forEachIndexed { index, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = { order = index; sorting = false })
                                    }
                                }
                            }
                        }
                        if (presentation.radio) Box(Modifier.fillMaxWidth().height(heroHeight).clipToBounds()
                            .combinedClickable(onClick = onMore, onLongClick = onMore)) {
                            // Show the upper part of the exact square Home cover; its printed title
                            // below the portraits must not appear behind our accessible header text.
                            GlideImage(presentation.cover, presentation.title, Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true)
                                .size(viewportWidth).graphicsLayer {
                                alpha = (1f - collapse).coerceIn(0f, 1f)
                            }, isAllowed = presentation.artworkAllowed)
                            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, heroBase),
                                startY = with(density) { heroHeight.toPx() } * .74f)))
                            Text(presentation.title, color = Color.White, fontSize = radioTitleSize, lineHeight = radioTitleSize * 1.12f,
                                fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 16.dp, vertical = 12.dp))
                        } else Box(Modifier.fillMaxWidth().height(heroHeight), contentAlignment = Alignment.Center) {
                            GlideImage(presentation.cover, presentation.title,
                                modifier = Modifier.size(coverSize).graphicsLayer {
                                    val scale = (1f - collapse).coerceAtLeast(.05f)
                                    scaleX = scale; scaleY = scale; translationY = coverScroll.coerceAtMost(coverPx) * .5f
                                    alpha = (1f - ((collapse - .65f) / .35f)).coerceIn(0f, 1f)
                                }.shadow(16.dp, RoundedCornerShape(4.dp)).clip(RoundedCornerShape(4.dp))
                                    .combinedClickable(onClick = onMore, onLongClick = onMore),
                                isAllowed = presentation.artworkAllowed)
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            if (!presentation.radio && (!presentation.artworkAllowed || presentation.cover.isBlank())) Text(presentation.title, color = Color.White,
                                fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                            if (presentation.description.isNotBlank()) Text(presentation.description, color = muted,
                                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
                            if (presentation.owner.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 10.dp)) {
                                if (presentation.owner.contains("Spotify", ignoreCase = true)) {
                                    Icon(painterResource(R.drawable.ic_spotify_waze), null, Modifier.size(24.dp), tint = Color.Unspecified)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(presentation.owner, color = muted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(listOf(PodcastDateFormatter.formatDuration(songs.sumOf { it.durationMs.toLong().coerceAtLeast(0) }),
                                "${songs.size} פריטים").filter { it.isNotBlank() }.joinToString(" • "), color = muted,
                                fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                        }
                    }
                }
                item(key = "actions") {
                    // Five separate 48dp targets wrap on narrow viewports instead of overlapping.
                    @Composable fun secondary() {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            songs.firstOrNull()?.let { first ->
                                GlideImage(first.coverUri, "עטיפת השיר הראשון", Modifier.padding(end = 12.dp).size(28.dp, 38.dp)
                                    .border(1.5.dp, muted, RoundedCornerShape(5.dp)).padding(2.dp).clip(RoundedCornerShape(4.dp)),
                                    isAllowed = KosherWhitelistManager.isSongWhitelisted(first))
                            }
                            SpotifyPlusButton(saved, onSave, itemKey = presentation.title, size = 24.dp, touchTargetSize = 48.dp, unsavedTint = muted)
                            IconButton(onClick = onDownload, enabled = songs.any { !it.isPodcast() }) {
                                Icon(painterResource(R.drawable.ic_spotify_download), "הורדת הפלייליסט", tint = muted, modifier = Modifier.size(24.dp))
                            }
                            IconButton(onClick = onMore) { Icon(Icons.Default.MoreVert, "אפשרויות לפלייליסט", tint = muted) }
                        }
                    }
                    @Composable fun playback() {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onShuffle(display) }, enabled = display.isNotEmpty()) {
                                Icon(painterResource(R.drawable.ic_player_shuffle), "ניגון בערבוב", tint = muted, modifier = Modifier.size(24.dp))
                            }
                            CollectionPlay(playing, display.isNotEmpty()) { onPlay(display) }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 20.dp).padding(top = 8.dp, bottom = 16.dp)) {
                        if (maxWidth < 288.dp) Column(Modifier.fillMaxWidth()) {
                            secondary(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { playback() }
                        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            secondary(); playback()
                        }
                    }
                }
                if (loading && songs.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF1ED760))
                } }
                if (error != null) item {
                    Text("לא ניתן לטעון את הפלייליסט כרגע", color = muted, modifier = Modifier.padding(16.dp))
                    TextButton(onClick = onRetry) { Text("ניסיון נוסף", color = Color.White) }
                }
                if (!loading && display.isEmpty() && error == null) item { Text(if (query.isNotBlank()) "לא נמצאו פריטים מתאימים" else "הפלייליסט ריק",
                    color = muted, modifier = Modifier.padding(24.dp)) }
                if (presentation.radio && query.isBlank() && display.isNotEmpty()) item(key = "radio-preview") {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 8.dp)) {
                        radioPressModifier("הצגת טעימות השירים ברדיו", onRadioPreview) { press, down ->
                        Text(buildAnnotatedString {
                            display.take(12).forEachIndexed { index, song ->
                                if (index > 0) append(" • ")
                                withStyle(SpanStyle(color = if (down) Color(0xFF777777) else Color.White)) { append("\u2068${song.singer}\u2069") }
                                append(" \u2068${song.title}\u2069")
                            }
                        }, color = if (down) Color(0xFF777777) else muted, fontSize = 16.sp, lineHeight = 30.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = press)
                        }
                    }
                }
                if (!presentation.radio || query.isNotBlank()) itemsIndexed(display, key = { index, song -> "${song.id}_${index}_${song.url.hashCode()}" }) { index, song ->
                    Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onSong(song, index, display) }, onLongClick = { onSongMore(song) })
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlideImage(song.coverUri, null, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                            isAllowed = KosherWhitelistManager.isSongWhitelisted(song))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(song.title, color = if (song.id == currentId) Color(0xFF1ED760) else Color.White,
                                fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.singer, color = muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { onSongMore(song) }) { Icon(Icons.Default.MoreVert, "אפשרויות לפריט", tint = muted) }
                    }
                }
                if (presentation.radio) {
                    if (recommendations.isNotEmpty() || recommendationsLoading || recommendationsFailed) item(key = "recommendations-title") {
                        Text("ייתכן שתתעניינו בזה גם", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                            modifier = Modifier.fillMaxWidth().padding(16.dp))
                    }
                    recommendations.chunked(recommendationColumns).forEach { row ->
                        item(key = "recommendations:${row.joinToString { it.id }}") {
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 24.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { recommended ->
                                    Column(Modifier.weight(1f).combinedClickable(onClick = { onRecommendation(recommended) },
                                        onLongClick = { onRecommendationMore(recommended) })) {
                                        GlideImage(recommended.imageUrl, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)),
                                            isAllowed = HomeArtworkPermissions.isAllowedInCollection(recommended))
                                        Text(recommended.name, color = Color.White, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 8.dp))
                                        Text(recommended.subtitle, color = muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                repeat(recommendationColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (recommendationsLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color(0xFF1ED760))
                    } }
                    if (recommendationsFailed) item { TextButton(onClick = onRecommendationsRetry) { Text("ניסיון נוסף לטעינת המלצות", color = Color.White) } }
                }
            }
            Box(Modifier.fillMaxWidth().background(if (pinnedTitle) headerColor else Color.Transparent).statusBarsPadding().height(56.dp)) {
                IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                    .background(if (presentation.radio && !pinnedTitle) Color.Black.copy(alpha = .3f) else Color.Transparent, CircleShape)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White, modifier = Modifier.size(28.dp))
                }
                if (pinnedTitle) Text(presentation.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(horizontal = 64.dp))
            }
            if (pinnedPlay) Box(Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = statusHeight + 40.dp)) {
                CollectionPlay(playing, display.isNotEmpty()) { onPlay(display) }
            }
        }
    }
}

@Composable
private fun CollectionPlay(playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).background(Color(0xFF1ED760), CircleShape)) {
        Icon(painterResource(if (playing) R.drawable.ic_playing else R.drawable.play_svgrepo_com),
            if (playing) "השהיה" else "ניגון", tint = Color.Black, modifier = Modifier.size(24.dp))
    }
}
