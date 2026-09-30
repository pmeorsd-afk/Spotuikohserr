package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import org.json.JSONArray
import org.json.JSONObject

private const val PREF = "LibraryCache"

fun cacheLibraryEntries(context: Context, entries: List<LibraryEntry>) {
    val json = JSONArray().apply {
        entries.forEach { e ->
            put(JSONObject().apply {
                put("spotifyId", e.spotifyId)
                put("name", e.name)
                put("subtitle", e.subtitle)
                put("coverUri", e.coverUri)
                put("isPlaylist", e.isPlaylist)
                put("artists", e.artists)
            })
        }
    }.toString()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString("entries", json).apply()
}

fun getCachedLibraryEntries(context: Context): List<LibraryEntry> = runCatching {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString("entries", null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { o ->
            val entry = LibraryEntry(
                spotifyId = o.getString("spotifyId"),
                name = o.getString("name"),
                subtitle = o.getString("subtitle"),
                coverUri = o.getString("coverUri"),
                isPlaylist = o.getBoolean("isPlaylist"),
                artists = o.optString("artists", ""),
            )
            if (!com.music.spotui.util.KosherWhitelistManager.isLibraryEntryWhitelisted(entry, context)) {
                entry.copy(coverUri = "")
            } else {
                entry
            }
        }
    }
}.getOrDefault(emptyList())

fun cacheFollowedArtists(context: Context, artists: List<ArtistsModel>) {
    val json = JSONArray().apply {
        artists.forEach { a ->
            put(JSONObject().apply {
                put("name", a.name)
                put("coverUri", a.coverUri)
                put("id", a.id)
            })
        }
    }.toString()
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString("followedArtists", json).apply()
}

fun getCachedFollowedArtists(context: Context): List<ArtistsModel> = runCatching {
    val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .getString("followedArtists", null) ?: return emptyList()
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        arr.getJSONObject(i).let { o ->
            ArtistsModel(
                name = o.getString("name"),
                coverUri = o.getString("coverUri"),
                id = o.optString("id", ""),
            )
        }
    }
}.getOrDefault(emptyList())

fun addCustomPlaylist(context: Context, entry: LibraryEntry) {
    val isAllowed = com.music.spotui.util.KosherWhitelistManager.isLibraryEntryWhitelisted(entry, context)
    val sanitizedEntry = if (!isAllowed) entry.copy(coverUri = "") else entry
    val current = getCachedLibraryEntries(context).toMutableList()
    current.removeAll { it.spotifyId == sanitizedEntry.spotifyId || (it.name.equals(sanitizedEntry.name, ignoreCase = true) && it.isPlaylist) }
    current.add(0, sanitizedEntry)
    cacheLibraryEntries(context, current)
}

fun getCachedPlaylists(context: Context): List<LibraryEntry> {
    // 1. Local custom playlists from CustomPlaylistStore
    val local = CustomPlaylistStore.getPlaylists(context).map {
        val entry = LibraryEntry(
            spotifyId = it.id,
            name = it.name,
            subtitle = "Playlist • " + CustomPlaylistStore.getSubtitle(it.songKeys.size),
            coverUri = it.coverUri,
            isPlaylist = true
        )
        if (!com.music.spotui.util.KosherWhitelistManager.isLibraryEntryWhitelisted(entry, context)) {
            entry.copy(coverUri = "")
        } else {
            entry
        }
    }
    // 2. Real Spotify playlists from server cache (strictly filtering out system shortcuts and duplicates)
    val server = getCachedLibraryEntries(context).filter { entry ->
        entry.isPlaylist &&
        entry.spotifyId != "liked" &&
        entry.spotifyId != "downloaded" &&
        !entry.name.equals("Liked Songs", ignoreCase = true) &&
        !entry.name.equals("Downloaded", ignoreCase = true) &&
        !entry.name.equals("שירים שאהבתם", ignoreCase = true)
    }.map { entry ->
        if (!com.music.spotui.util.KosherWhitelistManager.isLibraryEntryWhitelisted(entry, context)) {
            entry.copy(coverUri = "")
        } else {
            entry
        }
    }
    val localNames = local.map { it.name.trim().lowercase() }.toSet()
    return local + server.filter { it.name.trim().lowercase() !in localNames }
}

