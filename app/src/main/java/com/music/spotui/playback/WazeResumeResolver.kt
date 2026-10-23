package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.ExternalMediaIdentityResolver.Identity
import java.net.URI

/** No metadata matching, player calls, query construction or network lookup. */
internal object WazeResumeResolver {
    data class Request(
        val exact: PlaybackSnapshot? = null,
        val mediaType: MediaType? = null,
        val malformed: Boolean = false,
    )

    data class Plan(val playback: PlaybackSnapshot, val resumeActive: Boolean)

    fun resolve(request: Request, persisted: PlaybackSnapshot?, active: PlaybackSnapshot?): Plan? {
        if (request.malformed) return null
        val saved = persisted?.let { normalize(it.song)?.let { song -> it.copy(song = song) } }
        if (persisted != null && saved == null) return null
        val explicit = request.exact?.let { normalize(it.song)?.let { song -> it.copy(song = song) } }
        if (request.exact != null && explicit == null) return null
        if (saved != null && explicit != null && !sameMedia(saved.song, explicit.song)) return null
        // Legacy Waze metadata (including its numeric hash ID) is not playback identity.
        val chosen = saved ?: explicit ?: return null
        if (request.mediaType != null && chosen.song.mediaType != request.mediaType) return null
        val sameActive = active != null && sameMedia(chosen.song, active.song)
        return Plan(
            chosen.copy(positionMs = (if (sameActive) active!!.positionMs else chosen.positionMs).coerceAtLeast(0)),
            resumeActive = sameActive,
        )
    }

    fun dispatch(
        plan: Plan?,
        resumeActive: (PlaybackSnapshot) -> Unit,
        startTrack: (PlaybackSnapshot) -> Unit,
        startPodcast: (PlaybackSnapshot) -> Unit,
    ): Boolean {
        plan ?: return false
        when {
            plan.resumeActive -> resumeActive(plan.playback)
            plan.playback.song.mediaType == MediaType.PODCAST_EPISODE -> startPodcast(plan.playback)
            else -> startTrack(plan.playback)
        }
        return true
    }

    fun normalize(song: SongsModel): SongsModel? {
        if (song.title.isBlank() || song.url.isBlank()) return null
        val parse = ExternalMediaIdentityResolver::parse
        if (song.mediaType == MediaType.PODCAST_EPISODE) {
            val urlId = parse(song.url)
            if (urlId is Identity.SpotifyTrack) return null
            val explicit = song.spotifyTrackId.takeIf { it.isNotBlank() }?.let {
                parse(if (it.matches(Regex("[A-Za-z0-9]{22}"))) "spotify:episode:$it" else it)
            }
            if (explicit != null && explicit !is Identity.SpotifyEpisode) return null
            if (urlId is Identity.SpotifyEpisode && explicit != null && explicit != urlId) return null
            if (urlId !is Identity.SpotifyEpisode && urlId !is Identity.InternalEpisode &&
                !(isHttp(song.url) && explicit is Identity.SpotifyEpisode)) return null
            val internalShow = (urlId as? Identity.InternalEpisode)?.showId
            if (internalShow != null && song.podcastShowId.isNotBlank() && song.podcastShowId != internalShow) return null
            val episodeId = (explicit as? Identity.SpotifyEpisode)?.id
                ?: (urlId as? Identity.SpotifyEpisode)?.id.orEmpty()
            return song.copy(
                spotifyTrackId = episodeId,
                podcastShowId = song.podcastShowId.ifBlank { internalShow.orEmpty() },
            )
        }
        if (song.isPodcast() || song.podcastShowId.isNotBlank()) return null
        val urlId = parse(song.url.substringBefore('|'))
        val explicit = song.spotifyTrackId.takeIf { it.isNotBlank() }?.let {
            parse(if (it.matches(Regex("[A-Za-z0-9]{22}"))) "spotify:track:$it" else it)
        }
        if (explicit != null && explicit !is Identity.SpotifyTrack) return null
        if (urlId is Identity.SpotifyTrack) {
            if (explicit != null && explicit != urlId) return null
            return song.copy(spotifyTrackId = urlId.id)
        }
        // Local/YouTube items already have a typed, concrete playback key.
        val concreteMusicUrl = song.url.startsWith("content://") || song.url.startsWith("file://") ||
            song.url.startsWith("youtube:") || song.url.startsWith("yt:") ||
            song.url.matches(Regex("[A-Za-z0-9_-]{11}"))
        if (!concreteMusicUrl) return null
        return song.copy(spotifyTrackId = (explicit as? Identity.SpotifyTrack)?.id.orEmpty())
    }

    fun sameMedia(first: SongsModel, second: SongsModel): Boolean {
        val a = normalize(first) ?: return false
        val b = normalize(second) ?: return false
        if (a.mediaType != b.mediaType) return false
        if (a.mediaType == MediaType.PODCAST_EPISODE && a.podcastShowId.isNotBlank() &&
            b.podcastShowId.isNotBlank() && a.podcastShowId != b.podcastShowId) return false
        if (a.spotifyTrackId.isNotBlank() && b.spotifyTrackId.isNotBlank()) {
            return a.spotifyTrackId == b.spotifyTrackId
        }
        return a.url == b.url
    }

    private fun isHttp(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in listOf("https", "http") && !uri.host.isNullOrBlank() && uri.host != "open.spotify.com"
    }.getOrDefault(false)
}
