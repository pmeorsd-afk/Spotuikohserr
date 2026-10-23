package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel

/** A queue position is evidence only when it names the requested item uniquely. */
internal object PlaybackQueueIdentity {
    data class Selection(val song: SongsModel, val queue: List<SongsModel>, val index: Int)

    fun indexOf(queue: List<SongsModel>, currentId: Int): Int =
        queue.indices.filter { queue[it].id == currentId }.singleOrNull() ?: -1

    fun same(a: SongsModel, b: SongsModel): Boolean =
        WazeResumeResolver.sameMedia(a, b) ||
            (a.mediaType == b.mediaType && a.url.isNotBlank() && a.url == b.url &&
                a.spotifyTrackId == b.spotifyTrackId && a.podcastShowId == b.podcastShowId)

    fun select(song: SongsModel, queue: List<SongsModel> = emptyList()): Selection {
        val index = queue.indices.filter { same(queue[it], song) }.singleOrNull()
        if (index == null || queue.count { it.id == song.id } != 1) return Selection(song, listOf(song), 0)
        return Selection(song, queue.toMutableList().apply { this[index] = song }, index)
    }
}

/** Programmatic pager synchronization never grants permission to start playback. */
internal class PlaybackPagerGuard {
    private var dragToken: String? = null
    fun beginDrag(token: String) { if (dragToken == null) dragToken = token }
    fun settle(token: String, queue: List<SongsModel>, currentId: Int, page: Int): Int? {
        val origin = dragToken
        dragToken = null
        val current = PlaybackQueueIdentity.indexOf(queue, currentId)
        return page.takeIf { origin == token && current >= 0 && it in queue.indices && it != current }
    }
}
