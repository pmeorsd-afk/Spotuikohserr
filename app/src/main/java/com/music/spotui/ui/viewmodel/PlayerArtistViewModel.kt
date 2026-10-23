package com.music.spotui.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

/** Player-scoped loading; no general artist feed, tracks, or followed-artist requests. */
@HiltViewModel
class PlayerArtistViewModel @Inject constructor(repository: AppRepository) : ViewModel() {
    private val loader = PlayerArtistLoader(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.IO)) {
        repository.provideArtistOverview(it)
    }
    val overview = loader.overview
    fun load(artist: String?) = loader.load(artist)
}

data class PlayerArtistState(val artist: String?, val response: Response<ArtistOverviewModel>)

internal class PlayerArtistLoader(
    private val scope: CoroutineScope,
    private val request: suspend (String) -> Flow<Response<ArtistOverviewModel>>,
) {
    private val state = MutableStateFlow(PlayerArtistState(null, Response.Loading()))
    val overview: StateFlow<PlayerArtistState> = state.asStateFlow()
    private var key: String? = null
    private var generation = 0L
    private var job: Job? = null

    @Synchronized fun load(artist: String?) {
        val next = artist?.trim()?.takeIf { it.isNotEmpty() }
        if (key == next) return
        key = next
        val ticket = ++generation
        job?.cancel()
        state.value = PlayerArtistState(next, Response.Loading())
        if (next == null) return
        job = scope.launch {
            try {
                request(next).collect { result -> publish(ticket, result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                publish(ticket, Response.Error(error.message ?: "Artist information unavailable"))
            }
        }
    }

    @Synchronized private fun publish(ticket: Long, result: Response<ArtistOverviewModel>) {
        if (ticket == generation) state.value = PlayerArtistState(key, result)
    }
}
