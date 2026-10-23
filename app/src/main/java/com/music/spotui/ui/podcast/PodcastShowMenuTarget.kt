package com.music.spotui.ui.podcast

import com.music.spotui.data.entity.PodcastModel

/** A Show menu never borrows identity or artwork from the first episode. */
internal data class PodcastShowMenuTarget(val show: PodcastModel) {
    private val spotifyId = show.id.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
    val shareUrl: String? = spotifyId?.let { "https://open.spotify.com/show/$it" }
    // Same public image route used by spotifycodes.com; no credentials or private metadata.
    val codeUrl: String? = spotifyId?.let {
        "https://scannables.scdn.co/uri/plain/png/000000/white/640/spotify:show:$it"
    }

    companion object {
        fun fromRoute(id: String, name: String, loaded: PodcastModel?): PodcastShowMenuTarget? {
            if (id.isBlank() || id == "unknown") return null
            val exact = loaded?.takeIf { it.id == id }
            return PodcastShowMenuTarget(exact ?: PodcastModel(id, name.ifBlank { "פודקאסט" }, "", ""))
        }
    }
}
