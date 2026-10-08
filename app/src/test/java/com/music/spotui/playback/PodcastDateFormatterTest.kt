package com.music.spotui.playback

import com.music.spotui.util.PodcastDateFormatter
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PodcastDateFormatterTest {

    @Test
    fun testParsePubDateToMillis_validRfc822Formats() {
        val rfc1 = "Mon, 01 Jan 2024 12:00:00 GMT"
        val millis1 = PodcastDateFormatter.parsePubDateToMillis(rfc1)
        assertNotNull(millis1)
        assertEquals(1704110400000L, millis1)

        val rfc2 = "Tue, 26 Sep 2023 08:30:00 +0000"
        val millis2 = PodcastDateFormatter.parsePubDateToMillis(rfc2)
        assertNotNull(millis2)
        assertTrue(millis2!! > 0L)

        // Single digit day
        val rfc3 = "Wed, 5 Apr 2023 10:00:00 GMT"
        val millis3 = PodcastDateFormatter.parsePubDateToMillis(rfc3)
        assertNotNull(millis3)
        assertTrue(millis3!! > 0L)
    }

    @Test
    fun testParsePubDateToMillis_validIsoFormats() {
        val iso1 = "2024-01-01T12:00:00Z"
        val millis1 = PodcastDateFormatter.parsePubDateToMillis(iso1)
        assertNotNull(millis1)
        assertTrue(millis1!! > 0L)

        val iso2 = "2024-01-01T12:00:00"
        val millis2 = PodcastDateFormatter.parsePubDateToMillis(iso2)
        assertNotNull(millis2)
        assertTrue(millis2!! > 0L)

        val iso3 = "2024-01-01"
        val millis3 = PodcastDateFormatter.parsePubDateToMillis(iso3)
        assertNotNull(millis3)
        assertTrue(millis3!! > 0L)
    }

    @Test
    fun testParsePubDateToMillis_invalidAndNullInputs() {
        assertNull(PodcastDateFormatter.parsePubDateToMillis(null))
        assertNull(PodcastDateFormatter.parsePubDateToMillis(""))
        assertNull(PodcastDateFormatter.parsePubDateToMillis("   "))
        assertNull(PodcastDateFormatter.parsePubDateToMillis("invalid date string"))
        assertNull(PodcastDateFormatter.parsePubDateToMillis("99-99-9999"))
    }

    data class MockEpisode(val id: String, val pubDate: String?)

    @Test
    fun testSortByPubDateDescending_chronologicalAndStable() {
        val epNewest = MockEpisode("ep_newest", "Wed, 03 Jan 2024 12:00:00 GMT")
        val epMiddle = MockEpisode("ep_middle", "Tue, 02 Jan 2024 12:00:00 GMT")
        val epOldest = MockEpisode("ep_oldest", "Mon, 01 Jan 2024 12:00:00 GMT")
        val epNoDate1 = MockEpisode("ep_nodate_1", null)
        val epNoDate2 = MockEpisode("ep_nodate_2", "")
        val epNoDate3 = MockEpisode("ep_nodate_3", "corrupted-date")

        val input = listOf(
            epMiddle,
            epNoDate1,
            epOldest,
            epNoDate2,
            epNewest,
            epNoDate3
        )

        val sorted = PodcastDateFormatter.sortByPubDateDescending(input) { it.pubDate }

        // 1. Dated items must be sorted descending (newest first)
        assertEquals("ep_newest", sorted[0].id)
        assertEquals("ep_middle", sorted[1].id)
        assertEquals("ep_oldest", sorted[2].id)

        // 2. Undated items must be placed AFTER dated items while preserving their relative order
        assertEquals("ep_nodate_1", sorted[3].id)
        assertEquals("ep_nodate_2", sorted[4].id)
        assertEquals("ep_nodate_3", sorted[5].id)
    }

    @Test
    fun testSortByPubDateDescending_identicalDatesPreserveOrder() {
        val ep1 = MockEpisode("first", "Mon, 01 Jan 2024 12:00:00 GMT")
        val ep2 = MockEpisode("second", "Mon, 01 Jan 2024 12:00:00 GMT")
        val ep3 = MockEpisode("third", "Mon, 01 Jan 2024 12:00:00 GMT")

        val input = listOf(ep1, ep2, ep3)
        val sorted = PodcastDateFormatter.sortByPubDateDescending(input) { it.pubDate }

        assertEquals("first", sorted[0].id)
        assertEquals("second", sorted[1].id)
        assertEquals("third", sorted[2].id)
    }

    @Test
    fun testConcurrentParsing_threadSafety() {
        val pool = Executors.newFixedThreadPool(8)
        val dates = listOf(
            "Mon, 01 Jan 2024 12:00:00 GMT",
            "Tue, 02 Jan 2024 12:00:00 GMT",
            "Wed, 03 Jan 2024 12:00:00 GMT",
            "2024-01-04T12:00:00Z",
            "2024-01-05"
        )

        var hasError = false
        val tasks = (1..200).map { i ->
            Runnable {
                try {
                    val date = dates[i % dates.size]
                    val millis = PodcastDateFormatter.parsePubDateToMillis(date)
                    if (millis == null || millis <= 0L) {
                        hasError = true
                    }
                } catch (e: Exception) {
                    hasError = true
                }
            }
        }

        tasks.forEach { pool.submit(it) }
        pool.shutdown()
        val finished = pool.awaitTermination(5, TimeUnit.SECONDS)

        assertTrue(finished)
        assertFalse("Concurrent date parsing failed or corrupted state", hasError)
    }
}
