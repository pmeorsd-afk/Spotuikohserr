package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.LocalListeningTracker
import com.music.spotui.data.podcast.podcastData
import com.music.spotui.ui.collections.CollectionCandidate
import com.music.spotui.ui.collections.CollectionRecommendations
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

internal data class CollectionRecommendationState(val items: List<HomeItem.Playlist> = emptyList(), val loading: Boolean = false, val failed: Boolean = false)

@HiltViewModel
class CollectionRecommendationsViewModel @Inject constructor(private val repository: AppRepository, private val tracker: LocalListeningTracker) : ViewModel() {
    private val _state = MutableStateFlow(CollectionRecommendationState())
    internal val state = _state.asStateFlow()
    private var key: Pair<String, List<String>>? = null
    private var job: Job? = null
    internal fun load(id: String, songs: List<SongsModel>, force: Boolean = false) {
        val seeds = CollectionRecommendations.seeds(songs)
        val nextKey = id to seeds
        if (!force && key == nextKey) return
        key = nextKey
        job?.cancel()
        if (seeds.isEmpty()) { _state.value = CollectionRecommendationState(); return }
        _state.value = CollectionRecommendationState(loading = true)
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                var failed = false
                val candidates = mutableListOf<CollectionCandidate>()
                for (seed in seeds) {
                    try {
                        val entries = withTimeout(20_000) { repository.provideCategoryPlaylists(seed).podcastData() }
                        entries.filter { it.isPlaylist }.forEachIndexed { index, entry ->
                            candidates += CollectionCandidate(HomeItem.Playlist(entry.name, entry.coverUri, entry.subtitle, entry.spotifyId), seed, index)
                        }
                    } catch (_: TimeoutCancellationException) { failed = true }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { failed = true }
                }
                CollectionRecommendationState(CollectionRecommendations.rank(id, seeds, candidates,
                    tracker.topArtists(12).map { it.artistName }.toSet()), failed = failed)
            }
            ensureActive()
            _state.value = result
        }
    }
}
