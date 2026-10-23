package com.music.spotui.ui.overlay

import java.util.Locale

/** Only observed accessibility signals suppress the overlay; map labels alone never do. */
internal object WazeOverlayVisibility {
    data class Signals(
        val wazeForeground: Boolean,
        val keyboard: Boolean = false,
        val modalWindow: Boolean = false,
        val focusedEditor: Boolean = false,
        val paneTitles: List<String> = emptyList(),
    )
    enum class Decision { NOT_WAZE, MAP, INPUT_OR_MENU }

    fun decide(signals: Signals): Decision = when {
        !signals.wazeForeground -> Decision.NOT_WAZE
        signals.keyboard || signals.modalWindow || signals.focusedEditor -> Decision.INPUT_OR_MENU
        signals.paneTitles.any { title -> title.trim().lowercase(Locale.ROOT) in menuTitles } -> Decision.INPUT_OR_MENU
        else -> Decision.MAP
    }

    // Accessibility pane/heading titles, never arbitrary matching of text anywhere on the map.
    private val menuTitles = setOf("search", "settings", "menu", "sound settings", "where to?",
        "חיפוש", "הגדרות", "תפריט", "הגדרות קול", "לאן?", "לאן נוסעים?")
}
