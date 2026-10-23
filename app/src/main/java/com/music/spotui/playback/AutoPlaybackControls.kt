package com.music.spotui.playback

import androidx.media3.common.Player

/** Car presentation permissions; never alter the actual player's timeline or transport methods. */
internal object AutoPlaybackControls {
    fun commands(defaults: Set<Int>, carPresentation: Boolean): Set<Int> {
        if (!carPresentation) return defaults
        return defaults - setOf(
            // Free the previous slot for a custom action; next/play + radio/save/repeat fit five slots.
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            // No public queue button. The app's real queue and next/repeat behavior are unchanged.
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        )
    }
}
