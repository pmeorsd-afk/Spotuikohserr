package com.music.spotui.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.PlaybackSnapshotCodec
import com.music.spotui.playback.WazeResumeResolver
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Local, bounded listening log. Old metadata-only entries remain readable, but
 * cannot be turned into playback identities by guessing from titles or UI hashes.
 */
data class HistoryEntry(
    val ts: Long,
    val songId: Int,
    val title: String,
    val singer: String,
    val album: String = "",
    val image: String = "",
    val song: SongsModel? = null,
) {
    internal fun exactSong(): SongsModel? = song?.takeIf { it.title.isNotBlank() }?.let {
        WazeResumeResolver.normalize(it) ?: it.takeIf { PlaybackProgressStore.key(it) != null }
    }

    companion object {
        fun fromSong(song: SongsModel, ts: Long = System.currentTimeMillis()): HistoryEntry = HistoryEntry(
            ts, song.id, song.title, song.singer, song.album, song.coverUri, song,
        )
    }
}

private const val PREF = "ListeningHistory"
private const val KEY = "entries"
private const val MAX_ENTRIES = 500

private fun HistoryEntry.toJson() = JSONObject().apply {
    put("ts", ts); put("songId", songId); put("title", title)
    put("singer", singer); put("album", album); put("image", image)
    song?.let { put("media", JSONObject(PlaybackSnapshotCodec.encode(PlaybackSnapshot(it, 0)))) }
}

private fun JSONObject.toEntry() = HistoryEntry(
    ts = optLong("ts"), songId = optInt("songId", -1), title = optString("title"),
    singer = optString("singer"), album = optString("album"), image = optString("image"),
    song = optJSONObject("media")?.toString()?.let { PlaybackSnapshotCodec.decode(it)?.song },
)

/** Pure storage policy shared by production preferences and JVM regression tests. */
internal object ListeningHistoryLog {
    fun decode(raw: String?): List<HistoryEntry> = runCatching {
        val arr = JSONArray(raw ?: "[]")
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.toEntry() }
    }.getOrDefault(emptyList())

    fun encode(entries: List<HistoryEntry>): String =
        JSONArray().also { a -> entries.forEach { a.put(it.toJson()) } }.toString()

    fun append(existing: List<HistoryEntry>, entry: HistoryEntry, zone: ZoneId = ZoneId.systemDefault()): List<HistoryEntry> {
        val song = entry.exactSong() ?: return existing
        val key = PlaybackProgressStore.key(song)
        val previous = existing.firstOrNull()
        // Pause/resume, seeks and quality changes aren't additional listens. A new day is.
        if (previous != null && previous.exactSong()?.let(PlaybackProgressStore::key) == key &&
            Instant.ofEpochMilli(previous.ts).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(entry.ts).atZone(zone).toLocalDate()) return existing
        return (listOf(entry) + existing).take(MAX_ENTRIES)
    }
}

/** Runs once on first history access after upgrading; never clears another preference store. */
internal object ListeningHistoryMigration {
    private const val DONE = "typed_identity_cleanup_v1"

    fun run(prefs: SharedPreferences) {
        if (prefs.getBoolean(DONE, false)) return
        val raw = prefs.getString(KEY, null)
        val editor = prefs.edit()
        if (raw != null) {
            val exactEntries = ListeningHistoryLog.decode(raw).filter { it.exactSong() != null }
            editor.putString(KEY, ListeningHistoryLog.encode(exactEntries))
        }
        // One atomic update: reopening/upgrading again must not delete new listening entries.
        editor.putBoolean(DONE, true).apply()
    }
}

@Synchronized
fun getListeningHistory(context: Context): List<HistoryEntry> {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    ListeningHistoryMigration.run(prefs)
    val raw = prefs.getString(KEY, null)
        ?: return emptyList()
    return ListeningHistoryLog.decode(raw)
}

private fun save(context: Context, entries: List<HistoryEntry>) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        .edit().putString(KEY, ListeningHistoryLog.encode(entries)).apply()
}

@Synchronized
fun addListeningHistory(context: Context, entry: HistoryEntry) {
    val existing = getListeningHistory(context)
    val updated = ListeningHistoryLog.append(existing, entry)
    if (updated !== existing) save(context, updated)
}

@Synchronized
fun removeListeningHistory(context: Context, entry: HistoryEntry) {
    save(context, getListeningHistory(context).filterNot { it == entry })
}

@Synchronized
fun clearListeningHistory(context: Context) {
    context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
}

fun observeListeningHistory(context: Context) = callbackFlow {
    val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == KEY) trySend(getListeningHistory(context))
    }
    prefs.registerOnSharedPreferenceChangeListener(listener)
    trySend(getListeningHistory(context))
    awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}.distinctUntilChanged()
