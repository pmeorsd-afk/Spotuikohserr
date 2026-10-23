package com.music.spotui.ui.notification

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionCommands
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import androidx.compose.runtime.snapshotFlow
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.MainActivity
import com.music.spotui.playback.ExternalMediaIdentityResolver
import com.music.spotui.data.api.Api
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.MediaType
import com.music.spotui.playback.PlaybackSource
import com.music.spotui.playback.PlaybackOwnership
import com.music.spotui.playback.ExternalPlaybackRequests
import com.music.spotui.playback.PlaybackQueueIdentity
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.WazeResumeResolver
import com.music.spotui.playback.ResumeMediaButtonPolicy
import com.music.spotui.playback.ResumptionDispatchGate
import com.music.spotui.playback.PreparedResumption
import com.music.spotui.playback.awaitResumptionPreparation
import com.metrolist.spotify.Spotify
import com.music.spotui.data.local.LocalListeningTracker
import android.net.Uri
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import android.os.Bundle
import com.music.spotui.R
import com.music.spotui.playback.AutoMediaCatalog
import com.music.spotui.playback.AutoBrowseCatalog
import com.music.spotui.playback.AutoBrowseContent
import com.music.spotui.playback.AutoSearchCache
import com.music.spotui.playback.AutoBrowseWindow
import com.music.spotui.playback.AutoSearchHistory
import com.music.spotui.playback.AutoRadioQueue
import com.music.spotui.playback.AutoPlaybackControls
import com.music.spotui.playback.AutoPlaybackActions
import com.music.spotui.playback.AutoArtistPlayback
import com.music.spotui.playback.AutoHomeContent
import com.music.spotui.playback.AutoHomeFeedCache
import com.music.spotui.playback.PlaybackProgressStore
import com.music.spotui.data.preferences.addLikedSong
import com.music.spotui.data.preferences.getLocallyLikedSongs
import com.music.spotui.data.preferences.isSongLiked
import com.music.spotui.data.preferences.likedSongsRevision
import com.music.spotui.data.preferences.getListeningHistory
import com.music.spotui.data.preferences.observeListeningHistory
import com.music.spotui.data.api.SpotifySync
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SearchResults
import com.music.spotui.data.home.HomeFeedEngine
import com.music.spotui.util.KosherWhitelistManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    @Inject lateinit var listeningTracker: LocalListeningTracker
    @Inject lateinit var homeFeedEngine: HomeFeedEngine

    private var mediaSession: MediaLibrarySession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val carBrowseMutex = Mutex()
    @Volatile private var carHomeRequested = false
    private val carArtworkParents = linkedMapOf<String, Int>()
    private var lastCarArtworkSync: Long? = null
    private val carHomeFeed by lazy {
        AutoHomeFeedCache(serviceScope, { android.os.SystemClock.elapsedRealtime() }) {
            withContext(Dispatchers.IO) {
                val primary = try {
                    withTimeoutOrNull(10_000) { homeFeedEngine.getHomeFeed() }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { null }
                // The engine keeps the raw Spotify response separately from its fallback shelves.
                // A disk-only phone cache may not have populated it yet.
                val raw = Api.home ?: try {
                    withTimeoutOrNull(2_500) { lastSuccess(repository.provideHomeFeed()) }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) { null }
                val feed = primary?.takeIf { it.sections.isNotEmpty() || it.topGrid.isNotEmpty() } ?: raw
                feed?.let { AutoHomeContent.Snapshot(it, AutoHomeContent.recommendedStations(raw)) }
            }
        }
    }

    // Android Auto browse cache: mediaId → track, and mediaId → the list it was
    // browsed from (so playing a track queues its whole playlist/album).
    private val trackById = java.util.concurrent.ConcurrentHashMap<String, SongsModel>()
    private val queueByTrackId = java.util.concurrent.ConcurrentHashMap<String, List<SongsModel>>()
    private val browseFolders = java.util.concurrent.ConcurrentHashMap<String, MediaItem>()
    private val collectionById = java.util.concurrent.ConcurrentHashMap<String, AutoBrowseCatalog.Entry>()
    private val carCatalog by lazy {
        val prefs = getSharedPreferences("AutoMediaCatalog", MODE_PRIVATE)
        AutoMediaCatalog({ prefs.getString("entries", null) }, { prefs.edit().putString("entries", it).apply() })
    }
    private val carFolders by lazy {
        val prefs = getSharedPreferences("AutoBrowseCatalog", MODE_PRIVATE)
        AutoBrowseCatalog({ prefs.getString("entries", null) }, { prefs.edit().putString("entries", it).apply() })
    }
    // Presentation artwork must never evict a playable token advertised by a browse/search page.
    private val nowPlayingArtwork by lazy {
        val prefs = getSharedPreferences("AutoNowPlayingArtwork", MODE_PRIVATE)
        AutoMediaCatalog({ prefs.getString("entries", null) },
            { prefs.edit().putString("entries", it).apply() }, capacity = 8)
    }
    private val carSearchHistory by lazy {
        val prefs = getSharedPreferences("AutoSearchHistory", MODE_PRIVATE)
        AutoSearchHistory({ prefs.getString("queries", null) },
            { prefs.edit().putString("queries", it).apply() })
    }
    private val onlineSearch by lazy {
        AutoSearchCache(serviceScope, { android.os.SystemClock.elapsedRealtime() }) { query ->
            withContext(Dispatchers.IO) {
                val history = carSearchHistory.matching(query)
                val local = searchCarLibrary(query)
                val remote = try {
                    lastSuccess(repository.searchCarCatalog(query)) ?: SearchResults()
                } catch (e: java.io.IOException) {
                    if (local.isEmpty()) throw e
                    // Preserve offline search of known local items, never synthesize a remote hit.
                    SearchResults()
                }
                CarSearchSnapshot(remote.copy(songs = remote.songs + local.filterNot { it.isPodcast() },
                    episodes = remote.episodes + local.filter { it.isPodcast() }), history)
            }
        }
    }
    private data class CarSearchSnapshot(val results: SearchResults, val history: List<AutoSearchHistory.Entry>)
    private val pendingResolvedTracks = java.util.concurrent.ConcurrentHashMap<String, PlaybackOwnership.Ticket>()
    private val externalRequests = ExternalPlaybackRequests(
        currentToken = { SongPlayer.currentPlaybackToken },
        takeOver = { song, id -> SongPlayer.beginExternalPlaybackTakeover(song, id, applicationContext) },
        resolveSource = { song -> SongPlayer.episodeResolver.resolve(song, applicationContext) },
    )
    private var webPlayer: WebMediaPlayer? = null
    private var showingWeb = false
    private var sessionPlayer: AutoSessionPlayer? = null
    private var sessionMetadata: MediaMetadata? = null
    private var sessionMetadataToken: String? = null
    private var metadataJob: Job? = null
    private var metadataPendingToken: String? = null
    private var controlsSignature: String? = null
    private var radioLoading = false
    private var radioJob: Job? = null
    private var radioTrackIds = emptySet<String>()
    private val carConnectionType by lazy { CarConnection(applicationContext).type }
    private var carConnected = false
    private var notificationCommands: Pair<SessionCommands, Player.Commands>? = null
    private val carConnectionObserver = Observer<Int> { type ->
        val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION || type == CarConnection.CONNECTION_TYPE_NATIVE
        if (carConnected != connected) {
            carConnected = connected
            updateFrameworkCarControls()
            refreshCarPresentation()
        }
    }
    private var resumptionFuture: ListenableFuture<MediaSession.MediaItemsWithStartPosition>? = null
    private var resumptionToken: String? = null
    private val preparedResumption = PreparedResumption<MediaItem>()
    private val resumptionDispatch = ResumptionDispatchGate()
    private var resumptionStartup = false
    private var lastServiceStartId = 0
    private var resumptionWatchdog: Job? = null
    private var coldButtonGeneration = 0

    private val mediaItemListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refreshCarPresentation()
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem ?: return
            val ticket = mediaItem.localConfiguration?.tag as? PlaybackOwnership.Ticket
                ?: pendingResolvedTracks[mediaItem.mediaId]
                ?: return
            pendingResolvedTracks.remove(mediaItem.mediaId)
            if (!SongPlayer.adoptExternalPlayback(ticket, mediaItem)) return
            val song = ticket.song
            if (song != null) {
                currentSongState.commitPlayback(PlaybackQueueIdentity.select(song))
                SongPlayer.currentSource = if (song.isPodcast()) "Podcast" else "MediaSession"
                SongPlayer.setNowPlayingMeta(song.title, song.singer, song.coverUri)
                listeningTracker.onSongStarted(song)
                listeningTracker.recordRecent(song)
                com.music.spotui.data.preferences.saveLastPlayback(applicationContext, song)
            }
        }
    }

    private fun attachMediaItemListener(p: Player) {
        p.removeListener(mediaItemListener)
        p.addListener(mediaItemListener)
    }

    override fun onCreate() {
        super.onCreate()
        // Replace the temporary loading notification in place when Media3 takes over.
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(RESUMPTION_NOTIFICATION_ID).build())
        SongPlayer.ensureCreated(this)
        // Let the player advance the in-app queue itself during a crossfade.
        SongPlayer.bindState(currentSongState)
        val base = SongPlayer.exoPlayer ?: return

        attachMediaItemListener(base)
        SongPlayer.ownedPlaybackSnapshot()?.let {
            currentSongState.commitPlayback(PlaybackQueueIdentity.select(it.song, currentSongState.queue.value), base.playWhenReady)
            com.music.spotui.data.preferences.saveLastPosition(applicationContext, it.positionMs)
        }

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
        carConnectionType.observeForever(carConnectionObserver)

        serviceScope.launch {
            snapshotFlow { currentSongState.queue.value to currentSongState.repeatMode.value }
                .collect { refreshCarPresentation() }
        }
        serviceScope.launch { likedSongsRevision.collect { refreshCarPresentation() } }
        serviceScope.launch {
            com.music.spotui.util.HomeArtworkPermissions.updates.collect {
                // Refresh previously returned browse pages on approval/revocation, including
                // a cold car connection that first rendered before the whitelist loaded.
                carArtworkParents.toMap().forEach { (parent, count) ->
                    mediaSession?.notifyChildrenChanged(parent, count, null)
                }
            }
        }
        serviceScope.launch {
            while (isActive) {
                if (carConnected && carHomeRequested) refreshCarArtworkPermissions()
                delay(60_000)
            }
        }
        serviceScope.launch {
            combine(
                observeListeningHistory(applicationContext).map(AutoHomeContent::recent).distinctUntilChanged(),
                carHomeFeed.state,
            ) { recent, feed -> recent to feed }.collect { (recent, feed) ->
                if (carHomeRequested) {
                    val count = carHomeGroups(feed, recent).sumOf { it.items.size }
                    // Media3 publishes this to subscribed legacy Android Auto browsers as well.
                    mediaSession?.notifyChildrenChanged(NODE_HOME, count, null)
                    mediaSession?.notifyChildrenChanged(NODE_MUSIC, count, null)
                }
            }
        }
        refreshCarPresentation()

        // When a crossfade promotes a new ExoPlayer instance, re-bind the session to it
        // (runs on the main thread; setPlayer is the supported way to swap a session's player).
        SongPlayer.onPlayerSwapped = { newPlayer ->
            attachMediaItemListener(newPlayer)
            if (!showingWeb) mediaSession?.player = wrap(newPlayer)
            refreshCarPresentation()
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
                com.music.spotui.util.PresenceCoordinator.onPlaybackStateChanged(SpotifyWebPlayer.isPlaying)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastServiceStartId = startId
        if (intent?.action == ACTION_CANCEL_RESUMPTION) {
            if (resumptionStartup) {
                cancelPendingResumption()
                finishResumptionStartup(stop = true)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == Intent.ACTION_MEDIA_BUTTON &&
            intent.getBooleanExtra(ResumeMediaButtonReceiver.EXTRA_COLD_PLAY, false)) {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (event == null || !ResumeMediaButtonPolicy.accepts(event.action, event.keyCode, event.repeatCount)) {
                return START_NOT_STICKY
            }
            // Coalesce repeated receiver deliveries without extending the startup deadline.
            if (resumptionStartup) return START_NOT_STICKY
            if (!startResumptionNotification()) return START_NOT_STICKY
            intent.putExtra(EXTRA_BUTTON_GENERATION, ++coldButtonGeneration)
            resumptionWatchdog = serviceScope.launch {
                delay(RESUMPTION_TIMEOUT_MS + 5_000)
                if (resumptionStartup) {
                    cancelPendingResumption()
                    finishResumptionStartup(stop = true)
                }
            }
            // A stopped service can still share a stale, revoked item with the phone player.
            // Media3 invokes resumption only for an empty timeline.
            SongPlayer.exoPlayer?.let { if (!SongPlayer.canControlSessionPlayer(it)) it.clearMediaItems() }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun startResumptionNotification(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            RESUMPTION_CHANNEL, getString(R.string.playback_resume_channel), NotificationManager.IMPORTANCE_LOW,
        ))
        val cancel = PendingIntent.getService(this, 40,
            Intent(this, PlaybackService::class.java).setAction(ACTION_CANCEL_RESUMPTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, RESUMPTION_CHANNEL)
            .setSmallIcon(R.drawable.ic_auto_music)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.playback_resuming))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.playback_resume_cancel), cancel)
            .build()
        return try {
            ServiceCompat.startForeground(this, RESUMPTION_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            resumptionStartup = true
            true
        } catch (e: IllegalStateException) {
            Log.w("PlaybackResumption", "Foreground startup unavailable", e)
            stopSelf(lastServiceStartId)
            false
        } catch (e: SecurityException) {
            Log.w("PlaybackResumption", "Foreground startup not permitted", e)
            stopSelf(lastServiceStartId)
            false
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // An empty/idle controller must not remove the loading foreground notification.
        if (resumptionStartup && !startInForegroundRequired) return
        super.onUpdateNotification(session, startInForegroundRequired)
        if (resumptionStartup && startInForegroundRequired) finishResumptionStartup(stop = false)
    }

    private fun finishResumptionStartup(stop: Boolean) {
        if (!resumptionStartup) return
        resumptionStartup = false
        resumptionWatchdog?.cancel()
        resumptionWatchdog = null
        if (stop) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            getSystemService(NotificationManager::class.java).cancel(RESUMPTION_NOTIFICATION_ID)
            stopSelf(lastServiceStartId)
        }
        if (resumptionFuture?.isDone != false) resumptionToken = null
    }

    private fun cancelResumptionToken(token: String): Boolean {
        if (SongPlayer.currentPlaybackToken != token) return false
        externalRequests.invalidate()
        pendingResolvedTracks.clear()
        SongPlayer.stop()
        SongPlayer.exoPlayer?.clearMediaItems()
        return true
    }

    private fun cancelPendingResumption() {
        coldButtonGeneration++
        val pending = resumptionFuture
        resumptionFuture = null
        resumptionDispatch.complete(false) { if (pending?.isDone == false) pending.cancel(true) }
        resumptionToken?.let(::cancelResumptionToken)
        resumptionToken = null
        preparedResumption.clear()
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
        controlsSignature = null
        refreshCarPresentation()
    }

    /** Wrap an ExoPlayer so the media session routes next/previous to our in-app queue
     *  (the player only ever holds one resolved stream at a time). */
    private fun wrap(base: Player): AutoSessionPlayer = object : AutoSessionPlayer(
        base,
        metadata = { sessionMetadata.takeIf { sessionMetadataToken == SongPlayer.currentPlaybackToken } },
        repeat = { when (currentSongState.repeatMode.value) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        } },
        changeRepeat = { mode ->
            when (mode) {
                Player.REPEAT_MODE_OFF -> currentSongState.setRepeatMode(RepeatMode.OFF)
                Player.REPEAT_MODE_ALL -> currentSongState.setRepeatMode(RepeatMode.ALL)
                Player.REPEAT_MODE_ONE -> currentSongState.setRepeatMode(RepeatMode.ONE)
                else -> throw IllegalArgumentException("Invalid repeat mode")
            }
            refreshCarPresentation()
        },
    ) {
        override fun play() {
            com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
            if (resumptionDispatch.allowed && SongPlayer.canControlSessionPlayer(base)) super.play()
        }
        override fun prepare() {
            if (resumptionDispatch.allowed && SongPlayer.canControlSessionPlayer(base)) super.prepare()
        }
        override fun seekToDefaultPosition() {
            if (resumptionDispatch.allowed && SongPlayer.canControlSessionPlayer(base)) super.seekToDefaultPosition()
        }
        override fun seekToDefaultPosition(mediaItemIndex: Int) {
            if (resumptionDispatch.allowed && SongPlayer.canControlSessionPlayer(base)) super.seekToDefaultPosition(mediaItemIndex)
        }
        override fun pause() {
            com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
            radioJob?.cancel()
            cancelPendingResumption()
            finishResumptionStartup(stop = true)
            super.pause()
        }
        override fun stop() {
            com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
            radioJob?.cancel()
            cancelPendingResumption()
            finishResumptionStartup(stop = true)
            super.stop()
        }
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
            if (!playWhenReady) {
                radioJob?.cancel()
                cancelPendingResumption()
                finishResumptionStartup(stop = true)
            }
            if (!playWhenReady || (resumptionDispatch.allowed && SongPlayer.canControlSessionPlayer(base))) {
                super.setPlayWhenReady(playWhenReady)
            }
        }
        private fun alreadyPrepared(items: List<MediaItem>): Boolean = preparedResumption.consume(
            items, base.currentMediaItem, SongPlayer.currentPlaybackToken,
        )

        override fun setMediaItem(mediaItem: MediaItem) {
            if (!resumptionDispatch.allowed || alreadyPrepared(listOf(mediaItem))) return
            if (SongPlayer.acceptsExternalMediaItems(listOf(mediaItem))) super.setMediaItem(mediaItem)
        }
        override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) {
            if (!resumptionDispatch.allowed || alreadyPrepared(listOf(mediaItem))) return
            if (SongPlayer.acceptsExternalMediaItems(listOf(mediaItem))) super.setMediaItem(mediaItem, startPositionMs)
        }
        override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) {
            if (!resumptionDispatch.allowed || alreadyPrepared(listOf(mediaItem))) return
            if (SongPlayer.acceptsExternalMediaItems(listOf(mediaItem))) super.setMediaItem(mediaItem, resetPosition)
        }
        override fun setMediaItems(mediaItems: List<MediaItem>) {
            if (!resumptionDispatch.allowed || alreadyPrepared(mediaItems)) return
            if (SongPlayer.acceptsExternalMediaItems(mediaItems)) super.setMediaItems(mediaItems)
        }
        override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
            if (!resumptionDispatch.allowed || alreadyPrepared(mediaItems)) return
            if (SongPlayer.acceptsExternalMediaItems(mediaItems)) super.setMediaItems(mediaItems, resetPosition)
        }
        override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) {
            if (!resumptionDispatch.allowed || alreadyPrepared(mediaItems)) return
            if (SongPlayer.acceptsExternalMediaItems(mediaItems)) super.setMediaItems(mediaItems, startIndex, startPositionMs)
        }

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
    }.also { sessionPlayer = it }

    /** Enrich only an accepted, actually owned item. Never publish tentative queue metadata. */
    private fun refreshCarPresentation() {
        val session = mediaSession ?: return
        val song = if (showingWeb) null else SongPlayer.ownedPlaybackSnapshot()?.song
        val token = SongPlayer.currentPlaybackToken
        if (song == null) {
            metadataJob?.cancel()
            metadataPendingToken = null
            sessionMetadata = null
            sessionMetadataToken = null
        } else if (sessionMetadataToken != token && metadataPendingToken != token) {
            metadataJob?.cancel()
            metadataPendingToken = token
            metadataJob = serviceScope.launch {
                val mediaId = withContext(Dispatchers.IO) {
                    nowPlayingArtwork.register("now-playing", listOf(song)).singleOrNull()?.mediaId
                        ?.replaceFirst("auto/", "now-playing/")
                }
                if (token != SongPlayer.currentPlaybackToken ||
                    PlaybackProgressStore.key(SongPlayer.ownedPlaybackSnapshot()?.song ?: return@launch) !=
                    PlaybackProgressStore.key(song)) return@launch
                sessionMetadata = MediaMetadata.Builder()
                    .setTitle(song.title).setArtist(song.singer).setAlbumTitle(song.album)
                    .setMediaType(if (song.isPodcast()) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(if (mediaId != null && KosherWhitelistManager.isSongWhitelisted(song) && song.coverUri.startsWith("https://"))
                        carArtworkUri(mediaId) else Uri.parse("android.resource://$packageName/drawable/placeholder"))
                    .build()
                sessionMetadataToken = token
                metadataPendingToken = null
                sessionPlayer?.refreshPresentation()
            }
        }
        if (song != null && (song.isPodcast() || song.spotifyTrackId !in radioTrackIds)) radioTrackIds = emptySet()
        val signature = listOf(carConnected, token, song?.spotifyTrackId, song?.mediaType,
            song?.let(::isCarSongSaved), currentSongState.repeatMode.value,
            radioLoading, radioTrackIds.isNotEmpty()).joinToString("|")
        if (signature != controlsSignature) {
            controlsSignature = signature
            session.setCustomLayout(carButtons())
            // Modern car controllers can connect before CarConnection updates. The notification
            // controller still owns the shared legacy layout; only CarConnection changes that one.
            if (!carConnected) {
                session.connectedControllers.filter {
                    session.isAutoCompanionController(it) || session.isAutomotiveController(it)
                }.forEach { session.setCustomLayout(it, carButtons(carPresentation = true)) }
            }
        }
        sessionPlayer?.refreshPresentation()
    }

    private fun isCarSongSaved(song: SongsModel): Boolean = AutoPlaybackActions.isSaved(
        song, getLocallyLikedSongs(applicationContext),
        hasLegacySpotifyLike = { id -> isSongLiked(applicationContext, songId = "", spotifyTrackId = id) },
    )

    private fun carButtons(carPresentation: Boolean = carConnected): List<CommandButton> {
        val song = if (showingWeb) null else SongPlayer.ownedPlaybackSnapshot()?.song
        fun button(action: String, label: Int, icon: Int, enabled: Boolean) = CommandButton.Builder()
            .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
            .setDisplayName(getString(label)).setIconResId(icon).setEnabled(enabled).build()
        return AutoPlaybackActions.order(song, carPresentation).map { action -> when (action) {
            AutoPlaybackActions.Action.SAVE -> {
                val saved = isCarSongSaved(requireNotNull(song))
                button(ACTION_LIKE, if (saved) R.string.auto_track_saved else R.string.auto_save_track,
                    if (saved) R.drawable.ic_auto_saved else R.drawable.ic_auto_add, true)
            }
            AutoPlaybackActions.Action.RADIO -> button(ACTION_RADIO, when {
                    radioLoading -> R.string.auto_radio_loading
                    radioTrackIds.isNotEmpty() -> R.string.auto_radio_active
                    else -> R.string.auto_start_radio
                }, R.drawable.ic_auto_radio, !radioLoading && !song?.spotifyTrackId.isNullOrBlank())
            AutoPlaybackActions.Action.REPEAT -> {
                val (label, icon) = when (currentSongState.repeatMode.value) {
                    RepeatMode.OFF -> R.string.auto_repeat_off to R.drawable.ic_auto_repeat_off
                    RepeatMode.ALL -> R.string.auto_repeat_all to R.drawable.ic_auto_repeat_all
                    RepeatMode.ONE -> R.string.auto_repeat_one to R.drawable.ic_auto_repeat_one
                }
                button(ACTION_REPEAT, label, icon, true)
            }
        } }
    }

    private fun controllerPlayerCommands(defaults: Player.Commands, carPresentation: Boolean): Player.Commands {
        if (!carPresentation) return defaults
        val allowed = AutoPlaybackControls.commands((0 until defaults.size()).map(defaults::get).toSet(), true)
        return Player.Commands.Builder().addAll(*allowed.toIntArray()).build()
    }

    private fun updateFrameworkCarControls() {
        val session = mediaSession ?: return
        val controller = session.mediaNotificationControllerInfo ?: return
        val (sessionCommands, playerCommands) = notificationCommands ?: return
        // Media3 1.3.1 publishes legacy PlaybackState/queue from this controller's permissions.
        // Filtering only Gearhead's onConnect result does not change Android Auto's visible buttons.
        session.setAvailableCommands(controller, sessionCommands,
            controllerPlayerCommands(playerCommands, carConnected))
    }

    private suspend fun startCarRadio(song: SongsModel): SessionResult {
        if (song.isPodcast() || song.spotifyTrackId.isBlank()) return SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
        if (radioLoading) return SessionResult(SessionResult.RESULT_INFO_SKIPPED)
        val token = SongPlayer.currentPlaybackToken
        val originalQueue = currentSongState.queue.value
        val originalPlayWhenReady = SongPlayer.exoPlayer?.playWhenReady
        radioJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        radioLoading = true
        refreshCarPresentation()
        return try {
            val fresh = withContext(Dispatchers.IO) {
                withTimeoutOrNull(15_000) {
                    AutoRadioQueue.candidates(song, listOf(song), repository.provideRecommendations(listOf(song.spotifyTrackId)))
                }.orEmpty()
            }
            if (token != SongPlayer.currentPlaybackToken || SongPlayer.ownedPlaybackSnapshot()?.song != song ||
                currentSongState.queue.value != originalQueue || SongPlayer.exoPlayer?.playWhenReady != originalPlayWhenReady)
                return SessionResult(SessionResult.RESULT_INFO_SKIPPED)
            if (fresh.isEmpty()) return SessionResult(SessionResult.RESULT_ERROR_IO)
            val started = AutoRadioQueue.startStation(song, fresh,
                currentToken = { SongPlayer.currentPlaybackToken },
                cancel = { cancelResumptionToken(it) },
                prepare = { next, prepared ->
                    // Use the existing exact-track engine; start this new station from the beginning.
                    SongPlayer.resumeResolvedPlayback(next, 0L, applicationContext, prepared)
                    SongPlayer.currentPlaybackToken
                },
                commit = { selection ->
                    radioTrackIds = selection.queue.map { it.spotifyTrackId }.toSet()
                    currentSongState.commitPlayback(selection)
                })
            SessionResult(if (started) SessionResult.RESULT_SUCCESS else SessionResult.RESULT_ERROR_IO)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            SessionResult(SessionResult.RESULT_ERROR_IO)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            Log.w("PlaybackService", "Car radio unavailable", e)
            SessionResult(SessionResult.RESULT_ERROR_IO)
        } finally {
            radioJob = null
            radioLoading = false
            refreshCarPresentation()
        }
    }

    /** Manual user skip from notification / lock screen / Android Auto */
    private fun advanceManually(forward: Boolean) {
        val queue = currentSongState.queue.value
        if (queue.isEmpty()) return
        val curId = currentSongState.songId.value
        val cur = PlaybackQueueIdentity.indexOf(queue, curId)
        if (cur < 0) return
        val repeatMode = currentSongState.repeatMode.value
        if (forward) {
            if (cur < queue.size - 1) {
                playQueueIndex(queue, cur + 1)
            } else if (repeatMode == RepeatMode.ALL) {
                playQueueIndex(queue, 0)
            } else {
                val token = SongPlayer.currentPlaybackToken
                serviceScope.launch(Dispatchers.IO) {
                    val fresh = fetchKosherRadioOrArtistFallback(queue, cur)
                    withContext(Dispatchers.Main) {
                        if (token != SongPlayer.currentPlaybackToken) return@withContext
                        if (fresh.isNotEmpty()) {
                            if (radioTrackIds.isNotEmpty()) radioTrackIds += fresh.map { it.spotifyTrackId }
                            val newQueue = currentSongState.queue.value + fresh
                            currentSongState.updateQueue(newQueue)
                            playQueueIndex(newQueue, queue.size)
                        } else {
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
        val curIdx = PlaybackQueueIdentity.indexOf(queue, curId)
        if (curIdx < 0) {
            transitionInProgress = false
            return
        }

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
                    withContext(Dispatchers.Main) {
                        if (token != SongPlayer.currentPlaybackToken) return@withContext
                        if (radioTrackIds.isNotEmpty()) radioTrackIds += fresh.map { it.spotifyTrackId }
                        val newQueue = currentSongState.queue.value + fresh
                        currentSongState.updateQueue(newQueue)
                        playQueueIndex(newQueue, queue.size)
                    }
                } else {
                    // No kosher tracks available -> STOP cleanly! Do NOT replay track 0!
                    withContext(Dispatchers.Main) {
                        if (token != SongPlayer.currentPlaybackToken) return@withContext
                        stopPlaybackCleanly()
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Autoplay failed after track ended", e)
                withContext(Dispatchers.Main) {
                    if (token != SongPlayer.currentPlaybackToken) return@withContext
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
        if (currentSong.isPodcast()) return emptyList()
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
        const val RESUMPTION_TIMEOUT_MS = 30_000L
        const val RESUMPTION_NOTIFICATION_ID = 1041
        const val RESUMPTION_CHANNEL = "playback_resumption"
        const val ACTION_CANCEL_RESUMPTION = "spotui.CANCEL_RESUMPTION"
        const val EXTRA_BUTTON_GENERATION = "spotui.MEDIA_BUTTON_GENERATION"
        const val ROOT = "root"
        const val ACTION_LIKE = "spotui.auto.SAVE_TRACK"
        const val ACTION_RADIO = "spotui.auto.START_RADIO"
        const val ACTION_REPEAT = "spotui.auto.CYCLE_REPEAT"
        const val NODE_HOME = "home"
        const val NODE_BROWSE = "browse"
        const val NODE_LIBRARY = "library"
        const val NODE_ARTISTS = "artists"
        const val NODE_SEARCH_HISTORY = "search-history"
        const val NODE_DISCOVER_PODCASTS = "discover-podcasts"
        const val NODE_LIKED = "liked"
        const val NODE_DOWNLOADS = "downloads"
        const val NODE_PLAYLISTS = "playlists"
        const val NODE_ALBUMS = "albums"
        const val NODE_RECENT = "recent"
        const val NODE_MUSIC = "music"
        const val NODE_PODCASTS = "podcasts"
        const val NODE_EPISODES = "recent-episodes"
        const val CAR_PAGE_SIZE = 50
        const val STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val STYLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"
        const val STYLE_GROUP = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo,
            intent: Intent): Boolean {
            if (intent.getBooleanExtra(ResumeMediaButtonReceiver.EXTRA_COLD_PLAY, false) &&
                intent.getIntExtra(EXTRA_BUTTON_GENERATION, -1) != coldButtonGeneration) return true
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                if (event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE || event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP) {
                    cancelPendingResumption()
                    finishResumptionStartup(stop = true)
                } else if (ResumeMediaButtonPolicy.accepts(event.action, event.keyCode, event.repeatCount) &&
                    resumptionFuture?.isDone == false) return true
            }
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val defaults = super.onConnect(session, controller)
            if (!defaults.isAccepted) return defaults
            val commands = defaults.availableSessionCommands.buildUpon()
                .add(SessionCommand(ACTION_LIKE, Bundle.EMPTY))
                .add(SessionCommand(ACTION_RADIO, Bundle.EMPTY))
                .add(SessionCommand(ACTION_REPEAT, Bundle.EMPTY)).build()
            val notification = session.isMediaNotificationController(controller)
            if (notification) notificationCommands = commands to defaults.availablePlayerCommands
            val carPresentation = session.isAutoCompanionController(controller) ||
                session.isAutomotiveController(controller) || (notification && carConnected)
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setAvailablePlayerCommands(controllerPlayerCommands(defaults.availablePlayerCommands, carPresentation))
                .setCustomLayout(carButtons(carPresentation || carConnected))
                .build()
        }

        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            if (session.isMediaNotificationController(controller)) notificationCommands = null
            super.onDisconnected(session, controller)
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = future {
            val song = if (showingWeb) null else SongPlayer.ownedPlaybackSnapshot()?.song
            if (song == null) return@future SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE)
            when (customCommand.customAction) {
                ACTION_LIKE -> {
                    val result = AutoPlaybackActions.save(song, ::isCarSongSaved,
                        saveLocal = { addLikedSong(applicationContext, it) },
                        syncSpotify = { SpotifySync.setTrackSaved(applicationContext, it, true) })
                    if (result == AutoPlaybackActions.SaveResult.UNSUPPORTED)
                        return@future SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                    val displayed = currentSongState.queue.value.getOrNull(currentSongState.songIndex.value)
                    if (displayed != null && currentSongState.songId.value == song.id &&
                        PlaybackQueueIdentity.same(displayed, song)) currentSongState.updateLikeState(true)
                    refreshCarPresentation()
                    mediaSession?.notifyChildrenChanged(NODE_LIKED, com.music.spotui.data.preferences.getLikedSongsCount(applicationContext), null)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                ACTION_REPEAT -> {
                    currentSongState.toggleRepeatMode()
                    refreshCarPresentation()
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                ACTION_RADIO -> startCarRadio(song)
                else -> SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, getString(R.string.app_name)),
                LibraryParams.Builder().setExtras(Bundle().apply {
                    putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
                    putInt(STYLE_BROWSABLE, 2)
                    putInt(STYLE_PLAYABLE, 1)
                }).build()))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                require(page >= 0 && pageSize > 0)
                refreshCarArtworkPermissions()
                val children = withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) { carBrowseMutex.withLock { carChildren(parentId) } }
                } ?: throw java.io.IOException("Browse unavailable; retry")
                carArtworkParents[parentId] = children.size
                if (carArtworkParents.size > 256) carArtworkParents.remove(carArtworkParents.keys.first())
                LibraryResult.ofItemList(AutoMediaCatalog.page(children, page, pageSize), params)
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            } catch (e: Exception) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            val item = withContext(Dispatchers.IO) {
                carCatalog.find(mediaId)?.let { playable(it.song, it.mediaId) }
                    ?: trackById[mediaId]?.let { playable(it, mediaId) }
                    ?: findCarArtistAction(mediaId)?.let(::artistSearchItem)
                    ?: browseFolders[mediaId] ?: resolveCarFolder(mediaId)
            }
            item?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> = future {
            try {
                val results = onlineSearch.get(query)
                val items = withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) { carBrowseMutex.withLock { searchCarItems(query, results) } }
                } ?: throw java.io.IOException("Search unavailable; retry")
                session.notifySearchResultChanged(browser, query, items.size, params)
                LibraryResult.ofVoid()
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            } catch (e: Exception) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) }
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            query: String, page: Int, pageSize: Int, params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = future {
            try {
                require(page >= 0 && pageSize > 0)
                val results = onlineSearch.get(query)
                val items = withTimeoutOrNull(15_000) {
                    withContext(Dispatchers.IO) {
                        carBrowseMutex.withLock {
                            val selected = AutoMediaCatalog.page(searchCarItems(query, results), page, pageSize)
                            persistCarItems(searchParent(query), selected)
                            if (selected.any { !it.mediaId.startsWith("auto-search/") }) carSearchHistory.record(query)
                            selected
                        }
                    }
                } ?: throw java.io.IOException("Search unavailable; retry")
                LibraryResult.ofItemList(items, params)
            } catch (e: CancellationException) { throw e
            } catch (e: IllegalArgumentException) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
            } catch (e: Exception) { LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO) }
        }

        // Media3 1.3.1 routes add AND replace commands here, including playable URI items.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = future { externalRequests.rejectAdd() }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            radioTrackIds = emptySet()
            cancelPendingResumption()
            SongPlayer.beginExternalPlaybackRequest(applicationContext)
            val request = externalRequests.begin()
            pendingResolvedTracks.clear()
            // Preserve explicit playlist selection; INDEX_UNSET uses the requested list's default start.
            // The app queue, rather than ExoPlayer's stream list, handles later tracks.
            val selectedIndex = if (startIndex == C.INDEX_UNSET) 0 else startIndex
            if (selectedIndex !in mediaItems.indices) {
                return Futures.immediateFailedFuture(IllegalArgumentException("Invalid selected media index"))
            }
            val requested = mediaItems[selectedIndex]
            return future {
                var artistSelection: PlaybackQueueIdentity.Selection? = null
                val song = externalRequests.identity(request) {
                    if (requested.mediaId.startsWith(AutoArtistPlayback.PREFIX)) {
                        // Only the explicit artist-search action can choose a track from a collection.
                        require(requested.localConfiguration == null && requested.requestMetadata.mediaUri == null &&
                            requested.requestMetadata.searchQuery == null &&
                            (requested.mediaMetadata.mediaType == null || requested.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_ARTIST))
                        artistSelection = withContext(Dispatchers.IO) {
                            val entry = findCarArtistAction(requested.mediaId)
                                ?: throw IllegalArgumentException("Unknown artist action")
                            AutoArtistPlayback.select(entry, load = { artistId ->
                                lastSuccess(repository.provideArtistOverview(entry.title, artistId))
                            })
                        }
                        artistSelection?.song
                    } else resolveExternalMediaItem(requested)
                }
                val position = com.music.spotui.data.preferences.loadPlaybackPosition(
                    applicationContext, song, startPositionMs.takeIf { it >= 0 } ?: artistSelection?.let { 0L },
                )
                if (song.isPodcast()) {
                    podcastItems(externalRequests.podcast(request, song, position))
                } else {
                    val queue = artistSelection?.queue ?: queueByTrackId[requested.mediaId].orEmpty()
                    startExternalMusic(song, queue, position)
                }
            }
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = PlaybackResumptionRequest.dispatch(
            // Read before starting any asynchronous work; Media3 clears this after dispatch.
            hasTransportRequest = mediaSession.controllerForCurrentRequest != null,
            loadSaved = {
                com.music.spotui.data.preferences.loadLastPlayback(applicationContext)
                    ?.let { (song, position) -> PlaybackSnapshot(song, position) }
            },
            artwork = { song ->
                // Reuse only an already registered exact cover; boot metadata must not resolve audio.
                val id = AutoMediaCatalog.id("now-playing", song)
                val registered = id?.let(nowPlayingArtwork::find)
                if (registered != null && registered.song.coverUri == song.coverUri &&
                    KosherWhitelistManager.isSongWhitelisted(song) && song.coverUri.startsWith("https://")) {
                    carArtworkUri(registered.mediaId.replaceFirst("auto/", "now-playing/"))
                } else Uri.parse("android.resource://$packageName/drawable/placeholder")
            },
            resumePlayback = ::resumePlayback,
        )

        private fun resumePlayback(): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            resumptionFuture?.takeUnless { it.isDone }?.let { return it }
            val result = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            resumptionFuture = result
            val job = serviceScope.launch {
                var token: String? = null
                var cancelledOwnRequest = false
                try {
                    val items = withTimeout(RESUMPTION_TIMEOUT_MS) {
                        SongPlayer.beginExternalPlaybackRequest(applicationContext)
                        token = SongPlayer.currentPlaybackToken
                        resumptionToken = token
                        val request = externalRequests.begin()
                        pendingResolvedTracks.clear()
                        val saved = com.music.spotui.data.preferences.loadLastPlayback(applicationContext)
                            ?.let { (song, position) -> PlaybackSnapshot(song, position) }
                        val plan = WazeResumeResolver.resolve(WazeResumeResolver.Request(), saved, null)
                            ?: throw IllegalStateException("No exact playback identity to resume")
                        externalRequests.requireCurrent(request)
                        val song = plan.playback.song
                        val position = plan.playback.positionMs
                        if (song.isPodcast()) {
                            podcastItems(externalRequests.podcast(request, song, position) {
                                token = it.token
                                resumptionToken = it.token
                            })
                        } else {
                            val item = awaitResumptionPreparation<MediaItem>(
                                RESUMPTION_TIMEOUT_MS,
                                currentToken = { SongPlayer.currentPlaybackToken },
                                cancel = { cancelledOwnRequest = cancelResumptionToken(it) },
                            ) { prepared ->
                                SongPlayer.resumeResolvedPlayback(song, position, applicationContext) {
                                    val player = SongPlayer.exoPlayer
                                    val item = player?.currentMediaItem
                                    if (player != null && item != null && SongPlayer.canControlSessionPlayer(player) && prepared(item)) {
                                        currentSongState.commitPlayback(PlaybackQueueIdentity.select(song))
                                        com.music.spotui.data.preferences.saveLastPosition(applicationContext, position)
                                    }
                                }
                                SongPlayer.currentPlaybackToken.also { token = it; resumptionToken = it }
                            }
                            externalRequests.requireCurrent(request, requireNotNull(token))
                            preparedResumption.set(item, requireNotNull(token))
                            MediaSession.MediaItemsWithStartPosition(ImmutableList.of(item), 0, position)
                        }
                    }
                    // Media3 applies this genuine item and then plays on this same application thread.
                    resumptionDispatch.complete(token == SongPlayer.currentPlaybackToken) { result.set(items) }
                } catch (e: Exception) {
                    val stopped = token?.let(::cancelResumptionToken) ?: true
                    if (resumptionFuture === result && (stopped || cancelledOwnRequest)) {
                        preparedResumption.clear()
                        finishResumptionStartup(stop = true)
                    }
                    // 1.3.1 also calls play on failure: do not let that resume a newer user's item.
                    resumptionDispatch.complete(false) { result.setException(e) }
                } finally {
                    if (!resumptionStartup && resumptionToken == token) resumptionToken = null
                }
            }
            result.addListener({ if (result.isCancelled) job.cancel() },
                com.google.common.util.concurrent.MoreExecutors.directExecutor())
            return result
        }

        private fun startExternalMusic(
            song: SongsModel, queue: List<SongsModel>, positionMs: Long,
        ): MediaSession.MediaItemsWithStartPosition {
            // Preserve the established SongPlayer music engine. The wrapper ignores this empty
            // compatibility result, and transport commands cannot resume the previous owner's item.
            SongPlayer.resumeResolvedPlayback(song, positionMs, applicationContext) {
                currentSongState.commitPlayback(PlaybackQueueIdentity.select(song, queue))
                com.music.spotui.data.preferences.saveLastPosition(applicationContext, positionMs)
            }
            return MediaSession.MediaItemsWithStartPosition(emptyList(), C.INDEX_UNSET, C.TIME_UNSET)
        }

        private fun podcastItems(prepared: ExternalPlaybackRequests.Prepared): MediaSession.MediaItemsWithStartPosition {
            val ticket = prepared.ticket
            val song = requireNotNull(ticket.song)
            val item = MediaItem.Builder()
                .setMediaId(requireNotNull(ticket.mediaId))
                .setTag(ticket)
                .setUri(prepared.source.url)
                .setMimeType(prepared.source.mimeType)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title).setArtist(song.singer).setAlbumTitle(song.album)
                        .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                        .setArtworkUri(song.coverUri.takeIf { it.isNotBlank() }?.let(Uri::parse))
                        .setIsPlayable(true).setIsBrowsable(false).build()
                ).build()
            pendingResolvedTracks[item.mediaId] = ticket
            return MediaSession.MediaItemsWithStartPosition(ImmutableList.of(item), 0, prepared.positionMs)
        }
    }
    private fun externalIdentityRequest(item: MediaItem): ExternalMediaIdentityResolver.Request =
        ExternalMediaIdentityResolver.Request(
            identifiers = listOfNotNull(
                item.mediaId,
                item.requestMetadata.mediaUri?.toString(),
                item.localConfiguration?.uri?.toString(),
            ),
            mediaType = when (item.mediaMetadata.mediaType) {
                MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE -> MediaType.PODCAST_EPISODE
                MediaMetadata.MEDIA_TYPE_MUSIC -> MediaType.TRACK
                else -> null
            },
            title = item.mediaMetadata.title?.toString(),
            album = item.mediaMetadata.albumTitle?.toString(),
            artist = item.mediaMetadata.artist?.toString(),
        )

    private suspend fun resolveExternalMediaItem(requested: MediaItem): SongsModel? {
        // Non-item media types (Show, Album, Playlist, etc.) cannot become episodes.
        if (requested.mediaMetadata.mediaType != null &&
            requested.mediaMetadata.mediaType !in listOf(
                MediaMetadata.MEDIA_TYPE_MUSIC, MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
            )
        ) return null
        // Opaque car IDs are issued by this app, persisted with their typed snapshot and
        // queue context. Never use controller metadata to reconstruct an unknown token.
        if (requested.mediaId.startsWith("auto/")) {
            val song = withContext(Dispatchers.IO) { carCatalog.find(requested.mediaId)?.song } ?: return null
            val expected = if (song.isPodcast()) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC
            if (requested.mediaMetadata.mediaType != null && requested.mediaMetadata.mediaType != expected) return null
            if (requested.localConfiguration != null || requested.requestMetadata.mediaUri != null) return null
            return song
        }
        val searchQuery = requested.requestMetadata.searchQuery
        if (requested.mediaId.isBlank() && searchQuery != null && requested.localConfiguration == null &&
            requested.requestMetadata.mediaUri == null) {
            val songs = withContext(Dispatchers.IO) {
                val results = onlineSearch.get(searchQuery)
                results.results.songs + results.results.episodes
            }
            return AutoMediaCatalog.voiceSelection(searchQuery, songs)?.takeIf { song ->
                requested.mediaMetadata.mediaType == null ||
                    (requested.mediaMetadata.mediaType == MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE) == song.isPodcast()
            }
        }
        val candidates = buildList {
            addAll(currentSongState.queue.value)
            addAll(trackById.values)
            com.music.spotui.data.preferences.loadLastPlayback(applicationContext)?.first?.let(::add)
            addAll(listeningTracker.recentTracks(50).map { it.toSongModel() })
        }
        return ExternalMediaIdentityResolver.resolve(externalIdentityRequest(requested), candidates) { epId ->
            // Stable identity must be verified remotely when it is not known locally.
            // Controller-supplied text never substitutes for a different/unresolved ID.
            val episode = Spotify.episode(epId).getOrNull()
            if (episode == null || episode.id != epId) null else {
                val showName = episode.show?.name.orEmpty()
                SongsModel(
                    id = "episode:$epId".hashCode() and 0x7fffffff,
                    title = episode.name,
                    album = showName,
                    singer = showName,
                    coverUri = episode.images.firstOrNull()?.url
                        ?: episode.show?.images?.firstOrNull()?.url.orEmpty(),
                    url = "episode:$epId",
                    spotifyTrackId = epId,
                    explicit = false,
                    durationMs = episode.durationMs,
                    mediaType = MediaType.PODCAST_EPISODE,
                    podcastShowId = episode.show?.id.orEmpty(),
                )
            }
        }
    }

    private fun staticFolders(): Map<String, MediaItem> = mapOf(
        ROOT to folder(ROOT, getString(R.string.app_name)),
        NODE_HOME to folder(NODE_HOME, getString(R.string.auto_home), "ic_home_outline", 2),
        NODE_RECENT to folder(NODE_RECENT, getString(R.string.auto_recent), "ic_auto_recent", 2),
        NODE_BROWSE to folder(NODE_BROWSE, getString(R.string.auto_browse), "ic_auto_browse", 4),
        NODE_LIBRARY to folder(NODE_LIBRARY, getString(R.string.auto_library)),
        NODE_ARTISTS to folder(NODE_ARTISTS, getString(R.string.auto_artists), "ic_auto_artist", 2),
        NODE_MUSIC to folder(NODE_MUSIC, getString(R.string.auto_music), "ic_auto_music", 2),
        NODE_PODCASTS to folder(NODE_PODCASTS, getString(R.string.auto_podcasts), "ic_auto_podcast", 2),
        NODE_DISCOVER_PODCASTS to folder(NODE_DISCOVER_PODCASTS, getString(R.string.auto_podcasts), "ic_auto_podcast", 2),
        NODE_DOWNLOADS to folder(NODE_DOWNLOADS, getString(R.string.auto_downloads), "ic_download"),
        NODE_LIKED to coverFolder(NODE_LIKED, getString(R.string.auto_liked), "tile/liked", 1),
        NODE_SEARCH_HISTORY to folder(NODE_SEARCH_HISTORY, getString(R.string.auto_search_history), "ic_auto_recent"),
        NODE_PLAYLISTS to folder(NODE_PLAYLISTS, getString(R.string.auto_playlists)),
        NODE_ALBUMS to folder(NODE_ALBUMS, getString(R.string.auto_albums)),
        NODE_EPISODES to folder(NODE_EPISODES, getString(R.string.auto_recent_episodes)),
    )

    /** Also used by Media3's default onSubscribe before requesting children. */
    private suspend fun resolveCarFolder(id: String): MediaItem? {
        staticFolders()[id]?.let { return it }
        (carFolders.find(id) ?: collectionById[id])?.let { return collectionFolder(it) }
        browseCategories()[id]?.let { return coverFolder(id, it, "tile/${id.substringAfter('/')}", 2) }
        carSearchHistory.find(id)?.let { return searchHistoryFolder(it) }
        return when {
            id.startsWith("playlist/") -> libraryEntries().singleOrNull {
                it.isPlaylist && it.spotifyId == id.removePrefix("playlist/")
            }?.let { folder(id, it.name) }
            id.startsWith("album-id/") -> libraryEntries().singleOrNull {
                !it.isPlaylist && it.spotifyId == Uri.decode(id.removePrefix("album-id/"))
            }?.let { folder(id, it.name) }
            id.startsWith("show/") -> repository.getFollowedPodcasts().singleOrNull {
                it.showId == Uri.decode(id.removePrefix("show/"))
            }?.let { folder(id, it.name) }
            id.startsWith("auto-page/") -> {
                val parts = id.split('/')
                val offset = parts.getOrNull(2)?.toIntOrNull()
                val parent = parts.getOrNull(1)?.let(Uri::decode).orEmpty()
                if (parts.size == 3 && offset != null && offset > 0 && offset % CAR_PAGE_SIZE == 0 &&
                    !parent.startsWith("auto-page/") && resolveCarFolder(parent) != null)
                    folder(id, getString(R.string.auto_more)) else null
            }
            else -> null
        }
    }

    // Auto does not request Media3 pages. Use explicit, bounded browse folders instead.
    private suspend fun carChildren(id: String): List<MediaItem> {
        pruneTrackCache()
        val pageParts = id.takeIf { it.startsWith("auto-page/") }?.split('/')
        val parent = if (pageParts != null) {
            require(pageParts.size == 3)
            Uri.decode(pageParts[1]).also { require(!it.startsWith("auto-page/")) }
        } else id
        val offset = pageParts?.get(2)?.toIntOrNull() ?: if (pageParts == null) 0 else throw IllegalArgumentException()
        require(offset >= 0 && offset % CAR_PAGE_SIZE == 0)
        val children = childrenOf(parent)
        val window = AutoBrowseWindow.select(parent, children, offset, CAR_PAGE_SIZE)
        // Home exposes its complete feed in one scroll, collections retain bounded pages.
        val catalogParent = if (parent.startsWith("auto-search/"))
            searchParent(requireNotNull(carSearchHistory.find(parent)).query) else parent
        persistCarItems(catalogParent, window.items)
        return window.nextOffset?.let { next -> window.items + folder(
            "auto-page/${Uri.encode(parent)}/$next", getString(R.string.auto_more)
        ) } ?: window.items
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(NODE_HOME, NODE_RECENT, NODE_BROWSE, NODE_LIBRARY).map { staticFolders().getValue(it) }
        parentId == NODE_LIBRARY -> listOf(NODE_LIKED, NODE_PLAYLISTS, NODE_ARTISTS, NODE_ALBUMS, NODE_PODCASTS, NODE_DOWNLOADS, NODE_SEARCH_HISTORY)
            .map { staticFolders().getValue(it) }
        parentId == NODE_HOME || parentId == NODE_MUSIC -> homeItems(parentId)
        parentId == NODE_BROWSE -> listOf(
            coverFolder(NODE_MUSIC, getString(R.string.auto_music), "tile/music", 2),
            coverFolder(NODE_DISCOVER_PODCASTS, getString(R.string.auto_podcasts), "tile/podcasts", 2),
        ) + browseCategories().map { (id, title) -> coverFolder(id, title, "tile/${id.substringAfter('/')}", 2) }
        parentId == NODE_SEARCH_HISTORY -> carSearchHistory.items().map(::searchHistoryFolder)
        parentId.startsWith("auto-search/") -> {
            val query = carSearchHistory.find(parentId)?.query ?: throw IllegalArgumentException("Unknown search")
            searchCarItems(query, onlineSearch.get(query))
        }
        parentId in browseCategories() -> lastSuccess(repository.provideCategoryPlaylists(browseCategories().getValue(parentId)))
            .orEmpty().mapNotNull(AutoBrowseContent::collection).mapNotNull(::registerFolder)
        parentId == NODE_DISCOVER_PODCASTS -> lastSuccess(repository.providePodcastHubShows()).orEmpty()
            .mapNotNull(AutoBrowseContent::show).mapNotNull(::registerFolder)
        parentId == NODE_ARTISTS -> {
            val artists = withTimeoutOrNull(12_000) { repository.provideFollowedArtists() }
                ?: throw java.io.IOException("Artists unavailable; retry")
            artists.mapNotNull(AutoBrowseContent::artist).mapNotNull(::registerFolder)
        }
        parentId.startsWith("auto-folder/") -> collectionChildren(parentId)
        parentId == NODE_RECENT -> registerTracks(parentId, listeningTracker.recentTracks(50).map { it.toSongModel() })
        parentId == NODE_EPISODES -> registerTracks(parentId, listeningTracker.recentTracks(200).map { it.toSongModel() }.filter { it.isPodcast() })
        parentId == NODE_PODCASTS -> listOf(staticFolders().getValue(NODE_EPISODES)) +
            repository.getFollowedPodcasts().mapNotNull { show -> registerFolder(AutoBrowseCatalog.Entry(
                AutoBrowseCatalog.Kind.SHOW, show.showId, show.name, artwork = show.imageUrl)) }
        parentId.startsWith("show/") -> {
            val id = Uri.decode(parentId.removePrefix("show/"))
            val show = repository.getFollowedPodcasts().singleOrNull { it.showId == id }
                ?: throw IllegalArgumentException("Unknown followed show")
            registerTracks(parentId, lastSuccess(repository.provideShowEpisodes(show.showId, show.name)).orEmpty())
        }
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
            }.mapNotNull(AutoBrowseContent::collection).mapNotNull(::registerFolder)
        parentId == NODE_ALBUMS ->
            libraryEntries().filter { !it.isPlaylist }.mapNotNull(AutoBrowseContent::collection).mapNotNull(::registerFolder)
        parentId.startsWith("album-id/") -> {
            val id = Uri.decode(parentId.removePrefix("album-id/"))
            val album = libraryEntries().singleOrNull { !it.isPlaylist && it.spotifyId == id }
                ?: throw IllegalArgumentException("Unknown saved album")
            registerTracks(parentId, lastSuccess(repository.provideAlbumSongs(album.name, album.artists, album.spotifyId)).orEmpty())
        }
        parentId.startsWith("playlist/") -> {
            val songs = lastSuccess(repository.providePlaylistSongs(parentId.removePrefix("playlist/")))
            registerTracks(parentId, songs.orEmpty())
        }
        parentId.startsWith("album/") -> {
            val parts = parentId.removePrefix("album/").split('/')
            val name = android.net.Uri.decode(parts.getOrElse(0) { "" })
            val artist = android.net.Uri.decode(parts.getOrElse(1) { "" })
            val album = libraryEntries().singleOrNull { !it.isPlaylist && it.name == name && it.artists == artist }
                ?: throw IllegalArgumentException("Ambiguous legacy album")
            require(album.spotifyId.isNotBlank())
            registerTracks(parentId, lastSuccess(repository.provideAlbumSongs(name, artist, album.spotifyId)).orEmpty())
        }
        else -> throw IllegalArgumentException("Unknown browse folder")
    }

    private suspend fun libraryEntries() =
        lastSuccess(repository.provideLibrary()).orEmpty()

    private fun carHomeGroups(feed: AutoHomeContent.Snapshot?, recent: List<SongsModel>) =
        AutoHomeContent.groups(feed, recent, getString(R.string.auto_recent),
            getString(R.string.auto_recommended_stations), getString(R.string.auto_your_library))

    /** Real local history leads the car feed, independently of recommendation/cache freshness. */
    private suspend fun homeItems(parent: String): List<MediaItem> {
        carHomeRequested = true
        val hasHistory = AutoHomeContent.recent(getListeningHistory(applicationContext)).isNotEmpty()
        val feed = carHomeFeed.get(waitForFirst = !hasHistory)
        // Re-read after the initial fetch: playback may have started while it was in flight.
        val groups = carHomeGroups(feed, AutoHomeContent.recent(getListeningHistory(applicationContext)))
        val tracks = registerTracks(parent, groups.flatMap { it.items }.filterIsInstance<HomeItem.Track>().map { it.song })
            .associateBy { it.mediaId }
        return groups.flatMap { (title, items) -> items.mapNotNull { item ->
            val mediaItem = when (item) {
                is HomeItem.Track -> tracks[AutoMediaCatalog.id(parent, item.song)]
                is HomeItem.LikedSongs -> staticFolders()[NODE_LIKED]
                else -> AutoBrowseContent.homeFolder(item)?.let(::registerFolder)
            }
            mediaItem?.let { styled(it, 2, title) }
        } }
    }

    private fun browseCategories(): Map<String, String> = linkedMapOf(
        "category/new" to getString(R.string.auto_new),
        "category/pop" to getString(R.string.auto_pop),
        "category/hiphop" to getString(R.string.auto_hiphop),
        "category/rock" to getString(R.string.auto_rock),
        "category/israeli" to getString(R.string.auto_israeli),
        "category/jewish" to getString(R.string.auto_jewish),
    )

    private suspend fun collectionChildren(mediaId: String): List<MediaItem> {
        val entry = carFolders.find(mediaId) ?: collectionById[mediaId]
            ?: throw IllegalArgumentException("Unknown collection")
        val songs = when (entry.kind) {
            AutoBrowseCatalog.Kind.ALBUM -> lastSuccess(repository.provideAlbumSongs(entry.title, entry.artist, entry.id)).orEmpty()
            AutoBrowseCatalog.Kind.PLAYLIST -> lastSuccess(repository.providePlaylistSongs(entry.id)).orEmpty()
            AutoBrowseCatalog.Kind.SHOW -> lastSuccess(repository.provideShowEpisodes(entry.id, entry.title)).orEmpty()
            AutoBrowseCatalog.Kind.ARTIST -> {
                val artist = lastSuccess(repository.provideArtistOverview(entry.title, entry.id))
                    ?: throw java.io.IOException("Artist unavailable")
                require(artist.id == entry.id) { "Artist identity changed" }
                artist.topTracks.map { it.song }
            }
        }
        return registerTracks(mediaId, songs)
    }

    private fun searchParent(query: String) = "search/${Uri.encode(query.trim())}"

    private fun searchHistoryFolder(entry: AutoSearchHistory.Entry): MediaItem =
        folder(entry.mediaId, entry.query, "ic_auto_recent").let { styled(it, 1) }

    private fun searchCarItems(query: String, snapshot: CarSearchSnapshot): List<MediaItem> {
        pruneTrackCache()
        val results = snapshot.results
        // A controller can request the entire search in one page. Every advertised token
        // must fit the durable playable catalog even when 1,000 local matches are merged.
        val playable = registerTracks(searchParent(query), results.songs + results.episodes).take(1000)
        val matches = (results.artists.mapNotNull(AutoBrowseContent::artist).map(::artistSearchItem) +
            results.playlists.mapNotNull(AutoBrowseContent::collection).mapNotNull(::registerFolder) +
            playable + results.albums.mapNotNull(AutoBrowseContent::album).mapNotNull(::registerFolder) +
            results.shows.mapNotNull(AutoBrowseContent::show).mapNotNull(::registerFolder))
            .distinctBy { it.mediaId }.map { styled(it, 1, getString(R.string.auto_top_results)) }
        // The history snapshot is captured with the result set so count/result callbacks agree.
        return snapshot.history.map { styled(searchHistoryFolder(it), 1, getString(R.string.auto_search_history)) } + matches
    }

    private fun registerFolder(entry: AutoBrowseCatalog.Entry): MediaItem? {
        if (!entry.isValid()) return null
        collectionById[entry.mediaId] = entry
        return collectionFolder(entry)
    }

    private fun persistCarItems(parent: String, items: List<MediaItem>) {
        carCatalog.register(parent, items.mapNotNull { trackById[it.mediaId] })
        carFolders.registerAll(items.mapNotNull { collectionById[AutoArtistPlayback.folderId(it.mediaId) ?: it.mediaId] })
    }

    private fun findCarArtistAction(id: String): AutoBrowseCatalog.Entry? = AutoArtistPlayback.resolve(id) { folderId ->
        carFolders.find(folderId) ?: collectionById[folderId]
    }

    private fun artistSearchItem(entry: AutoBrowseCatalog.Entry): MediaItem {
        collectionById[entry.mediaId] = entry
        val item = collectionFolder(entry)
        return item.buildUpon().setMediaId(requireNotNull(AutoArtistPlayback.mediaId(entry)))
            .setMediaMetadata(item.mediaMetadata.buildUpon()
                .setSubtitle(entry.subtitle.ifBlank { getString(R.string.auto_artist) })
                .setIsBrowsable(false).setIsPlayable(true).build()).build()
    }

    private fun collectionFolder(entry: AutoBrowseCatalog.Entry): MediaItem = MediaItem.Builder()
        .setMediaId(entry.mediaId)
        .setMediaMetadata(MediaMetadata.Builder()
            .setTitle(entry.title).setSubtitle(entry.subtitle)
            .setMediaType(when (entry.kind) {
                AutoBrowseCatalog.Kind.ALBUM -> MediaMetadata.MEDIA_TYPE_ALBUM
                AutoBrowseCatalog.Kind.ARTIST -> MediaMetadata.MEDIA_TYPE_ARTIST
                AutoBrowseCatalog.Kind.PLAYLIST -> MediaMetadata.MEDIA_TYPE_PLAYLIST
                AutoBrowseCatalog.Kind.SHOW -> MediaMetadata.MEDIA_TYPE_PODCAST
            })
            .setArtworkUri(if (AutoArtworkPolicy.allowed(entry, applicationContext) && entry.artwork.startsWith("https://"))
                carArtworkUri(entry.mediaId, entry.artwork) else Uri.parse("android.resource://$packageName/drawable/placeholder"))
            .setExtras(Bundle().apply { putInt(STYLE_BROWSABLE, 2); putInt(STYLE_PLAYABLE, 1) })
            .setIsBrowsable(true).setIsPlayable(false).build()).build()

    private fun refreshCarArtworkPermissions() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastCarArtworkSync?.let { now - it < 60_000 } == true) return
        lastCarArtworkSync = now
        KosherWhitelistManager.syncWithRemote(applicationContext)
    }

    private fun carArtworkUri(id: String, artwork: String? = null): Uri =
        Uri.Builder().scheme("content").authority("$packageName.auto.artwork").appendPath(id).apply {
            // A changed exact cover must not reuse the car host's cached bitmap for this folder.
            if (artwork != null) appendQueryParameter("v", com.music.spotui.util.HomeArtworkPermissions.key(artwork))
        }.build()

    private fun coverFolder(id: String, title: String, artwork: String, childStyle: Int): MediaItem =
        styled(folder(id, title, childStyle = childStyle).let { item ->
            item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon()
                .setArtworkUri(carArtworkUri(artwork)).build()).build()
        }, if (childStyle == 2) 2 else 1)

    private fun styled(item: MediaItem, style: Int, group: String = ""): MediaItem {
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putInt(STYLE_ITEM, style)
            if (group.isNotBlank()) putString(STYLE_GROUP, group)
        }
        return item.buildUpon().setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build()).build()
    }

    /** Keep the latest successful page, with a deadline; cancellation is never swallowed. */
    private suspend fun <T> lastSuccess(flow: Flow<Response<T>>): T? {
        var latest: T? = null
        withTimeoutOrNull(12_000) {
            flow.collect { response -> when (response) {
                is Response.Success -> latest = response.data
                is Response.Error -> if (latest == null) throw java.io.IOException(response.error)
                else -> Unit
            } }
        }
        return latest ?: throw java.io.IOException("Browse unavailable; retry")
    }

    private fun searchCarLibrary(query: String): List<SongsModel> = AutoMediaCatalog.search(query, buildList {
        addAll(carCatalog.songs())
        addAll(listeningTracker.recentTracks(200).map { it.toSongModel() })
        addAll(com.music.spotui.data.preferences.getLocallyLikedSongs(applicationContext))
        addAll(com.music.spotui.data.preferences.getDownloadedSongs(applicationContext))
        com.music.spotui.data.preferences.loadLastPlayback(applicationContext)?.first?.let(::add)
    }).take(1000)

    private fun registerTracks(parentId: String, songs: List<SongsModel>): List<MediaItem> {
        val entries = songs.mapNotNull { song ->
            AutoMediaCatalog.id(parentId, song)?.let { AutoMediaCatalog.Entry(it, parentId, song) }
        }.distinctBy { it.mediaId }
        val musicQueue = entries.map { it.song }.filterNot { it.isPodcast() }
        entries.forEach { entry ->
            trackById[entry.mediaId] = entry.song
            queueByTrackId[entry.mediaId] = if (entry.song.isPodcast()) listOf(entry.song) else musicQueue
        }
        return entries.map { playable(it.song, it.mediaId) }
    }

    private fun pruneTrackCache() {
        trackById.keys.removeAll { carCatalog.find(it) == null }
        queueByTrackId.keys.removeAll { carCatalog.find(it) == null }
        collectionById.keys.removeAll { carFolders.find(it) == null }
    }

    private fun folder(id: String, title: String, icon: String = "ic_library_outline", childStyle: Int = 1): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setArtworkUri(Uri.parse("android.resource://$packageName/drawable/$icon"))
                    .setExtras(Bundle().apply {
                        putInt(STYLE_BROWSABLE, childStyle)
                        putInt(STYLE_PLAYABLE, if (childStyle == 2) 2 else 1)
                        putInt(STYLE_ITEM, 3)
                    })
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            )
            .build().also {
                if (browseFolders.size >= 2000) browseFolders.clear()
                browseFolders[id] = it
            }

    private fun playable(song: SongsModel, mediaId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.singer)
                    .setAlbumTitle(song.album)
                    .setMediaType(if (song.isPodcast()) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(if (AutoArtworkPolicy.allowedBrowseSong(song) && song.coverUri.startsWith("https://"))
                        carArtworkUri(mediaId, song.coverUri)
                        else Uri.parse("android.resource://$packageName/drawable/placeholder"))
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build(),
            )
            .build()

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try {
                f.set(block())
            } catch (e: Exception) {
                f.setException(e)
            }
        }
        f.addListener({ if (f.isCancelled) job.cancel() }, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        return f
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        SongPlayer.saveCurrentPosition()
        if (resumptionStartup) return // Bounded by the resumption watchdog.
        // Media3 1.3.1 has no default onTaskRemoved policy. Keep playing/buffering audio
        // alive when the phone task is dismissed; a paused, unbound service may stop.
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED) stopSelf()
    }

    override fun onDestroy() {
        carConnectionType.removeObserver(carConnectionObserver)
        cancelPendingResumption()
        finishResumptionStartup(stop = true)
        SongPlayer.saveCurrentPosition()
        com.music.spotui.util.PresenceCoordinator.onPlaybackServiceDestroyed()
        SongPlayer.setOnTrackEndedListener(null)
        SongPlayer.exoPlayer?.removeListener(mediaItemListener)
        pendingResolvedTracks.clear()
        externalRequests.invalidate()
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
