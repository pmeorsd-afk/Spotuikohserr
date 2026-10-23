package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PreviewWindow(val startMs: Long, val endMs: Long) {
    val durationMs get() = endMs - startMs
    companion object {
        /** Ten seconds centered on the known song duration. Unknown lengths are not guessed. */
        fun middle(durationMs: Long): PreviewWindow? {
            if (durationMs <= 0) return null
            val length = minOf(10_000L, durationMs)
            val start = (durationMs - length) / 2
            return PreviewWindow(start, start + length)
        }
    }
}

internal data class RadioPreviewSource(val uri: String, val mimeType: String? = null)
internal enum class PreviewPhase { IDLE, LOADING, PLAYING, PAUSED, COMPLETED, FAILED }
internal data class RadioPreviewState(
    val key: String = "", val phase: PreviewPhase = PreviewPhase.IDLE,
    val positionMs: Long = 0, val durationMs: Long = 10_000,
) {
    val progress get() = (positionMs.toFloat() / durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val active get() = phase == PreviewPhase.LOADING || phase == PreviewPhase.PLAYING
}
internal fun SongsModel.previewKey() = "$spotifyTrackId|$url"

internal interface PreviewMainPlayback {
    fun identity(): String
    fun wantsToPlay(): Boolean
    fun pause()
    fun resume()
}

internal interface PreviewOutput {
    interface Events {
        fun playing(playing: Boolean)
        fun position(positionMs: Long)
        fun ended()
        fun failed()
        fun interrupted()
    }
    fun load(source: RadioPreviewSource, window: PreviewWindow, positionMs: Long, events: Events)
    fun positionMs(): Long? = null
    fun stop()
}

/** Main-thread confined. No queue, metadata, history, or MediaSession writes. */
internal class RadioPreviewSession(
    private val scope: CoroutineScope,
    private val main: PreviewMainPlayback,
    private val output: PreviewOutput,
    private val resolve: suspend (SongsModel) -> RadioPreviewSource?,
) {
    private val mutable = MutableStateFlow(RadioPreviewState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var mainIdentity: String? = null
    private var resumeMain = false
    private var closed = false

    fun toggle(song: SongsModel) {
        if (closed || song.isPodcast() || song.url.isBlank()) return
        val key = song.previewKey()
        if (mutable.value.key == key && mutable.value.active) {
            finish(PreviewPhase.PAUSED, resume = true)
            return
        }
        val window = PreviewWindow.middle(song.durationMs.toLong())
        if (window == null) {
            finish(PreviewPhase.FAILED, resume = true)
            mutable.value = RadioPreviewState(key, PreviewPhase.FAILED)
            return
        }
        val position = mutable.value.takeIf { it.key == key && it.phase == PreviewPhase.PAUSED }?.positionMs ?: 0L
        val ticket = ++generation
        job?.cancel()
        output.stop()
        // Switching between previews retains the original lease, without a burst of main audio.
        if (mainIdentity == null) {
            resumeMain = main.wantsToPlay()
            if (resumeMain) main.pause()
            mainIdentity = main.identity() // pause can cancel an unresolved main request
        }
        mutable.value = RadioPreviewState(key, PreviewPhase.LOADING, position, window.durationMs)
        job = scope.launch {
            try {
                val source = withTimeoutOrNull(25_000) { resolve(song) }
                ensureActive()
                if (!owns(ticket)) return@launch
                if (source == null) { finish(PreviewPhase.FAILED, resume = true); return@launch }
                if (main.identity() != mainIdentity || main.wantsToPlay()) { interrupt(); return@launch }
                output.load(source, window, position, object : PreviewOutput.Events {
                    override fun playing(playing: Boolean) {
                        if (owns(ticket)) mutable.value = mutable.value.copy(phase = if (playing) PreviewPhase.PLAYING else PreviewPhase.LOADING)
                    }
                    override fun position(positionMs: Long) {
                        if (owns(ticket)) mutable.value = mutable.value.copy(positionMs = positionMs.coerceIn(0, window.durationMs))
                    }
                    override fun ended() { if (owns(ticket)) finish(PreviewPhase.COMPLETED, resume = true) }
                    override fun failed() { if (owns(ticket)) finish(PreviewPhase.FAILED, resume = true) }
                    override fun interrupted() { if (owns(ticket)) interrupt() }
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owns(ticket)) finish(PreviewPhase.FAILED, resume = true) }
        }
    }

    private fun owns(ticket: Long) = !closed && generation == ticket
    /** External transport, focus loss, unplugging, and backgrounding never auto-resume audio. */
    fun interrupt() { if (mutable.value.active) finish(PreviewPhase.PAUSED, resume = false) }
    fun checkMainPlayback() {
        if (mutable.value.active && (main.identity() != mainIdentity || main.wantsToPlay())) interrupt()
    }
    fun close(resume: Boolean = true) {
        if (closed) return
        finish(PreviewPhase.IDLE, resume)
        closed = true
    }
    private fun finish(phase: PreviewPhase, resume: Boolean) {
        output.positionMs()?.let { mutable.value = mutable.value.copy(positionMs = it.coerceIn(0, mutable.value.durationMs)) }
        ++generation // invalidate late source completions and old player callbacks before release
        job?.cancel(); job = null
        output.stop()
        val mayResume = resume && resumeMain && mainIdentity == main.identity() && !main.wantsToPlay()
        resumeMain = false; mainIdentity = null
        mutable.value = mutable.value.copy(phase = phase,
            positionMs = if (phase == PreviewPhase.COMPLETED) mutable.value.durationMs else mutable.value.positionMs)
        if (mayResume) main.resume()
    }
}

/** Optional foreground preview hook. Main transport remains unchanged when no preview is open. */
internal object RadioPreviewInterruption {
    var listener: (() -> Unit)? = null
    private var internalCommand = false
    fun mainCommand() { if (!internalCommand) listener?.invoke() }
    fun <T> fromPreview(block: () -> T): T {
        val previous = internalCommand
        internalCommand = true
        return try { block() } finally { internalCommand = previous }
    }
}
