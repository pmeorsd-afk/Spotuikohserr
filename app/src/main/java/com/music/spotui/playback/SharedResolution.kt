package com.music.spotui.playback

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Shares source work only; callers retain their own playback/ownership commits. */
internal class SharedResolution<K : Any, V>(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<K, Deferred<V>>()

    suspend fun get(key: K, resolve: suspend () -> V): V {
        jobs[key]?.let { return it.await() }
        val candidate = scope.async(start = CoroutineStart.LAZY) { resolve() }
        val existing = jobs.putIfAbsent(key, candidate)
        if (existing != null) {
            candidate.cancel()
            return existing.await()
        }
        candidate.invokeOnCompletion { jobs.remove(key, candidate) }
        candidate.start()
        return candidate.await()
    }
}
