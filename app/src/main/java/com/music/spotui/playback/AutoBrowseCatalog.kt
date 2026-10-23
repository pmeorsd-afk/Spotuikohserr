package com.music.spotui.playback

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Exact collection identities advertised to the car, retained across service recreation. */
internal class AutoBrowseCatalog(
    read: () -> String?,
    private val write: (String) -> Unit,
    private val capacity: Int = 500,
) {
    enum class Kind { ALBUM, ARTIST, PLAYLIST, SHOW }

    data class Entry(
        val kind: Kind,
        val id: String,
        val title: String,
        val subtitle: String = "",
        val artwork: String = "",
        val artist: String = "",
    ) {
        val mediaId: String
            get() {
                val key = kind.name + "\u0000" + id
                val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                return "auto-folder/" + digest.joinToString("") { "%02x".format(it) }
            }

        internal fun isValid(): Boolean = id.isNotBlank() && title.isNotBlank()
    }

    private val entries = linkedMapOf<String, Entry>()

    init {
        require(capacity > 0)
        runCatching {
            val rows = JSONArray(read() ?: "[]")
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val entry = decode(row) ?: continue
                if (entry.isValid() && entry.mediaId == row.opt("mediaId")) {
                    entries.remove(entry.mediaId)
                    entries[entry.mediaId] = entry
                }
            }
        }
        trim()
    }

    @Synchronized
    fun register(entry: Entry): Entry? = registerAll(listOf(entry)).singleOrNull()

    /** One durable write per exposed page; unseen library entries must not evict that page. */
    @Synchronized
    fun registerAll(candidates: List<Entry>): List<Entry> {
        val accepted = candidates.filter { it.isValid() }.distinctBy { it.mediaId }.take(capacity)
        if (accepted.isEmpty()) return emptyList()
        accepted.forEach { entry ->
            entries.remove(entry.mediaId)
            entries[entry.mediaId] = entry
        }
        trim()
        write(JSONArray().apply {
            entries.values.forEach { item ->
                put(JSONObject().apply {
                    put("mediaId", item.mediaId)
                    put("kind", item.kind.name)
                    put("id", item.id)
                    put("title", item.title)
                    put("subtitle", item.subtitle)
                    put("artwork", item.artwork)
                    put("artist", item.artist)
                })
            }
        }.toString())
        return accepted
    }

    @Synchronized
    fun find(mediaId: String): Entry? = entries[mediaId]

    private fun trim() {
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    private fun decode(row: JSONObject): Entry? {
        val kindName = row.opt("kind") as? String ?: return null
        val kind = Kind.values().singleOrNull { it.name == kindName } ?: return null
        val id = row.opt("id") as? String ?: return null
        val title = row.opt("title") as? String ?: return null
        return Entry(
            kind = kind,
            id = id,
            title = title,
            subtitle = row.opt("subtitle") as? String ?: "",
            artwork = row.opt("artwork") as? String ?: "",
            artist = row.opt("artist") as? String ?: "",
        )
    }
}
