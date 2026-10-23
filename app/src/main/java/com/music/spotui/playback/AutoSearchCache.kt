package com.music.spotui.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Shares a short-lived search snapshot between the car's search and result callbacks. */
internal class AutoSearchCache<T>(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val ttlMs: Long = 30_000,
    private val maxEntries: Int = 12,
    private val load: suspend (String) -> T,
) {
    private data class Entry<T>(val value: T, val completedAt: Long)

    private val lock = Any()
    private val completed = LinkedHashMap<String, Entry<T>>(16, 0.75f, true)
    private val pending = mutableMapOf<String, Deferred<Result<T>>>()

    init {
        require(ttlMs > 0) { "Search cache TTL must be positive" }
        require(maxEntries > 0) { "Search cache capacity must be positive" }
    }

    suspend fun get(query: String): T {
        val key = query.trim()
        require(key.isNotEmpty() && key.length <= 200) { "Invalid search query" }
        currentCoroutineContext().ensureActive()
        scope.coroutineContext.ensureActive()
        val request = synchronized(lock) {
            val now = clock()
            completed.entries.removeAll { (_, entry) ->
                val age = now - entry.completedAt
                age < 0 || age >= ttlMs
            }
            completed[key]?.let { return it.value }
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) {
                // Contain lookup failures so they fail the callback, not the service scope.
                val result = try {
                    Result.success(load(key))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                currentCoroutineContext().ensureActive()
                result.onSuccess { value ->
                    synchronized(lock) {
                        completed[key] = Entry(value, clock())
                        while (completed.size > maxEntries) {
                            completed.remove(completed.keys.first())
                        }
                    }
                }
                result
            }.also { candidate ->
                pending[key] = candidate
                candidate.invokeOnCompletion {
                    synchronized(lock) {
                        if (pending[key] === candidate) pending.remove(key)
                    }
                }
            }
        }
        request.start()
        return request.await().getOrThrow()
    }
}
