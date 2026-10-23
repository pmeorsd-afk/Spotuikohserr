package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import java.net.URI

/** Identity only: no player, queue, UI or RSS side effects. */
internal object ExternalMediaIdentityResolver {
    sealed interface Identity {
        data class SpotifyEpisode(val id: String) : Identity
        data class SpotifyTrack(val id: String) : Identity
        data class InternalEpisode(val showId: String, val episodeKey: String) : Identity
        data class EpisodeNumber(val id: Int) : Identity
        data class SongNumber(val id: Int) : Identity
        data class Unknown(val value: String) : Identity
    }

    data class Request(
        val identifiers: List<String> = emptyList(),
        val mediaType: MediaType? = null,
        val title: String? = null,
        val album: String? = null,
        val artist: String? = null,
    )

    private val spotifyId = Regex("[A-Za-z0-9]{22}")
    private val numericId = Regex("[0-9]+")

    fun parse(value: String): Identity {
        if (value.startsWith("episode:")) {
            val body = value.removePrefix("episode:")
            val separator = body.indexOf(':')
            if (separator > 0 && separator < body.lastIndex) {
                // Preserve the entire RSS key, including colons, query strings and URL fragments.
                return Identity.InternalEpisode(body.substring(0, separator), body.substring(separator + 1))
            }
            if (spotifyId.matches(body)) return Identity.SpotifyEpisode(body)
        }
        if (value.startsWith("episode/")) {
            val id = value.removePrefix("episode/")
            if (spotifyId.matches(id)) return Identity.SpotifyEpisode(id)
            if (numericId.matches(id)) id.toIntOrNull()?.let { return Identity.EpisodeNumber(it) }
        }
        if (value.startsWith("song/")) {
            val id = value.removePrefix("song/")
            if (numericId.matches(id)) id.toIntOrNull()?.let { return Identity.SongNumber(it) }
        }
        for (type in listOf("episode", "track")) {
            val prefix = "spotify:$type:"
            if (value.startsWith(prefix)) {
                val id = value.removePrefix(prefix)
                if (spotifyId.matches(id)) return spotifyIdentity(type, id)
            }
        }
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri != null && uri.scheme in listOf("https", "http") &&
            uri.host == "open.spotify.com" && uri.userInfo == null && uri.port == -1
        ) {
            val parts = uri.path.orEmpty().split('/')
            if (parts.size == 3 && parts[1] in listOf("episode", "track") && spotifyId.matches(parts[2])) {
                return spotifyIdentity(parts[1], parts[2])
            }
        }
        return Identity.Unknown(value)
    }

    private fun spotifyIdentity(type: String, id: String): Identity =
        if (type == "episode") Identity.SpotifyEpisode(id) else Identity.SpotifyTrack(id)

    fun spotifyEpisodeId(song: SongsModel): String? {
        if (!song.isPodcast()) return null
        val explicit = if (spotifyId.matches(song.spotifyTrackId)) song.spotifyTrackId
            else (parse(song.spotifyTrackId) as? Identity.SpotifyEpisode)?.id
        val fromUrl = (parse(song.url) as? Identity.SpotifyEpisode)?.id
        // A damaged snapshot must not advertise one Spotify ID while carrying another.
        return listOfNotNull(explicit, fromUrl).distinct().singleOrNull()
    }

    private fun identities(request: Request) =
        request.identifiers.filter { it.isNotBlank() }.map { value ->
            if (spotifyId.matches(value) && request.mediaType != null) {
                spotifyIdentity(if (request.mediaType == MediaType.PODCAST_EPISODE) "episode" else "track", value)
            } else parse(value)
        }.distinct()

    private fun matches(identity: Identity, song: SongsModel): Boolean = when (identity) {
        is Identity.SpotifyEpisode -> song.isPodcast() && spotifyEpisodeId(song) == identity.id
        is Identity.SpotifyTrack -> !song.isPodcast() &&
            (song.spotifyTrackId == identity.id || parse(song.spotifyTrackId) == identity ||
                parse(song.url.substringBefore('|')) == identity)
        is Identity.InternalEpisode -> song.isPodcast() && parse(song.url) == identity
        is Identity.EpisodeNumber -> song.isPodcast() && song.id == identity.id
        is Identity.SongNumber -> song.id == identity.id
        is Identity.Unknown -> false
    }

    fun matches(request: Request, song: SongsModel): Boolean {
        val ids = identities(request)
        return ids.isNotEmpty() && typeMatches(request, song) && ids.all { matches(it, song) }
    }

    private fun typeMatches(request: Request, song: SongsModel): Boolean =
        request.mediaType == null || (request.mediaType == MediaType.PODCAST_EPISODE) == song.isPodcast()

    private fun key(song: SongsModel): Any = if (song.isPodcast()) {
        spotifyEpisodeId(song)?.let { Identity.SpotifyEpisode(it) } ?: if (song.url.isBlank()) Identity.EpisodeNumber(song.id) else parse(song.url)
    } else {
        if (song.spotifyTrackId.isBlank() && song.url.isBlank()) Identity.SongNumber(song.id) else Identity.SpotifyTrack(song.spotifyTrackId.takeIf { it.isNotBlank() } ?: song.url)
    }

    private fun unique(candidates: List<SongsModel>): SongsModel? =
        // Copies of the same stable identity from multiple caches are one item.
        // Callers order snapshots by authority (live model, persisted model, history).
        candidates.distinctBy(::key).singleOrNull()

    suspend fun resolve(
        request: Request,
        candidates: List<SongsModel>,
        fetchSpotifyEpisode: suspend (String) -> SongsModel?,
    ): SongsModel? {
        val ids = identities(request)
        if (ids.isNotEmpty()) {
            val exact = candidates.filter { song -> ids.all { matches(it, song) } }
            if (exact.isNotEmpty()) {
                // Metadata may reject a stable match, never select between colliding IDs.
                return unique(exact)?.takeIf { typeMatches(request, it) }
            }
            val episode = ids.singleOrNull() as? Identity.SpotifyEpisode ?: return null
            if (request.mediaType == MediaType.TRACK) return null
            return fetchSpotifyEpisode(episode.id)?.takeIf { matches(request, it) }
        }

        // An untyped request cannot safely be inferred to be a podcast from text alone.
        if (request.mediaType == null) return null
        val title = request.title?.trim().orEmpty()
        val album = request.album?.trim().orEmpty()
        val artist = request.artist?.trim().orEmpty()
        if (title.isEmpty() || (album.isEmpty() && artist.isEmpty())) return null
        return unique(candidates.filter {
            typeMatches(request, it) && it.title.trim() == title &&
                (album.isEmpty() || it.album.trim() == album) &&
                (artist.isEmpty() || it.singer.trim() == artist)
        })
    }
}
