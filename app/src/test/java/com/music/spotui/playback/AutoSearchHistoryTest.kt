package com.music.spotui.playback

import org.junit.Assert.*
import org.junit.Test

class AutoSearchHistoryTest {
    @Test fun matchingQueriesAreRealSavedSearchesAndSnapshotDoesNotMutate() {
        val history = AutoSearchHistory({ null }, {})
        history.record("ישי ריבו")
        history.record("Akiva live")
        val snapshot = history.matching("ישי")
        history.record("ישי לוי")
        assertEquals(listOf("ישי ריבו"), snapshot.map { it.query })
        assertEquals(listOf("Akiva live"), history.matching("LIVE").map { it.query })
        assertTrue(history.matching("Akiva live").isEmpty())
        assertTrue(history.matching("לא חיפשתי").isEmpty())
        assertTrue(history.matching(" ").isEmpty())
    }
    @Test fun queriesSurviveRestartAndReopenTheirOwnExactQuery() {
        var disk: String? = null
        val history = AutoSearchHistory({ disk }, { disk = it }, { 100L })
        history.record("  ישי ריבו  ")
        val item = history.items().single()
        assertEquals("ישי ריבו", item.query)
        val restored = AutoSearchHistory({ disk }, {})
        assertEquals(item, restored.find(item.mediaId))
        assertNull(restored.find("auto-search/not-issued"))
    }
    @Test fun typingAndLatePrefixResponsesDoNotFillOrShortenHistory() {
        var now = 0L
        val history = AutoSearchHistory({ null }, {}, { now })
        for (query in listOf("י", "יש", "ישי", "ישי ריבו")) {
            history.record(query)
            now += 100
        }
        history.record("ישי")
        assertEquals(listOf("ישי ריבו"), history.items().map { it.query })
    }
    @Test fun independentLaterSearchesAndCaseInsensitiveRecencyArePreserved() {
        var now = 0L
        val history = AutoSearchHistory({ null }, {}, { now })
        history.record("Akiva")
        now += 11_000
        history.record("Akiva live")
        history.record("ריבו")
        history.record("AKIVA")
        assertEquals(listOf("AKIVA", "ריבו", "Akiva live"), history.items().map { it.query })
    }
    @Test fun boundedHistoryRejectsBlankAndOversizedQueries() {
        val history = AutoSearchHistory({ null }, {}, capacity = 3)
        for (query in listOf("alpha", "beta", "gamma", "delta", " ", "x".repeat(201))) history.record(query)
        assertEquals(listOf("delta", "gamma", "beta"), history.items().map { it.query })
    }
    @Test fun corruptStorageDoesNotPreventNewSearches() {
        val history = AutoSearchHistory({ "not json" }, {})
        assertTrue(history.items().isEmpty())
        history.record("new query")
        assertEquals("new query", history.items().single().query)
    }
}
