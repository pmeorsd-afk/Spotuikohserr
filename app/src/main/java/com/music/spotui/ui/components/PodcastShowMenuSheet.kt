package com.music.spotui.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.spotui.R
import com.music.spotui.data.preferences.getPersonalPodcastRating
import com.music.spotui.data.preferences.savePersonalPodcastRating
import com.music.spotui.ui.podcast.PodcastShowMenuTarget
import com.music.spotui.util.KosherWhitelistManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ShowMenuBackground = Color(0xFF1F1F1F)
private val ShowMenuMuted = Color(0xFFB3B3B3)
private val ShowMenuGreen = Color(0xFF1ED760)
private enum class ShowMenuPage { MENU, RATING, CODE }

/** This sheet owns Show actions only; episode/music options belong to their own menus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PodcastShowMenuSheet(
    target: PodcastShowMenuTarget,
    following: Boolean,
    onToggleFollow: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(target.show.id) { mutableStateOf(ShowMenuPage.MENU) }
    var closing by remember { mutableStateOf(false) }
    fun closeThen(action: () -> Unit = {}) {
        if (closing) return
        closing = true
        scope.launch {
            sheetState.hide()
            onDismiss()
            action()
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = ShowMenuBackground,
            contentColor = Color.White,
            scrimColor = Color.Black.copy(alpha = 0.6f),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = {
                Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF777777)))
                }
            },
        ) {
            BackHandler(enabled = page != ShowMenuPage.MENU && !closing) { page = ShowMenuPage.MENU }
            AnimatedContent(
                targetState = page,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "PodcastShowMenuPage",
            ) { currentPage ->
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (currentPage != ShowMenuPage.MENU) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { page = ShowMenuPage.MENU }, enabled = !closing) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "חזרה לתפריט")
                            }
                            Text(if (currentPage == ShowMenuPage.RATING) "דירוג התוכנית" else "קוד Spotify",
                                fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    when (currentPage) {
                        ShowMenuPage.MENU -> PodcastShowMenuContent(
                            target = target,
                            following = following,
                            enabled = !closing,
                            onShare = { closeThen { sharePodcastShow(context, target) } },
                            onToggleFollow = { if (!closing) { onToggleFollow(); closeThen() } },
                            onRating = { page = ShowMenuPage.RATING },
                            onCode = { page = ShowMenuPage.CODE },
                        )
                        ShowMenuPage.RATING -> PersonalPodcastRating(target, onSaved = {
                            closeThen { Toast.makeText(context, "הדירוג האישי נשמר ב־SpotUI", Toast.LENGTH_SHORT).show() }
                        })
                        ShowMenuPage.CODE -> PodcastShowCode(target)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PodcastShowMenuContent(
    target: PodcastShowMenuTarget,
    following: Boolean,
    enabled: Boolean = true,
    onShare: () -> Unit,
    onToggleFollow: () -> Unit,
    onRating: () -> Unit,
    onCode: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        PodcastShowMenuHeader(target)
        HorizontalDivider(color = Color(0xFF404040), thickness = 1.dp)
        ShowMenuAction("שיתוף", enabled && target.shareUrl != null, onShare) {
            Icon(painterResource(R.drawable.ic_share), null, Modifier.size(24.dp), tint = ShowMenuMuted)
        }
        ShowMenuAction(if (following) "הפסקת המעקב" else "מעקב", enabled, onToggleFollow) {
            Icon(if (following) Icons.Default.Close else Icons.Default.Add, null,
                Modifier.size(24.dp), tint = ShowMenuGreen)
        }
        ShowMenuAction("דירוג התוכנית", enabled, onRating) {
            Icon(painterResource(R.drawable.ic_podcast_star), null, Modifier.size(24.dp), tint = ShowMenuMuted)
        }
        ShowMenuAction("הציגו את קוד Spotify", enabled && target.codeUrl != null, onCode) {
            Icon(painterResource(R.drawable.ic_podcast_code), null, Modifier.size(24.dp), tint = ShowMenuMuted)
        }
    }
}

@Composable
private fun PodcastShowMenuHeader(target: PodcastShowMenuTarget) {
    val whitelistVersion by KosherWhitelistManager.versionState
    val artworkAllowed = remember(target.show, whitelistVersion) {
        KosherWhitelistManager.isPodcastShowWhitelisted(target.show)
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        GlideImage(model = target.show.coverUri, isAllowed = artworkAllowed, contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(target.show.name, color = Color.White, fontSize = 16.sp, lineHeight = 20.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (target.show.publisher.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(target.show.publisher, color = ShowMenuMuted, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ShowMenuAction(text: String, enabled: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Spacer(Modifier.width(12.dp))
        Text(text, color = if (enabled) Color.White else ShowMenuMuted.copy(alpha = 0.5f),
            fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PersonalPodcastRating(target: PodcastShowMenuTarget, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by rememberSaveable(target.show.id) { mutableIntStateOf(getPersonalPodcastRating(context, target.show.id) ?: 0) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(target.show.name, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text("הדירוג האישי שלכם נשמר ב־SpotUI בלבד ולא נשלח ל־Spotify.",
            color = ShowMenuMuted, fontSize = 14.sp, lineHeight = 20.sp)
        Spacer(Modifier.height(16.dp))
        PodcastRatingStars(selected, enabled = !saving) { selected = it; failed = false }
        Spacer(Modifier.height(16.dp))
        if (failed) {
            Text("לא ניתן לשמור את הדירוג. נסו שוב.", color = Color(0xFFFFB4AB), fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
        }
        Button(
            enabled = selected in 1..5 && !saving,
            onClick = {
                saving = true
                val ratingToSave = selected
                scope.launch {
                    val saved = withContext(Dispatchers.IO) { savePersonalPodcastRating(context, target.show.id, ratingToSave) }
                    saving = false
                    if (saved) onSaved() else failed = true
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = ShowMenuGreen, contentColor = Color.Black),
            modifier = Modifier.heightIn(min = 48.dp),
        ) { Text(if (saving) "שומר…" else "שמירה", fontWeight = FontWeight.Bold) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PodcastRatingStars(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    // Wrap at narrow widths instead of shrinking the five 48dp touch targets.
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.Center) {
        (1..5).forEach { rating ->
            Box(Modifier.size(48.dp)
                .selectable(selected == rating, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(rating) })
                .semantics { contentDescription = "דירוג $rating מתוך 5" }, contentAlignment = Alignment.Center) {
                if (rating <= selected) {
                    Icon(Icons.Default.Star, null, Modifier.size(32.dp), tint = ShowMenuGreen)
                } else {
                    Icon(painterResource(R.drawable.ic_podcast_star), null, Modifier.size(32.dp), tint = ShowMenuMuted)
                }
            }
        }
    }
}

@Composable
private fun PodcastShowCode(target: PodcastShowMenuTarget) {
    val context = LocalContext.current
    val url = target.codeUrl
    var retry by remember { mutableIntStateOf(0) }
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    DisposableEffect(url, retry) {
        bitmap = null
        failed = url == null
        var active = true
        val requestManager = Glide.with(context.applicationContext)
        // A functional scannable graphic from a fixed endpoint, not podcast cover artwork.
        val imageTarget = object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (active) bitmap = resource
            }
            override fun onLoadFailed(errorDrawable: Drawable?) { if (active) failed = true }
            override fun onLoadCleared(placeholder: Drawable?) { bitmap = null }
        }
        if (url != null) requestManager.asBitmap().load(url).timeout(10_000).into(imageTarget)
        onDispose { active = false; bitmap = null; requestManager.clear(imageTarget) }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PodcastShowMenuHeader(target)
        val code = bitmap
        when {
            code != null -> Image(code.asImageBitmap(), contentDescription = "קוד Spotify לתוכנית ${target.show.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(code.width.toFloat() / code.height))
            failed -> {
                Text("לא ניתן לטעון את הקוד. בדקו את החיבור ונסו שוב.", color = ShowMenuMuted)
                TextButton(onClick = { retry++ }, enabled = url != null) { Text("ניסיון נוסף", color = ShowMenuGreen) }
            }
            else -> Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ShowMenuGreen)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("סרקו באמצעות Spotify", color = ShowMenuMuted, fontSize = 14.sp)
    }
}

internal fun sharePodcastShow(context: Context, target: PodcastShowMenuTarget) {
    val url = target.shareUrl
    if (url == null) {
        Toast.makeText(context, "קישור Spotify אינו זמין לתוכנית הזו", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "${target.show.name}\n$url")
    }
    try {
        context.startActivity(Intent.createChooser(intent, "שיתוף התוכנית"))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "לא נמצאה אפליקציה לשיתוף", Toast.LENGTH_SHORT).show()
    }
}
