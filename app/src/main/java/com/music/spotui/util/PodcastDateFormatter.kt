package com.music.spotui.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Formats publication dates and playback durations for podcast episodes
 * matching official Spotify's Hebrew localization.
 */
object PodcastDateFormatter {

    private val DATE_FORMATS = listOf(
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH).apply { isLenient = false },
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).apply { isLenient = false },
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss z", Locale.ENGLISH).apply { isLenient = false },
        SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss Z", Locale.ENGLISH).apply { isLenient = false },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ENGLISH).apply { isLenient = false },
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ENGLISH).apply { isLenient = false },
        SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).apply { isLenient = false }
    )

    private val HEBREW_MONTHS = arrayOf(
        "ינו׳", "פבר׳", "מרץ", "אפר׳", "מאי", "יונ׳",
        "יול׳", "אוג׳", "ספט׳", "אוק׳", "נוב׳", "דצמ׳"
    )

    /**
     * Formats publication date to Hebrew notation:
     * - Within 6 days: Day name ("יום א'", ..., "שבת")
     * - Older: "26 ספט׳" or "26 ספט׳ 2023"
     */
    fun formatPubDate(rawPubDate: String?): String {
        if (rawPubDate.isNullOrBlank()) return ""
        val trimmed = rawPubDate.trim()
        val parsedDate = parseDate(trimmed) ?: return trimmed.take(12)

        val now = Calendar.getInstance()
        val pubCal = Calendar.getInstance().apply { time = parsedDate }

        val diffMs = now.timeInMillis - pubCal.timeInMillis
        val diffDays = diffMs / (1000L * 60 * 60 * 24)

        if (diffDays in 0..6) {
            return when (pubCal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.SUNDAY -> "יום א׳"
                Calendar.MONDAY -> "יום ב׳"
                Calendar.TUESDAY -> "יום ג׳"
                Calendar.WEDNESDAY -> "יום ד׳"
                Calendar.THURSDAY -> "יום ה׳"
                Calendar.FRIDAY -> "יום ו׳"
                Calendar.SATURDAY -> "שבת"
                else -> ""
            }
        }

        val day = pubCal.get(Calendar.DAY_OF_MONTH)
        val monthIdx = pubCal.get(Calendar.MONTH)
        val monthStr = if (monthIdx in HEBREW_MONTHS.indices) HEBREW_MONTHS[monthIdx] else ""
        val pubYear = pubCal.get(Calendar.YEAR)
        val currentYear = now.get(Calendar.YEAR)

        return if (pubYear == currentYear) {
            "$day $monthStr"
        } else {
            "$day $monthStr $pubYear"
        }
    }

    /**
     * Formats duration in milliseconds into Hebrew notation:
     * - "2שע' 17 דק'" (for >= 1 hour)
     * - "54 דק'" (for < 1 hour)
     */
    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return ""
        val totalSec = durationMs / 1000L
        val hours = totalSec / 3600L
        val minutes = (totalSec % 3600L) / 60L

        return when {
            hours > 0L -> {
                if (minutes > 0L) {
                    "${hours}שע' $minutes דק'"
                } else {
                    "${hours}שע'"
                }
            }
            minutes > 0L -> "$minutes דק'"
            else -> "${totalSec} שנ'"
        }
    }

    /**
     * Combines date and duration into Spotify metadata string:
     * e.g. "שבת • 2שע' 17 דק'" or "26 ספט׳ • 1שע' 54 דק'"
     */
    fun formatMetadata(rawPubDate: String?, durationMs: Long?): String {
        val dateStr = formatPubDate(rawPubDate)
        val durationStr = formatDuration(durationMs)
        return when {
            dateStr.isNotBlank() && durationStr.isNotBlank() -> "$dateStr • $durationStr"
            dateStr.isNotBlank() -> dateStr
            durationStr.isNotBlank() -> durationStr
            else -> ""
        }
    }

    /**
     * Parses raw publication date string into epoch milliseconds.
     * Returns null if date is missing, blank, or unparseable.
     */
    fun parsePubDateToMillis(rawPubDate: String?): Long? {
        if (rawPubDate.isNullOrBlank()) return null
        return parseDate(rawPubDate.trim())?.time
    }

    /**
     * Comparator for sorting publication dates descending:
     * - Valid dates are sorted descending (newest first).
     * - Null / invalid dates are placed after valid dates.
     * - Equal or undated entries return 0, preserving relative insertion order under stable sort.
     */
    val pubDateDescendingComparator: Comparator<String?> = Comparator { d1, d2 ->
        val t1 = parsePubDateToMillis(d1)
        val t2 = parsePubDateToMillis(d2)
        when {
            t1 != null && t2 != null -> t2.compareTo(t1)
            t1 != null && t2 == null -> -1
            t1 == null && t2 != null -> 1
            else -> 0
        }
    }

    /**
     * Sorts items deterministically by publication date descending.
     * Items without valid dates appear after dated items in their original relative order.
     */
    fun <T> sortByPubDateDescending(items: List<T>, dateExtractor: (T) -> String?): List<T> {
        return items.sortedWith { a, b ->
            pubDateDescendingComparator.compare(dateExtractor(a), dateExtractor(b))
        }
    }

    private fun parseDate(dateStr: String): Date? {
        for (format in DATE_FORMATS) {
            try {
                return synchronized(format) {
                    format.parse(dateStr)
                }
            } catch (_: Exception) {}
        }
        return null
    }
}
