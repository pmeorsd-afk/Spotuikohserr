package com.music.spotui.playback

import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.HomeSectionIds
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.HistoryEntry
import java.util.Locale

/** Car-only projection. History comes from successful playback, never a cached recommendation. */
internal object AutoHomeContent {
    data class Snapshot(val feed: HomeFeedModel, val stations: List<HomeItem.Playlist>)
    data class Group(val title: String, val items: List<HomeItem>)

    fun recent(history: List<HistoryEntry>): List<SongsModel> = history
        .sortedByDescending { it.ts }
        .mapNotNull { it.exactSong()?.let(WazeResumeResolver::normalize) }
        .distinctBy(PlaybackProgressStore::key)
        .take(8)

    // These are the reference shelf labels, not a search for playlists containing "radio".
    // Unknown/localized shelves remain ordinary feed sections; no station identity is guessed.
    private fun isStations(title: String): Boolean = title.trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ") in setOf("recommended stations", "תחנות מומלצות")

    fun recommendedStations(rawHome: HomeFeedModel?): List<HomeItem.Playlist> = rawHome?.sections.orEmpty()
        .filter { isStations(it.title) }
        .flatMap { it.items }
        .filterIsInstance<HomeItem.Playlist>()
        .filter { AutoBrowseContent.homeFolder(it) != null }
        .distinctBy { it.id }

    fun groups(
        snapshot: Snapshot?, recent: List<SongsModel>, recentTitle: String,
        stationsTitle: String, libraryTitle: String,
    ): List<Group> {
        val feed = snapshot?.feed ?: HomeFeedModel()
        val candidates = buildList {
            add(Group(recentTitle, recent.map(HomeItem::Track)))
            add(Group(stationsTitle, snapshot?.stations.orEmpty()))
            if (feed.sections.isEmpty()) add(Group("", feed.topGrid))
            feed.sections.forEach { section ->
                if (section.id == HomeSectionIds.RECENTLY_PLAYED) {
                    // The phone's "recent" shelf also contains saved playlists and Liked Songs.
                    // Preserve those library shortcuts, but don't present them as listening history.
                    add(Group(libraryTitle, section.items.filterNot { it is HomeItem.Track }))
                } else add(Group(section.title, section.items))
            }
        }
        val seen = hashSetOf<String>()
        return candidates.mapNotNull { group ->
            val items = group.items.filter { item ->
                val key = when (item) {
                    is HomeItem.Track -> item.song.let(WazeResumeResolver::normalize)
                        ?.let(PlaybackProgressStore::key)?.let { "track/$it" }
                    is HomeItem.LikedSongs -> "liked"
                    else -> AutoBrowseContent.homeFolder(item)?.mediaId
                }
                key != null && seen.add(key)
            }
            group.copy(items = items).takeIf { items.isNotEmpty() }
        }
    }
}
