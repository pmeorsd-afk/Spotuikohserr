package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import org.json.JSONObject

data class PlaybackSnapshot(val song: SongsModel, val positionMs: Long)

/** Shared disk/Intent representation. An episode ID uses an explicit alias for new readers. */
internal object PlaybackSnapshotCodec {
    fun encode(snapshot: PlaybackSnapshot): String = JSONObject().apply {
        val song = snapshot.song
        put("id", song.id); put("title", song.title); put("album", song.album)
        put("singer", song.singer); put("coverUri", song.coverUri)
        put("url", song.url); put("spotifyTrackId", song.spotifyTrackId)
        if (song.mediaType == MediaType.PODCAST_EPISODE) put("spotifyEpisodeId", song.spotifyTrackId)
        put("explicit", song.explicit); put("durationMs", song.durationMs)
        put("mediaType", song.mediaType.name); put("podcastShowId", song.podcastShowId)
        put("positionMs", snapshot.positionMs.coerceAtLeast(0))
    }.toString()

    fun decode(raw: String): PlaybackSnapshot? = runCatching {
        val o = JSONObject(raw)
        val url = o.optString("url")
        val type = o.optString("mediaType").takeIf { it.isNotBlank() }?.let(MediaType::valueOf)
            ?: if (url.startsWith("episode:")) MediaType.PODCAST_EPISODE else MediaType.TRACK
        val genericId = o.optString("spotifyTrackId")
        val episodeId = o.optString("spotifyEpisodeId")
        if (episodeId.isNotBlank() && (type != MediaType.PODCAST_EPISODE ||
                (genericId.isNotBlank() && genericId != episodeId))) return null
        val song = SongsModel(
            id = o.optInt("id", -1), title = o.optString("title"), album = o.optString("album"),
            singer = o.optString("singer"), coverUri = o.optString("coverUri"), url = url,
            spotifyTrackId = episodeId.ifBlank { genericId },
            explicit = o.optBoolean("explicit", false), durationMs = o.optInt("durationMs", 0),
            mediaType = type, podcastShowId = o.optString("podcastShowId"),
        )
        if (song.title.isBlank() || song.url.isBlank()) null
        else PlaybackSnapshot(song, o.optLong("positionMs", 0).coerceAtLeast(0))
    }.getOrNull()
}
