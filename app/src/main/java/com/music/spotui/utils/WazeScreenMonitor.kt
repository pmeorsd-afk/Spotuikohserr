package com.music.spotui.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What Waze is currently showing, as far as the floating button is concerned. */
enum class WazeScreenState {
    /** Waze is not the foreground app. */
    NOT_WAZE,

    /** Waze's map / navigation screen. */
    MAP,

    /** A Waze menu, search, settings, dialog... */
    MENU,
}

/**
 * Shared holder for the screen state reported by [com.music.spotui.service.WazeScreenAccessibilityService].
 *
 * [com.music.spotui.service.WazeOverlayService] collects [stateFlow] to react the moment the
 * state actually changes (map <-> menu), instead of waiting for its own periodic check - that
 * is what makes the button hide/show quickly. It also still reads [state] directly in that
 * periodic check, for the "Waze not in the foreground at all" / permission conditions that
 * this object doesn't know about.
 *
 * [state] defaults to MAP, and is reset to MAP whenever the accessibility service stops, so
 * that until the user grants it (or on a device where it isn't running) the button falls back
 * to its original behaviour: visible any time Waze is in the foreground. This is intentional -
 * this feature can only ever narrow when the button shows, never break the existing behaviour
 * for someone who hasn't granted the new permission yet.
 */
object WazeScreenMonitor {

    private val _state = MutableStateFlow(WazeScreenState.MAP)

    /** Emits every time the detected state actually changes (not on every re-evaluation). */
    val stateFlow: StateFlow<WazeScreenState> = _state.asStateFlow()

    var state: WazeScreenState
        get() = _state.value
        internal set(value) {
            _state.value = value
        }

    @Volatile
    var reason: String = "accessibility service not running yet"
        internal set
}
