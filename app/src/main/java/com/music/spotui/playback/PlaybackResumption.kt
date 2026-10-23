package com.music.spotui.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Only a fresh PLAY press may cold-start a service; key-up/repeat/pause must not do so. */
internal object ResumeMediaButtonPolicy {
    fun accepts(action: Int, keyCode: Int, repeatCount: Int): Boolean =
        action == 0 && repeatCount == 0 && (keyCode == 126 || keyCode == 85) // DOWN, PLAY, PLAY_PAUSE
}

/** Media3 1.3.1 invokes play even on a failed resumption future. Scope that call to its result. */
internal class ResumptionDispatchGate {
    var allowed = true
        private set

    fun <T> complete(allowTransport: Boolean, action: () -> T): T {
        val previous = allowed
        allowed = previous && allowTransport
        return try { action() } finally { allowed = previous }
    }
}

/** The music engine has already prepared this exact instance; applying it again would reset audio. */
internal class PreparedResumption<T : Any> {
    private var item: T? = null
    private var token: String? = null

    fun set(item: T, token: String) { this.item = item; this.token = token }
    fun clear() { item = null; token = null }
    fun consume(items: List<T>, currentItem: T?, currentToken: String): Boolean {
        if (items.size != 1 || items.single() !== item || item !== currentItem || token != currentToken) return false
        clear()
        return true
    }
}

/** Bridges the existing music prepare callback without guessing a source or returning an empty list. */
internal suspend fun <T> awaitResumptionPreparation(
    timeoutMs: Long,
    currentToken: () -> String,
    cancel: (String) -> Unit,
    start: (prepared: (T) -> Boolean) -> String,
): T {
    val prepared = CompletableDeferred<T>()
    var token: String? = null
    var succeeded = false
    try {
        token = start { item ->
            if (token != null && currentToken() != token) false else prepared.complete(item)
        }
        return withTimeout(timeoutMs) {
            while (!prepared.isCompleted) {
                if (currentToken() != token) throw CancellationException("Resumption superseded")
                delay(50)
            }
            if (currentToken() != token) throw CancellationException("Resumption superseded")
            prepared.await().also { succeeded = true }
        }
    } finally {
        prepared.cancel()
        if (!succeeded) token?.let(cancel)
    }
}
