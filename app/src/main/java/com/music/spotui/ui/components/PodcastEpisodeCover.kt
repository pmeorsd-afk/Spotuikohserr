package com.music.spotui.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.ui.podcast.PodcastCoverColors
import com.music.spotui.ui.podcast.PodcastCoverLayout
import com.music.spotui.ui.podcast.PodcastCoverStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private val coverColors = LruCache<String, PodcastCoverColors>(64)

/** Read permission before both the cache and Glide. Revocation resets the visible colors immediately. */
@Composable
internal fun rememberPodcastCoverColors(episode: PodcastEpisodeUiModel, allowed: Boolean): PodcastCoverColors {
    val context = LocalContext.current.applicationContext
    val inspecting = LocalInspectionMode.current
    val showKey = episode.showId.ifBlank { episode.showName.ifBlank { episode.id } }
    val fallback = remember(showKey) { PodcastCoverStyle.fallback(showKey) }
    val permittedUrl = episode.coverUri.takeIf { allowed && !inspecting && it.isNotBlank() }
    var colors by remember(permittedUrl, fallback) {
        mutableStateOf(permittedUrl?.let(coverColors::get) ?: fallback)
    }
    LaunchedEffect(permittedUrl, fallback) {
        if (permittedUrl != null) {
            val loaded = coverColors.get(permittedUrl)
                ?: withTimeoutOrNull(8_000) { loadCoverColors(context, permittedUrl) }
            if (loaded != null) {
                coverColors.put(permittedUrl, loaded)
                colors = loaded
            }
        }
    }
    return colors
}

private suspend fun loadCoverColors(context: Context, url: String): PodcastCoverColors? {
    val manager = Glide.with(context)
    var target: CustomTarget<Bitmap>? = null
    return try {
        val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
            val request = object : CustomTarget<Bitmap>(64, 64) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    if (continuation.isActive) continuation.resume(resource)
                }
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (continuation.isActive) continuation.resume(null)
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            target = request
            manager.asBitmap().load(url).timeout(6_000).into(request)
        } ?: return null
        // Keep the Glide target alive until this bounded background computation finishes.
        withContext(Dispatchers.Default) {
            val palette = Palette.from(bitmap).clearFilters().maximumColorCount(8).generate()
            val primary = palette.dominantSwatch ?: return@withContext null
            val accent = palette.vibrantSwatch ?: palette.mutedSwatch ?: primary
            PodcastCoverStyle.fromArtwork(primary.rgb, accent.rgb)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Missing/failed artwork keeps the same show's designed fallback.
        null
    } finally {
        target?.let(manager::clear)
    }
}

/** Large text cover used only by Podcast Home, with the existing reference container ratio. */
@Composable
internal fun PodcastEpisodeCover(
    episode: PodcastEpisodeUiModel,
    colors: PodcastCoverColors,
    modifier: Modifier = Modifier,
) {
    val fontScale = LocalDensity.current.fontScale
    val author = episode.publisher.ifBlank { episode.showName }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val layout = PodcastCoverLayout.forWidth(maxWidth.value, fontScale, episode.durationFormatted.isNotBlank())
        Box(Modifier.fillMaxWidth().height(layout.height.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(colors.start), Color(colors.end))))
            .clearAndSetSemantics {
                contentDescription = listOf(episode.title, author, episode.durationFormatted)
                    .filter(String::isNotBlank).joinToString(". ")
            }) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)
                .padding(top = layout.topPadding.dp, bottom = layout.bottomPadding.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(episode.title, color = Color(colors.text), fontSize = layout.titleSize.sp,
                    lineHeight = layout.titleLineHeight.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, maxLines = layout.titleLines, overflow = TextOverflow.Ellipsis)
                if (author.isNotBlank()) {
                    Spacer(Modifier.height(layout.gap.dp))
                    Text(author, color = Color(colors.text), fontSize = layout.authorSize.sp,
                        lineHeight = layout.authorLineHeight.sp, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (episode.durationFormatted.isNotBlank()) Text(episode.durationFormatted,
                color = Color.White, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 4.dp))
        }
    }
}

/** Small swatches share the large cover's colors and never use the shared library-logo placeholder. */
@Composable
internal fun PodcastCoverSwatch(colors: PodcastCoverColors, modifier: Modifier = Modifier) {
    Box(modifier.background(Brush.linearGradient(listOf(Color(colors.start), Color(colors.end)))))
}
