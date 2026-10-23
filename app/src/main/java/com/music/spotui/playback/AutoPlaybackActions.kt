package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel

/** Prioritizes Save for car hosts that fill an unused Previous slot from custom actions. */
internal object AutoPlaybackActions {
    enum class Action { SAVE, RADIO, REPEAT }
    enum class SaveResult { SAVED, ALREADY_SAVED, UNSUPPORTED }

    fun order(song: SongsModel?, carPresentation: Boolean): List<Action> {
        if (song == null) return emptyList()
        if (song.isPodcast()) return listOf(Action.REPEAT)
        val music = if (carPresentation) listOf(Action.SAVE, Action.RADIO)
            else listOf(Action.RADIO, Action.SAVE)
        return music.filter { it != Action.SAVE || musicKey(song) != null } + Action.REPEAT
    }

    private fun musicKey(song: SongsModel): String? =
        song.takeUnless { it.isPodcast() }?.let(PlaybackProgressStore::key)

    fun isSaved(
        song: SongsModel, localLikes: List<SongsModel>,
        hasLegacySpotifyLike: (String) -> Boolean = { false },
    ): Boolean {
        val key = musicKey(song) ?: return false
        if (localLikes.any { musicKey(it) == key }) return true
        // Old id-only likes may establish a Spotify track identity, never a title/numeric UI hash.
        val id = WazeResumeResolver.normalize(song)?.spotifyTrackId?.takeIf { it.isNotBlank() }
            ?: return false
        return hasLegacySpotifyLike(id)
    }

    /** Called on the session thread. Saving is idempotent, including when already showing a check. */
    fun save(
        song: SongsModel,
        isSaved: (SongsModel) -> Boolean,
        saveLocal: (SongsModel) -> Unit,
        syncSpotify: (String) -> Unit,
    ): SaveResult {
        if (musicKey(song) == null) return SaveResult.UNSUPPORTED
        if (isSaved(song)) return SaveResult.ALREADY_SAVED
        saveLocal(song)
        WazeResumeResolver.normalize(song)?.spotifyTrackId?.takeIf { it.isNotBlank() }?.let(syncSpotify)
        return SaveResult.SAVED
    }
}
