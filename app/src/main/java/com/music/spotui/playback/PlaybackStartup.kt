package com.music.spotui.playback

/** Local timing only. Never accepts titles, URLs, cookies or account IDs. */
internal class PlaybackStartup(private val nowMs: () -> Long, private val report: (String) -> Unit) {
    private var token: String? = null
    private var startedAt = 0L
    private var generation = 0L
    @Volatile var pending = false
        private set

    @Synchronized fun begin(token: String, generation: Long) {
        this.token = token
        this.generation = generation
        startedAt = nowMs()
        pending = true
        stage(token, "request")
    }

    @Synchronized fun stage(token: String, stage: String, terminal: Boolean = false) {
        if (this.token != token || !pending) return
        report("generation=" + generation + " stage=" + stage + " elapsedMs=" + (nowMs() - startedAt))
        if (terminal) pending = false
    }

    @Synchronized fun cancel() { token = null; pending = false }
}
