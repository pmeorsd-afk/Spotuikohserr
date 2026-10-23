package com.music.spotui.playback

import com.music.spotui.data.entity.ArtistOverviewModel
import kotlin.random.Random

/** An explicit car-search "play this artist" action, distinct from browsing that artist's folder. */
internal object AutoArtistPlayback {
    const val PREFIX = "auto-artist-play/"
    private val token = Regex("[0-9a-f]{64}")

    fun mediaId(entry: AutoBrowseCatalog.Entry): String? =
        entry.takeIf { it.kind == AutoBrowseCatalog.Kind.ARTIST && it.isValid() }
            ?.mediaId?.removePrefix("auto-folder/")?.let { PREFIX + it }

    fun folderId(mediaId: String): String? {
        if (!mediaId.startsWith(PREFIX)) return null
        val suffix = mediaId.removePrefix(PREFIX)
        return suffix.takeIf(token::matches)?.let { "auto-folder/$it" }
    }

    fun resolve(mediaId: String, find: (String) -> AutoBrowseCatalog.Entry?): AutoBrowseCatalog.Entry? =
        folderId(mediaId)?.let(find)?.takeIf { mediaId(it) == mediaId }

    suspend fun select(
        entry: AutoBrowseCatalog.Entry,
        load: suspend (artistId: String) -> ArtistOverviewModel?,
        random: Random = Random.Default,
    ): PlaybackQueueIdentity.Selection? {
        if (mediaId(entry) == null) return null
        val artist = load(entry.id) ?: return null
        if (artist.id != entry.id) return null
        // This is the requested random artist playback, never a fallback from a failed track lookup.
        // Only this exact artist's top-tracks response is eligible; related artists are not used.
        val candidates = artist.topTracks.mapNotNull { row ->
            row.song.takeUnless { it.isPodcast() }?.let(WazeResumeResolver::normalize)
                ?.takeIf { it.spotifyTrackId.isNotBlank() }
        }.distinctBy { it.spotifyTrackId }
        val collisions = candidates.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val queue = candidates.filterNot { it.id in collisions }.shuffled(random)
        if (queue.isEmpty()) return null
        return PlaybackQueueIdentity.select(queue[0], queue)
    }
}
