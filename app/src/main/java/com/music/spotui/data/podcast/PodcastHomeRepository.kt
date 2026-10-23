package com.music.spotui.data.podcast

import android.content.Context
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.local.LocalListeningTracker
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.filter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

internal suspend fun <T> Flow<Response<T>>.podcastData(): T = when (val response = filter { it !is Response.Loading }.last()) {
    is Response.Success -> response.data
    is Response.Error -> throw IllegalStateException(response.error)
    else -> error("Missing podcast result")
}

@Singleton
class PodcastHomeRepository @Inject constructor(
    private val repository: AppRepository,
    private val tracker: LocalListeningTracker,
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("PodcastHomeCatalog", Context.MODE_PRIVATE)
    private val discovery = com.music.spotui.playback.SharedResolution<Unit, List<PodcastModel>>(scope)
    private val loader = PodcastCatalogLoader(
        scope = scope,
        initial = PodcastHomeCodec.decode(prefs.getString("snapshot", null)),
        persist = { prefs.edit().putString("snapshot", PodcastHomeCodec.encode(it)).apply() },
        fetch = { repository.providePodcastEpisodes(it.id, it.name).podcastData() },
    )

    fun peek(): List<PodcastCatalog> = loader.peek()
    suspend fun catalog(show: PodcastModel, force: Boolean = false) = loader.load(show, force)

    suspend fun discover(): List<PodcastModel> = discovery.get(Unit) {
        withTimeout(20_000) { repository.providePodcastHubShows().podcastData() }
    }

    fun plan(discovered: List<PodcastModel>, seed: Long): List<PodcastModel> {
        val followed = repository.getFollowedPodcasts().sortedByDescending { it.followedAt }
            .map { PodcastModel(it.showId, it.name, "", it.imageUrl) }
        val listened = tracker.recentTracks(80).filter { it.mediaType == MediaType.PODCAST_EPISODE }
            .filter { it.podcastShowId.isNotBlank() && it.album.isNotBlank() }
            .map { PodcastModel(it.podcastShowId, it.album, "", "") }.distinctBy { it.id }.take(8)
        val searched = getRecentItems(context).filter { it.type == "show" && it.key.isNotBlank() }
            .map { PodcastModel(it.key, it.name, it.singer, it.image) }.take(4)
        val known = (discovered + peek().map { it.show }).associateBy { it.id }
        val interest = (followed + listened + searched).map { it.id }.toSet()
        val topics = known.values.filter { it.id in interest }.flatMap { it.topics }.map { it.lowercase() }.toSet()
        val explore = discovered.shuffled(Random(seed)).sortedByDescending { show ->
            show.topics.count { it.lowercase() in topics }
        }.take(20)
        // Start with a mix, then let explicit Load More work through the remaining followed shows.
        return (followed.take(2) + listened.take(1) + explore.take(2) + followed.drop(2) + listened.drop(1) + searched + explore.drop(2))
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
            .map { known[it.id] ?: it }
    }

    fun continuingEpisodes(): List<PodcastEpisodeUiModel> = tracker.recentTracks(80)
        .filter { it.mediaType == MediaType.PODCAST_EPISODE }.mapNotNull { item ->
            val song = item.toSongModel()
            val progress = readPodcastProgress(context, song) ?: return@mapNotNull null
            if (progress.completed || progress.positionMs <= 0) return@mapNotNull null
            PodcastEpisodeUiModel(
                id = song.url, numericId = song.id, title = song.title, coverUri = song.coverUri,
                playUrl = song.url, showId = song.podcastShowId, showName = song.album,
                publisher = song.singer, spotifyEpisodeId = song.spotifyTrackId, durationMs = progress.durationMs,
                durationFormatted = com.music.spotui.util.PodcastDateFormatter.formatDuration(progress.durationMs),
            ).takeIf { it.hasExactIdentity() }
        }.distinctBy { it.id }.take(3)

    fun signals(catalogs: List<PodcastCatalog>, continuingEpisodes: List<PodcastEpisodeUiModel> = emptyList()): PodcastHomeSignals {
        val followed = repository.getFollowedPodcasts().map { it.showId }.toSet()
        val affinity = mutableMapOf<String, Double>()
        tracker.recentTracks(80).filter { it.mediaType == MediaType.PODCAST_EPISODE && it.podcastShowId.isNotBlank() }
            .forEach { affinity[it.podcastShowId] = affinity.getOrDefault(it.podcastShowId, 0.0) + minOf(it.playCount, 5) * 2.0 }
        getRecentItems(context).filter { it.type == "show" }.forEach {
            affinity[it.key] = affinity.getOrDefault(it.key, 0.0) + 3.0
        }
        val completed = mutableSetOf<String>(); val continuing = mutableSetOf<String>()
        (catalogs.flatMap { it.episodes } + continuingEpisodes).forEach { ep ->
            val key = PodcastHomeRanking.key(ep) ?: return@forEach
            val progress = readPodcastProgress(context, ep.toSongModel()) ?: return@forEach
            if (progress.completed) completed.add(key)
            else if (progress.positionMs > 0) continuing.add(key)
        }
        val topics = catalogs.filter { it.show.id in followed || it.show.id in affinity }
            .flatMap { it.show.topics }.map { it.lowercase() }.toSet()
        return PodcastHomeSignals(followed, affinity, topics, completed, continuing)
    }
}
