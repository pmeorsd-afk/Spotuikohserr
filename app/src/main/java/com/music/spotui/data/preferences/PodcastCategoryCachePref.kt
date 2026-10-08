package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.PodcastModel
import org.json.JSONArray
import org.json.JSONObject

private const val PREF_NAME = "PodcastCategoryCache"
private const val KEY_PREFIX_TIMESTAMP = "ts_"
private const val KEY_PREFIX_SHOWS = "shows_"

/**
 * Persists discovered Podcast shows for a given category to SharedPreferences.
 * Supports Stale-While-Revalidate and 0ms instant display on subsequent opens.
 */
fun saveCachedPodcastCategoryShows(
    context: Context,
    categoryId: String,
    shows: List<PodcastModel>,
    timestamp: Long = System.currentTimeMillis()
) {
    if (shows.isEmpty() || categoryId.isBlank()) return
    runCatching {
        val json = JSONArray().apply {
            shows.forEach { s ->
                put(JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("publisher", s.publisher)
                    put("coverUri", s.coverUri)
                    put("description", s.description)
                    if (s.topics.isNotEmpty()) {
                        put("topics", JSONArray(s.topics))
                    }
                })
            }
        }.toString()
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_PREFIX_TIMESTAMP + categoryId, timestamp)
            .putString(KEY_PREFIX_SHOWS + categoryId, json)
            .apply()
    }
}

/**
 * Reads persisted category shows from SharedPreferences.
 * Returns showsList, or null if cache is empty or invalid.
 */
fun getCachedPodcastCategoryShows(
    context: Context,
    categoryId: String
): List<PodcastModel>? = runCatching {
    val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_PREFIX_SHOWS + categoryId, null) ?: return null
    if (raw.isBlank()) return null
    val arr = JSONArray(raw)
    val list = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val topicsArr = o.optJSONArray("topics")
        val topicsList = if (topicsArr != null) {
            (0 until topicsArr.length()).mapNotNull { topicsArr.optString(it, null) }
        } else emptyList()
        PodcastModel(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            publisher = o.optString("publisher", ""),
            coverUri = o.optString("coverUri", ""),
            description = o.optString("description", ""),
            topics = topicsList
        )
    }.filter { it.id.isNotBlank() && it.name.isNotBlank() }
    if (list.isEmpty()) null else list
}.getOrNull()

fun clearCachedPodcastCategoryShows(context: Context, categoryId: String? = null) {
    runCatching {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (categoryId != null) {
            prefs.edit()
                .remove(KEY_PREFIX_TIMESTAMP + categoryId)
                .remove(KEY_PREFIX_SHOWS + categoryId)
                .apply()
        } else {
            prefs.edit().clear().apply()
        }
    }
}
