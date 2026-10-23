package com.music.spotui.data.preferences

import android.content.Context

/**
 * The last track that was loaded as "current", persisted outside of
 * [com.music.spotui.di.CurrentSongState] (which lives only in memory) so the Waze mini player
 * can offer to resume it even after the app's process has been killed.
 */
data class LastPlayedTrack(
    val songId: Int,
    val title: String,
    val singer: String,
    val album: String,
    val coverUri: String,
    val song: com.music.spotui.data.entity.SongsModel? = null,
    val positionMs: Long = 0L,
)

private const val PREFS_NAME = "waze_last_track"
private const val KEY_SONG_ID = "song_id"
private const val KEY_TITLE = "title"
private const val KEY_SINGER = "singer"
private const val KEY_ALBUM = "album"
private const val KEY_COVER_URI = "cover_uri"

private fun prefs(context: Context) =
    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

/** Call this every time the current track actually changes - see CHANGES.md part 2 for where. */
fun saveLastPlayedTrack(context: Context, track: LastPlayedTrack) {
    prefs(context).edit()
        .putInt(KEY_SONG_ID, track.songId)
        .putString(KEY_TITLE, track.title)
        .putString(KEY_SINGER, track.singer)
        .putString(KEY_ALBUM, track.album)
        .putString(KEY_COVER_URI, track.coverUri)
        .apply()
}

fun getLastPlayedTrack(context: Context): LastPlayedTrack? {
    // Exact playback identity always precedes the legacy display-only Waze preference.
    loadLastPlayback(context)?.let { (song, position) ->
        return LastPlayedTrack(song.id, song.title, song.singer, song.album, song.coverUri, song, position)
    }
    val p = prefs(context)
    val title = p.getString(KEY_TITLE, null)?.takeIf { it.isNotBlank() } ?: return null
    return LastPlayedTrack(
        songId = p.getInt(KEY_SONG_ID, -1),
        title = title,
        singer = p.getString(KEY_SINGER, "").orEmpty(),
        album = p.getString(KEY_ALBUM, "").orEmpty(),
        coverUri = p.getString(KEY_COVER_URI, "").orEmpty(),
    )
}

/**
 * Intent-extra keys used to hand a track from the Waze mini player (WazeOverlayView.kt, running
 * inside WazeOverlayService) back to MainActivity, so it can load and play it, then return to
 * Waze on its own. See CHANGES.md part 2.
 */
object WazeResumeContract {
    const val EXTRA_PLAYBACK_SNAPSHOT = "waze_resume_playback_snapshot"
    const val EXTRA_MEDIA_TYPE = "waze_resume_media_type"
    const val EXTRA_SONG_ID = "waze_resume_song_id"
    const val EXTRA_TITLE = "waze_resume_title"
    const val EXTRA_SINGER = "waze_resume_singer"
    const val EXTRA_ALBUM = "waze_resume_album"
    const val EXTRA_COVER_URI = "waze_resume_cover_uri"
    const val EXTRA_RETURN_TO_WAZE = "waze_resume_return_to_waze"
}
