package com.music.spotui.playback

/**
 * Represents an episode parsed from a podcast RSS feed.
 */
data class PodcastRssEpisode(
    val guid: String?,
    val title: String,
    val pubDate: String?,
    val durationMs: Long?,
    val enclosureUrl: String?,
    val enclosureType: String?,
    val enclosureLength: Long?,
    val description: String? = null,
    val imageUrl: String? = null
)
