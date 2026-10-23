package com.music.spotui.playback

import com.music.spotui.data.entity.SongsModel

/** Recommendations may vary, but every accepted track retains its exact Spotify identity. */
internal object AutoRadioQueue {
    /** Start the highest-ranked valid recommendation, not the anchor or an arbitrary fallback. */
    suspend fun startStation(
        anchor: SongsModel,
        recommendations: List<SongsModel>,
        currentToken: () -> String,
        cancel: (String) -> Unit,
        prepare: (SongsModel, () -> Unit) -> String,
        commit: (PlaybackQueueIdentity.Selection) -> Unit,
        timeoutMs: Long = 45_000,
    ): Boolean {
        val queue = candidates(anchor, listOf(anchor), recommendations)
        if (queue.isEmpty()) return false
        val next = queue[0] // Recommendation rank is intentional; the anchor is already excluded.
        awaitResumptionPreparation<Unit>(timeoutMs, currentToken, cancel) { prepared ->
            prepare(next) {
                // Source preparation and ownership must both succeed before publishing the station.
                if (prepared(Unit)) commit(PlaybackQueueIdentity.select(next, queue))
            }
        }
        return true
    }

    fun candidates(anchor: SongsModel, existing: List<SongsModel>, recommendations: List<SongsModel>): List<SongsModel> {
        if (anchor.isPodcast() || anchor.spotifyTrackId.isBlank()) return emptyList()
        val seen = existing.filterNot { it.isPodcast() }.map { it.spotifyTrackId }.toMutableSet()
        seen += anchor.spotifyTrackId
        // The existing queue controls address numeric IDs. Omit collisions instead of making
        // another exact Spotify track indistinguishable to next/previous or collapsing the queue.
        val queueIds = existing.map { it.id }.toMutableSet().apply { add(anchor.id) }
        return recommendations.filter { song ->
            !song.isPodcast() && song.spotifyTrackId.isNotBlank() &&
                WazeResumeResolver.normalize(song) != null && song.id !in queueIds &&
                seen.add(song.spotifyTrackId) && queueIds.add(song.id)
        }.take(50)
    }
}
