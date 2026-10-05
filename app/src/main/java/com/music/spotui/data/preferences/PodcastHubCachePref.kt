package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.PodcastModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_NAME = "PodcastHubCache"
private const val KEY_TIMESTAMP = "timestamp"
private const val KEY_SHOWS = "shows"

/**
 * Persists the discovered Podcast Hub shows to SharedPreferences along with a timestamp.
 * Allows instant display on cold start without waiting for network round-trips.
 */
fun saveCachedPodcastHubShows(
    context: Context,
    shows: List<PodcastModel>,
    timestamp: Long = System.currentTimeMillis()
) {
    if (shows.isEmpty()) return
    runCatching {
        val json = JSONArray().apply {
            shows.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("publisher", s.publisher)
                    put("coverUri", s.coverUri)
                })
            }
        }.toString()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_TIMESTAMP, timestamp)
            .putString(KEY_SHOWS, json)
            .apply()
    }
}

/**
 * Reads persisted Podcast Hub shows from SharedPreferences.
 * Returns Pair(timestampMs, showsList), or null if cache is empty or invalid.
 */
fun getCachedPodcastHubShows(context: Context): Pair<Long, List<PodcastModel>>? = runCatching {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val ts = prefs.getLong(KEY_TIMESTAMP, 0L)
    val raw = prefs.getString(KEY_SHOWS, null) ?: return null
    if (ts == 0L || raw.isBlank()) return null
    val arr = JSONArray(raw)
    val list = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        PodcastModel(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            publisher = o.optString("publisher", ""),
            coverUri = o.optString("coverUri", "")
        )
    }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    if (list.isEmpty()) null else Pair(ts, list)
}.getOrNull()

fun clearCachedPodcastHubShows(context: Context) {
    runCatching {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }
}
