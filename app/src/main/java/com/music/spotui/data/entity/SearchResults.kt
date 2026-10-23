package com.music.spotui.data.entity

/** Combined Spotify search results with exact identities for playable and browsable items. */
data class SearchResults(
    val songs: List<SongsModel> = emptyList(),
    val albums: List<AlbumsModel> = emptyList(),
    val artists: List<ArtistsModel> = emptyList(),
    val shows: List<PodcastModel> = emptyList(),
    val episodes: List<SongsModel> = emptyList(),
    val playlists: List<LibraryEntry> = emptyList(),
)
