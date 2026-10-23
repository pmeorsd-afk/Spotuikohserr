package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.api.SpotifySession
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackProgressStore
import java.security.MessageDigest

internal fun remoteSavedItemUri(song: SongsModel): String? {
    val key = PlaybackProgressStore.key(song) ?: return null
    val kind = if (song.isPodcast()) "episode" else "track"
    val prefix = "$kind:spotify:"
    if (!key.startsWith(prefix)) return null
    val id = key.removePrefix(prefix)
    return if (id.matches(Regex("[A-Za-z0-9]{22}"))) "spotify:$kind:$id" else null
}

/** Last confirmed playlist membership, scoped to the signed-in session. No credentials are stored. */
internal class RemoteSavedMembershipStore(context: Context, sessionKey: String) {
    private val prefs = context.getSharedPreferences("RemoteSavedMembership_$sessionKey", Context.MODE_PRIVATE)
    fun contains(song: SongsModel): Boolean {
        val uri = remoteSavedItemUri(song) ?: return false
        return prefs.all.any { (key, value) -> key.startsWith("$uri|") && value == true }
    }
    fun record(playlistId: String, song: SongsModel, saved: Boolean) {
        val uri = remoteSavedItemUri(song) ?: return
        if (playlistId.isBlank() || '|' in playlistId) return
        val key = "$uri|$playlistId"
        if (prefs.getBoolean(key, false) == saved) return
        val editor = prefs.edit()
        if (saved) editor.putBoolean(key, true) else editor.remove(key)
        editor.apply(); notifyLikedSongsChanged()
    }
    /** Call only after a complete successful listing, never on a partial/offline result. */
    fun retainPlaylists(ids: Set<String>) {
        val obsolete = prefs.all.keys.filter { it.substringAfter('|') !in ids }
        if (obsolete.isEmpty()) return
        val editor = prefs.edit(); obsolete.forEach(editor::remove); editor.apply()
        notifyLikedSongsChanged()
    }
}

internal fun remoteSavedMembershipStore(context: Context): RemoteSavedMembershipStore? {
    val session = SpotifySession.spDc(context)
    if (session.isBlank() || session == "anonymous") return null
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(session.toByteArray()).joinToString("") { "%02x".format(it) }
    return RemoteSavedMembershipStore(context, fingerprint)
}
