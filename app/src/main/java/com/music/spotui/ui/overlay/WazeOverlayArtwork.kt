package com.music.spotui.ui.overlay

import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackQueueIdentity
import com.music.spotui.playback.WazeResumeResolver
import com.music.spotui.util.KosherWhitelistManager

/** Permission belongs to the exact displayed item, not a URL warmed by a different screen. */
internal object WazeOverlayArtwork {
    fun current(queue: List<SongsModel>, currentId: Int, title: String, artist: String,
        saved: SongsModel?, owned: SongsModel? = null): SongsModel? {
        if (title.isBlank()) return saved?.let(WazeResumeResolver::normalize)
        // A headless/background player can have a verified item before the phone queue is
        // populated. Its actual owner is stronger evidence than the display's numeric ID.
        if (owned != null) return WazeResumeResolver.normalize(owned)?.takeIf {
            it.id == currentId && it.title == title && it.singer == artist
        }
        val index = PlaybackQueueIdentity.indexOf(queue, currentId)
        val song = queue.getOrNull(index) ?: return null
        // State can be in transition; do not borrow another item's permission or artwork.
        if (song.title != title || song.singer != artist) return null
        return WazeResumeResolver.normalize(song)
    }

    fun allowed(song: SongsModel?): Boolean = song != null && song.coverUri.isNotBlank() &&
        KosherWhitelistManager.isSongWhitelisted(song)
}
