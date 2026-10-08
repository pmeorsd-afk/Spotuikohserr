package com.music.spotui.data.preferences

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal snapshot of a followed podcast show for instant local rendering
 * without needing network or RSS requests.
 */
data class FollowedPodcastShow(
    val showId: String,
    val name: String,
    val imageUrl: String = "",
    val followedAt: Long = System.currentTimeMillis(),
    val lastSeenEpisodeGuid: String? = null,
)

private const val PREF_NAME = "PodcastFollowPref"
private const val KEY_FOLLOWED_SHOWS = "followed_shows"

private val _podcastFollowRevision = MutableStateFlow(0L)
val podcastFollowRevision: StateFlow<Long> = _podcastFollowRevision.asStateFlow()

fun notifyPodcastFollowChanged() {
    _podcastFollowRevision.value = System.currentTimeMillis()
}

private fun FollowedPodcastShow.toJson(): JSONObject = JSONObject().apply {
    put("showId", showId)
    put("name", name)
    put("imageUrl", imageUrl)
    put("followedAt", followedAt)
    if (lastSeenEpisodeGuid != null) {
        put("lastSeenEpisodeGuid", lastSeenEpisodeGuid)
    }
}

private fun JSONObject.toFollowedPodcastShow(): FollowedPodcastShow = FollowedPodcastShow(
    showId = optString("showId"),
    name = optString("name"),
    imageUrl = optString("imageUrl"),
    followedAt = optLong("followedAt", 0L),
    lastSeenEpisodeGuid = if (has("lastSeenEpisodeGuid") && !isNull("lastSeenEpisodeGuid")) {
        optString("lastSeenEpisodeGuid")
    } else {
        null
    },
)

fun getFollowedShows(context: Context): List<FollowedPodcastShow> {
    val raw = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .getString(KEY_FOLLOWED_SHOWS, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getJSONObject(it).toFollowedPodcastShow() }
            .filter { it.showId.isNotBlank() }
    }.getOrDefault(emptyList())
}

private fun saveFollowedShows(context: Context, shows: List<FollowedPodcastShow>) {
    val arr = JSONArray().apply {
        shows.forEach { put(it.toJson()) }
    }
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_FOLLOWED_SHOWS, arr.toString())
        .apply()
    notifyPodcastFollowChanged()
}

fun isPodcastFollowed(context: Context, showId: String): Boolean {
    if (showId.isBlank()) return false
    return getFollowedShows(context).any { it.showId == showId }
}

fun followPodcast(context: Context, show: FollowedPodcastShow) {
    if (show.showId.isBlank()) return
    val existing = getFollowedShows(context).filterNot { it.showId == show.showId }
    // Prepend so the most recently followed show appears first
    saveFollowedShows(context, listOf(show) + existing)
}

fun followPodcast(context: Context, showId: String, name: String, imageUrl: String = "") {
    followPodcast(
        context = context,
        show = FollowedPodcastShow(
            showId = showId,
            name = name,
            imageUrl = imageUrl,
            followedAt = System.currentTimeMillis(),
        )
    )
}

fun unfollowPodcast(context: Context, showId: String): Boolean {
    if (showId.isBlank()) return false
    val current = getFollowedShows(context)
    val updated = current.filterNot { it.showId == showId }
    if (updated.size != current.size) {
        saveFollowedShows(context, updated)
        return true
    }
    return false
}
