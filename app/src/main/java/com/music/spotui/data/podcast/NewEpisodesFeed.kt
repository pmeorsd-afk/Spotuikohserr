package com.music.spotui.data.podcast

import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.util.PodcastDateFormatter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class EpisodeListening(val positionMs: Long = 0, val durationMs: Long = 0, val completed: Boolean = false) {
    val partial get() = !completed && positionMs > 0 && (durationMs <= 0 || positionMs < durationMs)
    val fraction get() = if (durationMs > 0) (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f) else 0f
}

internal data class ContinuingEpisode(val episode: PodcastEpisodeUiModel, val listening: EpisodeListening)
internal data class NewEpisodesFeed(
    val continuing: List<ContinuingEpisode> = emptyList(),
    val episodes: List<PodcastEpisodeUiModel> = emptyList(),
)

internal object NewEpisodesProjection {
    fun key(episode: PodcastEpisodeUiModel) = PlaybackProgressStore.key(episode.toSongModel())

    /** History is newest-listened first, independent of the followed-show filter. */
    fun build(
        catalogs: List<PodcastCatalog>, followed: Set<String>, history: List<PodcastEpisodeUiModel>,
        completed: Set<String>, listening: (PodcastEpisodeUiModel) -> EpisodeListening,
    ): NewEpisodesFeed {
        val catalogEpisodes = catalogs.filter { it.show.id in followed }.flatMap { catalog ->
            catalog.episodes.filter { it.showId == catalog.show.id && it.hasExactIdentity() }
        }.distinctBy(::key)
        val metadata = catalogEpisodes.associateBy(::key)
        fun unfinished(ep: PodcastEpisodeUiModel) = key(ep) !in completed && !listening(ep).completed
        val continuing = history.filter { it.hasExactIdentity() }.distinctBy(::key)
            .map { metadata[key(it)] ?: it }.filter(::unfinished)
            .map { ContinuingEpisode(it, listening(it)) }.filter { it.listening.partial }.take(2)
        val continuingKeys = continuing.map { key(it.episode) }.toSet()
        return NewEpisodesFeed(continuing, PodcastDateFormatter.sortByPubDateDescending(
            catalogEpisodes.filter { unfinished(it) && key(it) !in continuingKeys },
        ) { it.pubDate })
    }

    fun fromHistory(song: SongsModel): PodcastEpisodeUiModel? = if (!song.isPodcast()) null else
        PodcastEpisodeUiModel(id = song.url, numericId = song.id, title = song.title,
            showId = song.podcastShowId, showName = song.album, publisher = song.singer,
            playUrl = song.url, spotifyEpisodeId = song.spotifyTrackId, coverUri = song.coverUri,
            durationMs = song.durationMs.toLong()).takeIf { it.hasExactIdentity() }

    fun section(date: String?, today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        val published = runCatching { LocalDate.parse(date) }.getOrNull()
            ?: PodcastDateFormatter.parsePubDateToMillis(date)?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            ?: return "פרקים נוספים"
        return when {
            published > today -> "בקרוב"
            published == today -> "היום"
            published == today.minusDays(1) -> "אתמול"
            published >= today.minusDays(6) -> "השבוע"
            else -> "מוקדם יותר"
        }
    }
}

internal const val EPISODE_UNDO_DURATION_MS = 6_000L
internal data class EpisodeCompletionUndo(val key: String, val token: String, val expiresAt: Long)

/** A separate completion decision never seeks, resets a bookmark, or changes the Player. */
internal class EpisodeCompletionStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Boolean,
    private val clock: () -> Long,
    private val token: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    @Synchronized fun complete(episode: PodcastEpisodeUiModel): EpisodeCompletionUndo? {
        if (!episode.hasExactIdentity()) return null
        val key = NewEpisodesProjection.key(episode) ?: return null
        if (read(key) != null) return null
        val value = token()
        if (!write(key, value)) return null
        return EpisodeCompletionUndo(key, value, clock() + EPISODE_UNDO_DURATION_MS)
    }

    @Synchronized fun undo(undo: EpisodeCompletionUndo): Boolean {
        if (clock() >= undo.expiresAt || read(undo.key) != undo.token) return false
        return write(undo.key, null)
    }
}
