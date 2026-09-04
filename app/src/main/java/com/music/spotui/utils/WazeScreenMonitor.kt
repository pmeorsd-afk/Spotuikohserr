package com.music.spotui.utils

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
 * [com.music.spotui.service.WazeOverlayService] reads [state] as one more condition before
 * showing the floating button - it does not otherwise change.
 *
 * [state] defaults to MAP, and is reset to MAP whenever the accessibility service stops, so
 * that until the user grants it (or on a device where it isn't running) the button falls back
 * to its original behaviour: visible any time Waze is in the foreground. This is intentional -
 * this feature can only ever narrow when the button shows, never break the existing behaviour
 * for someone who hasn't granted the new permission yet.
 *
 * Both services do their relevant work on the main thread, so there is no real concurrent
 * access today; the fields are still `@Volatile` as cheap insurance.
 */
object WazeScreenMonitor {

    @Volatile
    var state: WazeScreenState = WazeScreenState.MAP
        internal set

    @Volatile
    var reason: String = "accessibility service not running yet"
        internal set
}
