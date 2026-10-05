package com.music.spotui.ui.navigation

import com.music.spotui.data.entity.SongsModel
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry holding exact [SongsModel] instances for navigation to ItemDetailScreen.
 * Preserves all fields (mediaType, spotifyTrackId, podcastShowId, album, coverUri, url, durationMs)
 * without requiring large or lossy serialization over URL route parameters.
 */
object ItemDetailRegistry {
    private val items = ConcurrentHashMap<String, SongsModel>()

    fun register(song: SongsModel): String {
        val key = when {
            song.spotifyTrackId.isNotBlank() -> song.spotifyTrackId
            song.url.isNotBlank() -> song.url
            else -> "${song.title}_${song.singer}_${song.album}"
        }
        items[key] = song
        return key
    }

    fun get(key: String): SongsModel? = items[key]

    fun remove(key: String) {
        items.remove(key)
    }

    fun clear() {
        items.clear()
    }
}
