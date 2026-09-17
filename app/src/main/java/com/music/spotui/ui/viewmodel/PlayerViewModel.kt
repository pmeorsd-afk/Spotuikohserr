package com.music.spotui.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.navigation.artistRoute
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.music.spotui.data.local.LocalListeningTracker
import com.music.spotui.data.home.HomeFeedEngine
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val currentSongState: CurrentSongState,
    private val repository: AppRepository,
    private val listeningTracker: LocalListeningTracker,
    private val homeFeedEngine: HomeFeedEngine
) : ViewModel(){

    val currentSongTitle: State<String> get() = currentSongState.title
    val currentSongSinger: State<String> get() = currentSongState.singer
    val currentSongCoverUri: State<String> get() = currentSongState.coverUri
    val currentSongPlayingState: State<Boolean> get() = currentSongState.playingState
    val currentSongIndex : State<Int> get() = currentSongState.songIndex
    val currentSongAlbum : State<String> get() = currentSongState.album

    val currentSongId : State<Int> get() = currentSongState.songId

    val queue : State<List<SongsModel>> get() = currentSongState.queue

    fun updateQueue(songs: List<SongsModel>) = currentSongState.updateQueue(songs)

    /** Insert a track right after the one currently playing ("Play next"). */
    fun playNext(song: SongsModel) {
        val q = currentSongState.queue.value.toMutableList()
        q.removeAll { it.id == song.id }
        val cur = q.indexOfFirst { it.id == currentSongId.value }
        val insertAt = (if (cur >= 0) cur + 1 else 0).coerceAtMost(q.size)
        q.add(insertAt, song)
        currentSongState.updateQueue(q)
    }

    /** Append a track to the end of the queue ("Add to queue"). */
    fun addToQueue(song: SongsModel) {
        val q = currentSongState.queue.value
        if (q.any { it.id == song.id }) return
        currentSongState.updateQueue(q + song)
    }

    /** Reorder the queue, moving the track at [from] to [to] (both absolute indices). */
    fun moveQueueItem(from: Int, to: Int) {
        val q = currentSongState.queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        q.add(to, q.removeAt(from))
        currentSongState.updateQueue(q)
    }

    /** Remove a track from the queue entirely. */
    fun removeFromQueue(song: SongsModel) {
        val q = currentSongState.queue.value.toMutableList()
        q.removeAll { it.id == song.id }
        currentSongState.updateQueue(q)
    }


    val shuffleState = currentSongState.shuffle
    val repeatState = currentSongState.repeat
    val repeatMode = currentSongState.repeatMode
    val likeState = currentSongState.likeState


    private val _songs : MutableStateFlow<Response<List<SongsModel>>> = MutableStateFlow(Response.Loading())
    val songs : StateFlow<Response<List<SongsModel>>> = _songs

    val playingArtist by mutableStateOf(currentSongSinger.value)

    private var listeningSongKey: String = ""
    private var thirtySecondReported = false
    private var completionReported = false

    init {
        fetchSongs()
        SongPlayer.setPositionListener { positionMs, durationMs ->
            val q = currentSongState.queue.value
            val song = q.firstOrNull { it.id == currentSongState.songId.value } ?: return@setPositionListener
            val key = song.spotifyTrackId.trim().ifBlank { song.url.trim() }
            if (key.isBlank()) return@setPositionListener

            if (listeningSongKey != key) {
                listeningSongKey = key
                thirtySecondReported = false
                completionReported = false
                listeningTracker.onSongStarted(song)
            }

            if (!thirtySecondReported && positionMs >= 30_000L) {
                thirtySecondReported = true
                listeningTracker.recordPlay(song)
            }

            if (!completionReported && durationMs > 0L && positionMs >= durationMs * 0.75f) {
                completionReported = true
                listeningTracker.recordCompletion(song)
            }
        }
    }


    //val songsResponse = (songs.value as Response.Success).data

    // Resolve where we currently are in the queue. The stored index can be stale
    // (e.g. queue swapped out), so match by song id first and fall back to the index.
    private fun currentPositionIn(queueSongs: List<SongsModel>): Int {
        val byId = queueSongs.indexOfFirst { it.id == currentSongId.value }
        if (byId >= 0) return byId
        return currentSongIndex.value.coerceIn(0, queueSongs.size - 1)
    }

    // ── Autoplay radio (Spotify recommendations) ──
    // When the queue nears its end, fetch Spotify-recommended tracks seeded by what's
    // playing and append them, so music keeps going like Spotify's autoplay instead of
    // looping the same list. On by default; can be turned off via [autoplayRadioEnabled].
    var autoplayRadioEnabled = true
    @Volatile private var radioLoading = false

    private fun maybeExtendRadio(queueSongs: List<SongsModel>, cur: Int) {
        if (!autoplayRadioEnabled || radioLoading) return
        // Only start fetching when we're within one track of the end.
        if (cur < queueSongs.size - 2) return
        val seeds = queueSongs.takeLast(5)
            .mapNotNull { it.spotifyTrackId.ifBlank { null } }
            .distinct()
        if (seeds.isEmpty()) return
        radioLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recs = repository.provideRecommendations(seeds)
                val existing = currentSongState.queue.value
                val existingIds = existing.map { it.id }.toSet()
                val fresh = recs.filter { it.id !in existingIds }
                if (fresh.isNotEmpty()) currentSongState.updateQueue(existing + fresh)
            } finally {
                radioLoading = false
            }
        }
    }

    // Function to play the next song in the album (Manual user skip)
    fun playNextSongs(queueSongs : List<SongsModel>, context: Context) {
        if (queueSongs.isEmpty()) return
        // A crossfade is already advancing the queue itself — don't double-skip.
        if (SongPlayer.isCrossfadeActive()) return
        val cur = currentPositionIn(queueSongs)
        val mode = repeatMode.value
        // Top up the queue with Spotify recommendations as we approach the end.
        maybeExtendRadio(queueSongs, cur)
        if (cur >= queueSongs.size - 1) {
            if (mode == com.music.spotui.di.RepeatMode.ALL) {
                val nextSong = queueSongs[0]
                updateSongState(nextSong.coverUri, nextSong.title, nextSong.singer, true, nextSong.id, 0, nextSong.album)
                SongPlayer.playSong(nextSong.url, context)
            } else if (autoplayRadioEnabled) {
                continueIntoRadio(queueSongs, context)
            } else {
                SongPlayer.seekTo(0)
                SongPlayer.pause()
                updateSongState(queueSongs[cur].coverUri, queueSongs[cur].title, queueSongs[cur].singer, false, queueSongs[cur].id, cur, queueSongs[cur].album)
            }
            return
        }
        val nextIdx = cur + 1
        val nextSong = queueSongs[nextIdx]
        updateSongState(
            nextSong.coverUri,
            nextSong.title,
            nextSong.singer,
            true,
            nextSong.id,
            nextIdx,
            nextSong.album
        )
        SongPlayer.playSong(nextSong.url, context)
    }

    /**
     * Opens the artist page for a track: resolves the EXACT artist (name + id)
     * from the track's Spotify id when available, so a display name like "RAM"
     * can't fuzzy-match to "Rammstein". Falls back to the display name.
     */
    // Spotify Canvas (looping video) URL for the current track, or null. Fetched
    // per track; null means no canvas / not resolved yet.
    private val _canvasUrl = mutableStateOf<String?>(null)
    val canvasUrl: State<String?> get() = _canvasUrl
    @Volatile private var canvasRequestId: String = ""

    fun loadCanvas(trackId: String) {
        _canvasUrl.value = null
    }

    fun goToArtist(trackId: String, fallbackName: String, navigate: (route: String) -> Unit) {
        viewModelScope.launch {
            val route = withContext(Dispatchers.IO) {
                val artist = if (trackId.isNotBlank())
                    com.metrolist.spotify.Spotify.track(trackId).getOrNull()?.artists?.firstOrNull()
                else null
                artistRoute(
                    artist?.name?.ifBlank { null } ?: fallbackName.substringBefore(",").trim(),
                    artist?.id.orEmpty(),
                )
            }
            navigate(route)
        }
    }

    @Volatile private var awaitingRadioContinue = false

    /** Attempts to fetch kosher radio recommendations or same-artist tracks to extend the queue.
     *  If none are available and Repeat is OFF, stops cleanly instead of infinitely looping. */
    private fun continueIntoRadio(queueSongs: List<SongsModel>, context: Context) {
        if (awaitingRadioContinue) return
        awaitingRadioContinue = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cur = currentPositionIn(queueSongs)
                val currentSong = queueSongs.getOrNull(cur) ?: queueSongs.firstOrNull() ?: return@launch
                val currentSpotifyId = currentSong.spotifyTrackId.ifBlank { null }
                val currentTitle = currentSong.title.trim().lowercase()

                val existingSpotifyIds = queueSongs.mapNotNull { it.spotifyTrackId.ifBlank { null } }.toSet()
                val existingTitles = queueSongs.map { it.title.trim().lowercase() }.toSet()

                fun filterValidNewTracks(candidates: List<SongsModel>): List<SongsModel> {
                    return candidates.filter { candidate ->
                        val candSpotifyId = candidate.spotifyTrackId.ifBlank { null }
                        val candTitle = candidate.title.trim().lowercase()

                        // Must not be current track
                        val isCurrent = (currentSpotifyId != null && candSpotifyId == currentSpotifyId) ||
                                (candTitle.isNotBlank() && candTitle == currentTitle)
                        if (isCurrent) return@filter false

                        // Must not already be in queue
                        val inQueue = (candSpotifyId != null && candSpotifyId in existingSpotifyIds) ||
                                (candTitle.isNotBlank() && candTitle in existingTitles)
                        if (inQueue) return@filter false

                        true
                    }
                }

                // Step 1: Spotify Radio recommendations from seed track IDs
                val seeds = queueSongs.takeLast(5)
                    .mapNotNull { it.spotifyTrackId.ifBlank { null } }
                    .distinct()

                var freshTracks = emptyList<SongsModel>()
                if (seeds.isNotEmpty()) {
                    try {
                        val recs = repository.provideRecommendations(seeds)
                        freshTracks = filterValidNewTracks(recs)
                    } catch (e: Exception) {
                        Log.w("PlayerViewModel", "Radio recommendations fetch failed", e)
                    }
                }

                // Step 2: Same-Artist fallback
                if (freshTracks.isEmpty() && currentSong.singer.isNotBlank()) {
                    try {
                        var artistSongs = emptyList<SongsModel>()
                        repository.provideArtistSongs(currentSong.singer.trim()).collect { response ->
                            if (response is Response.Success) {
                                artistSongs = response.data.orEmpty()
                            }
                        }
                        freshTracks = filterValidNewTracks(artistSongs).shuffled().take(10)
                    } catch (e: Exception) {
                        Log.w("PlayerViewModel", "Same-artist fallback fetch failed", e)
                    }
                }

                if (freshTracks.isNotEmpty()) {
                    val newQueue = currentSongState.queue.value + freshTracks
                    currentSongState.updateQueue(newQueue)
                    val next = newQueue[queueSongs.size]
                    withContext(Dispatchers.Main) {
                        updateSongState(next.coverUri, next.title, next.singer, true, next.id, queueSongs.size, next.album)
                        SongPlayer.playSong(next.url, context)
                    }
                } else {
                    // No kosher tracks available: if repeat is on, loop, else STOP cleanly!
                    val mode = repeatMode.value
                    if (mode == com.music.spotui.di.RepeatMode.ALL || mode == com.music.spotui.di.RepeatMode.ONE) {
                        val first = queueSongs.first()
                        withContext(Dispatchers.Main) {
                            updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, first.album)
                            SongPlayer.playSong(first.url, context)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            SongPlayer.seekTo(0)
                            SongPlayer.pause()
                            updateSongState(currentSong.coverUri, currentSong.title, currentSong.singer, false, currentSong.id, cur, currentSong.album)
                        }
                    }
                }
            } finally {
                awaitingRadioContinue = false
            }
        }
    }

    /**
     * Play the track at an absolute [index] in [queueSongs]. Used by the now-playing
     * swipe pager, where the artwork follows the finger and settles on a chosen page.
     * No-op if [index] is already the current track (prevents a feedback replay when
     * the pager is merely snapping to reflect an external track change).
     */
    fun playSongAt(queueSongs: List<SongsModel>, index: Int, context: Context) {
        if (index !in queueSongs.indices) return
        val song = queueSongs[index]
        if (song.id == currentSongId.value) return
        maybeExtendRadio(queueSongs, index)
        updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
        SongPlayer.playSong(song.url, context)
    }

    // Function to play the previous song in the album
    fun playPreviousSong(queueSongs : List<SongsModel>, context: Context) {
        if (queueSongs.isEmpty()) return
        val cur = currentPositionIn(queueSongs)
        val mode = repeatMode.value
        val prevIdx = if (cur > 0) {
            cur - 1
        } else if (mode == com.music.spotui.di.RepeatMode.ALL) {
            queueSongs.size - 1
        } else {
            0
        }
        val previousSong = queueSongs[prevIdx]
        updateSongState(previousSong.coverUri, previousSong.title, previousSong.singer, true, previousSong.id, prevIdx, previousSong.album)
        SongPlayer.playSong(previousSong.url, context)
    }

    private fun fetchSongs() = viewModelScope.launch(Dispatchers.IO) {

        repository.provideSongs().collect { songs ->
            _songs.value = songs as Response<List<SongsModel>>

        }
    }
    fun formatDuration(durationMillis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMillis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMillis) - TimeUnit.MINUTES.toSeconds(minutes)
        return String.format("%01d:%02d", minutes, seconds)
    }
    fun updateSongState(coverUri: String, title: String, singer: String, playingState: Boolean, songId : Int, songIndex : Int = 0, album : String = "") {
        currentSongState.updateSongState(coverUri, title, singer, playingState, songId, songIndex, album)
    }

    fun updateShuffleState(shuffleState : Boolean){
        currentSongState.updateShuffleState(shuffleState)
    }
    fun updateRepeatState(repeatState : Boolean){
        currentSongState.updateRepeatState(repeatState)
    }
    fun toggleRepeatMode(): com.music.spotui.di.RepeatMode {
        return currentSongState.toggleRepeatMode()
    }
    fun updateLikeState(likeState : Boolean){
        currentSongState.updateLikeState(likeState)
    }
}
