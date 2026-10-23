package com.music.spotui.ui.viewmodel

import android.content.Context
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.podcast.*
import com.music.spotui.data.preferences.podcastFollowRevision
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.playback.PlaybackQueueIdentity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class PodcastHomeState(
    val sections: PodcastHomeSections = PodcastHomeSections(),
    val loading: Boolean = false,
    val partialError: Boolean = false,
    val hasMore: Boolean = false,
    val followedCount: Int = 0,
)

@HiltViewModel
class PodcastHomeViewModel @Inject constructor(
    private val repository: PodcastHomeRepository,
    private val currentSong: CurrentSongState,
) : ViewModel() {
    private val catalogs = repository.peek().associateBy { it.show.id }.toMutableMap()
    private var discovered = emptyList<PodcastModel>()
    private var planned = emptyList<PodcastModel>()
    private var seed = System.nanoTime()
    private var attempted = emptySet<String>()
    private var lastLoad = 0L
    private var active = false
    private var followRevision = podcastFollowRevision.value
    private var job: Job? = null
    private val _state = MutableStateFlow(PodcastHomeState())
    val state = _state.asStateFlow()
    init {
        publish()
        viewModelScope.launch {
            podcastFollowRevision.drop(1).collect { if (active) refresh() }
        }
    }

    fun enter() {
        active = true
        publish()
        if (lastLoad == 0L || System.currentTimeMillis() - lastLoad >= 3_600_000L || followRevision != podcastFollowRevision.value) refresh()
    }
    fun leave() { active = false }
    fun refresh(force: Boolean = false) {
        if (force) seed = System.nanoTime()
        followRevision = podcastFollowRevision.value
        load(force)
    }
    fun loadMore() {
        if (_state.value.loading) return
        load(force = false, more = true)
    }
    private fun load(force: Boolean, more: Boolean = false) {
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, partialError = false) }
            var failed = false
            try {
                discovered = withContext(Dispatchers.IO) { repository.discover() }
            } catch (_: TimeoutCancellationException) { failed = true }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            planned = repository.plan(discovered, seed)
            val targets = (if (more) planned.filter { it.id !in attempted } else planned).take(4)
            val results = withContext(Dispatchers.IO) {
                coroutineScope { targets.map { show -> async { repository.catalog(show, force) } }.awaitAll() }
            }
            ensureActive()
            results.forEach { result ->
                failed = failed || result.failed
                result.catalog?.let { catalogs[it.show.id] = it }
            }
            attempted = (if (more) attempted else emptySet()) + targets.map { it.id }
            lastLoad = System.currentTimeMillis()
            publish(loading = false, failed = failed)
        }
    }
    private fun publish(loading: Boolean = _state.value.loading, failed: Boolean = _state.value.partialError) {
        val values = catalogs.values.toList()
        val continuing = repository.continuingEpisodes()
        val signals = repository.signals(values, continuing)
        _state.value = PodcastHomeState(
            PodcastHomeRanking.build(values, signals, seed, System.currentTimeMillis(), continuing), loading, failed,
            planned.any { it.id !in attempted }, signals.followed.size,
        )
    }

    fun togglePlay(ep: PodcastEpisodeUiModel, context: Context) {
        if (!ep.hasExactIdentity()) return
        val song = ep.toSongModel()
        val owned = SongPlayer.ownedPlaybackSnapshot()
        if (owned != null && PlaybackProgressStore.key(owned.song) == PlaybackProgressStore.key(song)) {
            if (currentSong.playingState.value) SongPlayer.pause() else SongPlayer.play()
            return
        }
        SongPlayer.resumeResolvedPlayback(song, null, context) {
            currentSong.commitPlayback(PlaybackQueueIdentity.select(song, listOf(song)))
        }
    }
}
