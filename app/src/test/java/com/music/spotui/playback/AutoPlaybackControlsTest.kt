package com.music.spotui.playback

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class AutoPlaybackControlsTest {
    // Android's mock JVM SparseBooleanArray cannot store Player.Commands. Exercise the production
    // permission policy directly; actual framework publication remains a DHU/device check.
    private val defaults = setOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
        Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SET_REPEAT_MODE)

    @Test fun carFreesPreviousAndQueueWithoutDisablingSeekPlaybackOrRepeat() {
        val car = AutoPlaybackControls.commands(defaults, true)
        for (hidden in listOf(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE, Player.COMMAND_SEEK_TO_MEDIA_ITEM)) assertFalse(car.contains(hidden))
        for (required in listOf(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_PREPARE,
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Player.COMMAND_SET_REPEAT_MODE)) assertTrue(car.contains(required))
    }

    @Test fun disconnectRestoresTheOriginalPhoneAndBluetoothPermissions() {
        val car = AutoPlaybackControls.commands(defaults, true)
        assertFalse(car.contains(Player.COMMAND_GET_TIMELINE))
        val phone = AutoPlaybackControls.commands(defaults, false)
        assertSame(defaults, phone)
        assertTrue(phone.contains(Player.COMMAND_GET_TIMELINE))
        assertTrue(phone.contains(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertEquals(car, AutoPlaybackControls.commands(defaults, true))
    }

    @Test fun presentationNeverGrantsCommandsMissingFromTheController() {
        val limited = setOf(Player.COMMAND_PLAY_PAUSE)
        assertEquals(limited, AutoPlaybackControls.commands(limited, true))
        assertEquals(emptySet<Int>(), AutoPlaybackControls.commands(emptySet(), true))
    }
}
