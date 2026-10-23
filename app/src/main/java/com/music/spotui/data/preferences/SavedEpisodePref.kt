package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.PlaybackSnapshotCodec
import org.json.JSONObject

private const val EPISODES_PREF = "SavedPodcastEpisodes"

/** Preserve a positively identified legacy episode before a music record reuses its old key. */
internal fun preserveLegacyEpisode(context: Context, raw: String?) {
    val value = raw ?: return
    val song = PlaybackSnapshotCodec.decode(value)?.song ?: return
    val key = episodeSaveKey(song) ?: return
    val prefs = context.getSharedPreferences(EPISODES_PREF, Context.MODE_PRIVATE)
    if (prefs.contains(key) || prefs.getBoolean("removed:$key", false)) return
    val json = JSONObject(PlaybackSnapshotCodec.encode(PlaybackSnapshot(song.copy(mediaType = MediaType.PODCAST_EPISODE), 0)))
        .put("savedAt", JSONObject(value).optLong("likedAt", 0L))
    prefs.edit().putString(key, json.toString()).apply()
}

internal fun episodeSaveKey(song: SongsModel): String? {
    if (!song.isPodcast()) return null
    PlaybackProgressStore.key(song)?.takeIf { it.startsWith("episode:") }?.let { return it }
    // Saving an explicitly typed RSS enclosure does not require external-controller eligibility.
    // No numeric/title fallback, and never ignore a conflicting Spotify identifier.
    if (song.mediaType != MediaType.PODCAST_EPISODE || song.spotifyTrackId.isNotBlank()) return null
    val uri = runCatching { java.net.URI(song.url) }.getOrNull() ?: return null
    return if (uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() && uri.host != "open.spotify.com")
        "episode:source:${song.url}" else null
}

/** Read-through migration: only explicitly typed/episode-prefixed legacy records are episodes.
 * Keep old data intact. Tombstones prevent an explicitly removed episode from reappearing. */
internal fun legacySavedEpisodes(context: Context): List<Pair<SongsModel, Long>> =
    context.getSharedPreferences("LikedSongs_Data", Context.MODE_PRIVATE).all.values.mapNotNull { raw ->
        (raw as? String)?.let { value -> runCatching {
            val song = PlaybackSnapshotCodec.decode(value)?.song ?: return@runCatching null
            if (!song.isPodcast() || episodeSaveKey(song) == null) return@runCatching null
            song.copy(mediaType = MediaType.PODCAST_EPISODE) to JSONObject(value).optLong("likedAt", 0)
        }.getOrNull() }
    }

fun getSavedEpisodes(context: Context): List<SongsModel> {
    val prefs = context.getSharedPreferences(EPISODES_PREF, Context.MODE_PRIVATE)
    val current = prefs.all.filterKeys { !it.startsWith("removed:") }.values.mapNotNull { raw ->
        (raw as? String)?.let { value -> runCatching {
            val song = PlaybackSnapshotCodec.decode(value)?.song ?: return@runCatching null
            if (episodeSaveKey(song) == null) return@runCatching null
            song to JSONObject(value).optLong("savedAt", 0)
        }.getOrNull() }
    }
    return (current + legacySavedEpisodes(context)).filterNot { (song, _) ->
        prefs.getBoolean("removed:${episodeSaveKey(song)}", false)
    }.sortedByDescending { it.second }.distinctBy { episodeSaveKey(it.first) }.map { it.first }
}

fun isEpisodeSaved(context: Context, song: SongsModel): Boolean {
    val key = episodeSaveKey(song) ?: return false
    return getSavedEpisodes(context).any { episodeSaveKey(it) == key }
}

fun saveEpisode(context: Context, song: SongsModel): Boolean {
    val key = episodeSaveKey(song) ?: return false
    if (song.title.isBlank() || song.url.isBlank()) return false
    val exact = song.copy(mediaType = MediaType.PODCAST_EPISODE)
    val json = JSONObject(PlaybackSnapshotCodec.encode(PlaybackSnapshot(exact, 0)))
        .put("savedAt", System.currentTimeMillis()).toString()
    context.getSharedPreferences(EPISODES_PREF, Context.MODE_PRIVATE).edit()
        .putString(key, json).remove("removed:$key").apply()
    notifyLikedSongsChanged()
    return true
}

fun removeSavedEpisode(context: Context, song: SongsModel) {
    val key = episodeSaveKey(song) ?: return
    context.getSharedPreferences(EPISODES_PREF, Context.MODE_PRIVATE).edit()
        .remove(key).putBoolean("removed:$key", true).apply()
    notifyLikedSongsChanged()
}

fun isSavedInAnyDestination(context: Context, song: SongsModel): Boolean =
    isSongLiked(context, song) || CustomPlaylistStore.getPlaylists(context).any {
        CustomPlaylistStore.containsSong(it, song)
    } || remoteSavedMembershipStore(context)?.contains(song) == true

/** A second tap on a check opens destinations; it does not silently remove the saved item. */
fun saveToDefaultIfNeeded(context: Context, song: SongsModel): Boolean {
    if (!isSavedInAnyDestination(context, song)) addLikedSong(context, song)
    return isSavedInAnyDestination(context, song)
}

fun savedEpisodeCountLabel(count: Int): String = if (count == 1) "פרק 1" else "$count פרקים"
