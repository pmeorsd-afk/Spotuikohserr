package com.music.spotui.data.entity

enum class MediaType {
    TRACK,
    PODCAST_EPISODE
}

data class SongsModel(
    val id : Int,
    val title : String,
    val album : String,
    val singer : String,
    val coverUri : String,
    val url : String,
    // Real Spotify track id (e.g. "3n3Ppam7vgaVa1iaRUc9Lp"), kept so playback can
    // seed Spotify's recommendations endpoint for autoplay radio. Empty when unknown.
    val spotifyTrackId : String = "",
    // Whether the Spotify track is the explicit version, so the YouTube fallback
    // can pick the matching (explicit vs clean) edit.
    val explicit : Boolean = false,
    // Track length in ms (from Spotify). Used to disambiguate the YouTube match:
    // a same-title-different-artist song almost always has a different duration.
    val durationMs : Int = 0,
    val mediaType: MediaType = MediaType.TRACK,
    val podcastShowId: String = "",
){
    constructor() : this(-1 ,"" ,"" ,"" ,"" ,"", "", false, 0, MediaType.TRACK, "")

    /**
     * Resolves the Spotify Show ID for a podcast episode safely and deterministically.
     * Order of resolution:
     * 1. Direct explicit [podcastShowId] property
     * 2. Encoded showId in [url] if formatted as "episode:<showId>:..."
     * 3. Fallback to cached show in [com.music.spotui.data.api.Api.podcastShowCache] by matching show name ([album])
     * 4. Empty string if unresolvable (safe fallback, prevents any crash or invalid routing)
     */
    fun resolvePodcastShowId(): String {
        if (podcastShowId.isNotBlank()) return podcastShowId
        if (url.startsWith("episode:")) {
            val parts = url.removePrefix("episode:").split(":")
            if (parts.isNotEmpty() && parts[0].isNotBlank()) {
                return parts[0]
            }
        }
        val cached = com.music.spotui.data.api.Api.podcastShowCache.values.firstOrNull {
            it.name.isNotBlank() && it.name.equals(album, ignoreCase = true)
        }?.id
        if (!cached.isNullOrBlank()) return cached
        return ""
    }

    /**
     * Checks if this model represents a podcast episode.
     */
    fun isPodcast(): Boolean =
        mediaType == MediaType.PODCAST_EPISODE ||
                url.startsWith("episode:") ||
                spotifyTrackId.startsWith("episode:")
}
