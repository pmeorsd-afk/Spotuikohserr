package com.music.spotui.playback

import com.music.spotui.ui.overlay.WazeOverlayPlacement as Placement
import org.junit.Assert.*
import org.junit.Test

class WazeOverlayPlacementTest {
    private val normal = Placement.Bounds(8, 32, 298, 722)

    @Test fun actualButtonRectFitsRegularSmallLandscapeAndCutoutFrames() {
        for (safe in listOf(Placement.Bounds(0, 24, 360, 776), Placement.Bounds(0, 20, 240, 300),
            Placement.Bounds(40, 0, 760, 336), Placement.Bounds(0, 40, 360, 776))) {
            val frame = Placement.frame(safe.left, safe.top, safe.right, safe.bottom, 54, 8)
            assertEquals(54, frame.size)
            for (point in listOf(Placement.Point(-1000, -1000), Placement.Point(5000, 5000))) {
                val clamped = Placement.clamp(point, frame.bounds)
                assertTrue(clamped.x >= safe.left + 8 && clamped.y >= safe.top + 8)
                assertTrue(clamped.x + frame.size <= safe.right - 8)
                assertTrue(clamped.y + frame.size <= safe.bottom - 8)
            }
        }
    }
    @Test fun extremelySmallWindowStillContainsItsButton() {
        val frame = Placement.frame(0, 0, 12, 9, 54, 8)
        assertEquals(1, frame.size)
        assertEquals(Placement.Point(7, 4), Placement.restore(null, frame.bounds, 125))
    }

    @Test fun defaultIsPhysicalRightAndBelowTopControls() {
        assertEquals(Placement.Point(298, 157), Placement.restore(null, normal, 125))
    }
    @Test fun allFourEdgesClampTheEntireButton() {
        assertEquals(Placement.Point(8, 32), Placement.clamp(Placement.Point(-900, -500), normal))
        assertEquals(Placement.Point(298, 722), Placement.clamp(Placement.Point(10000, 10000), normal))
        assertEquals(Placement.Point(8, 722), Placement.clamp(Placement.Point(-10, 10000), normal))
        assertEquals(Placement.Point(298, 32), Placement.clamp(Placement.Point(10000, -10), normal))
    }
    @Test fun defaultFitsVeryShortAndNarrowViewports() {
        assertEquals(Placement.Point(82, 60), Placement.restore(null, Placement.Bounds(8, 32, 82, 60), 125))
    }
    @Test fun resizedViewportPreservesRelativePlacement() {
        val saved = Placement.save(Placement.Point(153, 377), normal)
        assertEquals(Placement.Fraction(.5f, .5f), saved)
        assertEquals(Placement.Point(90, 150), Placement.restore(saved, Placement.Bounds(10, 30, 170, 270), 125))
    }
    @Test fun bottomRightStaysInsideAfterRotationAndCutoutChanges() {
        val saved = Placement.save(Placement.Point(10000, 10000), normal)
        val landscape = Placement.Bounds(42, 8, 720, 250)
        assertEquals(Placement.Point(720, 250), Placement.restore(saved, landscape, 125))
    }
    @Test fun invalidStoredFractionsNeverEscapeBounds() {
        assertEquals(Placement.Point(8, 722), Placement.restore(Placement.Fraction(-4f, 9f), normal, 125))
        assertEquals(Placement.Point(298, 157), Placement.restore(Placement.Fraction(Float.NaN, Float.POSITIVE_INFINITY), normal, 125))
    }
    @Test fun zeroTravelAreaDoesNotDivideByZero() {
        val tiny = Placement.Bounds(10, 20, 10, 20)
        val saved = Placement.save(Placement.Point(800, -300), tiny)
        assertEquals(Placement.Point(10, 20), Placement.restore(saved, tiny, 125))
        assertTrue(saved.x.isFinite() && saved.y.isFinite())
    }
}
