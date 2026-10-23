package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CancellationException

/** Shared by MediaSession set/resumption. Android only adapts the prepared result to MediaItem. */
internal class ExternalPlaybackRequests(
    private val currentToken: () -> String,
    private val takeOver: (SongsModel, String) -> PlaybackOwnership.Ticket,
    private val resolveSource: suspend (SongsModel) -> PlaybackSource?,
) {
    data class Request(val epoch: Long, val token: String)
    data class Prepared(
        val ticket: PlaybackOwnership.Ticket,
        val source: PlaybackSource.DirectAudio,
        val positionMs: Long,
    )
    private var epoch = 0L

    fun begin(): Request = Request(++epoch, currentToken())
    fun invalidate() { epoch++ }

    fun requireCurrent(request: Request, token: String = request.token) {
        if (request.epoch != epoch || token != currentToken()) throw CancellationException("Playback request superseded")
    }

    suspend fun identity(request: Request, resolve: suspend () -> SongsModel?): SongsModel {
        requireCurrent(request)
        val song = resolve() ?: throw IllegalArgumentException("Unresolved external media identity")
        requireCurrent(request)
        return song
    }

    suspend fun podcast(
        request: Request, song: SongsModel, positionMs: Long,
        onReserved: (PlaybackOwnership.Ticket) -> Unit = {},
    ): Prepared {
        requireCurrent(request)
        val exact = WazeResumeResolver.normalize(song)
            ?.takeIf { it.mediaType == MediaType.PODCAST_EPISODE }
            ?: throw IllegalArgumentException("Invalid podcast identity")
        val mediaId = ExternalMediaIdentityResolver.spotifyEpisodeId(exact)?.let { "spotify:episode:$it" }
            ?: exact.url
        val ticket = takeOver(exact, mediaId)
        onReserved(ticket)
        val source = resolveSource(exact)
        requireCurrent(request, ticket.token)
        if (source !is PlaybackSource.DirectAudio || source.url.isBlank()) {
            throw IllegalStateException("Podcast has no DirectAudio source")
        }
        return Prepared(ticket, source, positionMs.coerceAtLeast(0))
    }

    fun rejectAdd(): Nothing = throw UnsupportedOperationException("External add/replace is unsupported; use setMediaItems")
}
