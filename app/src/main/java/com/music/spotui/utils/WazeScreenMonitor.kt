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
 * Read-only status mirror of what [com.music.spotui.service.WazeScreenAccessibilityService]
 * last detected. The button's own show/hide decision is now made directly inside that service,
 * so nothing else needs to observe this reactively any more - it is kept around as a simple
 * diagnostic snapshot (handy for a future debug screen, or just reading in the debugger).
 */
object WazeScreenMonitor {

    @Volatile
    var state: WazeScreenState = WazeScreenState.NOT_WAZE
        internal set

    @Volatile
    var reason: String = "accessibility service not running yet"
        internal set
}
