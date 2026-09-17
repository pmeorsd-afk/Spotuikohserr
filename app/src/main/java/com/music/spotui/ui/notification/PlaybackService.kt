package com.music.spotui.ui.notification

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.MainActivity
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.di.SpotifyWebPlayer
import com.music.spotui.ui.repository.AppRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import com.music.spotui.di.RepeatMode
import javax.inject.Inject

/**
 * Hosts a [MediaSession] over the app's single ExoPlayer (owned by [SongPlayer]).
 * This is what surfaces the track in the system notification center / lock screen
 * and routes the notification's transport controls (play/pause/seek/next/prev)
 * back into playback. Next/previous are wired to the in-app queue because our
 * player only ever holds one resolved stream at a time (YouTube URLs are resolved
 * lazily per track), so we advance the queue ourselves rather than via a playlist.
 *
 * It is a [MediaLibraryService] (not just a session service) so Android Auto can
 * browse the library — Liked Songs, Downloads, playlists and albums — and start
 * playback from the car.
 */
@AndroidEntryPoint
class PlaybackService : MediaLibraryService() {

    @Inject lateinit var currentSongState: CurrentSongState
    @Inject lateinit var repository: AppRepository

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Android Auto browse cache: mediaId → track, and mediaId → the list it was
    // browsed from (so playing a track queues its whole playlist/album).
    private val trackById = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()
    private val queueByTrackId = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
    private var webPlayer: WebMediaPlayer? = null
    private var showingWeb = false

    override fun onCreate() {
        super.onCreate()
        SongPlayer.ensureCreated(this)
        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
        val base = SongPlayer.exoPlayer ?: return

        // Tapping the notification opens the app (back on the Now Playing screen).
        val activityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 0, activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        webPlayer = WebMediaPlayer(mainLooper, currentSongState) { forward -> advanceManually(forward) }

        mediaSession = MediaLibrarySession.Builder(this, wrap(base), LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()

        // When a crossfade promotes a new ExoPlayer instance, re-bind the session to it
        // (runs on the main thread; setPlayer is the supported way to swap a session's player).
        SongPlayer.onPlayerSwapped = { newPlayer ->
            if (!showingWeb) mediaSession?.player = wrap(newPlayer)
        }

        // When stream resolution fails for a track, skip it automatically so the
        // queue keeps moving instead of going silent (issues 2 + 3).
        SongPlayer.onStreamFailed = { _ ->
            advanceManually(forward = true)
        }

        // Single Owner: Listen to ExoPlayer STATE_ENDED via token-guarded callback
        SongPlayer.setOnTrackEndedListener { token ->
            handleTrackEnded(token)
        }

        // As the hidden web player streams, keep the notification in sync and swap
        // the session between the web player (during web playback) and the ExoPlayer.
        SpotifyWebPlayer.onStateChanged = {
            syncSessionPlayer()
            if (showingWeb) {
                webPlayer?.refresh()
                // Reflect the web player's real play/pause state into the in-app UI
                // so the on-screen icon matches after the notification's pause.
                currentSongState.updatePlayingState(SpotifyWebPlayer.isPlaying)
            }
        }
    }

    /** Point the media session at whichever engine is currently producing audio. */
    private fun syncSessionPlayer() {
        val wantWeb = SongPlayer.webPlaybackActive()
        if (wantWeb == showingWeb) return
        showingWeb = wantWeb
        val session = mediaSession ?: return
        session.player = if (wantWeb) {
            webPlayer ?: return
        } else {
            wrap(SongPlayer.exoPlayer ?: return)
        }
    }

    /** Wrap an ExoPlayer so the media session routes next/previous to our in-app queue
     *  (the player only ever holds one resolved stream at a time). */
    private fun wrap(base: Player): ForwardingPlayer = object : ForwardingPlayer(base) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(COMMAND_SEEK_TO_NEXT)
                .add(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(COMMAND_SEEK_TO_PREVIOUS)
                .add(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(COMMAND_SEEK_FORWARD)
                .add(COMMAND_SEEK_BACK)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            COMMAND_SEEK_TO_NEXT, COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            COMMAND_SEEK_TO_PREVIOUS, COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            COMMAND_SEEK_FORWARD, COMMAND_SEEK_BACK -> true
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem() = true
        override fun hasPreviousMediaItem() = true
        override fun seekToNext() = advanceManually(forward = true)
        override fun seekToNextMediaItem() = advanceManually(forward = true)
        override fun seekToPrevious() = advanceManually(forward = false)
        override fun seekToPreviousMediaItem() = advanceManually(forward = false)
    }

    /** Manual user skip from notification / lock screen / Android Auto */
    private fun advanceManually(forward: Boolean) {
        val queue = currentSongState.queue.value
        if (queue.isEmpty()) return
        val curId = currentSongState.songId.value
        val cur = queue.indexOfFirst { it.id == curId }
            .let { if (it >= 0) it else currentSongState.songIndex.value }
            .coerceIn(0, queue.size - 1)
        val repeatMode = currentSongState.repeatMode.value
        if (forward) {
            if (cur < queue.size - 1) {
                playQueueIndex(queue, cur + 1)
            } else if (repeatMode == RepeatMode.ALL) {
                playQueueIndex(queue, 0)
            } else {
                serviceScope.launch(Dispatchers.IO) {
                    val fresh = fetchKosherRadioOrArtistFallback(queue, cur)
                    if (fresh.isNotEmpty()) {
                        val newQueue = currentSongState.queue.value + fresh
                        currentSongState.updateQueue(newQueue)
                        withContext(Dispatchers.Main) {
                            playQueueIndex(newQueue, queue.size)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            stopPlaybackCleanly()
                        }
                    }
                }
            }
        } else {
            if (cur > 0) {
                playQueueIndex(queue, cur - 1)
            } else if (repeatMode == RepeatMode.ALL) {
                playQueueIndex(queue, queue.size - 1)
            } else {
                SongPlayer.seekTo(0)
            }
        }
    }

    @Volatile private var transitionInProgress = false

    /**
     * SINGLE OWNER: The ONLY place in the app that decides what happens when a track finishes naturally.
     * Guarded against duplicate events and stale tokens from manual user skips.
     */
    private fun handleTrackEnded(token: String) {
        // Guard 1: Drop event if token is stale (user manually skipped or new track already started)
        if (token != SongPlayer.currentPlaybackToken) return

        // Guard 2: Drop if another transition is already in progress
        if (transitionInProgress) return
        transitionInProgress = true

        val queue = currentSongState.queue.value
        if (queue.isEmpty()) {
            stopPlaybackCleanly()
            transitionInProgress = false
            return
        }

        val curId = currentSongState.songId.value
        val curIdx = queue.indexOfFirst { it.id == curId }
            .let { if (it >= 0) it else currentSongState.songIndex.value }
            .coerceIn(0, queue.size - 1)

        val repeatMode = currentSongState.repeatMode.value

        // 1. Repeat ONE (Top priority - overrides all other rules)
        if (repeatMode == RepeatMode.ONE) {
            SongPlayer.seekTo(0)
            SongPlayer.play()
            currentSongState.updatePlayingState(true)
            transitionInProgress = false
            return
        }

        // 2. Next track exists in queue
        if (curIdx < queue.size - 1) {
            playQueueIndex(queue, curIdx + 1)
            transitionInProgress = false
            return
        }

        // 3. Repeat ALL at the end of queue -> loop back to index 0
        if (repeatMode == RepeatMode.ALL) {
            playQueueIndex(queue, 0)
            transitionInProgress = false
            return
        }

        // 4. End of queue and Repeat is OFF -> Try Autoplay (Kosher Radio -> Same-Artist Fallback)
        serviceScope.launch(Dispatchers.IO) {
            try {
                val fresh = fetchKosherRadioOrArtistFallback(queue, curIdx)
                if (fresh.isNotEmpty()) {
                    // Check token once more before modifying queue in case user acted during network call
                    if (token != SongPlayer.currentPlaybackToken) return@launch
                    val newQueue = currentSongState.queue.value + fresh
                    currentSongState.updateQueue(newQueue)
                    withContext(Dispatchers.Main) {
                        playQueueIndex(newQueue, queue.size)
                    }
                } else {
                    // No kosher tracks available -> STOP cleanly! Do NOT replay track 0!
                    withContext(Dispatchers.Main) {
                        stopPlaybackCleanly()
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Autoplay failed after track ended", e)
                withContext(Dispatchers.Main) {
                    stopPlaybackCleanly()
                }
            } finally {
                transitionInProgress = false
            }
        }
    }

    private fun playQueueIndex(queue: List<SongsModel>, index: Int) {
        if (index !in queue.indices) return
        val song = queue[index]
        currentSongState.updateSongState(
            song.coverUri, song.title, song.singer, true,
            song.id, index, currentSongState.album.value
        )
        SongPlayer.playSong(song.url, applicationContext)
    }

    private fun stopPlaybackCleanly() {
        SongPlayer.seekTo(0)
        SongPlayer.pause()
        currentSongState.updatePlayingState(false)
    }

    /**
     * Fetches kosher-approved radio recommendations seeded from the queue.
     * If empty, falls back to other tracks by the same artist.
     * Strictly filters out the current track and any tracks already present in the queue.
     */
    private suspend fun fetchKosherRadioOrArtistFallback(
        queue: List<SongsModel>,
        curIdx: Int
    ): List<SongsModel> {
        val currentSong = queue.getOrNull(curIdx) ?: return emptyList()
        val currentSpotifyId = currentSong.spotifyTrackId.ifBlank { null }
        val currentTitle = currentSong.title.trim().lowercase()

        val existingSpotifyIds = queue.mapNotNull { it.spotifyTrackId.ifBlank { null } }.toSet()
        val existingTitles = queue.map { it.title.trim().lowercase() }.toSet()

        fun filterValidNewTracks(candidates: List<SongsModel>): List<SongsModel> {
            return candidates.filter { candidate ->
                val candSpotifyId = candidate.spotifyTrackId.ifBlank { null }
                val candTitle = candidate.title.trim().lowercase()

                // Must not be the current track
                val isCurrent = (currentSpotifyId != null && candSpotifyId == currentSpotifyId) ||
                        (candTitle.isNotBlank() && candTitle == currentTitle)
                if (isCurrent) return@filter false

                // Must not already be in the queue
                val inQueue = (candSpotifyId != null && candSpotifyId in existingSpotifyIds) ||
                        (candTitle.isNotBlank() && candTitle in existingTitles)
                if (inQueue) return@filter false

                true
            }
        }

        // Step 1: Spotify Radio recommendations from seed track IDs
        val seeds = queue.takeLast(5)
            .mapNotNull { it.spotifyTrackId.ifBlank { null } }
            .distinct()

        if (seeds.isNotEmpty()) {
            try {
                val recs = repository.provideRecommendations(seeds)
                val freshRecs = filterValidNewTracks(recs)
                if (freshRecs.isNotEmpty()) {
                    return freshRecs
                }
            } catch (e: Exception) {
                Log.w("PlaybackService", "Radio recommendations fetch failed: ${e.message}")
            }
        }

        // Step 2: Same-Artist fallback (retrieves other approved songs by this artist)
        val artistName = currentSong.singer.trim()
        if (artistName.isNotBlank()) {
            try {
                var artistSongs = emptyList<SongsModel>()
                repository.provideArtistSongs(artistName).collect { response ->
                    if (response is Response.Success) {
                        artistSongs = response.data.orEmpty()
                    }
                }
                val freshArtistSongs = filterValidNewTracks(artistSongs)
                if (freshArtistSongs.isNotEmpty()) {
                    return freshArtistSongs.shuffled().take(10)
                }
            } catch (e: Exception) {
                Log.w("PlaybackService", "Same-artist fallback fetch failed: ${e.message}")
            }
        }

        return emptyList()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    // ── Android Auto browse tree ──────────────────────────────────────────

    private companion object {
        const val ROOT = "root"
        const val NODE_LIKED = "liked"
        const val NODE_DOWNLOADS = "downloads"
        const val NODE_PLAYLISTS = "playlists"
        const val NODE_ALBUMS = "albums"
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "spotui"), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(ImmutableList.copyOf(childrenOf(parentId)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(
            trackById[mediaId]?.let { LibraryResult.ofItem(playable(it), null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE),
        )

        // A browsed track was tapped in the car: queue the list it came from and
        // play through our own engine (streams are resolved lazily per track, so
        // we never hand the session a playlist of URIs).
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val requested = mediaItems.getOrNull(startIndex) ?: mediaItems.firstOrNull()
            val song = requested?.let { trackById[it.mediaId] }
            if (song != null) {
                val queue = queueByTrackId[requested.mediaId] ?: listOf(song)
                currentSongState.updateQueue(queue)
                val idx = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                currentSongState.updateSongState(
                    song.coverUri, song.title, song.singer, true,
                    song.id, idx, song.album,
                )
                SongPlayer.playSong(song.url, applicationContext)
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET),
            )
        }
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            folder(NODE_LIKED, "Liked Songs"),
            folder(NODE_DOWNLOADS, "Downloads"),
            folder(NODE_PLAYLISTS, "Playlists"),
            folder(NODE_ALBUMS, "Albums"),
        )
        parentId == NODE_LIKED ->
            registerTracks(NODE_LIKED, lastSuccess(repository.provideLikedSongs()).orEmpty())
        parentId == NODE_DOWNLOADS ->
            registerTracks(
                NODE_DOWNLOADS,
                com.music.spotui.data.preferences.getDownloadedSongs(applicationContext),
            )
        parentId == NODE_PLAYLISTS ->
            libraryEntries().filter {
                it.isPlaylist && it.spotifyId != Api.LIKED_SONGS_ID && it.spotifyId != Api.DOWNLOADS_ID
            }.map { folder("playlist/${it.spotifyId}", it.name, it.coverUri) }
        parentId == NODE_ALBUMS ->
            libraryEntries().filter { !it.isPlaylist }.map {
                folder(
                    "album/${android.net.Uri.encode(it.name)}/${android.net.Uri.encode(it.artists)}",
                    it.name,
                    it.coverUri,
                )
            }
        parentId.startsWith("playlist/") -> {
            val songs = lastSuccess(repository.providePlaylistSongs(parentId.removePrefix("playlist/")))
            registerTracks(parentId, songs.orEmpty())
        }
        parentId.startsWith("album/") -> {
            val parts = parentId.removePrefix("album/").split('/')
            val name = android.net.Uri.decode(parts.getOrElse(0) { "" })
            val artist = android.net.Uri.decode(parts.getOrElse(1) { "" })
            registerTracks(parentId, lastSuccess(repository.provideAlbumSongs(name, artist)).orEmpty())
        }
        else -> emptyList()
    }

    private suspend fun libraryEntries() =
        lastSuccess(repository.provideLibrary()).orEmpty()

    /** Runs a paged/cached response flow to completion and keeps the final data. */
    private suspend fun <T> lastSuccess(flow: Flow<Response<T>>): T? =
        runCatching { flow.toList() }.getOrNull()
            ?.filterIsInstance<Response.Success<T>>()
            ?.lastOrNull()?.data

    private fun registerTracks(parentId: String, songs: List<SongsModel>): List<MediaItem> {
        songs.forEach { song ->
            trackById["song/${song.id}"] = song
            queueByTrackId["song/${song.id}"] = songs
        }
        return songs.map { playable(it) }
    }

    private fun folder(id: String, title: String, coverUri: String = ""): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build()

    private fun playable(song: SongsModel): MediaItem =
        MediaItem.Builder()
            .setMediaId("song/${song.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.singer)
                    .setAlbumTitle(song.album)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        serviceScope.launch {
            try {
                f.set(block())
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        return f
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Stop playback + tear the service down when the app is swiped away.
        SongPlayer.pause()
        stopSelf()
    }

    override fun onDestroy() {
        SongPlayer.setOnTrackEndedListener(null)
        serviceScope.cancel()
        SongPlayer.onPlayerSwapped = null
        SongPlayer.onStreamFailed = null
        SpotifyWebPlayer.onStateChanged = null
        webPlayer?.release()
        webPlayer = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
