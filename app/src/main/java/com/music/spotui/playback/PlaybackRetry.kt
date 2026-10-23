package com.music.spotui.playback

/** A retry belongs to one ownership token, including after a crossfade promotion. */
internal class PlaybackRetry {
    private var token: String? = null
    private var action: (() -> Unit)? = null

    @Synchronized fun set(token: String, action: (() -> Unit)?) {
        this.token = token
        this.action = action
    }

    @Synchronized fun get(token: String): (() -> Unit)? = action.takeIf { this.token == token }

    @Synchronized fun clear() { token = null; action = null }
}
