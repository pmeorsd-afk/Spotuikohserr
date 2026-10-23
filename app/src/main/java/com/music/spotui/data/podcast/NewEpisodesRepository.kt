package com.music.spotui.data.podcast

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.repository.AppRepository
import com.music.spotui.util.PodcastDateFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Full followed-show catalogs; Home's eight-episode discovery samples are not this feed. */
@Singleton
class NewEpisodesRepository @Inject constructor(
    private val repository: AppRepository,
    @param:ApplicationContext private val context: Context,
) {
    private val cache = context.getSharedPreferences("NewEpisodesCatalogs", Context.MODE_PRIVATE)
    private val decisions = context.getSharedPreferences("CompletedPodcastEpisodes", Context.MODE_PRIVATE)
    private val permits = Semaphore(2)
    internal val completions = EpisodeCompletionStore(
        read = { decisions.getString(it, null) },
        write = { key, value -> decisions.edit().apply { if (value == null) remove(key) else putString(key, value) }.commit() },
        clock = SystemClock::elapsedRealtime,
    )

    private fun cacheKey(id: String) = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
        .joinToString("") { "%02x".format(it) }

    internal fun cached(shows: List<PodcastModel>): List<PodcastCatalog> = shows.mapNotNull { show ->
        NewEpisodesCatalogCodec.decode(cache.getString(cacheKey(show.id), null), show.id)
    }

    internal suspend fun catalog(show: PodcastModel, force: Boolean): PodcastCatalogResult = withContext(Dispatchers.IO) {
        val old = cached(listOf(show)).singleOrNull()
        if (!force && old != null && System.currentTimeMillis() - old.updatedAt in 0 until 60_000L) {
            return@withContext PodcastCatalogResult(old)
        }
        try {
            val episodes = permits.withPermit {
                withTimeout(30_000) { repository.providePodcastEpisodes(show.id, show.name).podcastData() }
            }
            ensureActive()
            val catalog = PodcastCatalog(show, PodcastDateFormatter.sortByPubDateDescending(
                episodes.filter { it.showId == show.id && it.hasExactIdentity() }.distinctBy(NewEpisodesProjection::key),
            ) { it.pubDate }, System.currentTimeMillis())
            val raw = NewEpisodesCatalogCodec.encode(catalog)
            // Large feeds remain fully available in memory; never silently truncate the live feed.
            if (raw.length <= 4_000_000) cache.edit().putString(cacheKey(show.id), raw).apply()
            PodcastCatalogResult(catalog)
        } catch (_: TimeoutCancellationException) { PodcastCatalogResult(old, true) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { PodcastCatalogResult(old, true) }
    }

    internal fun pruneCache(shows: List<PodcastModel>) {
        val retained = shows.map { cacheKey(it.id) }.toSet()
        cache.edit().also { editor -> cache.all.keys.filter { it !in retained }.forEach(editor::remove) }.apply()
    }

    internal fun feed(catalogs: List<PodcastCatalog>, followed: Set<String>): NewEpisodesFeed {
        loadLastPlayback(context) // Migrate a legacy last-item bookmark, without restoring or starting playback.
        val history = getListeningHistory(context).mapNotNull { it.exactSong()?.let(NewEpisodesProjection::fromHistory) }
        return NewEpisodesProjection.build(catalogs, followed, history, decisions.all.keys) { ep ->
            val song = ep.toSongModel()
            val progress = readPodcastProgress(context, song)
            EpisodeListening(progress?.positionMs ?: loadPlaybackPosition(context, song),
                progress?.durationMs ?: ep.durationMs, progress?.completed == true)
        }
    }

    /** Playback/history changes also update this screen while it stays open. No polling of the Player. */
    internal fun observeListeningChanges() = callbackFlow {
        val stores = listOf("PlaybackProgress", "ListeningHistory", "CompletedPodcastEpisodes")
            .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        stores.forEach { it.registerOnSharedPreferenceChangeListener(listener) }
        trySend(Unit)
        awaitClose { stores.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) } }
    }
}
