package com.music.spotui.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.components.GlideImage
import com.music.spotui.ui.components.ListeningProgressBar
import com.music.spotui.ui.history.*
import com.music.spotui.ui.navigation.ItemDetailRegistry
import com.music.spotui.ui.navigation.albumRoute
import com.music.spotui.ui.navigation.showRoute
import com.music.spotui.util.KosherWhitelistManager
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HistoryGray = Color(0xFFB3B3B3)

@Composable
fun HistoryScreen(navController: NavController) {
    val context = LocalContext.current.applicationContext
    val initialHistory = remember(context) { getListeningHistory(context) }
    val history by remember(context) { observeListeningHistory(context) }.collectAsState(initial = initialHistory)
    var filter by rememberSaveable { mutableStateOf(HistoryFilter.ALL) }
    var expandedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val days = remember(history, filter) { historyDays(history, filter) }
    var removeEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    val dateFormat = remember { DateTimeFormatter.ofPattern("EEEE, d בMMM yyyy", Locale.forLanguageTag("he")) }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Surface(color = Color(0xFF121212), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                    IconButton(onClick = { navController.navigateUp() }, modifier = Modifier.align(Alignment.CenterStart)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה", tint = Color.White)
                    }
                    Text("לאחרונה", color = Color.White, fontSize = 17.sp, modifier = Modifier.align(Alignment.Center))
                }
                Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf(HistoryFilter.MUSIC to "מוזיקה", HistoryFilter.PODCASTS to "פודקאסטים")) {
                        FilterChip(
                            selected = filter == value,
                            onClick = { filter = if (filter == value) HistoryFilter.ALL else value },
                            label = { Text(label, fontSize = 14.sp) },
                            shape = RoundedCornerShape(50), border = null,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color(0xFF2A2A2A), labelColor = Color.White,
                                selectedContainerColor = Color(0xFF1ED760), selectedLabelColor = Color.Black,
                            ),
                        )
                    }
                }
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 160.dp)) {
                    if (days.isEmpty()) item {
                        Text("עדיין אין האזנות להצגה", color = HistoryGray, modifier = Modifier.padding(16.dp, 32.dp))
                    }
                    for (day in days) {
                        item(key = "date:${day.date}") {
                            Text(day.date.format(dateFormat), color = Color.White, fontSize = 20.sp,
                                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
                        }
                        day.groups.forEachIndexed { index, group ->
                            val groupKey = "${day.date}:$index:${group.entries.last().ts}:${group.type}"
                            val expanded = groupKey in expandedGroups
                            if (group.expandable) item(key = groupKey) {
                                HistoryGroupHeader(group, expanded) {
                                    expandedGroups = if (expanded) expandedGroups - groupKey else expandedGroups + groupKey
                                }
                            }
                            group.visibleEntries(expanded).forEachIndexed { row, entry ->
                                item(key = "$groupKey:$row:${entry.ts}") {
                                    HistoryRow(entry, nested = group.expandable,
                                        onOpen = { openHistoryDetail(navController, entry) },
                                        onRemove = { removeEntry = entry })
                                }
                            }
                        }
                    }
                }
            }
        }
        if (removeEntry != null) {
            AlertDialog(
                onDismissRequest = { removeEntry = null },
                title = { Text("למחוק את ההאזנה מההיסטוריה?") },
                confirmButton = {
                    TextButton(onClick = {
                        removeEntry?.let { removeListeningHistory(context, it) }
                        removeEntry = null
                    }) { Text("מחיקה") }
                },
                dismissButton = { TextButton(onClick = { removeEntry = null }) { Text("ביטול") } },
            )
        }
    }
}

private fun openHistoryDetail(navController: NavController, entry: HistoryEntry) {
    val target = historyDetail(entry) ?: return
    val song = target.song
    val key = ItemDetailRegistry.register(song)
    when (target) {
        is HistoryDetail.Track -> navController.navigate(albumRoute(
            name = song.title, artist = song.singer, cover = song.coverUri, singleTrackId = key,
        ))
        is HistoryDetail.Episode -> navController.navigate(showRoute(
            id = song.podcastShowId, name = song.album.ifBlank { song.singer }, singleEpisodeId = key,
        ))
    }
}

@Composable
private fun HistoryGroupHeader(group: HistoryGroup, expanded: Boolean, onToggle: () -> Unit) {
    val title = if (group.type == MediaType.PODCAST_EPISODE) "נוגנו ${group.entries.size} פרקים" else "נוגנו ${group.entries.size} שירים"
    Row(
        Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "מורחב" else "מכווץ" }
            .clickable(role = Role.Button, onClickLabel = if (expanded) "כיווץ הקבוצה" else "הרחבת הקבוצה", onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryArtwork(group.entries.first())
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        HistoryChevron(circular = true, expanded = expanded)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryRow(entry: HistoryEntry, nested: Boolean, onOpen: () -> Unit, onRemove: () -> Unit) {
    val exact = remember(entry) { entry.exactSong() }
    Row(
        Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (exact != null) onOpen() },
            onClickLabel = if (exact != null) "פתיחת פרטי הפריט" else null,
            onLongClick = onRemove, onLongClickLabel = "מחיקה מההיסטוריה",
        ).padding(start = if (nested) 16.dp else 0.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HistoryArtwork(entry, small = nested)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(entry.title, color = Color.White, fontSize = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                if (exact == null) "${entry.singer} • זהות הפריט לא נשמרה"
                else "${if (exact.mediaType == MediaType.PODCAST_EPISODE) "פרק" else "שיר"} • ${entry.singer}",
                color = HistoryGray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (exact?.mediaType == MediaType.PODCAST_EPISODE) HistoryEpisodeProgress(entry)
        }
        if (exact != null) HistoryChevron(circular = false)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun HistoryArtwork(entry: HistoryEntry, small: Boolean = false) {
    val exact = entry.exactSong()
    GlideImage(
        modifier = Modifier.size(if (small) 48.dp else 64.dp).clip(RoundedCornerShape(4.dp)),
        model = entry.image, contentDescription = null, contentScale = ContentScale.Crop,
        failure = placeholder(R.drawable.placeholder), loading = placeholder(R.drawable.placeholder),
        isAllowed = exact?.let { KosherWhitelistManager.isSongWhitelisted(it) }
            ?: KosherWhitelistManager.isUrlAllowed(entry.image),
    )
}

@Composable
private fun HistoryEpisodeProgress(entry: HistoryEntry) {
    val context = LocalContext.current.applicationContext
    val song = entry.exactSong() ?: return
    val progress by remember(context, song) { observePodcastProgress(context, song) }.collectAsState(initial = null)
    val value = progress ?: return
    val minutesLeft = ((value.durationMs - value.positionMs).coerceAtLeast(0) + 59_999) / 60_000
    Text(if (value.completed) "ההאזנה הושלמה" else "נותרו $minutesLeft דקות", color = HistoryGray, fontSize = 12.sp)
    ListeningProgressBar(value.fraction, Modifier.widthIn(max = 100.dp).fillMaxWidth().padding(top = 4.dp).height(4.dp))
}

@Composable
private fun HistoryChevron(circular: Boolean, expanded: Boolean = false) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(18.dp)) {
            val stroke = 1.5.dp.toPx()
            if (circular) {
                drawCircle(HistoryGray, size.minDimension / 2 - stroke, style = Stroke(stroke))
                val startY = size.height * if (expanded) .58f else .42f
                val midY = size.height * if (expanded) .40f else .60f
                drawLine(HistoryGray, Offset(size.width * .32f, startY), Offset(size.width * .5f, midY), stroke, StrokeCap.Round)
                drawLine(HistoryGray, Offset(size.width * .5f, midY), Offset(size.width * .68f, startY), stroke, StrokeCap.Round)
            } else {
                drawLine(HistoryGray, Offset(size.width * .6f, size.height * .2f), Offset(size.width * .3f, size.height * .5f), stroke, StrokeCap.Round)
                drawLine(HistoryGray, Offset(size.width * .3f, size.height * .5f), Offset(size.width * .6f, size.height * .8f), stroke, StrokeCap.Round)
            }
        }
    }
}
