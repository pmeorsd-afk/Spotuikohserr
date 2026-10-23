package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel

internal data class PodcastProgress(val positionMs: Long, val durationMs: Long, val completed: Boolean) {
    val fraction: Float get() = if (completed) 1f else
        (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
}

/** Presentation metadata beside the existing resume bookmark; never changes resume behavior. */
internal class PodcastProgressStore(
    private val read: (String) -> Long?,
    private val write: (Map<String, Long>) -> Unit,
) {
    fun record(song: SongsModel, durationMs: Long, completed: Boolean) {
        val key = episodeKey(song) ?: return
        val values = mutableMapOf(completedKey(key) to if (completed) 1L else 0L)
        if (durationMs > 0) values[durationKey(key)] = durationMs
        write(values)
    }

    fun progress(song: SongsModel): PodcastProgress? {
        val key = episodeKey(song) ?: return null
        val duration = read(durationKey(key))?.takeIf { it > 0 } ?: song.durationMs.toLong()
        if (duration <= 0) return null // No invented percentage for unknown-length audio.
        return PodcastProgress(
            (read(key) ?: 0L).coerceIn(0, duration), duration, read(completedKey(key)) == 1L,
        )
    }

    fun observes(song: SongsModel, changedKey: String?): Boolean {
        val key = episodeKey(song) ?: return false
        return changedKey == null || changedKey == key ||
            changedKey == durationKey(key) || changedKey == completedKey(key)
    }

    private fun episodeKey(song: SongsModel) =
        if (song.mediaType == MediaType.PODCAST_EPISODE) PlaybackProgressStore.key(song) else null

    private fun durationKey(key: String) = "display-duration:" + key
    private fun completedKey(key: String) = "display-completed:" + key
}
