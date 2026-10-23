package com.music.spotui.ui.overlay

import kotlin.math.roundToInt

/** Physical screen coordinates, independent of text direction. Includes the entire button. */
internal object WazeOverlayPlacement {
    data class Point(val x: Int, val y: Int)
    data class Fraction(val x: Float, val y: Float)
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        init { require(right >= left && bottom >= top) }
    }
    data class Frame(val bounds: Bounds, val size: Int)

    fun frame(left: Int, top: Int, right: Int, bottom: Int, requestedSize: Int, requestedMargin: Int): Frame {
        require(right > left && bottom > top && requestedSize > 0 && requestedMargin >= 0)
        val shortest = minOf(right - left, bottom - top)
        val margin = minOf(requestedMargin, (shortest - 1) / 2)
        val size = minOf(requestedSize, shortest - 2 * margin)
        return Frame(Bounds(left + margin, top + margin, right - margin - size, bottom - margin - size), size)
    }

    fun clamp(point: Point, bounds: Bounds) = Point(
        point.x.coerceIn(bounds.left, bounds.right), point.y.coerceIn(bounds.top, bounds.bottom))

    fun restore(saved: Fraction?, bounds: Bounds, defaultTopOffset: Int): Point {
        if (saved == null || !saved.x.isFinite() || !saved.y.isFinite())
            return clamp(Point(bounds.right, bounds.top + defaultTopOffset), bounds)
        return Point(
            bounds.left + ((bounds.right - bounds.left) * saved.x.coerceIn(0f, 1f)).roundToInt(),
            bounds.top + ((bounds.bottom - bounds.top) * saved.y.coerceIn(0f, 1f)).roundToInt())
    }

    fun save(point: Point, bounds: Bounds): Fraction {
        val safe = clamp(point, bounds)
        return Fraction(
            if (bounds.right == bounds.left) 0f else (safe.x - bounds.left).toFloat() / (bounds.right - bounds.left),
            if (bounds.bottom == bounds.top) 0f else (safe.y - bounds.top).toFloat() / (bounds.bottom - bounds.top))
    }
}
