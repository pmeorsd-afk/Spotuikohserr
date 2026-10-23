package com.music.spotui.playback

import org.junit.Assert.*
import org.junit.Test

class AutoBrowseWindowTest {
    @Test fun homeShowsAllSectionsBeyondTheFormerFiftyItemBoundary() {
        val items = (0..156).toList()
        for (parent in listOf("home", "music")) {
            val window = AutoBrowseWindow.select(parent, items, 0, 50)
            assertEquals(items, window.items)
            assertNull(window.nextOffset)
        }
    }
    @Test fun oldHomeContinuationStillOpensItsRemainingContentWithoutAnotherButton() {
        val window = AutoBrowseWindow.select("home", (0..156).toList(), 50, 50)
        assertEquals((50..156).toList(), window.items)
        assertNull(window.nextOffset)
    }
    @Test fun largeLibraryRetainsBoundedPagesAndExactLastItem() {
        val items = (0..156).toList()
        val first = AutoBrowseWindow.select("liked", items, 0, 50)
        assertEquals((0..49).toList(), first.items)
        assertEquals(50, first.nextOffset)
        val last = AutoBrowseWindow.select("liked", items, 150, 50)
        assertEquals((150..156).toList(), last.items)
        assertNull(last.nextOffset)
    }
}
