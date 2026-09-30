package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_CUSTOM_PL = "SpotUI_CustomPlaylists"
private const val KEY_PLAYLISTS = "playlists"

/**
 * Resolves a unique and stable track key.
 * Always prefers real Spotify Track ID; falls back to real SpotUI song ID.
 * NEVER invents or mocks dummy IDs.
 */
fun SongsModel.toTrackKey(): String =
    if (spotifyTrackId.isNotBlank()) spotifyTrackId else id.toString()

data class CustomPlaylist(
    val id: String,
    val name: String,
    val coverUri: String,
    val updatedAt: Long,
    val songKeys: List<String>,
    val cachedTracks: List<SongsModel> = emptyList()
)

object CustomPlaylistStore {

    fun getPlaylists(context: Context): List<CustomPlaylist> {
        val prefs = context.getSharedPreferences(PREF_CUSTOM_PL, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PLAYLISTS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val sArr = o.optJSONArray("songKeys") ?: JSONArray()
                val keys = (0 until sArr.length()).map { sArr.getString(it) }
                val tArr = o.optJSONArray("cachedTracks") ?: JSONArray()
                val tracks = (0 until tArr.length()).mapNotNull { idx ->
                    runCatching {
                        val to = tArr.getJSONObject(idx)
                        SongsModel(
                            id = to.getInt("id"),
                            title = to.getString("title"),
                            album = to.optString("album", ""),
                            singer = to.getString("singer"),
                            coverUri = to.getString("coverUri"),
                            url = to.getString("url"),
                            spotifyTrackId = to.optString("spotifyTrackId", ""),
                            explicit = to.optBoolean("explicit", false),
                            durationMs = to.optInt("durationMs", 0)
                        )
                    }.getOrNull()
                }
                val rawCover = o.optString("coverUri", "")
                val sanitizedCover = if (rawCover.isNotBlank() && tracks.isNotEmpty()) {
                    val matchingTrack = tracks.find { it.coverUri == rawCover } ?: tracks.firstOrNull()
                    if (matchingTrack != null && !com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(matchingTrack)) {
                        ""
                    } else {
                        rawCover
                    }
                } else {
                    rawCover
                }
                CustomPlaylist(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    coverUri = sanitizedCover,
                    updatedAt = o.optLong("updatedAt", 0L),
                    songKeys = keys,
                    cachedTracks = tracks
                )
            }.sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    fun createPlaylist(context: Context, name: String, initialSong: SongsModel? = null): CustomPlaylist {
        val current = getPlaylists(context).toMutableList()
        val key = initialSong?.toTrackKey()
        val isAllowed = initialSong != null && com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(initialSong)
        val newPl = CustomPlaylist(
            id = "custom_" + System.currentTimeMillis(),
            name = name.trim(),
            coverUri = if (isAllowed) initialSong.coverUri.orEmpty() else "",
            updatedAt = System.currentTimeMillis(),
            songKeys = if (key != null) listOf(key) else emptyList(),
            cachedTracks = if (initialSong != null) listOf(initialSong) else emptyList()
        )
        current.add(0, newPl)
        save(context, current)
        com.music.spotui.data.api.Api.HomeCache.library = null
        return newPl
    }

    fun toggleSong(context: Context, playlistId: String, song: SongsModel): Boolean {
        val current = getPlaylists(context).toMutableList()
        val idx = current.indexOfFirst { it.id == playlistId }
        if (idx == -1) return false
        val pl = current[idx]
        val key = song.toTrackKey()
        val exists = pl.songKeys.contains(key)
        val newKeys = if (exists) pl.songKeys - key else pl.songKeys + key
        val newTracks = if (exists) pl.cachedTracks.filterNot { it.toTrackKey() == key } else pl.cachedTracks + song
        val isSongAllowed = com.music.spotui.util.KosherWhitelistManager.isSongWhitelisted(song)
        val currentCoverAllowed = pl.coverUri.isNotBlank() && com.music.spotui.util.KosherWhitelistManager.isUrlAllowed(pl.coverUri)
        val newCover = if (!exists) {
            if (!currentCoverAllowed) {
                if (isSongAllowed) song.coverUri else ""
            } else {
                pl.coverUri
            }
        } else {
            if (!currentCoverAllowed) "" else pl.coverUri
        }
        val updated = pl.copy(
            songKeys = newKeys,
            cachedTracks = newTracks,
            coverUri = newCover,
            updatedAt = System.currentTimeMillis()
        )
        current.removeAt(idx)
        current.add(0, updated) // Most recently updated playlist moves to top
        save(context, current)
        com.music.spotui.data.api.Api.HomeCache.library = null
        return !exists
    }

    fun isSongInPlaylist(context: Context, playlistId: String, song: SongsModel): Boolean {
        val pl = getPlaylists(context).firstOrNull { it.id == playlistId } ?: return false
        return pl.songKeys.contains(song.toTrackKey())
    }

    fun deletePlaylist(context: Context, playlistId: String): Boolean {
        val current = getPlaylists(context).toMutableList()
        val removed = current.removeAll { it.id == playlistId }
        if (removed) {
            save(context, current)
            val cached = getCachedLibraryEntries(context).toMutableList()
            if (cached.removeAll { it.spotifyId == playlistId }) {
                cacheLibraryEntries(context, cached)
            }
            com.music.spotui.data.api.Api.HomeCache.library = null
        }
        return removed
    }

    fun updatePlaylist(context: Context, playlistId: String, newName: String, newDescription: String = ""): Boolean {
        val current = getPlaylists(context).toMutableList()
        val idx = current.indexOfFirst { it.id == playlistId }
        if (idx == -1) return false
        val pl = current[idx]
        val updated = pl.copy(name = newName.trim(), updatedAt = System.currentTimeMillis())
        current[idx] = updated
        save(context, current)
        val cached = getCachedLibraryEntries(context).toMutableList()
        val cIdx = cached.indexOfFirst { it.spotifyId == playlistId }
        if (cIdx != -1) {
            cached[cIdx] = cached[cIdx].copy(name = newName.trim())
            cacheLibraryEntries(context, cached)
        }
        com.music.spotui.data.api.Api.HomeCache.library = null
        return true
    }

    fun getSubtitle(count: Int): String = when (count) {
        0 -> "ריק"
        1 -> "שיר 1"
        else -> "$count שירים"
    }

    private fun save(context: Context, list: List<CustomPlaylist>) {
        val arr = JSONArray()
        list.forEach { pl ->
            arr.put(JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("coverUri", pl.coverUri)
                put("updatedAt", pl.updatedAt)
                put("songKeys", JSONArray(pl.songKeys))
                val tArr = JSONArray()
                pl.cachedTracks.forEach { t ->
                    tArr.put(JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("album", t.album)
                        put("singer", t.singer)
                        put("coverUri", t.coverUri)
                        put("url", t.url)
                        put("spotifyTrackId", t.spotifyTrackId)
                        put("explicit", t.explicit)
                        put("durationMs", t.durationMs)
                    })
                }
                put("cachedTracks", tArr)
            })
        }
        context.getSharedPreferences(PREF_CUSTOM_PL, Context.MODE_PRIVATE)
            .edit().putString(KEY_PLAYLISTS, arr.toString()).apply()
    }
}
