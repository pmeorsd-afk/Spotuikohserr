package com.music.spotui.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Refreshes only on browse. Cached/local content stays usable during a shared, bounded fetch. */
internal class AutoHomeFeedCache(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMs: Long = 120_000,
    private val retryMs: Long = 15_000,
    private val timeoutMs: Long = 14_000,
    private val load: suspend () -> AutoHomeContent.Snapshot?,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<AutoHomeContent.Snapshot?>(null)
    val state = mutableState.asStateFlow()
    private var inFlight: Deferred<Unit>? = null
    private var retryAt: Long? = null

    suspend fun get(waitForFirst: Boolean): AutoHomeContent.Snapshot? {
        val refresh = mutex.withLock {
            inFlight?.takeIf { it.isActive } ?: if (retryAt?.let { clock() < it } == true) null else {
                scope.async(start = CoroutineStart.LAZY) {
                    val result = try {
                        withTimeoutOrNull(timeoutMs) { load() }
                    } catch (e: CancellationException) { throw e
                    } catch (e: Exception) { null }
                    mutex.withLock {
                        if (result != null) mutableState.value = result
                        retryAt = clock() + if (result == null) retryMs else ttlMs
                    }
                }.also { inFlight = it; it.start() }
            }
        }
        if (waitForFirst && state.value == null) refresh?.await()
        return state.value
    }
}
