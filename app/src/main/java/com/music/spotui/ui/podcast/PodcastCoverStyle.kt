package com.music.spotui.ui.podcast

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class PodcastCoverColors(val start: Int, val end: Int, val text: Int)

/** Podcast Home artwork only. No changes to the shared music/player palette. */
internal object PodcastCoverStyle {
    private const val BLACK = -0x1000000
    private const val WHITE = -1
    private val fallbackColors = listOf(0xFF8057DB, 0xFF237F8D, 0xFFC58A42,
        0xFF416CAE, 0xFFB94E67, 0xFF57746C).map(Long::toInt)

    fun fallback(showKey: String): PodcastCoverColors {
        val index = (showKey.hashCode().toUInt() % fallbackColors.size.toUInt()).toInt()
        return fromArtwork(fallbackColors[index])
    }

    fun fromArtwork(dominant: Int, accent: Int = dominant): PodcastCoverColors {
        val light = luminance(dominant) > 0.45
        // Keep every RGB channel on the same side of the contrast boundary. This also
        // protects the middle of the gradient, not just the two sampled endpoints.
        return if (light) PodcastCoverColors(mix(dominant, WHITE, 0.65),
            mix(accent, WHITE, 0.50), BLACK)
        else PodcastCoverColors(mix(dominant, BLACK, 0.58),
            mix(accent, BLACK, 0.78), WHITE)
    }

    fun mix(a: Int, b: Int, amount: Double): Int {
        fun channel(shift: Int) = (((a ushr shift) and 255) * (1 - amount) +
            ((b ushr shift) and 255) * amount).roundToInt().coerceIn(0, 255)
        return BLACK or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val c = ((color ushr shift) and 255) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }
}

internal data class PodcastCoverLayout(
    val height: Float, val titleSize: Float, val titleLineHeight: Float,
    val authorSize: Float, val authorLineHeight: Float, val titleLines: Int,
    val topPadding: Float, val bottomPadding: Float, val gap: Float,
) {
    companion object {
        fun forWidth(width: Float, fontScale: Float, hasDuration: Boolean): PodcastCoverLayout {
            val scale = fontScale.coerceAtLeast(1f)
            val title = (width / 13f).coerceIn(16f, 32f)
            val author = (width / 24f).coerceIn(11f, 16f)
            val titleLine = title * 1.18f
            val authorLine = author * 1.3f
            val top = if (hasDuration) 20f + 14f * scale else 12f
            val bottom = if (width < 280f) 8f else 16f
            val gap = if (width < 280f) 4f else 8f
            // Preserve 852:360 at ordinary text sizes. Grow only if accessibility text
            // would otherwise clip even a single title line plus the creator and badge.
            val height = max(width * 360f / 852f, top + bottom + gap + (titleLine + authorLine) * scale)
            val lines = floor((height - top - bottom - gap - authorLine * scale + 0.01f) /
                (titleLine * scale)).toInt().coerceIn(1, 3)
            return PodcastCoverLayout(height, title, titleLine, author, authorLine, lines, top, bottom, gap)
        }
    }
}
