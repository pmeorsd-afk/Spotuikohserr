package com.music.spotui.playback

import com.music.spotui.ui.overlay.WazeOverlayVisibility as Visibility
import org.junit.Assert.*
import org.junit.Test

class WazeOverlayVisibilityTest {
    @Test fun mapShowsAndLeavingWazeAlwaysHides() {
        assertEquals(Visibility.Decision.MAP, Visibility.decide(Visibility.Signals(true)))
        assertEquals(Visibility.Decision.NOT_WAZE, Visibility.decide(Visibility.Signals(false, keyboard = true)))
    }
    @Test fun keyboardFocusedInputAndModalEachSuppressTheOverlay() {
        for (signals in listOf(Visibility.Signals(true, keyboard = true),
            Visibility.Signals(true, focusedEditor = true), Visibility.Signals(true, modalWindow = true)))
            assertEquals(Visibility.Decision.INPUT_OR_MENU, Visibility.decide(signals))
    }
    @Test fun englishAndHebrewPaneTitlesHideAndClosingThemRestoresMap() {
        for (title in listOf("Settings", " SEARCH ", "הגדרות", "תפריט", "לאן?"))
            assertEquals(Visibility.Decision.INPUT_OR_MENU, Visibility.decide(Visibility.Signals(true, paneTitles = listOf(title))))
        assertEquals(Visibility.Decision.MAP, Visibility.decide(Visibility.Signals(true)))
    }
    @Test fun mapDestinationContainingSearchOrSettingsIsNotAMenuTitle() {
        assertEquals(Visibility.Decision.MAP, Visibility.decide(Visibility.Signals(true,
            paneTitles = listOf("Search Street", "Waze", "ניווט"))))
    }
}
