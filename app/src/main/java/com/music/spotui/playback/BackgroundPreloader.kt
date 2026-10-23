package com.music.spotui.playback

import kotlinx.coroutines.*

/** One background worker; new lists replace queued work, never spawn another worker. */
internal class BackgroundPreloader<K>(
    private val scope: CoroutineScope,
    private val canRun: suspend () -> Boolean,
    private val load: suspend (K) -> Unit,
    private val waitForTurn: suspend () -> Unit = { delay(500) },
) {
    private val pending = LinkedHashSet<K>()
    private var worker: Job? = null

    @Synchronized fun offer(keys: List<K>, replace: Boolean = false) {
        if (replace) pending.clear()
        keys.take(4).forEach { if (pending.size < 4) pending.add(it) }
        if (worker != null || pending.isEmpty()) return
        worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (isActive) {
                    waitForTurn()
                    if (!canRun()) continue
                    val key = synchronized(this@BackgroundPreloader) {
                        pending.firstOrNull()?.also { pending.remove(it) }
                    } ?: break
                    try { load(key) } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Speculative work must not affect playback. */ }
                }
            } finally {
                synchronized(this@BackgroundPreloader) {
                    worker = null
                    if (pending.isNotEmpty() && scope.isActive) offer(emptyList())
                }
            }
        }.also { it.start() }
    }

    @Synchronized fun clearQueued() { pending.clear() }
}
