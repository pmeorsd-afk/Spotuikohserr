package com.music.spotui.playback

/** Bounded, monotonic cache. A different quality/account/network scope is always a miss. */
internal class PlaybackSourceCache<K, V>(
    private val nowMs: () -> Long,
    private val capacity: Int = 128,
) {
    private data class Entry<V>(val value: V, val scope: Any, val expiresAt: Long)
    private val entries = LinkedHashMap<K, Entry<V>>()

    @Synchronized fun get(key: K, scope: Any): V? {
        val entry = entries[key] ?: return null
        if (nowMs() >= entry.expiresAt) {
            entries.remove(key)
            return null
        }
        // A late reader from an old network/account must not evict the newer entry.
        return entry.value.takeIf { entry.scope == scope }
    }

    @Synchronized fun put(key: K, value: V, scope: Any, lifetimeMs: Long) {
        entries.remove(key)
        if (lifetimeMs <= 0) return
        entries[key] = Entry(value, scope, nowMs() + lifetimeMs.coerceAtMost(300_000))
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    @Synchronized fun remove(key: K) { entries.remove(key) }
}
