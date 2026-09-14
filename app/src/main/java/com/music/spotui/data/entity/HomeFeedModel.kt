package com.music.spotui.data.entity

/**
 * Personalized Spotify home feed (the `home` GQL operation): a greeting plus an
 * ordered list of titled sections ("Your top mixes", "Jump back in", "Your
 * favorite artists", …), each holding albums / artists / playlists.
 */
data class HomeFeedModel(
    val greeting: String = "",
    val topGrid: List<HomeItem> = emptyList(),
    val sections: List<HomeSection> = emptyList(),
)

object HomeSectionIds {
    const val TOP_MIXES = "top_mixes"
    const val POPULAR_RADIO = "popular_radio"
    const val RECENTLY_PLAYED = "recently_played"
    const val RECOMMENDED_TODAY = "recommended_today"
    const val SIMILAR_ARTISTS = "similar_artists"
    const val HISTORY_BASED = "history_based"
    const val POPULAR_ALBUMS = "popular_albums"
    const val POPULAR_ARTISTS = "popular_artists"
}

enum class HomeSectionType {
    HORIZONTAL,
    ARTISTS,
    ALBUMS,
    MIXES
}

data class HomeSection(
    val id: String = "",
    val title: String,
    val subtitle: String? = null,
    val headerArtist: ArtistsModel? = null,
    val type: HomeSectionType = HomeSectionType.HORIZONTAL,
    val items: List<HomeItem> = emptyList(),
)

sealed class HomeItem {
    abstract val name: String
    abstract val imageUrl: String

    data class Album(
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val artists: String = "",
    ) : HomeItem()

    data class Artist(
        override val name: String,
        override val imageUrl: String,
        val id: String = "",
    ) : HomeItem()

    data class Playlist(
        override val name: String,
        override val imageUrl: String,
        val subtitle: String,
        val id: String = "",
    ) : HomeItem()

    data class Track(
        val song: SongsModel,
    ) : HomeItem() {
        override val name: String get() = song.title
        override val imageUrl: String get() = song.coverUri
        val subtitle: String get() = song.singer
    }

    data class LikedSongs(
        val count: Int,
        override val name: String = "שירים שאהבתם",
        override val imageUrl: String = "",
    ) : HomeItem()
}
