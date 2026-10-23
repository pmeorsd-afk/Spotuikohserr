package com.music.spotui.playback

import com.music.spotui.ui.podcast.PodcastCoverLayout
import com.music.spotui.ui.podcast.PodcastCoverStyle
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class PodcastCoverStyleTest {
    @Test fun blackAndWhiteTextStayReadableAcrossTheWholeGradientEvenWithOppositeHues() {
        val samples = listOf(0xFF000000, 0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF,
            0xFFFFFF00, 0xFFFF00FF, 0xFF00FFFF, 0xFF777777, 0xFFE7CE7B, 0xFF376F96).map(Long::toInt)
        val textColors = mutableSetOf<Int>()
        for (primary in samples) for (accent in samples) {
            val colors = PodcastCoverStyle.fromArtwork(primary, accent)
            textColors += colors.text
            for (step in 0..100) {
                val background = PodcastCoverStyle.mix(colors.start, colors.end, step / 100.0)
                val a = PodcastCoverStyle.luminance(background)
                val b = PodcastCoverStyle.luminance(colors.text)
                assertTrue("contrast at $step for $primary/$accent", (max(a, b) + 0.05) / (min(a, b) + 0.05) >= 4.5)
            }
        }
        assertEquals(setOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), textColors)
    }

    @Test fun unavailableArtworkUsesAStableShowPaletteWithDifferentShowOptions() {
        val saved = PodcastCoverStyle.fallback("exact-show-id")
        repeat(20) { assertEquals(saved, PodcastCoverStyle.fallback("exact-show-id")) }
        assertTrue((0..30).map { PodcastCoverStyle.fallback("show-$it") }.distinct().size > 1)
        assertNotEquals(saved.start, saved.end)
    }

    @Test fun referenceRatioIsPreservedAtOrdinaryPhoneAndSmallViewportSizes() {
        for (viewport in listOf(240f, 320f, 360f, 400f, 600f)) {
            val width = viewport - 32f
            for (hasDuration in listOf(false, true)) {
                val layout = PodcastCoverLayout.forWidth(width, 1f, hasDuration)
                assertEquals(width * 360f / 852f, layout.height, 0.01f)
                assertTrue(layout.titleSize >= 16f)
            }
        }
    }

    @Test fun titleAuthorAndDurationRemainSeparatedAtLargeFontSizes() {
        for (width in listOf(208f, 288f, 328f, 568f)) for (scale in listOf(1f, 1.3f, 2f, 3f)) {
            val layout = PodcastCoverLayout.forWidth(width, scale, true)
            val used = layout.topPadding + layout.bottomPadding + layout.gap +
                (layout.titleLineHeight * layout.titleLines + layout.authorLineHeight) * scale
            assertTrue("$width / $scale: $used > ${layout.height}", used <= layout.height + 0.02f)
            assertTrue(layout.titleLines in 1..3)
        }
    }
}
