package com.music.spotui.ui.history

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class HistoryFilter { ALL, MUSIC, PODCASTS }

internal data class HistoryGroup(val entries: List<HistoryEntry>) {
    val type: MediaType? get() = entries.first().exactSong()?.mediaType
    val expandable: Boolean get() = entries.size > 1
    fun visibleEntries(expanded: Boolean): List<HistoryEntry> =
        if (!expandable || expanded) entries else emptyList()
}

internal data class HistoryDay(val date: LocalDate, val groups: List<HistoryGroup>)

/** Groups contiguous known media types within each real local date, never invented playlists. */
internal fun historyDays(
    entries: List<HistoryEntry>, filter: HistoryFilter, zone: ZoneId = ZoneId.systemDefault(),
): List<HistoryDay> = entries.sortedByDescending { it.ts }
    .groupBy { Instant.ofEpochMilli(it.ts).atZone(zone).toLocalDate() }
    .mapNotNull { (date, entriesForDate) ->
        val groups = mutableListOf<HistoryGroup>()
        for (entry in entriesForDate) {
            val type = entry.exactSong()?.mediaType
            val last = groups.lastOrNull()
            if (type != null && last?.type == type) {
                groups[groups.lastIndex] = HistoryGroup(last.entries + entry)
            } else groups += HistoryGroup(listOf(entry))
        }
        val visible = groups.filter {
            filter == HistoryFilter.ALL ||
                (filter == HistoryFilter.MUSIC && it.type == MediaType.TRACK) ||
                (filter == HistoryFilter.PODCASTS && it.type == MediaType.PODCAST_EPISODE)
        }
        visible.takeIf { it.isNotEmpty() }?.let { HistoryDay(date, it) }
    }

/** The UI receives a detail target, never a playback action or a reconstructed search query. */
internal sealed interface HistoryDetail {
    val song: SongsModel
    data class Track(override val song: SongsModel) : HistoryDetail
    data class Episode(override val song: SongsModel) : HistoryDetail
}

internal fun historyDetail(entry: HistoryEntry): HistoryDetail? = entry.exactSong()?.let {
    when (it.mediaType) {
        MediaType.TRACK -> HistoryDetail.Track(it)
        MediaType.PODCAST_EPISODE -> HistoryDetail.Episode(it)
    }
}
