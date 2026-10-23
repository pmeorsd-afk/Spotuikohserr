package com.music.spotui.data.preferences

import android.content.Context

private const val PODCAST_RATINGS = "PodcastPersonalRatings"

/** Personal, device-local ratings only. Never presented or submitted as a public Spotify rating. */
fun getPersonalPodcastRating(context: Context, showId: String): Int? {
    if (showId.isBlank() || showId == "unknown") return null
    return runCatching {
        context.getSharedPreferences(PODCAST_RATINGS, Context.MODE_PRIVATE)
            .getInt("show:$showId", 0).takeIf { it in 1..5 }
    }.getOrNull()
}

/** Call off the main thread. Success means the preference write has actually completed. */
fun savePersonalPodcastRating(context: Context, showId: String, rating: Int): Boolean {
    if (showId.isBlank() || showId == "unknown" || rating !in 1..5) return false
    return runCatching {
        context.getSharedPreferences(PODCAST_RATINGS, Context.MODE_PRIVATE)
            .edit().putInt("show:$showId", rating).commit()
    }.getOrDefault(false)
}
