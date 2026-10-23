package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.PlaybackSnapshotCodec
import com.music.spotui.playback.PodcastProgressStore
import com.music.spotui.playback.PodcastProgress
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val PREF = "PlaybackState"
private const val PROGRESS_PREF = "PlaybackProgress"
private const val KEY_SONG = "song"
private const val KEY_POSITION = "positionMs"

private fun progressStore(context: Context): PlaybackProgressStore {
    val p = context.getSharedPreferences(PROGRESS_PREF, Context.MODE_PRIVATE)
    return PlaybackProgressStore(
        read = { key -> if (p.contains(key)) p.getLong(key, 0) else null },
        write = { key, position -> p.edit().putLong(key, position).apply() },
    )
}

private fun legacySnapshot(context: Context): PlaybackSnapshot? {
    val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val song = p.getString(KEY_SONG, null)?.let(PlaybackSnapshotCodec::decode)?.song ?: return null
    return PlaybackSnapshot(song, p.getLong(KEY_POSITION, 0).coerceAtLeast(0))
}

/** Unset: resume only current music or a saved episode. An explicit position (including zero) wins. */
internal fun loadPlaybackPosition(context: Context, song: SongsModel, requested: Long? = null): Long =
    progressStore(context).position(song, requested, legacySnapshot(context))

/** Called with the tagged Player owner, never a possibly newer UI selection. */
internal fun savePlaybackPosition(context: Context, song: SongsModel, positionMs: Long, completed: Boolean = false, durationMs: Long = 0) {
    if (positionMs < 0) return
    val key = PlaybackProgressStore.key(song) ?: return
    progressStore(context).save(song, positionMs, completed)
    if (song.mediaType == com.music.spotui.data.entity.MediaType.PODCAST_EPISODE) {
        podcastProgressStore(context).record(song, durationMs, completed)
    }
    // Keep the last-item resume snapshot consistent only when it still names this item.
    if (legacySnapshot(context)?.song?.let(PlaybackProgressStore::key) == key) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong(KEY_POSITION, if (completed) 0 else positionMs).apply()
    }
}

private fun podcastProgressStore(context: Context): PodcastProgressStore {
    val prefs = context.getSharedPreferences(PROGRESS_PREF, Context.MODE_PRIVATE)
    return PodcastProgressStore(
        read = { key -> if (prefs.contains(key)) prefs.getLong(key, 0) else null },
        write = { values ->
            prefs.edit().also { editor -> values.forEach { (key, value) -> editor.putLong(key, value) } }.apply()
        },
    )
}

/** Uses the same durable identity/bookmark as resume, including external Player updates. */
internal fun readPodcastProgress(context: Context, song: SongsModel): PodcastProgress? =
    podcastProgressStore(context).progress(song)

internal fun observePodcastProgress(context: Context, song: SongsModel) = callbackFlow<PodcastProgress?> {
    val prefs = context.getSharedPreferences(PROGRESS_PREF, Context.MODE_PRIVATE)
    val store = podcastProgressStore(context)
    val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (store.observes(song, key)) trySend(store.progress(song))
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    loadPlaybackPosition(context, song) // Migrate an old last-item bookmark if necessary.
    trySend(store.progress(song))
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()

/** Replacing current music resets its position; independent episode bookmarks remain available. */
fun saveLastPlayback(context: Context, song: SongsModel) {
    if (song.title.isBlank() || song.url.isBlank()) return
    val store = progressStore(context)
    val previous = legacySnapshot(context)
    previous?.let { store.position(it.song, legacy = it) } // Migrate legacy episode progress before replacing it.
    val position = store.position(song, legacy = previous)
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        .putString(KEY_SONG, PlaybackSnapshotCodec.encode(PlaybackSnapshot(song, position)))
        .putLong(KEY_POSITION, position).apply()
}

fun saveLastPosition(context: Context, positionMs: Long) {
    val song = legacySnapshot(context)?.song ?: return
    savePlaybackPosition(context, song, positionMs)
}

/** Restore the current MiniPlayer item after recreation; only episodes have per-item bookmarks. */
fun loadLastPlayback(context: Context): Pair<SongsModel, Long>? {
    val snapshot = legacySnapshot(context) ?: return null
    return snapshot.song to progressStore(context).position(snapshot.song, legacy = snapshot)
}
