package com.music.spotui.ui.library

import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.preferences.FollowedPodcastShow

internal enum class LibraryFilter { ALL, PLAYLISTS, PODCASTS, ALBUMS, ARTISTS }

/** Presentation only: a followed show must never become a saved album or playlist. */
internal sealed interface LibraryItem {
    val key: String

    data class Saved(val entry: LibraryEntry) : LibraryItem {
        override val key = "${if (entry.isPlaylist) "playlist" else "album"}:${entry.spotifyId}"
    }
    data class Podcast(val followed: FollowedPodcastShow) : LibraryItem {
        override val key = "show:${followed.showId}"
        val show = PodcastModel(followed.showId, followed.name, "", followed.imageUrl)
    }
    data class Artist(val artist: ArtistsModel) : LibraryItem {
        override val key = "artist:${artist.id}"
    }
    data class Episodes(val count: Int) : LibraryItem { override val key = "collection:saved-episodes" }
    // Derived from follows, never persisted as a removable playlist/library entry.
    data object NewEpisodes : LibraryItem { override val key = "collection:new-episodes" }
}

internal fun libraryItems(
    entries: List<LibraryEntry>,
    followedPodcasts: List<FollowedPodcastShow>,
    followedArtists: List<ArtistsModel>,
    filter: LibraryFilter,
    query: String,
    savedEpisodeCount: Int = 0,
): List<LibraryItem> {
    val search = query.trim()
    fun matches(vararg text: String) = search.isEmpty() || text.any { it.contains(search, ignoreCase = true) }
    val saved = entries.filter { entry ->
        when (filter) {
            LibraryFilter.ALL -> true
            LibraryFilter.PLAYLISTS -> entry.isPlaylist
            LibraryFilter.ALBUMS -> !entry.isPlaylist
            LibraryFilter.PODCASTS, LibraryFilter.ARTISTS -> false
        } && matches(entry.name, entry.subtitle)
    }.map(LibraryItem::Saved)
    val podcasts = if (filter == LibraryFilter.ALL || filter == LibraryFilter.PODCASTS) {
        followedPodcasts.filter { it.showId.isNotBlank() && matches(it.name) }
            .distinctBy { it.showId }.map(LibraryItem::Podcast)
    } else emptyList()
    val artists = if (filter == LibraryFilter.ALL || filter == LibraryFilter.ARTISTS) {
        followedArtists.filter { matches(it.name) }.map(LibraryItem::Artist)
    } else emptyList()

    // Keep existing shortcuts first; newly followed shows are visible without scrolling past music.
    // Retain the saved music's existing relative order instead of inventing missing timestamps.
    val insertion = saved.takeWhile { it.entry.spotifyId in setOf("liked-songs", "liked", "downloaded-offline", "downloaded") }.size
    val episodes = if (savedEpisodeCount > 0 && filter in listOf(LibraryFilter.ALL, LibraryFilter.PODCASTS, LibraryFilter.PLAYLISTS)
        && matches("הפרקים שלכם", "Your Episodes")) listOf(LibraryItem.Episodes(savedEpisodeCount)) else emptyList()
    val newEpisodes = if (followedPodcasts.any { it.showId.isNotBlank() }
        && filter in listOf(LibraryFilter.ALL, LibraryFilter.PODCASTS)
        && matches("פרקים חדשים", "New Episodes")) listOf(LibraryItem.NewEpisodes) else emptyList()
    return saved.take(insertion) + episodes + newEpisodes + podcasts + saved.drop(insertion) + artists
}
