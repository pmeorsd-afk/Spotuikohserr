package com.music.spotui.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object PlayerDismissPolicy {
    fun consume(offset: Float, delta: Float, atTop: Boolean, userDrag: Boolean, enabled: Boolean,
                horizontalGesture: Boolean = false): Float =
        if (!enabled || !userDrag || (offset == 0f && (horizontalGesture || !atTop || delta <= 0f))) 0f
        else (offset + delta).coerceAtLeast(0f) - offset

    fun shouldDismiss(offsetDp: Float, velocityDp: Float, heightDp: Float): Boolean {
        if (velocityDp < -600f) return false
        return offsetDp >= minOf(96f, maxOf(48f, heightDp * .12f)) ||
            (offsetDp >= 12f && velocityDp >= 1000f)
    }
}

@Stable
internal class PlayerDismissGesture(private val scope: CoroutineScope, private val density: Float, private val onDismiss: () -> Unit) {
    var offset by mutableFloatStateOf(0f)
        private set
    var height by mutableFloatStateOf(1f)
    var enabled by mutableStateOf(true)
    private var closing = false
    private var animation: Job? = null

    fun drag(delta: Float, atTop: Boolean, userDrag: Boolean, horizontalGesture: Boolean = false): Float {
        if (closing) return 0f
        val consumed = PlayerDismissPolicy.consume(offset, delta, atTop, userDrag, enabled, horizontalGesture)
        val previous = offset
        if (consumed != 0f) {
            animation?.cancel()
            offset = (offset + consumed).coerceAtMost(height)
        }
        return offset - previous
    }

    fun release(velocity: Float) {
        if (closing || offset <= 0f) return
        settle(PlayerDismissPolicy.shouldDismiss(offset / density, velocity / density, height / density))
    }

    fun close() { if (enabled && !closing) settle(true) }

    private fun settle(dismiss: Boolean) {
        animation?.cancel()
        closing = dismiss
        animation = scope.launch {
            Animatable(offset).animateTo(if (dismiss) height else 0f, tween(if (dismiss) 240 else 220)) {
                offset = value
            }
            if (dismiss) onDismiss()
        }
    }

    fun connection(list: LazyListState, horizontalGesture: () -> Boolean = { false }) = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, drag(available.y, !list.canScrollBackward, source == NestedScrollSource.Drag, horizontalGesture()))
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            Offset(0f, drag(available.y, !list.canScrollBackward, source == NestedScrollSource.Drag, horizontalGesture()))
        override suspend fun onPreFling(available: Velocity): Velocity {
            if (offset <= 0f) return Velocity.Zero
            release(available.y)
            return Velocity(0f, available.y)
        }
    }
}

@Composable
internal fun Modifier.playerHeaderDrag(gesture: PlayerDismissGesture): Modifier = draggable(
    state = rememberDraggableState { gesture.drag(it, atTop = true, userDrag = true) },
    orientation = Orientation.Vertical,
    enabled = gesture.enabled,
    onDragStopped = { gesture.release(it) },
)
