package com.music.spotui.data.entity

/**
 * Dedicated presentation model for podcast episodes in ShowScreen.
 * Decouples rich podcast UI elements (descriptions, publication date, duration)
 * from the generic SongsModel while providing a conversion for playback.
 */
data class PodcastEpisodeUiModel(
    val id: String,
    val numericId: Int,
    val title: String,
    val description: String? = null,
    val pubDate: String? = null,
    val pubDateFormatted: String = "",
    val durationMs: Long = 0L,
    val durationFormatted: String = "",
    val formattedMetadata: String = "",
    val coverUri: String = "",
    val playUrl: String = "",
    val showId: String = "",
    val showName: String = "",
    val publisher: String = ""
) {
    fun toSongModel(): SongsModel {
        return SongsModel(
            id = numericId,
            title = title,
            album = showName,
            singer = publisher.ifBlank { showName },
            coverUri = coverUri,
            url = playUrl,
            spotifyTrackId = "",
            explicit = false,
            durationMs = durationMs.toInt(),
            mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = showId
        )
    }
}
