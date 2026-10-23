package com.music.spotui.playback

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/** Car queries are distinct from listening history. A typing burst replaces its own prefixes. */
internal class AutoSearchHistory(
    read: () -> String?,
    private val write: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val capacity: Int = 20,
) {
    data class Entry(val query: String, val searchedAt: Long) {
        val mediaId: String get() = "auto-search/" + MessageDigest.getInstance("SHA-256")
            .digest(query.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private val entries = mutableListOf<Entry>()
    init {
        require(capacity > 0)
        runCatching {
            val rows = JSONArray(read() ?: "[]")
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val query = (row.opt("query") as? String)?.trim() ?: continue
                if (query.isNotEmpty() && query.length <= 200) entries += Entry(query, row.optLong("at"))
            }
        }
        val valid = entries.distinctBy { it.mediaId }.take(capacity)
        entries.clear()
        entries.addAll(valid)
    }

    @Synchronized fun items(): List<Entry> = entries.toList()
    @Synchronized fun find(id: String): Entry? = entries.singleOrNull { it.mediaId == id }

    @Synchronized fun matching(rawQuery: String): List<Entry> {
        val query = rawQuery.trim()
        if (query.isEmpty() || query.length > 200) return emptyList()
        return entries.filter { it.query.contains(query, true) && !it.query.equals(query, true) }
    }

    @Synchronized fun record(rawQuery: String) {
        val query = rawQuery.trim()
        if (query.isEmpty() || query.length > 200) return
        val now = clock()
        val newest = entries.firstOrNull()
        // Search-as-you-type must not fill history with every intermediate letter.
        if (newest != null && now - newest.searchedAt in 0..10_000 &&
            (query.startsWith(newest.query, true) || newest.query.startsWith(query, true))) {
            if (newest.query.length > query.length) return // Late response for an earlier prefix.
            entries.removeAt(0)
        }
        val entry = Entry(query, now)
        entries.removeAll { it.mediaId == entry.mediaId }
        entries.add(0, entry)
        while (entries.size > capacity) entries.removeAt(entries.lastIndex)
        write(JSONArray().apply {
            entries.forEach { put(JSONObject().put("query", it.query).put("at", it.searchedAt)) }
        }.toString())
    }
}
