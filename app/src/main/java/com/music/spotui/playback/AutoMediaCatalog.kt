package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Car browse IDs include their queue context and survive service/process recreation.
 * Only app-issued snapshots are accepted; controller text/URLs never create an entry.
 */
internal class AutoMediaCatalog(
    read: () -> String?,
    private val write: (String) -> Unit,
    private val capacity: Int = 1000,
) {
    data class Entry(val mediaId: String, val parent: String, val song: SongsModel)
    private val entries = linkedMapOf<String, Entry>()

    init {
        require(capacity > 0)
        runCatching {
            val array = JSONArray(read() ?: "[]")
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val song = PlaybackSnapshotCodec.decode(row.optString("snapshot"))?.song ?: continue
                val parent = row.optString("parent")
                val id = id(parent, song) ?: continue
                if (id == row.optString("id")) entries[id] = Entry(id, parent, song)
            }
        }
        trim()
    }

    @Synchronized fun register(parent: String, songs: List<SongsModel>): List<Entry> {
        val result = songs.mapNotNull { song ->
            val id = id(parent, song) ?: return@mapNotNull null
            Entry(id, parent, song)
        }.distinctBy { it.mediaId }.take(capacity)
        result.forEach { entries.remove(it.mediaId); entries[it.mediaId] = it }
        trim()
        write(JSONArray().apply {
            entries.values.forEach { entry -> put(JSONObject().apply {
                put("id", entry.mediaId); put("parent", entry.parent)
                put("snapshot", PlaybackSnapshotCodec.encode(PlaybackSnapshot(entry.song, 0)))
            }) }
        }.toString())
        return result
    }

    @Synchronized fun find(id: String): Entry? = entries[id]
    @Synchronized fun songs(): List<SongsModel> = entries.values.map { it.song }

    private fun trim() { while (entries.size > capacity) entries.remove(entries.keys.first()) }

    companion object {
        fun id(parent: String, song: SongsModel): String? {
            if (parent.isBlank() || WazeResumeResolver.normalize(song) == null) return null
            val key = PlaybackProgressStore.key(song) ?: return null
            val digest = MessageDigest.getInstance("SHA-256").digest((parent + "\u0000" + key).toByteArray(Charsets.UTF_8))
            return "auto/" + digest.joinToString("") { "%02x".format(it) }
        }

        fun search(query: String, songs: List<SongsModel>): List<SongsModel> {
            val text = query.trim()
            if (text.isEmpty() || text.length > 200) return emptyList()
            return songs.filter { WazeResumeResolver.normalize(it) != null }
                .distinctBy { PlaybackProgressStore.key(it) }
                .filter { it.title.contains(text, true) || it.singer.contains(text, true) || it.album.contains(text, true) }
        }

        /** Voice playback is stricter than browsing results: never play the first fuzzy hit. */
        fun voiceSelection(query: String, songs: List<SongsModel>): SongsModel? =
            search(query, songs).filter { it.title.equals(query.trim(), true) }.singleOrNull()

        fun <T> page(items: List<T>, page: Int, size: Int): List<T> {
            require(page >= 0 && size > 0)
            val start = page.toLong() * size
            if (start >= items.size) return emptyList()
            return items.subList(start.toInt(), minOf(items.size.toLong(), start + size).toInt())
        }
    }
}
