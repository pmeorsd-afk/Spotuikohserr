package com.music.spotui.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.podcast.PodcastHomeRepository
import com.music.spotui.data.podcast.podcastData
import com.music.spotui.data.preferences.applyPodcastFollowEdits
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class PodcastChooserState(
    val shows: List<PodcastModel> = emptyList(),
    val selected: Set<String> = emptySet(),
    val query: String = "",
    val category: String? = null,
    val loading: Boolean = false,
    val error: Boolean = false,
    val hasMore: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class PodcastChooserViewModel @Inject constructor(
    private val repository: AppRepository,
    private val homeRepository: PodcastHomeRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val followedShows = repository.getFollowedPodcasts().map { PodcastModel(it.showId, it.name, "", it.imageUrl) }
    private val initial = followedShows.map { it.id }.toSet()
    private val edits = mutableMapOf<String, Pair<PodcastModel, Boolean>>()
    private val _state = MutableStateFlow(PodcastChooserState(shows = followedShows, selected = initial))
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var generation = 0L
    private var offset = 0
    init { load() }

    fun search(query: String) {
        _state.update { it.copy(query = query, category = null, shows = emptyList(), hasMore = false) }
        offset = 0
        load(debounce = true)
    }
    fun category(id: String?) {
        _state.update { it.copy(category = id, query = "", shows = emptyList(), hasMore = false) }
        offset = 0
        load()
    }
    fun retry() = load()
    fun loadMore() {
        if (_state.value.loading || !_state.value.hasMore) return
        load(more = true)
    }
    private fun load(debounce: Boolean = false, more: Boolean = false) {
        request?.cancel()
        val epoch = ++generation
        val input = _state.value
        val requestedOffset = if (more) offset + 20 else 0
        _state.update { it.copy(loading = true, error = false) }
        request = viewModelScope.launch {
            if (debounce) delay(350)
            try {
                val found = withContext(Dispatchers.IO) {
                    withTimeout(25_000) {
                        when {
                            input.query.isNotBlank() -> repository.searchPodcastShows(input.query.trim()).podcastData().shows
                            input.category != null -> repository.providePodcastCategoryShows(input.category, requestedOffset).podcastData()
                            else -> homeRepository.discover()
                        }
                    }
                }.filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
                if (epoch != generation) return@launch
                val previous = if (more) _state.value.shows
                    else if (input.query.isBlank() && input.category == null) followedShows else emptyList()
                val newItems = found.filter { show -> previous.none { it.id == show.id } }
                offset = requestedOffset
                _state.update { it.copy(shows = previous + newItems, loading = false, error = false,
                    hasMore = input.category != null && newItems.isNotEmpty()) }
            } catch (_: TimeoutCancellationException) {
                if (epoch == generation) _state.update { it.copy(loading = false, error = true) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) {
                if (epoch == generation) _state.update { it.copy(loading = false, error = true) }
            }
        }
    }
    fun toggle(show: PodcastModel) {
        if (show.id.isBlank() || show.name.isBlank()) return
        val selected = show.id !in _state.value.selected
        if (selected == (show.id in initial)) edits.remove(show.id) else edits[show.id] = show to selected
        _state.update { it.copy(selected = if (selected) it.selected + show.id else it.selected - show.id) }
    }
    fun save() {
        runCatching { applyPodcastFollowEdits(context, edits) }.onSuccess {
            _state.update { it.copy(saved = true) }
        }.onFailure { _state.update { it.copy(error = true) } }
    }
}
