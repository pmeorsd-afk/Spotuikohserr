package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.podcast.*
import com.music.spotui.data.preferences.observeFollowedShows
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

internal data class NewEpisodesState(
    val feed: NewEpisodesFeed = NewEpisodesFeed(), val loading: Boolean = true,
    val partialError: Boolean = false, val followedCount: Int = 0,
    val undo: EpisodeCompletionUndo? = null, val actionError: String? = null,
)

@HiltViewModel
class NewEpisodesViewModel @Inject constructor(
    private val repository: NewEpisodesRepository,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(NewEpisodesState())
    internal val state = _state.asStateFlow()
    private var shows = emptyList<PodcastModel>()
    private val catalogs = mutableMapOf<String, PodcastCatalog>()
    private var active = false
    private var refreshJob: Job? = null
    private var publishJob: Job? = null
    private var undoJob: Job? = null
    private val completing = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            observeFollowedShows(context).collect { followed ->
                shows = followed.map { PodcastModel(it.showId, it.name, "", it.imageUrl) }.distinctBy { it.id }
                val ids = shows.map { it.id }.toSet()
                catalogs.keys.retainAll(ids)
                val cached = withContext(Dispatchers.IO) { repository.cached(shows).also { repository.pruneCache(shows) } }
                cached.forEach { if (it.show.id !in catalogs) catalogs[it.show.id] = it }
                publish()
                if (active) refresh(restart = true)
            }
        }
        viewModelScope.launch { repository.observeListeningChanges().conflate().collect { publish() } }
    }

    internal fun enter() { active = true; publish(); refresh() }
    internal fun leave() { active = false; refreshJob?.cancel(); refreshJob = null; _state.update { it.copy(loading = false) } }
    internal fun refresh(force: Boolean = false, restart: Boolean = false) {
        // A large follow list may take longer than the timer interval. Do not starve its tail.
        if (!force && !restart && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        val targets = shows.toList()
        refreshJob = viewModelScope.launch {
            _state.update { it.copy(loading = targets.isNotEmpty(), partialError = false) }
            // Two feeds at a time; every followed show is visited, without discovery/recommendation limits.
            for (batch in targets.chunked(2)) {
                val results = coroutineScope { batch.map { async { repository.catalog(it, force) } }.awaitAll() }
                ensureActive()
                results.forEach { result ->
                    result.catalog?.takeIf { c -> shows.any { it.id == c.show.id } }?.let { catalogs[it.show.id] = it }
                    if (result.failed) _state.update { it.copy(partialError = true) }
                }
                publish()
            }
            _state.update { it.copy(loading = false) }
            publish()
        }
    }

    private fun publish() {
        publishJob?.cancel()
        val snapshot = catalogs.values.toList()
        val ids = shows.map { it.id }.toSet()
        publishJob = viewModelScope.launch {
            val feed = withContext(Dispatchers.IO) { repository.feed(snapshot, ids) }
            _state.update { it.copy(feed = feed, followedCount = ids.size) }
        }
    }

    internal fun complete(ep: PodcastEpisodeUiModel) {
        val key = NewEpisodesProjection.key(ep) ?: return
        if (!completing.add(key)) return
        viewModelScope.launch {
            try {
                val undo = withContext(Dispatchers.IO) { repository.completions.complete(ep) }
                if (undo == null) { _state.update { it.copy(actionError = "לא ניתן לסמן את הפרק. נסו שוב.") }; return@launch }
                undoJob?.cancel()
                _state.update { it.copy(undo = undo, actionError = null) }
                publish()
                undoJob = viewModelScope.launch {
                    delay(EPISODE_UNDO_DURATION_MS)
                    _state.update { if (it.undo == undo) it.copy(undo = null) else it }
                }
            } finally { completing.remove(key) }
        }
    }

    internal fun undo(undo: EpisodeCompletionUndo) {
        if (_state.value.undo != undo) return
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { repository.completions.undo(undo) }
            _state.update { if (it.undo == undo) it.copy(undo = null,
                actionError = if (restored) null else "לא ניתן לבטל את הפעולה.") else it }
            publish()
        }
    }
    internal fun clearError() { _state.update { it.copy(actionError = null) } }
}
