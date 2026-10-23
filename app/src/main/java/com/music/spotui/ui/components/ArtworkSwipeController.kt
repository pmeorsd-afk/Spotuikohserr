package com.music.spotui.ui.components

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackPagerGuard

/** Authorize from a real drag event, and consume only after the pager's snap/fling ends. */
internal class ArtworkSwipeController {
    private var sequence = 0L
    private var guard = PlaybackPagerGuard()
    var gesture: Long? = null
        private set

    fun begin(token: String) {
        gesture = ++sequence
        guard = PlaybackPagerGuard().apply { beginDrag(token) }
    }

    fun cancel() {
        gesture = null
        guard = PlaybackPagerGuard()
    }

    fun finish(ticket: Long?, token: String, queue: List<SongsModel>, currentId: Int, page: Int): Int? {
        if (ticket == null || ticket != gesture) return null
        gesture = null
        return guard.settle(token, queue, currentId, page)
    }
}
