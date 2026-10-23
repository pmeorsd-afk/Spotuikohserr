package com.music.spotui.data.home

import com.music.spotui.data.entity.HomeItem
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.PlaybackSnapshotCodec
import com.music.spotui.playback.WazeResumeResolver
import org.json.JSONObject

/** Home cache metadata only. Ambiguous legacy playback entries are omitted, never reclassified. */
internal object HomeFeedItemCodec {
    fun encode(item: HomeItem): JSONObject? {
        val o = JSONObject()
        when (item) {
            is HomeItem.Album -> {
                o.put("itemType", "album")
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
                o.put("subtitle", item.subtitle)
                o.put("artists", item.artists)
                o.put("spotifyId", item.spotifyId)
            }
            is HomeItem.Artist -> {
                o.put("itemType", "artist")
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
                o.put("id", item.id)
            }
            is HomeItem.Playlist -> {
                o.put("itemType", "playlist")
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
                o.put("subtitle", item.subtitle)
                o.put("id", item.id)
            }
            is HomeItem.Track -> {
                val song = WazeResumeResolver.normalize(item.song) ?: return null
                o.put("itemType", "track")
                o.put("song", JSONObject(PlaybackSnapshotCodec.encode(PlaybackSnapshot(song, 0))))
            }
            is HomeItem.LikedSongs -> {
                o.put("itemType", "liked_songs")
                o.put("count", item.count)
                o.put("name", item.name)
                o.put("imageUrl", item.imageUrl)
            }
        }
        return o
    }

    fun decode(o: JSONObject): HomeItem? = when (o.optString("itemType")) {
        "album" -> HomeItem.Album(
            name = o.optString("name"),
            imageUrl = o.optString("imageUrl"),
            subtitle = o.optString("subtitle"),
            artists = o.optString("artists"),
            spotifyId = o.optString("spotifyId"),
        )
        "artist" -> HomeItem.Artist(
            name = o.optString("name"),
            imageUrl = o.optString("imageUrl"),
            id = o.optString("id"),
        )
        "playlist" -> HomeItem.Playlist(
            name = o.optString("name"),
            imageUrl = o.optString("imageUrl"),
            subtitle = o.optString("subtitle"),
            id = o.optString("id"),
        )
        "track" -> o.optJSONObject("song")?.let { stored ->
            PlaybackSnapshotCodec.decode(stored.toString())?.song
                ?.let(WazeResumeResolver::normalize)?.let { HomeItem.Track(it) }
        }
        "liked_songs" -> HomeItem.LikedSongs(
            count = o.optInt("count"),
            name = o.optString("name", "שירים שאהבתם"),
            imageUrl = o.optString("imageUrl"),
        )
        else -> null
    }
}
