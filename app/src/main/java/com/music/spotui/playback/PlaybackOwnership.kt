package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import java.util.UUID

/** Shared generation gate for asynchronous playback work. Contains no Android/player operations. */
internal class PlaybackOwnership {
    data class Ticket(
        val generation: Long,
        val token: String,
        val request: String,
        val mediaType: MediaType,
        val song: SongsModel?,
        val external: Boolean,
        val mediaId: String?,
    )

    @Volatile var current = Ticket(0, UUID.randomUUID().toString(), "", MediaType.TRACK, null, false, null)
        private set
    @Volatile var active: Ticket? = null
        private set

    @Synchronized
    fun begin(
        request: String,
        mediaType: MediaType,
        song: SongsModel? = null,
        external: Boolean = false,
        mediaId: String? = null,
        token: String = UUID.randomUUID().toString(),
    ): Ticket {
        current = Ticket(current.generation + 1, token, request, mediaType, song, external, mediaId)
        active = null
        return current
    }

    fun isCurrent(token: String): Boolean = current.token == token

    fun canControl(itemToken: String?): Boolean = itemToken != null && isCurrent(itemToken)

    /** Revoke a failed/cancelled request even when its resolver ignores cancellation. */
    @Synchronized fun revoke(token: String): Boolean {
        if (!isCurrent(token)) return false
        val owner = current
        begin(owner.request, owner.mediaType, owner.song, external = true)
        return true
    }

    /** The check and mutation are one operation even when a result arrives from a worker thread. */
    @Synchronized
    fun runIfCurrent(token: String, action: () -> Unit): Boolean {
        if (!isCurrent(token)) return false
        action()
        return true
    }

    @Synchronized
    fun activate(token: String, mediaId: String? = null): Boolean {
        if (!isCurrent(token)) return false
        if (current.external && (current.song == null || current.mediaId != mediaId)) return false
        active = current
        return true
    }

    @Synchronized
    fun deactivate(token: String) {
        if (active?.token == token) active = null
    }

    fun positionOwner(token: String): Ticket? = active?.takeIf { it.token == token && isCurrent(token) }

    fun canRecoverMusic(token: String): Boolean =
        positionOwner(token)?.let { it.mediaType == MediaType.TRACK && !it.external } == true
}
