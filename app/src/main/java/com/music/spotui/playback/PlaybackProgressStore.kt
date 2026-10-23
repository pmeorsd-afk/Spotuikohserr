package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel

/** Music resumes only the current item; podcast episodes retain independent durable bookmarks. */
internal class PlaybackProgressStore(
    private val read: (String) -> Long?,
    private val write: (String, Long) -> Unit,
) {
    fun position(song: SongsModel, requested: Long? = null, legacy: PlaybackSnapshot? = null): Long {
        if (requested != null && requested >= 0) return requested
        val key = key(song) ?: return 0
        val lastPosition = legacy?.takeIf { key(it.song) == key }?.positionMs?.coerceAtLeast(0)
        // Old releases saved every track. Ignore those entries even if they still
        // exist on disk: only the exact item left in the MiniPlayer may resume.
        if (song.mediaType == MediaType.TRACK) return lastPosition ?: 0
        read(key)?.let { return it.coerceAtLeast(0) }
        if (lastPosition != null) {
            write(key, lastPosition)
            return lastPosition
        }
        return 0
    }

    fun save(song: SongsModel, positionMs: Long, completed: Boolean = false) {
        if (positionMs < 0 || song.mediaType != MediaType.PODCAST_EPISODE) return
        val key = key(song) ?: return
        write(key, if (completed) 0 else positionMs)
    }

    companion object {
        fun key(song: SongsModel): String? {
            val exact = WazeResumeResolver.normalize(song)
            if (exact != null) {
                val type = if (exact.mediaType == MediaType.PODCAST_EPISODE) "episode" else "track"
                return if (exact.spotifyTrackId.isNotBlank()) type + ":spotify:" + exact.spotifyTrackId
                    else type + ":source:" + exact.url
            }
            // Legacy music queries have no Spotify ID. Keep the exact playback key, never the UI hash/title.
            if (song.mediaType == MediaType.TRACK && !song.isPodcast() &&
                song.spotifyTrackId.isBlank() && song.podcastShowId.isBlank() && song.url.isNotBlank() &&
                ExternalMediaIdentityResolver.parse(song.url) is ExternalMediaIdentityResolver.Identity.Unknown &&
                !song.url.startsWith("spotify:") && !song.url.contains("open.spotify.com")
            ) return "track:source:" + song.url
            return null
        }
    }
}
