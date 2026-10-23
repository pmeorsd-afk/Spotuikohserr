package com.music.spotui.data.podcast

import com.metrolist.spotify.models.SpotifyTrack
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel

/** Playlist transport shares a container, not the item's media type. */
internal fun savedPlaylistEpisode(item: SpotifyTrack): SongsModel? {
    val uri = item.uri ?: return null
    if (!uri.startsWith("spotify:episode:")) return null
    val id = uri.removePrefix("spotify:episode:")
    if (!id.matches(Regex("[A-Za-z0-9]{22}")) || (item.id.isNotBlank() && item.id != id)) return null
    return SongsModel(id = ("episode:$id").hashCode(), title = item.name, album = "", singer = "",
        coverUri = "", url = "episode:$id", spotifyTrackId = id, durationMs = item.durationMs,
        mediaType = MediaType.PODCAST_EPISODE)
}
