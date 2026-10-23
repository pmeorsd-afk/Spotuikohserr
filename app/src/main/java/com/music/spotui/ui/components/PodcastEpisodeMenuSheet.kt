package com.music.spotui.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.spotui.R
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.ui.podcast.exactSpotifyEpisodeId
import com.music.spotui.ui.screens.NewEpisodeArtwork
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastEpisodeMenuSheet(
    episode: PodcastEpisodeUiModel, saved: Boolean, downloading: Boolean,
    onDismiss: () -> Unit, onToggleSaved: () -> Unit, onPlaylist: () -> Unit, onDownload: () -> Unit,
    onComplete: () -> Unit, onShow: () -> Unit, onEpisode: () -> Unit,
) {
    val context = LocalContext.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    var code by remember(episode.id) { mutableStateOf(false) }
    val spotifyId = remember(episode) { exactSpotifyEpisodeId(episode) }
    fun close(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch { sheet.hide(); onDismiss(); action() }
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF1F1F1F),
            contentColor = Color.White, scrimColor = Color.Black.copy(alpha = .6f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = { Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF777777)))
            } }) {
            BackHandler(code && !closing) { code = false }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                if (code) {
                    IconButton(onClick = { code = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה לתפריט") }
                    EpisodeCode(spotifyId, episode.title)
                } else PodcastEpisodeMenuContent(episode, saved, enabled = !closing, downloading,
                    onShare = { close {
                        val link = spotifyId?.let { "\nhttps://open.spotify.com/episode/$it" }.orEmpty()
                        try { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${episode.title} — ${episode.showName}$link")
                        }, "שיתוף הפרק")) } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "לא נמצאה אפליקציה לשיתוף", Toast.LENGTH_SHORT).show()
                        }
                    } },
                    onToggleSaved = { close(onToggleSaved) }, onPlaylist = { close(onPlaylist) }, onDownload = { close(onDownload) },
                    onComplete = { close(onComplete) }, onShow = { close(onShow) }, onEpisode = { close(onEpisode) },
                    onCode = { code = true })
            }
        }
    }
}

@Composable
internal fun PodcastEpisodeMenuContent(episode: PodcastEpisodeUiModel, saved: Boolean, enabled: Boolean = true,
    downloading: Boolean = false, onShare: () -> Unit, onToggleSaved: () -> Unit, onPlaylist: () -> Unit,
    onDownload: () -> Unit, onComplete: () -> Unit, onShow: () -> Unit, onEpisode: () -> Unit, onCode: () -> Unit,
) {
    val gray = Color(0xFFB3B3B3)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        NewEpisodeArtwork(episode, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(episode.title, color = Color.White, fontSize = 16.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(episode.showName, episode.publisher.takeIf { it != episode.showName }.orEmpty()).filter { it.isNotBlank() }
                .joinToString(" • "), color = gray, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(color = Color(0xFF404040))
    @Composable fun icon(id: Int) { Icon(painterResource(id), null, Modifier.size(24.dp), tint = gray) }
    EpisodeMenuAction("שיתוף", enabled, onShare) { icon(R.drawable.ic_share) }
    EpisodeMenuAction(if (saved) "הסרה מהפרקים שלכם" else "הוספה לפרקים שלכם", enabled, onToggleSaved) {
        SavedCollectionCover(true, Modifier.size(24.dp))
    }
    EpisodeMenuAction("הוספה לפלייליסט", enabled, onPlaylist) { icon(R.drawable.ic_auto_add) }
    EpisodeMenuAction(if (downloading) "מכין הורדה…" else "הורדה", enabled && !downloading, onDownload) { icon(R.drawable.ic_spotify_download) }
    EpisodeMenuAction("סימון שסיימתם לקרוא", enabled, onComplete) { Icon(Icons.Default.Check, null, Modifier.size(26.dp), tint = gray) }
    EpisodeMenuAction("מעבר לפודקאסט", enabled, onShow) { icon(R.drawable.ic_auto_podcast) }
    EpisodeMenuAction("מעבר לפרק", enabled, onEpisode) { icon(R.drawable.ic_auto_podcast) }
    EpisodeMenuAction("הציגו את קוד Spotify", enabled && exactSpotifyEpisodeId(episode) != null, onCode) { icon(R.drawable.ic_podcast_code) }
}

@Composable
private fun EpisodeMenuAction(label: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon(); Spacer(Modifier.width(12.dp))
        Text(label, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f),
            color = if (enabled) Color.White else Color(0xFF777777))
    }
}

@Composable
private fun EpisodeCode(id: String?, title: String) {
    val context = LocalContext.current
    var retry by remember { mutableIntStateOf(0) }
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(id) { mutableStateOf(false) }
    DisposableEffect(id, retry) {
        bitmap = null; failed = id == null
        val manager = Glide.with(context.applicationContext)
        var active = true
        val target = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) { if (active) bitmap = resource }
            override fun onLoadFailed(errorDrawable: Drawable?) { if (active) failed = true }
            override fun onLoadCleared(placeholder: Drawable?) { bitmap = null }
        }
        if (id != null) manager.asBitmap().load("https://scannables.scdn.co/uri/plain/png/000000/white/640/spotify:episode:$id")
            .timeout(10_000).into(target)
        onDispose { active = false; manager.clear(target) }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 18.sp)
        Spacer(Modifier.height(16.dp))
        bitmap?.let { Image(it.asImageBitmap(), "קוד Spotify לפרק $title", contentScale = ContentScale.Fit,
            modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(it.width.toFloat() / it.height)) }
            ?: if (failed) {
                Text("לא ניתן לטעון את הקוד")
                TextButton(onClick = { retry++ }, enabled = id != null) { Text("ניסיון נוסף") }
            } else CircularProgressIndicator(color = Color(0xFF1ED760))
        Spacer(Modifier.height(16.dp))
        Text("סרקו באמצעות Spotify", color = Color(0xFFB3B3B3))
    }
}
