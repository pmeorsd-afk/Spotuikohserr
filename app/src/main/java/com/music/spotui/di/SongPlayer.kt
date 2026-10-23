package com.music.spotui.di

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.utils.YouTubeUrlParser
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.YTPlayerUtils
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackFailure
import com.music.spotui.playback.PlaybackRetry
import com.music.spotui.playback.BackgroundPreloader
import com.music.spotui.playback.PlaybackSourceCache
import com.music.spotui.playback.PlaybackStartup
import com.music.spotui.playback.SharedResolution
import kotlinx.coroutines.ensureActive
import com.music.spotui.playback.PlaybackControlState
import com.music.spotui.playback.PlaybackOwnership
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.DefaultEpisodePlaybackResolver
import com.music.spotui.playback.EpisodePlaybackResolver
import com.music.spotui.playback.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Plays audio resolved from YouTube. The `song` argument is a "title artist"
 * search query (set by the Spotify-backed data layer): it's matched to a
 * YouTube video, whose stream URL is resolved via the ported [YTPlayerUtils]
 * flow (cipher / PoToken / sabr) and handed to ExoPlayer.
 */
object SongPlayer {
    private const val TAG = "SongPlayer"
    private const val SPOTIFY_TRACK_PREFIX = "spotify:track:"
    private var player: ExoPlayer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val ownership = PlaybackOwnership()
    val currentPlaybackToken: String get() = ownership.current.token
    val activePlaybackToken: String? get() = ownership.active?.token
    @Volatile private var resolutionJob: kotlinx.coroutines.Job? = null
    @Volatile private var qualityUpgradeJob: kotlinx.coroutines.Job? = null
    @Volatile private var errorRecoveryJob: kotlinx.coroutines.Job? = null
    @Volatile private var onTrackEndedListener: ((token: String) -> Unit)? = null

    fun setOnTrackEndedListener(listener: ((token: String) -> Unit)?) {
        onTrackEndedListener = listener
    }

    // Cache of resolved stream URLs keyed by the "title artist" query, so replays
    // and prefetched neighbours start instantly instead of re-hitting the network.
    private data class CachedStream(val url: String, val source: String, val quality: String)
    private data class SourceScope(val network: String, val settings: String, val session: List<String?>, val revision: Long)
    private val sourceRevision = java.util.concurrent.atomic.AtomicLong()
    private data class MatchScope(val metadata: TrackMatchMetadata?, val duration: Int?, val explicit: Boolean?)
    private fun matchScope(query: String) = MatchScope(metadataRegistry[query], durationRegistry[query], explicitRegistry[query])
    private data class StreamScope(val source: SourceScope, val match: MatchScope)
    private fun streamScope(query: String, context: Context) = StreamScope(sourceScope(context), matchScope(query))
    private data class ResolutionKey(val query: String, val quality: AudioQuality, val scope: SourceScope, val match: MatchScope)
    private val streamCache = PlaybackSourceCache<String, CachedStream>({ android.os.SystemClock.elapsedRealtime() })
    private val ytCache = PlaybackSourceCache<ResolutionKey, YTPlayerUtils.PlaybackData>({ android.os.SystemClock.elapsedRealtime() })
    private val ytRequests = SharedResolution<ResolutionKey, YTPlayerUtils.PlaybackData?>(scope)
    private val startup = PlaybackStartup({ android.os.SystemClock.elapsedRealtime() }) { Log.i("PlaybackTiming", it) }
    private var startupWatchdog: kotlinx.coroutines.Job? = null
    private val retryPlayback = PlaybackRetry()
    private var recoveredToken: String? = null

    private fun sourceScope(context: Context): SourceScope {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        return SourceScope(
            cm?.activeNetwork?.toString().orEmpty(),
            listOf(
                com.music.spotui.data.preferences.currentStreamingQuality(context),
                com.music.spotui.data.preferences.getPrimaryMusicSource(context),
                youtubeEnabled, deezerEnabled, losslessStreaming, losslessHiRes,
                com.music.spotui.data.preferences.isDeezerEnabled(context), YouTube.proxy,
            ).joinToString("|"),
            listOf(YouTube.cookie, com.music.spotui.data.preferences.getDeezerArl(context),
                com.music.spotui.data.api.SpotifySession.spDc(context)),
            sourceRevision.get(),
        )
    }

    private fun cacheStream(song: String, url: String, source: String, quality: String, key: StreamScope, lifetimeMs: Long = 300_000) {
        // Conservative cap for providers without explicit expiry; honor signed URL expiry too.
        val expiry = runCatching {
            java.net.URI(url).rawQuery?.split('&')?.firstOrNull { it.startsWith("expire=") }
                ?.substringAfter('=')?.toLongOrNull()?.times(1000)
        }.getOrNull()
        val lifetime = minOf(lifetimeMs, expiry?.let { it - System.currentTimeMillis() - 30_000 } ?: 300_000L)
        streamCache.put(song, CachedStream(url, source, quality), key, lifetime)
        sourceCache[song] = source
        qualityCache[song] = quality
    }

    private val preloader = BackgroundPreloader<String>(
        scope,
        canRun = { !startup.pending },
        load = { song ->
            val ctx = appCtx
            if (ctx != null && com.music.spotui.data.preferences.isPreloadEnabled(ctx)) {
                val url = resolveStreamUrl(song, ctx, forPlayback = false)
                if (url != null && !startup.pending) cacheIntro(url, ctx)
            }
        },
    )
    // Which engine each cached stream came from ("YouTube", "Lossless • …") so a
    // cache hit can restore the correct source badge.
    private val sourceCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    // ── Lossless (SpotiFLAC) ──
    // When enabled, playback first tries to resolve a lossless FLAC stream (Tidal/
    // Amazon via SpotiFLAC's free community proxies) for the current track and only
    // falls back to YouTube if no FLAC is available or the proxies are throttled.
    // Trades a little first-tap latency for true lossless audio.
    @Volatile var losslessStreaming = true
    @Volatile var losslessHiRes = true

    // Source kill-switches. The Spotify web player is currently broken (off).
    // YouTube is the last-resort fallback, kept on so tracks SpotiFLAC misses or
    // can't serve during a proxy cooldown still play — with the wrong-song guards
    // (videoId match check + artist/title scoring + candidate fallback).
    @Volatile var webPlayerEnabled = false
    @Volatile var youtubeEnabled = true

    // ── Deezer ──
    // When enabled and a Deezer account is logged in (ARL stored), playback tries
    // Deezer FIRST (ISRC → Deezer track → encrypted CDN stream, decrypted on the
    // fly by DeezerDataSource). Quality follows the account tier: free → MP3 128,
    // Premium → MP3 320 / FLAC. Falls back to SpotiFLAC/YouTube on any miss.
    @Volatile var deezerEnabled = true

    // Which engine is feeding the CURRENT track, for the on-screen source badge.
    // "Lossless" (SpotiFLAC: Tidal/Qobuz/Amazon) is NOT Spotify — surfaced so the
    // user knows real Spotify vs a lossless mirror vs the YouTube fallback.
    @Volatile var currentSource: String = "YouTube"
        internal set
    // Human-readable quality of the CURRENT stream (e.g. "FLAC 16-bit",
    // "OPUS 141 kbps"), shown next to the source badge.
    @Volatile var currentQuality: String = ""
        private set
    private val qualityCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Maps a "title artist" play query -> the track's real Spotify id, so the
    // lossless resolver can be seeded from a play site that only has the query.
    // Populated centrally whenever the queue changes (see CurrentSongState).
    private val trackIdRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val alternativeKeyRegistry = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Register query→spotifyTrackId pairs so lossless can be resolved by query. */
    fun registerLossless(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, spotifyId) ->
            if (query.isNotBlank() && spotifyId.isNotBlank()) trackIdRegistry[query] = spotifyId
        }
    }

    fun registerAlternativeKeys(pairs: List<Pair<String, String>>) {
        pairs.forEach { (query, key) ->
            if (query.isNotBlank() && key.isNotBlank()) alternativeKeyRegistry[query] = key
        }
    }

    // Whether each play query is the explicit version on Spotify, so the YouTube
    // fallback can pick the matching (explicit vs clean) edit.
    private val explicitRegistry = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** Register query→explicit pairs (populated whenever the queue changes). */
    fun registerExplicit(pairs: List<Pair<String, Boolean>>) {
        pairs.forEach { (query, explicit) ->
            if (query.isNotBlank()) explicitRegistry[query] = explicit
        }
    }

    // Expected track length (ms) per query, from Spotify — lets the YouTube match
    // reject a same-title song by a different artist (different duration).
    private val durationRegistry = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Register query→durationMs pairs (populated whenever the queue changes). */
    fun registerDuration(pairs: List<Pair<String, Int>>) {
        pairs.forEach { (query, ms) ->
            if (query.isNotBlank() && ms > 0) durationRegistry[query] = ms
        }
    }

    data class TrackMatchMetadata(
        val title: String,
        val artist: String,
        val album: String,
        val isrc: String = "",
    )

    private val metadataRegistry =
        java.util.concurrent.ConcurrentHashMap<String, TrackMatchMetadata>()
    // GQL track objects do not consistently carry the explicit flag. Keep this
    // separate from explicitRegistry so the model's default `false` is not
    // mistaken for a confirmed clean track.
    private val spotifyMetadataRepaired = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Register query→Spotify metadata pairs for strict YouTube result scoring. */
    fun registerMetadata(pairs: List<Pair<String, TrackMatchMetadata>>) {
        pairs.forEach { (query, meta) ->
            if (query.isNotBlank() && meta.title.isNotBlank()) metadataRegistry[query] = meta
        }
    }

    // ── Media Type & Episode Registry ──
    private val mediaTypeRegistry = java.util.concurrent.ConcurrentHashMap<String, MediaType>()
    private val episodeModelRegistry =
        java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.SongsModel>()

    @Volatile var episodeResolver: EpisodePlaybackResolver = DefaultEpisodePlaybackResolver()

    fun setEpisodePlaybackResolver(resolver: EpisodePlaybackResolver) {
        episodeResolver = resolver
    }

    /** Register query→MediaType pairs (populated whenever the queue changes). */
    fun registerMediaType(pairs: List<Pair<String, MediaType>>) {
        pairs.forEach { (query, type) ->
            if (query.isNotBlank()) mediaTypeRegistry[query] = type
        }
    }

    fun registerMediaType(query: String, type: MediaType) {
        if (query.isNotBlank()) mediaTypeRegistry[query] = type
    }

    /** Register query→SongsModel pairs for podcast episodes. */
    fun registerEpisodeModel(pairs: List<Pair<String, com.music.spotui.data.entity.SongsModel>>) {
        pairs.forEach { (query, model) ->
            if (query.isNotBlank()) episodeModelRegistry[query] = model
        }
    }

    fun registerEpisodeModel(query: String, model: com.music.spotui.data.entity.SongsModel) {
        if (query.isNotBlank()) episodeModelRegistry[query] = model
    }
    // Tracks which query is the latest play request so a slow resolve for an old
    // tap doesn't clobber a newer one (fast switching).
    private val currentRequest: String get() = ownership.current.request

    // Latest track metadata (title / artist / cover URL) so the MediaItem we build
    // carries it into the system media notification. Set via [setNowPlayingMeta]
    // (driven by CurrentSongState) just before / as playback starts.
    @Volatile private var metaTitle: String = ""
    @Volatile private var metaArtist: String = ""
    @Volatile private var metaCover: String = ""

    fun setNowPlayingMeta(title: String, artist: String, coverUri: String) {
        metaTitle = title
        metaArtist = artist
        metaCover = coverUri
    }

    /**
     * Build a stable playback identity for Spotify tracks. The full value is used
     * as cache/registry key, while only the text after "|" is sent to YouTube
     * search. This prevents same-title/same-artist tracks from reusing each
     * other's resolved stream.
     */
    fun buildSpotifyPlayQuery(spotifyTrackId: String, title: String, artist: String): String {
        val searchText = listOf(cleanSpotifySearchTitle(title), artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return if (spotifyTrackId.isBlank()) searchText else "$SPOTIFY_TRACK_PREFIX$spotifyTrackId|$searchText"
    }

    private val featSearchPattern = Regex("""\s*[\(\[]\s*(feat|ft)\..*?[\)\]]""", RegexOption.IGNORE_CASE)

    private fun cleanSpotifySearchTitle(title: String): String =
        title.replace(featSearchPattern, "").trim()

    private fun searchTextForPlayback(song: String): String =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX) && song.contains('|')) {
            song.substringAfter('|').ifBlank { song }
        } else {
            song
        }

    private fun spotifyTrackIdForPlayback(song: String): String? =
        if (song.startsWith(SPOTIFY_TRACK_PREFIX)) {
            song.removePrefix(SPOTIFY_TRACK_PREFIX).substringBefore('|').takeIf { it.isNotBlank() }
        } else {
            null
        }

    fun videoIdFromYouTubeLink(text: String): String? =
        YouTubeUrlParser.extractVideoId(text)
            ?: text.trim().takeIf { it.matches(Regex("""[A-Za-z0-9_-]{11}""")) }

    fun invalidateResolvedStream(song: String) {
        sourceRevision.incrementAndGet()
        discardCachedStream(song)
    }

    private fun discardCachedStream(song: String) {
        streamCache.remove(song)
        sourceCache.remove(song)
        qualityCache.remove(song)
    }


    private data class PlaybackItemTag(val token: String)

    private fun itemToken(item: MediaItem?): String? = when (val tag = item?.localConfiguration?.tag) {
        is PlaybackOwnership.Ticket -> tag.token
        is PlaybackItemTag -> tag.token
        else -> null
    }

    private fun ownsPlayerItem(token: String, p: ExoPlayer? = player): Boolean =
        p != null && p === player && ownership.isCurrent(token) && itemToken(p.currentMediaItem) == token

    private fun cancelPendingPlaybackWork() {
        startupWatchdog?.cancel()
        startupWatchdog = null
        startup.cancel()
        retryPlayback.clear()
        preloader.clearQueued()
        resolutionJob?.cancel()
        resolutionJob = null
        qualityUpgradeJob?.cancel()
        qualityUpgradeJob = null
        errorRecoveryJob?.cancel()
        errorRecoveryJob = null
        positionWatchJob?.cancel()
        positionWatchJob = null
        cancelCrossfade()
    }

    /** Capture the outgoing tagged owner even if UI/queue already moved to the next selection. */
    internal fun saveCurrentPosition(completed: Boolean = false) {
        val owner = ownership.active ?: return
        val p = player ?: return
        val ctx = appCtx ?: return
        if (!ownsPlayerItem(owner.token, p) || p.playbackState == Player.STATE_IDLE) return
        val model = owner.song ?: SongsModel().copy(
            title = "Playback", url = owner.request, mediaType = owner.mediaType,
        )
        com.music.spotui.data.preferences.savePlaybackPosition(
            ctx, model, p.currentPosition, completed, durationMs = p.duration,
        )
    }

    /** Reserve external ownership before even identity lookup; an invalid request cannot resume old audio. */
    internal fun beginExternalPlaybackRequest(context: Context) {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        appCtx = context.applicationContext
        pause()
        ownership.begin("", MediaType.TRACK, external = true)
        cancelPendingPlaybackWork()
        restoreQuery = null
    }

    internal fun canControlSessionPlayer(base: Player): Boolean =
        base === player && ownership.canControl(itemToken(base.currentMediaItem))

    /** Invalidates the former owner before external source resolution. No source lookup or play here. */
    internal fun beginExternalPlaybackTakeover(
        song: SongsModel,
        mediaId: String,
        context: Context,
    ): PlaybackOwnership.Ticket {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        appCtx = context.applicationContext
        saveCurrentPosition()
        val ticket = ownership.begin(song.url, song.mediaType, song, external = true, mediaId = mediaId)
        cancelPendingPlaybackWork()
        restoreQuery = null
        player?.pause()
        return ticket
    }

    internal fun isExternalPlaybackCurrent(ticket: PlaybackOwnership.Ticket): Boolean =
        ticket.external && ownership.isCurrent(ticket.token)

    /** Called by the transition listener after Media3 has accepted the item on our ExoPlayer. */
    internal fun adoptExternalPlayback(ticket: PlaybackOwnership.Ticket, item: MediaItem): Boolean {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        if (!isExternalPlaybackCurrent(ticket) || item.mediaId != ticket.mediaId ||
            itemToken(item) != ticket.token || !ownsPlayerItem(ticket.token)
        ) return false
        if (!ownership.activate(ticket.token, item.mediaId)) return false
        val song = ticket.song ?: return false
        currentSource = if (ticket.mediaType == MediaType.PODCAST_EPISODE) "Podcast" else "MediaSession"
        currentQuality = item.localConfiguration?.mimeType.orEmpty()
        setNowPlayingMeta(song.title, song.singer, song.coverUri)
        startPositionWatch(ticket.token)
        return true
    }

    /** A resolved item can wait in Media3's command queue while another request takes ownership. */
    internal fun acceptsExternalMediaItems(items: List<MediaItem>): Boolean = items.isNotEmpty() && items.all {
        val ticket = it.localConfiguration?.tag as? PlaybackOwnership.Ticket
        ticket != null && isExternalPlaybackCurrent(ticket)
    }


    private suspend fun commitIfCurrent(token: String, action: () -> Unit) {
        withContext(Dispatchers.Main) { ownership.runIfCurrent(token, action) }
    }

    /** Actual tagged Player ownership, never inferred from CurrentSongState. Main-thread only. */
    internal fun ownedPlaybackSnapshot(): PlaybackSnapshot? {
        val owner = ownership.active ?: return null
        val model = owner.song ?: return null
        val p = player ?: return null
        if (!ownsPlayerItem(owner.token, p) || p.playerError != null ||
            p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) return null
        return PlaybackSnapshot(model, p.currentPosition.coerceAtLeast(0))
    }

    internal fun resumeOwnedPlayback(token: String): Boolean {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        if (!ownership.isCurrent(token) || ownedPlaybackSnapshot() == null) return false
        player?.play()
        return true
    }

    /** Resume an already verified full model without publishing queue/UI before source resolution. */
    internal fun resumeResolvedPlayback(song: SongsModel, positionMs: Long?, context: Context, onPrepared: () -> Unit) {
        registerMediaType(song.url, song.mediaType)
        if (song.mediaType == MediaType.PODCAST_EPISODE) {
            registerEpisodeModel(song.url, song)
        } else {
            registerLossless(listOf(song.url to song.spotifyTrackId))
            registerAlternativeKeys(listOf(song.url to com.music.spotui.data.preferences.alternativeStreamKey(song)))
            registerExplicit(listOf(song.url to song.explicit))
            registerDuration(listOf(song.url to song.durationMs))
            registerMetadata(listOf(song.url to TrackMatchMetadata(song.title, song.singer, song.album)))
        }
        playSong(song.url, context, song, positionMs?.coerceAtLeast(0), onPrepared)
    }

    fun playSong(song: String, context: Context) = playSong(song, context, null, null, null)

    private fun playSong(
        song: String, context: Context, resolvedSong: SongsModel?,
        startPositionMs: Long?, onPrepared: (() -> Unit)?,
    ) {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        val appContext = context.applicationContext
        appCtx = appContext
        val model = resolvedSong ?: boundState?.queue?.value?.find { it.url == song } ?: episodeModelRegistry[song]
        val type = if (model?.isPodcast() == true || song.startsWith("episode:") ||
            mediaTypeRegistry[song] == MediaType.PODCAST_EPISODE) MediaType.PODCAST_EPISODE else MediaType.TRACK
        saveCurrentPosition()
        val progressModel = model ?: SongsModel().copy(title = "Playback", url = song, mediaType = type)
        // A different selection immediately disarms the cold-start MiniPlayer bookmark,
        // even if its preparation is cancelled before another song becomes ready.
        if (song != restoreQuery) restoreQuery = null
        val resumePosition = com.music.spotui.data.preferences.loadPlaybackPosition(
            appContext, progressModel, startPositionMs ?: restorePositionMs.takeIf { song == restoreQuery },
        )
        val preparedToken = ownership.begin(song, type, model).token
        Log.i(TAG, "playSong: requested '$song'")
        cancelPendingPlaybackWork()
        retryPlayback.set(preparedToken) { playSong(song, appContext, model, null, onPrepared) }
        recoveredToken = null
        if (type == MediaType.TRACK) {
            startup.begin(preparedToken, ownership.current.generation)
            startupWatchdog = scope.launch(Dispatchers.Main) {
                delay(45_000)
                if (startup.pending) failPlayback(preparedToken, appContext, PlaybackFailure.TIMEOUT)
            }
        }
        // Do not keep the previous track audible while this request resolves.
        // Otherwise the UI can show the newly tapped track while old audio keeps
        // playing for several seconds, or forever if resolution fails.
        runCatching {
            player?.pause()
        }

        // Podcast episodes are routed directly to the EpisodePlaybackResolver (RSS / direct audio engine),
        // completely bypassing music track matching and YouTube search.
        val isPodcast = type == MediaType.PODCAST_EPISODE

        if (isPodcast) {
            val startTimeMs = System.currentTimeMillis()
            resolutionJob = scope.launch {
                try {
                    val episodeModel = resolvedSong ?: episodeModelRegistry[song] ?: run {
                        val meta = metadataRegistry[song]
                        val epId = if (song.startsWith("episode:")) song.removePrefix("episode:") else ""
                        com.music.spotui.data.entity.SongsModel(
                            id = epId.hashCode(),
                            title = meta?.title ?: metaTitle,
                            album = meta?.album ?: metaArtist,
                            singer = meta?.artist ?: metaArtist,
                            coverUri = metaCover,
                            url = song,
                            spotifyTrackId = epId,
                            durationMs = durationRegistry[song] ?: 0,
                            mediaType = MediaType.PODCAST_EPISODE,
                        )
                    }
                    Log.i(TAG, "playSong: routing podcast episode to EpisodePlaybackResolver: title='${episodeModel.title}', id='${episodeModel.spotifyTrackId}'")
                    val source = episodeResolver.resolve(episodeModel, appContext)
                    if (source == null) {
                        Log.w(TAG, "playSong: episodeResolver returned null for podcast episode '${episodeModel.title}'")
                        commitIfCurrent(preparedToken) {
                            android.widget.Toast.makeText(
                                appContext,
                                "Could not resolve podcast audio stream",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            if (onPrepared == null) onStreamFailed?.invoke(song)
                        }
                        return@launch
                    }

                    when (source) {
                        is PlaybackSource.DirectAudio -> {
                            Log.i(TAG, "playSong: resolved DirectAudio podcast stream: ${source.url}")
                            if (!ownership.isCurrent(preparedToken)) return@launch
                            commitIfCurrent(preparedToken) {
                                currentSource = "Podcast"
                                currentQuality = source.mimeType
                                ensurePlayer(appContext)
                                resolvedSong?.let { setNowPlayingMeta(it.title, it.singer, it.coverUri) }
                                player!!.setMediaItem(buildMediaItem(source.url, source.mimeType))
                                player!!.prepare()
                                val position = resumePosition
                                if (position > 0) player!!.seekTo(position)
                                restoreQuery = null
                                ownership.activate(preparedToken)
                                saveCurrentPosition()
                                onPrepared?.invoke()
                                player!!.playWhenReady = true
                                val elapsed = System.currentTimeMillis() - startTimeMs
                                Log.i(TAG, "[PLAY_SPEED] Podcast episode '${episodeModel.title}' took $elapsed ms to start audio!")
                            }
                            commitIfCurrent(preparedToken) { startPositionWatch(preparedToken) }
                        }
                        is PlaybackSource.YouTube -> {
                            Log.i(TAG, "playSong: resolved YouTube podcast stream videoId: ${source.videoId}")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "playSong failed for podcast episode: $song", e)
                }
            }
            return
        }

        // Downloaded tracks ALWAYS play the local file — even with Spotify web
        // playback on. (Web is now the default and used to run first, so a
        // downloaded track streamed from Spotify instead of playing offline.)
        val downloadedPath = com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)
        if (resolvedSong == null && downloadedPath == null && webPlayerEnabled &&
            // Experimental: stream through Spotify's own web player (real Spotify audio,
            // no bypass) when enabled AND the device WebView has Widevine. Otherwise
            // fall through to the normal YouTube/FLAC engine so playback is never silent.
            com.music.spotui.data.preferences.isWebPlaybackEnabled(appContext) &&
            SpotifyWebPlayer.canPlay
        ) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            if (spotifyId != null) {
                runCatching { player?.pause() }
                currentSource = "Spotify"
                currentQuality = ""
                startup.cancel()
                startupWatchdog?.cancel()
                SpotifyWebPlayer.play(spotifyId)
                return
            }
            Log.w(TAG, "web playback on but no Spotify id for query: $song — using fallback engine")
        }
        resolutionJob = scope.launch {
            try {
                Log.i(TAG, "playSong: resolving stream for '$song'...")
                val streamUrl = resolveStreamUrl(song, appContext, forPlayback = true, playbackToken = preparedToken) ?: run {
                    Log.w(TAG, "playSong: resolveStreamUrl returned null for '$song'")
                    withContext(Dispatchers.Main) {
                        failPlayback(preparedToken, appContext, PlaybackFailure.UNAVAILABLE)
                    }
                    return@launch
                }
                startup.stage(preparedToken, "source_resolved")
                // A newer tap superseded this one while we were resolving — drop it.
                if (!ownership.isCurrent(preparedToken)) {
                    Log.w(TAG, "playSong: DROPPED because currentRequest ('$currentRequest') != song ('$song')")
                    return@launch
                }
                commitIfCurrent(preparedToken) {
                    startup.stage(preparedToken, "prepare")
                    ensurePlayer(appContext)
                    resolvedSong?.let { setNowPlayingMeta(it.title, it.singer, it.coverUri) }
                    player!!.setMediaItem(buildMediaItem(streamUrl, streamMimeType(streamUrl)))
                    player!!.prepare()
                    // Restored session: continue from where the last run stopped.
                    val position = resumePosition
                    if (position > 0) player!!.seekTo(position)
                    restoreQuery = null
                    ownership.activate(preparedToken)
                    saveCurrentPosition()
                    onPrepared?.invoke()
                    player!!.playWhenReady = true
                }
                commitIfCurrent(preparedToken) { startPositionWatch(preparedToken) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    failPlayback(preparedToken, appContext, PlaybackFailure.from(e))
                }
            }
        }
    }

    // Build a MediaItem carrying the current track's metadata so the system media
    // notification (MediaSession) shows the right title / artist / artwork.
    private fun buildMediaItem(streamUrl: String, mimeType: String? = null, token: String = currentPlaybackToken): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(metaTitle)
            .setArtist(metaArtist)
            .build()
        return MediaItem.Builder()
            .setUri(streamUrl)
            .setTag(PlaybackItemTag(token))
            // Hint the container so ExoPlayer picks the right source/extractor even
            // when the URL has no extension: TIDAL lossless is a DASH .mpd manifest,
            // and single-file lossless is FLAC.
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .setMediaMetadata(metadata)
            .build()
    }

    /** MIME hint for a resolved stream: DASH manifest, single-file FLAC, or none. */
    private fun streamMimeType(streamUrl: String, source: String = currentSource): String? {
        val bare = streamUrl.substringBefore('?').lowercase()
        return when {
            streamUrl.startsWith("deezer://") ->
                if (streamUrl.contains("fmt=flac")) androidx.media3.common.MimeTypes.AUDIO_FLAC
                else androidx.media3.common.MimeTypes.AUDIO_MPEG
            streamUrl.startsWith("data:application/dash+xml") ||
                bare.endsWith(".mpd") || streamUrl.contains("manifest.tidal.com") || streamUrl.contains("/manifests/") ->
                androidx.media3.common.MimeTypes.APPLICATION_MPD
            bare.endsWith(".flac") || source.startsWith("Lossless") ->
                androidx.media3.common.MimeTypes.AUDIO_FLAC
            else -> null
        }
    }

    /** Preview-only read of the existing source pipeline. Never publishes main-player state. */
    internal suspend fun resolveRadioPreview(song: SongsModel, context: Context): com.music.spotui.playback.RadioPreviewSource? {
        if (song.isPodcast() || song.url.isBlank()) return null
        registerMetadata(listOf(song.url to TrackMatchMetadata(song.title, song.singer, song.album)))
        registerDuration(listOf(song.url to song.durationMs))
        registerExplicit(listOf(song.url to song.explicit))
        registerLossless(listOf(song.url to song.spotifyTrackId))
        registerAlternativeKeys(listOf(song.url to com.music.spotui.data.preferences.alternativeStreamKey(song)))
        val url = resolveStreamUrl(song.url, context.applicationContext, forPlayback = false) ?: return null
        val source = streamCache.get(song.url, streamScope(song.url, context))?.source.orEmpty()
        return com.music.spotui.playback.RadioPreviewSource(url, streamMimeType(url, source))
    }

    /** Same headers/cache/decryption as music; no main listeners, session, filters or queue. */
    internal fun createRadioPreviewPlayer(context: Context): ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            com.music.spotui.deezer.DeezerAwareDataSourceFactory(cacheDataSourceFactory(context.applicationContext)))
            .setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy()))
        .setAudioAttributes(buildAudioAttributes(), true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    /** Bound speculative work; resolution is shared with a tap on the same exact query. */
    fun prefetch(song: String, context: Context) {
        if (song.isBlank() || song.startsWith("episode:") ||
            mediaTypeRegistry[song] == MediaType.PODCAST_EPISODE) return
        appCtx = context.applicationContext
        if (webPlaybackActive() || !com.music.spotui.data.preferences.isPreloadEnabled(context)) return
        preloader.offer(listOf(song))
    }

    fun prefetchList(songs: List<String>, context: Context, count: Int = 4) {
        appCtx = context.applicationContext
        if (webPlaybackActive() || !com.music.spotui.data.preferences.isPreloadEnabled(context)) return
        preloader.offer(songs.filter { it.isNotBlank() && !it.startsWith("episode:") &&
            mediaTypeRegistry[it] != MediaType.PODCAST_EPISODE }.take(count), replace = true)
    }

    /** Register the same exact matching inputs that a later tap will use. */
    fun prefetchSongs(songs: List<SongsModel>, context: Context, count: Int = 4) {
        val tracks = songs.filterNot { it.isPodcast() }.take(count)
        registerMetadata(tracks.map { it.url to TrackMatchMetadata(it.title, it.singer, it.album) })
        registerDuration(tracks.map { it.url to it.durationMs })
        registerExplicit(tracks.map { it.url to it.explicit })
        registerLossless(tracks.map { it.url to it.spotifyTrackId })
        registerAlternativeKeys(tracks.map { it.url to com.music.spotui.data.preferences.alternativeStreamKey(it) })
        prefetchList(tracks.map { it.url }, context, count)
    }
    // ── Intro preloading (instant playback) ──
    // Resolving the stream URL hides most latency, but ExoPlayer still has to open the
    // connection and buffer the first segment on tap. We pre-cache the first ~1 MB (≈20–40s
    // of audio) of upcoming tracks into a media cache the player reads through, so a tap on a
    // preloaded track starts almost instantly. Skipped for local files (already instant) and
    // when the user turns preloading off in Settings.
    private const val PRELOAD_BYTES = 1L * 1024 * 1024

    @Volatile private var mediaCache: androidx.media3.datasource.cache.SimpleCache? = null

    private fun mediaCache(context: Context): androidx.media3.datasource.cache.SimpleCache =
        mediaCache ?: synchronized(this) {
            mediaCache ?: androidx.media3.datasource.cache.SimpleCache(
                java.io.File(context.cacheDir, "media"),
                androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                androidx.media3.database.StandaloneDatabaseProvider(context),
            ).also { mediaCache = it }
        }

    private fun cacheDataSourceFactory(context: Context): androidx.media3.datasource.cache.CacheDataSource.Factory {
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
            com.metrolist.music.utils.YTPlayerUtils.httpClient,
        )
        val chunkedHttp = com.music.spotui.playback.ChunkedDataSource.Factory(
            http,
            com.music.spotui.playback.ChunkedDataSource.DEFAULT_CHUNK_BYTES,
        )
        val resolvedHttp = androidx.media3.datasource.ResolvingDataSource.Factory(chunkedHttp) { dataSpec ->
            val url = dataSpec.uri.toString()
            if (url.contains("googlevideo.com")) {
                val client = com.metrolist.innertube.models.YouTubeClient.forStreamUrl(url)
                val headers = client.mediaHeaders()
                android.util.Log.d("SongPlayer_DS", "Resolving googlevideo DataSpec for pos=${dataSpec.position}, len=${dataSpec.length}, client=${client.clientName} (${client.clientVersion}): headers=$headers")
                dataSpec.withAdditionalHeaders(headers)
            } else {
                android.util.Log.d("SongPlayer_DS", "Resolving non-googlevideo DataSpec for pos=${dataSpec.position}, len=${dataSpec.length}: $url")
                dataSpec
            }
        }
        val baseUpstream = androidx.media3.datasource.DefaultDataSource.Factory(context, resolvedHttp)
        return androidx.media3.datasource.cache.CacheDataSource.Factory()
            .setCache(mediaCache(context))
            .setUpstreamDataSourceFactory(baseUpstream)
            .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Pre-cache the first [PRELOAD_BYTES] of [url] into the media cache (http(s) only). */
    private fun cacheIntro(url: String, appContext: Context) {
        if (!url.startsWith("http")) return
        if (!com.music.spotui.data.preferences.isPreloadEnabled(appContext)) return
        runCatching {
            val ds = cacheDataSourceFactory(appContext).createDataSource()
            val spec = androidx.media3.datasource.DataSpec.Builder()
                .setUri(android.net.Uri.parse(url))
                .setLength(PRELOAD_BYTES)
                .build()
            androidx.media3.datasource.cache.CacheWriter(ds, spec, null, null).cache()
        }.onFailure { Log.d(TAG, "intro preload skipped: ${it.message}") }
    }

    // forPlayback=true only for the track actually being played — so background
    // prefetch of upcoming tracks doesn't clobber the current source badge (a
    // prefetch resolving the NEXT track via YouTube was flipping the badge to
    // "YouTube" while the current track streamed from Spotify).
    private suspend fun resolveStreamUrl(song: String, appContext: Context, forPlayback: Boolean = false, playbackToken: String = currentPlaybackToken): String? {
        val cacheScope = streamScope(song, appContext)
        // Podcast episodes are handled by EpisodePlaybackResolver and must never enter the music pipeline
        if (song.startsWith("episode:") || mediaTypeRegistry[song] == MediaType.PODCAST_EPISODE) {
            Log.d(TAG, "resolveStreamUrl: skipping music stream resolution for podcast episode '$song'")
            return null
        }
        // Imported local files: the play query IS the file's content:// / file:// URI.
        // ExoPlayer plays it directly (FLAC/MP3/WAV/… via its built-in extractors).
        if (song.startsWith("content://") || song.startsWith("file://")) {
            if (forPlayback) ownership.runIfCurrent(playbackToken) {
                currentSource = "Local file"
                currentQuality = song.substringBefore('?').substringAfterLast('.', "")
                    .uppercase().takeIf { it.length in 2..5 }.orEmpty()
            }
            return song
        }

        // Direct YouTube track playback (instant start, no search / no proxy latency)
        val directVideoId = when {
            song.startsWith("youtube:") -> song.removePrefix("youtube:").substringBefore('|').trim()
            song.startsWith("yt:") -> song.removePrefix("yt:").substringBefore('|').trim()
            else -> null
        }
        if (directVideoId != null) {
            val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
            if (forPlayback) ownership.runIfCurrent(playbackToken) {
                currentSource = "YouTube"
                currentQuality = ""
            }
            val playback = resolveYtPlayback(directVideoId, quality.audioQuality, appContext)
            if (playback != null) {
                val codec = playback.format.mimeType
                    .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
                    .uppercase()
                val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
                    .filter { it.isNotBlank() }.joinToString(" ")
                if (forPlayback) ownership.runIfCurrent(playbackToken) { currentQuality = ytQuality }
                cacheStream(song, playback.streamUrl, "YouTube", ytQuality, cacheScope, playback.streamExpiresInSeconds.toLong() * 1000 - 30_000)
                return playback.streamUrl
            }
        }
        alternativeStreamForPlayback(song, appContext)?.let { alt ->
            discardCachedStream(song)
            return when {
                alt.isLocal -> {
                    if (forPlayback) ownership.runIfCurrent(playbackToken) {
                        currentSource = "Alternative file"
                        currentQuality = alt.label.substringAfterLast('.', "").uppercase().takeIf { it.length in 2..5 }.orEmpty()
                    }
                    alt.value
                }
                alt.isYouTube -> {
                    if (forPlayback) ownership.runIfCurrent(playbackToken) {
                        currentSource = "Alternative YouTube"
                        currentQuality = ""
                    }
                    val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
                    val playback = resolveYtPlayback(alt.value, quality.audioQuality, appContext) ?: return null
                    val codec = playback.format.mimeType
                        .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
                        .uppercase()
                    val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
                        .filter { it.isNotBlank() }.joinToString(" ")
                    if (forPlayback) ownership.runIfCurrent(playbackToken) { currentQuality = ytQuality }
                    playback.streamUrl
                }
                else -> null
            }
        }
        // Offline: if this track was downloaded, play the local file instead of the network.
        com.music.spotui.data.preferences.downloadedPathForQuery(appContext, song)?.let { path ->
            if (forPlayback) ownership.runIfCurrent(playbackToken) {
                currentSource = "Downloaded"
                currentQuality = path.substringAfterLast('.', "").uppercase()
            }
            return android.net.Uri.fromFile(java.io.File(path)).toString()
        }
        streamCache.get(song, cacheScope)?.let { cached ->
            // Source choices take effect immediately; never let a URL cached under the
            // previous choice silently keep using that provider.
            val cachedSource = cached.source
            val selectedSource = com.music.spotui.data.preferences.getPrimaryMusicSource(appContext)
            val sourceStillSelected = when (cachedSource) {
                "Deezer" -> selectedSource == com.music.spotui.data.preferences.MusicSource.DEEZER
                "YouTube" -> selectedSource != com.music.spotui.data.preferences.MusicSource.DEEZER
                else -> true // local, downloaded, alternative and lossless sources are independent
            }
            if (!sourceStillSelected) {
                discardCachedStream(song)
            } else {
            // Cache hits must still update the badge — returning early kept the
            // previous track's label (e.g. "Downloaded") on a streamed track.
                if (forPlayback) ownership.runIfCurrent(playbackToken) {
                    currentSource = cachedSource
                    currentQuality = cached.quality
                }
                return cached.url
            }
        }
        // Quality for the current network (Wi-Fi vs cellular), from Settings.
        val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)
        val primarySource = com.music.spotui.data.preferences.getPrimaryMusicSource(appContext)

        // 1. If user explicitly chose DEEZER as Primary in Settings, try Deezer first
        if (primarySource == com.music.spotui.data.preferences.MusicSource.DEEZER && deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val r = kotlinx.coroutines.withTimeoutOrNull(2_000) {
                com.music.spotui.deezer.DeezerSource.resolve(
                    appContext,
                    spotifyId = spotifyId,
                    isrc = null,
                    searchQuery = searchTextForPlayback(song),
                    expectedDurationSec = (durationRegistry[song] ?: 0) / 1000,
                )
            }
            if (r is com.music.spotui.deezer.DeezerSource.Result.Success) {
                if (forPlayback) ownership.runIfCurrent(playbackToken) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
                cacheStream(song, r.uri, "Deezer", r.qualityLabel, cacheScope)
                return r.uri
            }
        }

        // 2. Fast Path: Resolve YouTube stream IMMEDIATELY (< 200ms with TrackVideoCache + ANDROID_VR client)
        if (youtubeEnabled) {
            val playback = resolveYtPlayback(song, quality.audioQuality, appContext)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (playback != null) {
                val codec = playback.format.mimeType
                    .substringAfter("codecs=\"", "").substringBefore('"').substringBefore('.')
                    .uppercase()
                val ytQuality = listOf(codec, "${playback.format.bitrate / 1000} kbps")
                    .filter { it.isNotBlank() }.joinToString(" ")
                if (forPlayback) ownership.runIfCurrent(playbackToken) {
                    currentSource = "YouTube"
                    currentQuality = ytQuality
                }
                cacheStream(song, playback.streamUrl, "YouTube", ytQuality, cacheScope, playback.streamExpiresInSeconds.toLong() * 1000 - 30_000)

                // Seamless background upgrade to Lossless FLAC or Deezer if enabled, without delaying start
                if (forPlayback && ownership.isCurrent(playbackToken) && (losslessStreaming && quality.lossless || (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)))) {
                    triggerBackgroundUpgrade(song, playbackToken, appContext)
                }

                return playback.streamUrl
            }
        }

        // 3. Fallback: If YouTube failed, try Deezer/Lossless before failing
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
            val r = kotlinx.coroutines.withTimeoutOrNull(2_500) {
                com.music.spotui.deezer.DeezerSource.resolve(
                    appContext,
                    spotifyId = spotifyId,
                    isrc = null,
                    searchQuery = searchTextForPlayback(song),
                    expectedDurationSec = (durationRegistry[song] ?: 0) / 1000,
                )
            }
            if (r is com.music.spotui.deezer.DeezerSource.Result.Success) {
                if (forPlayback) ownership.runIfCurrent(playbackToken) { currentSource = "Deezer"; currentQuality = r.qualityLabel }
                cacheStream(song, r.uri, "Deezer", r.qualityLabel, cacheScope)
                return r.uri
            }
        }

        if (losslessStreaming && quality.lossless) {
            (trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song))?.let { spotifyId ->
                val r = kotlinx.coroutines.withTimeoutOrNull(2_500) {
                    com.music.spotui.lossless.LosslessSource.resolve(appContext, spotifyId, preferHiRes = losslessHiRes)
                }
                if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                    val flacQuality = "FLAC ${r.track.quality}-bit"
                    if (forPlayback) ownership.runIfCurrent(playbackToken) {
                        currentSource = "Lossless • ${r.track.provider}"
                        currentQuality = flacQuality
                    }
                    cacheStream(song, r.track.url, "Lossless • ${r.track.provider}", flacQuality, cacheScope)
                    return r.track.url
                }
            }
        }

        Log.w(TAG, "No playable stream found across any source for: $song")
        return null
    }

    private suspend fun triggerBackgroundUpgrade(song: String, playbackToken: String, appContext: Context) = withContext(Dispatchers.Main) {
        if (!ownership.isCurrent(playbackToken) || currentRequest != song) return@withContext
        qualityUpgradeJob?.cancel()
        val cacheScope = streamScope(song, appContext)
        qualityUpgradeJob = scope.launch {
            try {
                // Short delay to let the live track start playing smoothly without competing for initial bandwidth
                delay(1_200)
                while (startup.pending && ownership.isCurrent(playbackToken)) delay(250)
                if (!ownership.isCurrent(playbackToken) || currentRequest != song) return@launch

                val spotifyId = trackIdRegistry[song] ?: spotifyTrackIdForPlayback(song)
                val quality = com.music.spotui.data.preferences.currentStreamingQuality(appContext)

                // 1. Try Lossless FLAC first (Tidal / SpotiFLAC)
                if (losslessStreaming && quality.lossless && spotifyId != null) {
                    val r = kotlinx.coroutines.withTimeoutOrNull(4_000) {
                        com.music.spotui.lossless.LosslessSource.resolve(appContext, spotifyId, preferHiRes = losslessHiRes)
                    }
                    if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                        if (!ownership.isCurrent(playbackToken) || currentRequest != song) return@launch
                        val flacQuality = "FLAC ${r.track.quality}-bit"
                        Log.d(TAG, "Background upgrade to Lossless (${r.track.provider}): $song")
                        cacheStream(song, r.track.url, "Lossless • ${r.track.provider}", flacQuality, cacheScope)
                        withContext(Dispatchers.Main) {
                            ownership.runIfCurrent(playbackToken) {
                                if (!ownsPlayerItem(playbackToken)) return@runIfCurrent
                                currentSource = "Lossless • ${r.track.provider}"
                                currentQuality = flacQuality
                                val curPos = player!!.currentPosition
                                val wasPlaying = player!!.playWhenReady
                                player!!.setMediaItem(buildMediaItem(r.track.url, streamMimeType(r.track.url)), curPos)
                                player!!.prepare()
                                player!!.playWhenReady = wasPlaying
                            }
                        }
                        return@launch
                    }
                }

                // 2. Try Deezer FLAC if Deezer is enabled
                if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
                    val r = kotlinx.coroutines.withTimeoutOrNull(4_000) {
                        com.music.spotui.deezer.DeezerSource.resolve(
                            appContext,
                            spotifyId = spotifyId,
                            isrc = null,
                            searchQuery = searchTextForPlayback(song),
                            expectedDurationSec = (durationRegistry[song] ?: 0) / 1000,
                        )
                    }
                    if (r is com.music.spotui.deezer.DeezerSource.Result.Success && r.mimeFlac) {
                        if (!ownership.isCurrent(playbackToken) || currentRequest != song) return@launch
                        Log.d(TAG, "Background upgrade to Deezer FLAC: $song")
                        cacheStream(song, r.uri, "Deezer", r.qualityLabel, cacheScope)
                        withContext(Dispatchers.Main) {
                            ownership.runIfCurrent(playbackToken) {
                                if (!ownsPlayerItem(playbackToken)) return@runIfCurrent
                                currentSource = "Deezer"
                                currentQuality = r.qualityLabel
                                val curPos = player!!.currentPosition
                                val wasPlaying = player!!.playWhenReady
                                player!!.setMediaItem(buildMediaItem(r.uri, streamMimeType(r.uri)), curPos)
                                player!!.prepare()
                                player!!.playWhenReady = wasPlaying
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Background upgrade skipped: ${e.message}")
            }
        }
    }

    private fun alternativeStreamForPlayback(
        song: String,
        appContext: Context,
    ): com.music.spotui.data.preferences.AlternativeStream? {
        val key = alternativeKeyRegistry[song]
            ?: spotifyTrackIdForPlayback(song)?.let {
                com.music.spotui.data.preferences.alternativeStreamKeyForSpotifyId(it)
            }
        return key?.let { com.music.spotui.data.preferences.getAlternativeStream(appContext, it) }
    }

    // ── Downloads (offline playback) ──
    // Tracks which song queries are mid-download so the UI can show a spinner and
    // we don't kick off the same download twice.
    private val downloading = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )
    @Volatile var onDownloadsChanged: (() -> Unit)? = null

    /** Fired (main thread) when a stream can't be resolved for [song], so the caller
     *  can skip to the next track instead of leaving playback silent. */
    @Volatile var onStreamFailed: ((String) -> Unit)? = null

    // Per-query download progress, 0..100. Present only while a download is active.
    private val downloadProgress = java.util.concurrent.ConcurrentHashMap<String, Int>()
    // The actual SongsModel of each in-progress download, so the Downloads screen can
    // render it (with a progress bar) before the file exists / it's added to prefs.
    private val downloadingSongs =
        java.util.concurrent.ConcurrentHashMap<String, com.music.spotui.data.entity.SongsModel>()

    fun isDownloading(query: String): Boolean = downloading.contains(query)

    /** Current download progress (0..100) for a query, or -1 if unknown/not downloading. */
    fun downloadProgress(query: String): Int = downloadProgress[query] ?: -1

    /** Snapshot of the currently-downloading tracks paired with their percent (0..100). */
    fun downloadingSnapshot(): List<Pair<com.music.spotui.data.entity.SongsModel, Int>> =
        downloadingSongs.entries.map { (q, song) -> song to (downloadProgress[q] ?: 0) }

    // Last download failure reason, surfaced to the user as a Toast for diagnosis.
    @Volatile var lastDownloadError: String? = null

    // googlevideo stream URLs 403 without a browser User-Agent and need redirects
    // followed (http↔https) — ExoPlayer does both, so a raw URLConnection must too.
    private fun openDownloadConn(url: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
        }

    /**
     * Download [url] to [tmpFile] using HTTP **Range** requests in chunks, reporting
     * progress (0..100) for [query]. A single full-file GET of a googlevideo stream gets
     * reset partway through (`SocketException: Connection reset`) — the server expects the
     * audio fetched in byte ranges, which is how ExoPlayer/NewPipe get it. Each chunk is a
     * short connection (retried a few times on reset); writing is append-continuous so a
     * retried chunk resumes from the current byte position. Returns true iff the whole
     * file was written. Falls back gracefully if the server ignores Range (HTTP 200).
     */
    private fun httpDownloadRanged(url: String, tmpFile: java.io.File, query: String): Boolean {
        val chunk = 8L * 1024 * 1024 // 8 MB
        var total = -1L
        var position = 0L
        downloadProgress[query] = 0
        try {
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                outer@ while (true) {
                    val end = if (total > 0) minOf(position + chunk - 1, total - 1) else position + chunk - 1
                    var attempt = 0
                    var fullBody = false
                    while (true) {
                        attempt++
                        val conn = openDownloadConn(url)
                        conn.setRequestProperty("Range", "bytes=$position-$end")
                        try {
                            val code = conn.responseCode
                            if (code !in 200..299) {
                                lastDownloadError = "Stream returned HTTP $code"
                                return false
                            }
                            if (total < 0) {
                                total = conn.getHeaderField("Content-Range")
                                    ?.substringAfter('/')?.toLongOrNull()
                                    ?: conn.contentLengthLong
                            }
                            fullBody = code == 200 // server ignored Range → whole file in one body
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val r = input.read(buf)
                                    if (r < 0) break
                                    output.write(buf, 0, r)
                                    position += r
                                    if (total > 0) {
                                        val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                                        if (downloadProgress[query] != pct) {
                                            downloadProgress[query] = pct
                                            onDownloadsChanged?.invoke()
                                        }
                                    }
                                }
                            }
                            break // this chunk completed
                        } catch (e: Exception) {
                            Log.w(TAG, "chunk @${position} failed (attempt $attempt): ${e.message}")
                            if (attempt >= 4) {
                                lastDownloadError = e.message ?: "Connection reset"
                                return false
                            }
                            // retry the remainder of this chunk from the current position
                        } finally {
                            conn.disconnect()
                        }
                    }
                    if (fullBody) { total = position; break@outer }
                    if (total in 1..position) break@outer
                    if (total < 0) break@outer // couldn't determine size; assume done
                }
            }
            downloadProgress[query] = 100
            return total <= 0 || position >= total
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Download error"
            return false
        }
    }

    /**
     * Resolve the track's stream and save the audio to local storage for offline
     * playback. Runs on the IO scope; invokes [onComplete] (main thread) with whether
     * it succeeded. No-op if it's already downloaded or downloading.
     */
    /** Download every track in a list (album/playlist). Each song dedupes and
     *  reports its own progress via the existing per-song machinery. */
    fun downloadAll(songs: List<com.music.spotui.data.entity.SongsModel>, context: Context) {
        songs.forEach { downloadSong(it, context) }
    }

    /** True once every track in [songs] is downloaded (for the album's "downloaded" state). */
    fun allDownloaded(
        songs: List<com.music.spotui.data.entity.SongsModel>,
        context: Context,
    ): Boolean {
        if (songs.isEmpty()) return false
        val appContext = context.applicationContext
        return songs.all {
            com.music.spotui.data.preferences.isDownloaded(appContext, it.id.toString())
        }
    }

    fun downloadSong(
        song: com.music.spotui.data.entity.SongsModel,
        context: Context,
        onComplete: (Boolean) -> Unit = {},
    ) {
        val appContext = context.applicationContext
        val query = song.url
        if (query.isBlank() ||
            com.music.spotui.data.preferences.isDownloaded(appContext, song.id.toString()) ||
            !downloading.add(query)
        ) return
        downloadingSongs[query] = song
        downloadProgress[query] = 0
        onDownloadsChanged?.invoke()
        lastDownloadError = null
        scope.launch {
            val ok = runCatching { downloadToFile(song, appContext) }
                .onFailure { lastDownloadError = it.message ?: "Unexpected error" }
                .getOrDefault(false)
            downloading.remove(query)
            downloadProgress.remove(query)
            downloadingSongs.remove(query)
            withContext(Dispatchers.Main) {
                if (!ok) {
                    android.widget.Toast.makeText(
                        appContext,
                        "Download failed: ${lastDownloadError ?: "unknown reason"}",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                onDownloadsChanged?.invoke()
                onComplete(ok)
            }
        }
    }

    private suspend fun downloadToFile(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): Boolean {
        val dlQuality = com.music.spotui.data.preferences.getDownloadQuality(appContext)
        // Deezer: use immediately if it yields FLAC (HiFi). If it only yields MP3
        // (free account), HOLD it and try the real FLAC sources first — otherwise a
        // free Deezer MP3 would pre-empt lossless.
        var heldDeezer: com.music.spotui.deezer.DeezerSource.Resolved? = null
        if (deezerEnabled && com.music.spotui.data.preferences.isDeezerEnabled(appContext)) {
            val raw = kotlinx.coroutines.withTimeoutOrNull(30_000) { resolveDeezerRaw(song, appContext) }
            if (raw != null) {
                if (raw.isFlac) {
                    if (downloadDeezerRaw(song, appContext, raw)) return true
                } else {
                    heldDeezer = raw
                }
            }
        }
        // Lossless FLAC: SpotiFLAC gated (if verified) + Tidal/community. Saves .flac.
        if (losslessStreaming && song.spotifyTrackId.isNotBlank()) {
            val r = kotlinx.coroutines.withTimeoutOrNull(45_000) {
                com.music.spotui.lossless.LosslessSource.resolve(appContext, song.spotifyTrackId, preferHiRes = losslessHiRes)
            }
            if (r is com.music.spotui.lossless.LosslessSource.Result.Success) {
                val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
                val outFile = java.io.File(dir, "${song.id}.flac")
                val tmpFile = java.io.File(dir, "${song.id}.flacpart")
                if (httpDownloadRanged(r.track.url, tmpFile, song.url) && tmpFile.renameTo(outFile)) {
                    com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
                    Log.d(TAG, "lossless downloaded (${r.track.provider} ${r.track.quality}-bit): ${song.title}")
                    return true
                }
                runCatching { tmpFile.delete() }
            }
        }
        // Deezer MP3 fallback (held above) before dropping to a YouTube m4a.
        heldDeezer?.let { if (downloadDeezerRaw(song, appContext, it)) return true }
        if (!youtubeEnabled) {
            lastDownloadError = "Track not available for download"
            return false
        }

        val query = song.url
        // Resolve a fresh network stream URL (bypass any local-file short-circuit),
        // walking the ranked video candidates like playback does.
        val playback = resolveYtPlayback(query, dlQuality.audioQuality, appContext) ?: run {
            lastDownloadError = "Couldn't resolve a stream"
            return false
        }

        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = java.io.File(dir, "${song.id}.m4a")
        val tmpFile = java.io.File(dir, "${song.id}.part")

        if (!httpDownloadRanged(playback.streamUrl, tmpFile, song.url)) {
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        return true
    }

    // (Removed downloadFlacToFile — the SpotiFLAC community-proxy FLAC downloader.
    // Those servers are dead and it never produced a file; real FLAC now comes from
    // the Deezer downloader below.)

    /**
     * Download a Deezer track and decrypt it to a real audio file. Resolves the
     * stream (ISRC → Deezer, account-tier quality), streams the Blowfish-encrypted
     * CDN bytes and decrypts every 3rd 2048-byte block on the way to `<id>.flac` /
     * `<id>.mp3`. Returns false on any miss so the caller can fall back.
     */
    private suspend fun resolveDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
    ): com.music.spotui.deezer.DeezerSource.Resolved? =
        com.music.spotui.deezer.DeezerSource.resolveRaw(
            appContext,
            spotifyId = song.spotifyTrackId.takeIf { it.isNotBlank() },
            isrc = null,
            searchQuery = listOf(song.title, song.singer).filter { it.isNotBlank() }.joinToString(" "),
            expectedDurationSec = if (song.durationMs > 0) song.durationMs / 1000 else 0,
        )

    private fun downloadDeezerRaw(
        song: com.music.spotui.data.entity.SongsModel,
        appContext: Context,
        raw: com.music.spotui.deezer.DeezerSource.Resolved,
    ): Boolean {
        val ext = if (raw.isFlac) "flac" else "mp3"
        val dir = java.io.File(appContext.filesDir, "downloads").apply { mkdirs() }
        val outFile = java.io.File(dir, "${song.id}.$ext")
        val tmpFile = java.io.File(dir, "${song.id}.dzpart")
        if (!deezerDownloadDecrypted(raw.url, raw.encrypted, raw.trackId, tmpFile, song.url)) {
            runCatching { tmpFile.delete() }
            return false
        }
        if (!tmpFile.renameTo(outFile)) {
            lastDownloadError = "Couldn't save file"
            runCatching { tmpFile.delete() }
            return false
        }
        com.music.spotui.data.preferences.addDownload(appContext, song, outFile.absolutePath)
        Log.d(TAG, "Deezer downloaded (${raw.qualityLabel}): ${song.title}")
        return true
    }

    /**
     * Stream [url] and, if [encrypted], Blowfish-decrypt every 3rd 2048-byte block
     * (Deezer's stripe cipher) while writing to [tmpFile], reporting progress for
     * [query]. Produces a plain, fully-decrypted audio file.
     */
    private fun deezerDownloadDecrypted(
        url: String,
        encrypted: Boolean,
        trackId: String,
        tmpFile: java.io.File,
        query: String,
    ): Boolean {
        downloadProgress[query] = 0
        val conn = openDownloadConn(url)
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                lastDownloadError = "Deezer CDN HTTP $code"
                return false
            }
            val total = conn.contentLengthLong
            val cipher = if (encrypted) {
                com.music.spotui.deezer.DeezerCrypto.cipher(
                    com.music.spotui.deezer.DeezerCrypto.trackKey(trackId),
                )
            } else {
                null
            }
            java.io.BufferedOutputStream(tmpFile.outputStream()).use { output ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(2048)
                    var counter = 0
                    var position = 0L
                    while (true) {
                        var read = 0
                        while (read < 2048) {
                            val r = input.read(buf, read, 2048 - read)
                            if (r < 0) break
                            read += r
                        }
                        if (read == 0) break
                        val out = if (encrypted && read == 2048 && counter % 3 == 0) {
                            cipher!!.doFinal(buf)
                        } else {
                            buf
                        }
                        output.write(out, 0, read)
                        counter++
                        position += read
                        if (total > 0) {
                            val pct = ((position * 100) / total).toInt().coerceIn(0, 100)
                            if (downloadProgress[query] != pct) {
                                downloadProgress[query] = pct
                                onDownloadsChanged?.invoke()
                            }
                        }
                        if (read < 2048) break // final partial chunk
                    }
                }
            }
            downloadProgress[query] = 100
            true
        } catch (e: Exception) {
            lastDownloadError = e.message ?: "Deezer download error"
            false
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private data class CandidateScore(
        val item: SongItem,
        val score: Double,
        val titleScore: Double,
        val artistScore: Double,
        val artistEvidenceScore: Double,
        val durationScore: Double?,
        val albumScore: Double?,
        val alternatePenalty: Double,
        val unexpectedAlternates: List<String>,
        val missingExpected: List<String> = emptyList(),
    )

    private fun normalizedForMatch(value: String): String =
        value.lowercase()
            .replace(featSearchPattern, "")
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val anyLatinTransliterator by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII")
        }.getOrNull()
    }

    private fun foldLatinDiacritics(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

    private fun transliterateCyrillic(value: String): String {
        val map = mapOf(
            'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d",
            'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
            'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n",
            'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
            'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
            'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "",
            'э' to "e", 'ю' to "yu", 'я' to "ya",
        )
        return buildString {
            value.lowercase().forEach { ch ->
                append(map[ch] ?: ch)
            }
        }
    }

    internal fun bigramSimilarity(a: String, b: String): Double {
        fun variants(value: String): List<String> =
            listOfNotNull(
                value,
                foldLatinDiacritics(value),
                transliterateCyrillic(value),
                anyLatinTransliterator?.transliterate(value),
            )
                .map(::normalizedForMatch)
                .filter { it.isNotBlank() }
                .distinct()

        fun score(na: String, nb: String): Double {
            if (na == nb) return 1.0
            if (na.length < 2 || nb.length < 2) return 0.0
            val aBigrams = na.windowed(2).toSet()
            val bBigrams = nb.windowed(2).toSet()
            if (aBigrams.isEmpty() || bBigrams.isEmpty()) return 0.0
            val intersection = aBigrams.count { it in bBigrams }
            return (2.0 * intersection) / (aBigrams.size + bBigrams.size)
        }
        return variants(a).maxOfOrNull { aa ->
            variants(b).maxOfOrNull { bb -> score(aa, bb) } ?: 0.0
        } ?: 0.0
    }

    private data class VersionMarker(
        val name: String,
        val pattern: Regex,
        val hardReject: Boolean,
    )

    private fun markerPattern(terms: String): Regex =
        Regex("""(^|\s)($terms)(\s|$)""", RegexOption.IGNORE_CASE)

    private val alternateVersionMarkers = listOf(
        VersionMarker("remix", markerPattern("""re\s*mix|rmx|club mix|dance mix|dub mix|vip mix|ремиקס|рмикс|רמיקס|רמיקס רשמי"""), true),
        VersionMarker("alternate", markerPattern("""alternative|alternate|alt version|demo|demo version|unreleased|rough mix|early version|альтернатив\w*|демо|неиздан\w*|чернов\w*|גרסה חלופית"""), true),
        VersionMarker("sped up", markerPattern("""sped\s*up|speed\s*up|fast version|ускоренн\w*|быстрая версия|גרסה מואצת"""), true),
        VersionMarker("slowed", markerPattern("""slowed|slowed reverb|slow version|замедленн\w*|медленная версия|גרסה מואטת"""), true),
        VersionMarker("nightcore", markerPattern("""nightcore|daycore"""), true),
        VersionMarker("live", markerPattern("""live|concert|session|performance|лайв|концерт|с концерта|выступлен\w*|לייב|הופעה|מופע|הופעה חיה|בהופעה"""), true),
        VersionMarker("acoustic", markerPattern("""acoustic|unplugged|piano version|guitar version|акустик\w*|пианино|гитар\w*|אקוסטי|גרסה אקוסטית"""), true),
        VersionMarker("cover", markerPattern("""cover|covered by|tribute|кавер|трибьют|קאבר|מחווה"""), true),
        VersionMarker("karaoke", markerPattern("""karaoke|minus one|караоке|минусовка|קריוקי|פלייבק"""), true),
        VersionMarker("instrumental", markerPattern("""instrumental|no vocals|инструментал|без вокала|אינסטרומנטל|ללא מילים"""), true),
        VersionMarker("mashup", markerPattern("""mashup|mash up|bootleg|rework|flip|мешап|мэшап|бутлег|מאשאפ"""), true),
        VersionMarker("fan edit", markerPattern("""fan edit|fanmade|right version|edit audio|перезалив|перезалит\w*"""), true),
        VersionMarker("extended", markerPattern("""extended mix|extended version|12 inch|12|גרסה מורחבת"""), false),
        VersionMarker("radio edit", markerPattern("""radio edit|single edit|edit version|גרסת רדיו"""), false),
        VersionMarker("remaster", markerPattern("""remaster|remastered|anniversary edition|רימאסטר"""), false),
    )

    private fun versionMarkers(value: String): Set<String> {
        val normalized = normalizedForMatch(value)
        return alternateVersionMarkers
            .filter { marker -> marker.pattern.containsMatchIn(normalized) }
            .map { it.name }
            .toSet()
    }

    private fun hardVersionMarkers(names: Collection<String>): Set<String> {
        val hardNames = alternateVersionMarkers.filter { it.hardReject }.map { it.name }.toSet()
        return names.filterTo(mutableSetOf()) { it in hardNames }
    }

    private fun ytmusicTransferScore(
        candidate: SongItem,
        expected: TrackMatchMetadata,
        expectedDurationMs: Int,
    ): CandidateScore {
        var candidateTitle = candidate.title
        if (candidate.isVideoSong) {
            val split = candidateTitle.split("-", limit = 2)
            if (split.size == 2) candidateTitle = split[1].trim()
        }

        val titleScore = bigramSimilarity(candidateTitle, expected.title)
        val uploaderArtistScore = bigramSimilarity(
            candidate.artists.joinToString(" ") { it.name },
            expected.artist,
        )
        val titleArtistScore = if (candidate.isVideoSong) {
            bigramSimilarity(candidate.title.substringBefore("-"), expected.artist)
        } else {
            0.0
        }
        val artistScore = maxOf(uploaderArtistScore, titleArtistScore)

        val expectedDurationSec = expectedDurationMs / 1000.0
        val candidateDuration = candidate.duration
        val durationScore = if (expectedDurationSec > 0 && candidateDuration != null) {
            val diff = abs(candidateDuration - expectedDurationSec)
            when {
                diff <= 4.0 -> 1.0
                diff <= 10.0 -> (1.0 - (diff - 4.0) * 0.05).coerceIn(0.7, 1.0)
                diff <= 20.0 -> (0.7 - (diff - 10.0) * 0.04).coerceIn(0.3, 0.7)
                else -> 0.0 // severe mismatch (>20s)
            }
        } else {
            null
        }

        val albumScore = if (!candidate.isVideoSong && expected.album.isNotBlank()) {
            candidate.album?.name?.let { bigramSimilarity(it, expected.album) }
        } else {
            null
        }

        val expectedMarkers = versionMarkers(expected.title)
        val candidateMarkers = versionMarkers(
            listOf(
                candidate.title,
                candidate.album?.name.orEmpty(),
            ).joinToString(" "),
        )

        // Asymmetric version matching:
        // 1. Candidate has extra alternate markers not requested by Expected
        val unexpectedAlternates = (candidateMarkers - expectedMarkers).toList().sorted()
        // 2. Candidate lacks alternate markers explicitly requested by Expected (e.g. Expected=Live, Candidate=Studio)
        val missingExpected = (expectedMarkers - candidateMarkers).toList().sorted()

        val hardUnexpected = hardVersionMarkers(unexpectedAlternates).size
        val softUnexpected = unexpectedAlternates.size - hardUnexpected
        val hardMissing = hardVersionMarkers(missingExpected).size
        val softMissing = missingExpected.size - hardMissing

        val unexpectedPenalty = hardUnexpected * 1.75 + softUnexpected * 0.65
        val missingPenalty = hardMissing * 2.0 + softMissing * 0.75
        val versionBonus = if (expectedMarkers.isNotEmpty() && candidateMarkers.containsAll(expectedMarkers)) 0.5 else 0.0
        val alternatePenalty = (unexpectedPenalty + missingPenalty - versionBonus).coerceAtLeast(0.0)

        val parts = mutableListOf(titleScore, artistScore)
        durationScore?.let { parts += it * 5.0 }
        albumScore?.let { parts += it }
        val resultTypeBoost = if (candidate.isVideoSong) 1.0 else 2.0
        val baseScore = parts.average() * resultTypeBoost
        return CandidateScore(
            item = candidate,
            score = (baseScore - alternatePenalty).coerceAtLeast(0.0),
            titleScore = titleScore,
            artistScore = uploaderArtistScore,
            artistEvidenceScore = artistScore,
            durationScore = durationScore,
            albumScore = albumScore,
            alternatePenalty = alternatePenalty,
            unexpectedAlternates = unexpectedAlternates,
            missingExpected = missingExpected,
        )
    }

    private fun CandidateScore.isAcceptableMatch(): Boolean {
        val durationStrong = durationScore?.let { it >= 0.85 } ?: false
        val albumUseful = albumScore?.let { it >= 0.40 } ?: false
        val hasDuration = durationScore != null
        val minScore = when {
            hasDuration && item.isVideoSong -> 0.90
            hasDuration -> 1.10
            item.isVideoSong -> 0.50
            else -> 0.70
        }

        val hasUnexpectedHardAlternate = hardVersionMarkers(unexpectedAlternates).isNotEmpty()
        val hasMissingHardExpected = hardVersionMarkers(missingExpected).isNotEmpty()
        if (hasUnexpectedHardAlternate || hasMissingHardExpected) return false

        // Severe duration mismatch (>20s) rejects non-video tracks when expected duration is known
        if (durationScore != null && durationScore == 0.0 && !item.isVideoSong) {
            return false
        }

        // 1. Strong title match (e.g. Hebrew/English transliterated or direct titles)
        if (titleScore >= 0.60 && (durationStrong || (durationScore?.let { it >= 0.70 } ?: false) || artistEvidenceScore >= 0.15 || albumUseful)) {
            return true
        }

        // 2. High title similarity (> 0.75) alone without hard mismatch
        if (titleScore >= 0.75) {
            return true
        }

        // 3. General match
        return score >= minScore &&
            titleScore >= 0.35 &&
            (
                artistEvidenceScore >= 0.20 ||
                    (albumUseful && artistEvidenceScore >= 0.10) ||
                    (durationStrong)
            )
    }

    private fun ensureSpotifyMatchMetadata(query: String): TrackMatchMetadata? {
        val currentMeta = metadataRegistry[query]
        if (currentMeta != null) return currentMeta

        val text = searchTextForPlayback(query)
        // Clean title preservation without destructive " - " splits
        val fallback = TrackMatchMetadata(title = text, artist = "", album = "")
        metadataRegistry[query] = fallback

        // Background non-blocking metadata enhancement:
        val spotifyId = trackIdRegistry[query] ?: spotifyTrackIdForPlayback(query)
        if (spotifyId != null && !spotifyMetadataRepaired.contains(query)) {
            scope.launch {
                runCatching {
                    com.metrolist.spotify.Spotify.track(spotifyId).getOrNull()?.let { track ->
                        metadataRegistry[query] = TrackMatchMetadata(
                            title = track.name,
                            artist = track.artists.joinToString(", ") { it.name },
                            album = track.album?.name.orEmpty(),
                            isrc = track.isrc.orEmpty(),
                        )
                        explicitRegistry[query] = track.explicit
                        if (track.durationMs > 0 && durationRegistry[query] == null) {
                            durationRegistry[query] = track.durationMs
                        }
                        spotifyMetadataRepaired += query
                    }
                }
            }
        }
        return fallback
    }

    private suspend fun resolveVideoCandidates(
        query: String,
        filter: YouTube.SearchFilter = YouTube.SearchFilter.FILTER_SONG,
    ): List<String> {
        val searchText = searchTextForPlayback(query)
        // A raw YouTube videoId is 11 chars with no spaces — accept it directly.
        if (searchText.length == 11 && !searchText.contains(' ')) return listOf(searchText)

        val spotifyId = spotifyTrackIdForPlayback(query) ?: trackIdRegistry[query]
        val context = appCtx
        if (context != null) {
            val cachedId = (spotifyId?.let { com.music.spotui.data.preferences.TrackVideoCache.get(context, it) })
                ?: com.music.spotui.data.preferences.TrackVideoCache.get(context, searchText)
            if (cachedId != null && cachedId.matches(Regex("""[A-Za-z0-9_-]{11}"""))) {
                Log.d(TAG, "resolveVideoCandidates CACHE HIT: '$searchText' -> $cachedId")
                return listOf(cachedId)
            }
        }
        val exactMeta = ensureSpotifyMatchMetadata(query)
        val wantExplicit = explicitRegistry[query]

        // Fast Single Search: First page only, avoids multi-page waterfall and duplicate queries
        val firstPage = YouTube.search(searchText, filter)
            .onFailure { Log.w(TAG, "resolveVideoId: YouTube search failed for: $searchText", it) }
            .getOrNull()
        val found = firstPage?.items?.filterIsInstance<SongItem>().orEmpty()
        val hits = if (found.isEmpty() && filter == YouTube.SearchFilter.FILTER_SONG) {
            YouTube.search(searchText).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
        } else found
        if (hits.isEmpty()) {
            Log.w(TAG, "resolveVideoId: no YouTube song results for: $searchText")
            return emptyList()
        }
        // YouTube's top hit is NOT always the requested song (worst for
        // non-English titles). Score every hit against the query — the query is
        // "title artist1, artist2":
        //   +2 artist match, +1 title match, +2 duration match.
        // Duration is the key disambiguator for same-title/different-artist: a
        // wrong-artist song with the same name almost always has a different
        // length, so it never ties the real track once duration is in the score.
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        val qn = norm(searchText)
        val wantSec = durationRegistry[query]?.let { it / 1000 }
        val scored = hits.map { h ->
            val cleanTitle = norm(h.title.substringBefore('(').substringBefore('['))
            var s = 0
            if (cleanTitle.isNotEmpty() && qn.contains(cleanTitle)) s += 1
            if (h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }) s += 2
            // Within ~4s of the Spotify track length → very likely the same recording.
            val hDur = h.duration
            if (wantSec != null && hDur != null && kotlin.math.abs(hDur - wantSec) <= 4) s += 2
            h to s
        }
        val transferScored = if (exactMeta != null) {
            hits.map { ytmusicTransferScore(it, exactMeta, durationRegistry[query] ?: 0) }
        } else {
            emptyList()
        }
        // A hit is "verified" as the right recording when its artist matches the
        // query, OR (when we know the Spotify length) its duration is within ~4s.
        fun verified(h: SongItem): Boolean {
            val artistOk = h.artists.any { a -> norm(a.name).let { it.isNotEmpty() && qn.contains(it) } }
            val d = h.duration
            val durOk = wantSec != null && d != null && kotlin.math.abs(d - wantSec) <= 4
            return artistOk || durOk
        }
        fun isRequestedEdition(item: SongItem): Boolean = when (wantExplicit) {
            true -> item.explicit || (filter == YouTube.SearchFilter.FILTER_VIDEO &&
                Regex("\\b(explicit|uncensored)\\b", RegexOption.IGNORE_CASE).containsMatchIn(item.title))
            false -> !item.explicit
            null -> true
        }
        fun explicitFirst(list: List<SongItem>) =
            if (wantExplicit != null) list.sortedByDescending { it.explicit == wantExplicit } else list
        val ordered = if (transferScored.isNotEmpty()) {
            val accepted = transferScored
                .filter { it.isAcceptableMatch() }
                .filter { isRequestedEdition(it.item) }
                .sortedWith(
                    compareByDescending<CandidateScore> { it.item.explicit == wantExplicit || wantExplicit == null }
                        .thenByDescending { it.score }
                )
                .map { it.item }
            if (accepted.isEmpty()) {
                val fallbackList = hits.filter(::isRequestedEdition).ifEmpty { hits }
                Log.w(
                    TAG,
                    "resolveVideoId: strict match empty for '$searchText' — using YouTube search ranking fallback: '${fallbackList.firstOrNull()?.title}'",
                )
                fallbackList.distinctBy { it.id }
            } else {
                accepted.distinctBy { it.id }
            }
        } else {
            // Legacy/plain queries without registered Spotify metadata: keep the
            // old best-effort ordering.
            val verifiedRanked = scored
                .filter { verified(it.first) }
                .sortedByDescending { it.second }
                .map { it.first }
            val restRanked = scored
                .filter { !verified(it.first) }
                .sortedByDescending { it.second }
                .map { it.first }
            (explicitFirst(verifiedRanked) + explicitFirst(restRanked))
                .filter(::isRequestedEdition)
                .ifEmpty { hits }
                .distinctBy { it.id }
        }

        if (ordered.isEmpty()) return emptyList()
        val chosen = ordered.first()
        val isVerified = if (transferScored.isNotEmpty()) {
            transferScored.any { it.item.id == chosen.id && it.isAcceptableMatch() }
        } else {
            verified(chosen)
        }
        if (isVerified && context != null && chosen.id.isNotBlank()) {
            spotifyId?.let { com.music.spotui.data.preferences.TrackVideoCache.put(context, it, chosen.id) }
            com.music.spotui.data.preferences.TrackVideoCache.put(context, searchText, chosen.id)
        } else {
            Log.w(
                TAG,
                "resolveVideoId: chosen candidate '${chosen.title}' is an unverified fallback — NOT writing to TrackVideoCache",
            )
        }
        if (transferScored.isEmpty() && !verified(chosen)) {
            Log.w(TAG, "resolveVideoId: no verified match for: $searchText (want=${wantSec}s) — best-effort '${chosen.title}'")
        }
        val chosenScore = transferScored.firstOrNull { it.item.id == chosen.id }
        Log.d(
            TAG,
            "resolveVideoId: '$searchText' -> '${chosen.title}' by " +
                chosen.artists.joinToString { it.name } +
                " [explicit=${chosen.explicit} dur=${chosen.duration}s want=${wantSec}s id=${chosen.id}] " +
                (chosenScore?.let {
                    "score=${"%.2f".format(it.score)} title=${"%.2f".format(it.titleScore)} " +
                        "artist=${"%.2f".format(it.artistEvidenceScore)} duration=${"%.2f".format(it.durationScore ?: 0.0)} " +
                        "album=${"%.2f".format(it.albumScore ?: 0.0)} " +
                        "altPenalty=${"%.2f".format(it.alternatePenalty)} " +
                        "alt=${it.unexpectedAlternates.joinToString("/")}"
                } ?: "${ordered.count { verified(it) }} verified/${ordered.size}"),
        )
        return ordered.map { it.id }.distinct()
    }

    /**
     * Resolves a playable YouTube stream for [query], falling back through up to
     * 5 ranked video candidates when one has no obtainable stream.
     */
    private suspend fun resolveYtPlayback(
        query: String, audioQuality: AudioQuality, appContext: Context,
    ): YTPlayerUtils.PlaybackData? {
        val key = ResolutionKey(query, audioQuality, sourceScope(appContext), matchScope(query))
        ytCache.get(key, key.scope)?.let { return it }
        val result = ytRequests.get(key) {
            ytCache.get(key, key.scope) ?: kotlinx.coroutines.withTimeoutOrNull(30_000) {
                resolveYtPlaybackUncached(query, audioQuality, appContext)
            }?.also {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                ytCache.put(key, it, key.scope, (it.streamExpiresInSeconds.toLong() * 1000 - 30_000).coerceAtMost(300_000))
            }
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (key.scope != sourceScope(appContext)) throw java.io.IOException("Playback source context changed")
        return result
    }
    private suspend fun resolveYtPlaybackUncached(
        query: String,
        audioQuality: com.metrolist.music.constants.AudioQuality,
        appContext: Context,
    ): YTPlayerUtils.PlaybackData? {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Direct videoId fast-path: if query is already a direct YouTube videoId, or cached in TrackVideoCache, try it immediately
        val directVid = videoIdFromYouTubeLink(query)
            ?: when {
                query.startsWith("youtube:") -> query.removePrefix("youtube:").substringBefore('|').trim()
                query.startsWith("yt:") -> query.removePrefix("yt:").substringBefore('|').trim()
                query.trim().matches(Regex("""[A-Za-z0-9_-]{11}""")) -> query.trim()
                else -> {
                    val spId = spotifyTrackIdForPlayback(query) ?: trackIdRegistry[query]
                    (spId?.let { com.music.spotui.data.preferences.TrackVideoCache.get(appContext, it) })
                        ?: com.music.spotui.data.preferences.TrackVideoCache.get(appContext, searchTextForPlayback(query))
                }
            }
        if (directVid != null && directVid.matches(Regex("""[A-Za-z0-9_-]{11}"""))) {
            YTPlayerUtils.playerResponseForPlayback(
                videoId = directVid,
                audioQuality = audioQuality,
                connectivityManager = connectivityManager,
            ).fold(
                onSuccess = { return it },
                onFailure = { Log.w(TAG, "direct videoId stream failed for $directVid: ${it.message}") }
            )
        }

        val tried = mutableSetOf<String>().apply { if (directVid != null) add(directVid) }
        suspend fun tryIds(ids: List<String>): YTPlayerUtils.PlaybackData? {
            for (videoId in ids) {
                if (!tried.add(videoId)) continue
                YTPlayerUtils.playerResponseForPlayback(
                    videoId = videoId,
                    audioQuality = audioQuality,
                    connectivityManager = connectivityManager,
                ).fold(
                    onSuccess = { return it },
                    onFailure = { Log.w(TAG, "stream failed for $videoId (${it.message}) — trying next candidate for: ${searchTextForPlayback(query)}") },
                )
            }
            return null
        }
        tryIds(resolveVideoCandidates(query).take(5))?.let { return it }

        // Fallback to video search if song candidates fail
        Log.w(TAG, "Song candidates exhausted, trying video search for: ${searchTextForPlayback(query)}")
        tryIds(resolveVideoCandidates(query, YouTube.SearchFilter.FILTER_VIDEO).take(5))?.let { return it }
        Log.e(TAG, "All YouTube candidates failed for: ${searchTextForPlayback(query)}")
        return null
    }

    private fun buildAudioAttributes() =
        androidx.media3.common.AudioAttributes.Builder()
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .build()

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private class PermanentAwareLoadErrorPolicy : androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val error = loadErrorInfo.exception
            if (isPermanentLoadError(error)) {
                Log.w(TAG, "Permanent stream load error (${error.javaClass.simpleName}: ${error.message}) — aborting retry loop immediately")
                return androidx.media3.common.C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

    private fun isPermanentLoadError(error: Throwable?): Boolean {
        var cause = error
        var depth = 0
        while (cause != null && depth++ < 8) {
            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                if (cause.responseCode in listOf(401, 403, 404, 410)) return true
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
    }

    /**
     * Build an ExoPlayer that reads through the shared media cache (so preloaded intro
     * bytes are reused) and carries its own [CrossfadeFilterAudioProcessor] so the DJ-style
     * low/high-pass sweep can be applied per track during a crossfade. The filter is
     * disabled (pass-through) outside a crossfade, so there's no overhead in normal playback.
     *
     * @param handleAudioFocus true for the active/session player, false for the transient
     *   secondary (incoming) player so it doesn't fight the primary for focus mid-fade.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun createPlayerWithFilter(
        context: Context,
        handleAudioFocus: Boolean,
    ): Pair<ExoPlayer, com.music.spotui.audio.CrossfadeFilterAudioProcessor> {
        val filter = com.music.spotui.audio.CrossfadeFilterAudioProcessor()
        val renderers = object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(true)
                    .setEnableAudioTrackPlaybackParams(true)
                    .setAudioProcessorChain(
                        androidx.media3.exoplayer.audio.DefaultAudioSink.DefaultAudioProcessorChain(
                            filter,
                        ),
                    ).build()
        }
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                    // Routes deezer:// URIs to the decrypting DeezerDataSource and
                    // everything else through the normal cached HTTP stack.
                    com.music.spotui.deezer.DeezerAwareDataSourceFactory(cacheDataSourceFactory(context)),
                ).setLoadErrorHandlingPolicy(PermanentAwareLoadErrorPolicy()),
            )
            .setRenderersFactory(renderers)
            .setAudioAttributes(buildAudioAttributes(), handleAudioFocus)
            .setHandleAudioBecomingNoisy(handleAudioFocus)
            .build()
        attachListener(p)
        return p to filter
    }

    /** Read the final Player snapshot after a batch of callbacks, including external transport. */
    private fun syncPlayingState(p: ExoPlayer) {
        val state = boundState ?: return
        val playing = PlaybackControlState.playing(
            ownership, itemToken(p.currentMediaItem), p === player, p.playbackState, p.playWhenReady,
        ) ?: return
        if (state.playingState.value != playing) state.updatePlayingState(playing)
    }

    private fun attachListener(p: ExoPlayer) {
        p.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: PlaybackException) = handlePlayerError(p, error)

            override fun onEvents(player: Player, events: Player.Events) {
                // onIsPlayingChanged alone misses pause/play while already buffering.
                // Read after transition callbacks have committed the accepted item's identity.
                syncPlayingState(p)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                    ownsPlayerItem(currentPlaybackToken, p)) {
                    if (startup.pending && errorRecoveryJob?.isActive == true) {
                        // External pause must also cancel a pending automatic recovery.
                        pause()
                        return
                    }
                    startup.stage(currentPlaybackToken, "paused", terminal = true)
                    startupWatchdog?.cancel()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!ownsPlayerItem(currentPlaybackToken, p)) return
                if (!isPlaying) saveCurrentPosition()
                if (isPlaying) {
                    startup.stage(currentPlaybackToken, "first_playing", terminal = true)
                    startupWatchdog?.cancel()
                }
                onIsPlayingChangedListener?.invoke(isPlaying)
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK && ownsPlayerItem(currentPlaybackToken, p)) {
                    saveCurrentPosition()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == androidx.media3.common.Player.STATE_ENDED) {
                    val token = activePlaybackToken
                    // CRITICAL: Only fire if there is an active playing token, matching the current token,
                    // the player actually has media items, and playback reached near the duration end.
                    // This protects against spurious STATE_ENDED from clearMediaItems() or empty timeline.
                    if (token != null && ownsPlayerItem(token, p) &&
                        token == currentPlaybackToken &&
                        p.mediaItemCount > 0 &&
                        p.currentMediaItem != null
                    ) {
                        val dur = runCatching { p.duration }.getOrDefault(0L)
                        val pos = runCatching { p.currentPosition }.getOrDefault(0L)
                        val reachedEnd = dur <= 0L || pos >= (dur - 2_000L).coerceAtLeast(0L)
                        if (reachedEnd) {
                            saveCurrentPosition(completed = true)
                            ownership.deactivate(token)
                            onTrackEndedListener?.invoke(token)
                        }
                    }
                }
            }
        })
    }

    private fun ensurePlayer(context: Context) {
        appCtx = context.applicationContext
        if (player == null) {
            val (p, filter) = createPlayerWithFilter(context, handleAudioFocus = true)
            player = p
            currentPlayerFilter = filter
            onPlayerCreated?.invoke(p)
        }
    }

    /**
     * Catches ExoPlayer errors that happen mid-stream (expired CDN URL, network drop,
     * HTTP 403/404, decode failure, etc.) — none of which were previously handled,
     * so the player would silently go idle.
     *
     * IO errors (2xxx) get one re-resolve attempt: the cached URL is discarded and a
     * fresh one is fetched; if that succeeds we resume from where we left off.
     * Exhausted recovery and non-IO errors stop with feedback and an explicit same-item retry.
     */
    private fun handlePlayerError(p: ExoPlayer, error: PlaybackException) {
        val owner = ownership.active ?: return
        val token = owner.token
        if (!ownsPlayerItem(token, p)) return
        Log.w(TAG, "mid-play error for ${owner.request}: ${error.errorCodeName} (code ${error.errorCode})")
        // DirectAudio is owned by Media3. Keep its error visible; never run music recovery or skip
        // into a music queue for a podcast (nor restart external playback through playSong).
        if (!ownership.canRecoverMusic(token)) return
        val song = owner.request
        invalidateResolvedStream(song)
        val ctx = appCtx ?: return
        if (recoveredToken == token) {
            failPlayback(token, ctx, PlaybackFailure.from(error))
            return
        }
        recoveredToken = token
        startup.begin(token, owner.generation)
        startupWatchdog?.cancel()
        startupWatchdog = scope.launch(Dispatchers.Main) {
            delay(45_000)
            if (startup.pending) failPlayback(token, ctx, PlaybackFailure.TIMEOUT)
        }
        val savedPos = p.currentPosition
        val isIoError = error.errorCode in 2000..2999
        errorRecoveryJob?.cancel()
        errorRecoveryJob = scope.launch {
            try {
                if (isIoError) {
                    val cause = error.cause
                    val isPermanent = cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException &&
                        cause.responseCode in listOf(401, 403, 404, 410)
                    if (!isPermanent) delay(1_000L)
                    if (!ownership.canRecoverMusic(token)) return@launch
                    val fresh = resolveStreamUrl(song, ctx, forPlayback = true, playbackToken = token)
                    if (fresh != null) {
                        withContext(Dispatchers.Main) {
                            ownership.runIfCurrent(token) {
                                if (!ownsPlayerItem(token, p) || !ownership.canRecoverMusic(token)) return@runIfCurrent
                                p.setMediaItem(buildMediaItem(fresh, streamMimeType(fresh), token))
                                p.prepare()
                                if (savedPos > 5_000L) p.seekTo(savedPos)
                                p.play()
                            }
                        }
                        return@launch
                    }
                }
                withContext(Dispatchers.Main) {
                    ownership.runIfCurrent(token) {
                        if (ownsPlayerItem(token, p) && ownership.canRecoverMusic(token)) failPlayback(token, ctx, PlaybackFailure.from(error))
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { failPlayback(token, ctx, PlaybackFailure.from(e)) }
            }
        }
    }

    /** Terminal failure is generation guarded and never guesses the next song. Main-thread only. */
    private fun failPlayback(token: String, context: Context, failure: PlaybackFailure) {
        if (!ownership.isCurrent(token)) return
        val owner = ownership.current
        startup.stage(token, "failed_" + failure.name.lowercase(), terminal = true)
        val retry = retryPlayback.get(token)
        saveCurrentPosition()
        player?.pause()
        // Revoke the failed request before cancellation: a blocking resolver may finish late.
        if (!ownership.revoke(token)) return
        cancelPendingPlaybackWork()
        retryPlayback.set(currentPlaybackToken, retry)
        boundState?.updatePlayingState(false)
        Log.w("PlaybackTiming", "generation=" + owner.generation + " failure=" + failure.name)
        android.widget.Toast.makeText(context, failure.message, android.widget.Toast.LENGTH_LONG).show()
    }
    /** The live ExoPlayer instance (may be null before first play). */
    val exoPlayer: ExoPlayer? get() = player

    /** Make sure the player exists (used by the media-session service). */
    fun ensureCreated(context: Context) = ensurePlayer(context.applicationContext)

    /** Notified right after the ExoPlayer is built so the session can attach to it. */
    @Volatile var onPlayerCreated: ((ExoPlayer) -> Unit)? = null

    /** Notified when audio playback state changes (isPlaying). Direct source of truth for Presence. */
    @Volatile var onIsPlayingChangedListener: ((Boolean) -> Unit)? = null

    fun isPlaying(): Boolean {
        if (webPlaybackActive()) return SpotifyWebPlayer.isPlaying
        return player?.isPlaying ?: false
    }

    fun webPlaybackActive(): Boolean {
        if (!webPlayerEnabled) return false
        val ctx = appCtx ?: return false
        // Spotify web playback needs: user hasn't opted out, the WebView actually has
        // Widevine, AND the user is logged into Spotify (sp_dc). Missing any of these
        // → fall back to the YouTube/FLAC engine so playback is never silent.
        return com.music.spotui.data.preferences.isWebPlaybackEnabled(ctx) &&
            SpotifyWebPlayer.canPlay &&
            com.music.spotui.data.api.SpotifySession.spDc(ctx).isNotBlank()
    }

    // ── Session restore (survive app restarts) ──
    // Set at launch from the persisted playback state; the first playSong for
    // this query seeks to the saved position, and play() with an empty player
    // re-resolves the track instead of doing nothing.
    @Volatile private var restoreQuery: String? = null
    @Volatile private var restorePositionMs: Long = 0L

    fun setRestorePoint(query: String, positionMs: Long) {
        if (query.isBlank()) return
        restoreQuery = query
        restorePositionMs = positionMs.coerceAtLeast(0L)
    }

    fun play() {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        retryPlayback.get(currentPlaybackToken)?.takeIf { ownership.current.external && ownership.active == null }?.let {
            it()
            return
        }
        if (webPlaybackActive()) { SpotifyWebPlayer.resume(); return }
        // Fresh launch: nothing loaded yet — resume the restored session track.
        if ((player?.mediaItemCount ?: 0) == 0 && !ownership.current.external) {
            val q = restoreQuery
            val ctx = appCtx
            if (q != null && ctx != null) { playSong(q, ctx); return }
        }
        val p = player ?: return
        if (!ownership.canControl(itemToken(p.currentMediaItem))) return
        ownership.activate(currentPlaybackToken, p.currentMediaItem?.mediaId)
        p.play()
    }

    fun pause() {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.pause(); return }
        if (startup.pending) {
            val token = currentPlaybackToken
            startup.stage(token, "paused", terminal = true)
            startupWatchdog?.cancel()
            if (!ownsPlayerItem(token) || errorRecoveryJob?.isActive == true) {
                saveCurrentPosition()
                val retry = retryPlayback.get(token)
                ownership.revoke(token)
                cancelPendingPlaybackWork()
                retryPlayback.set(currentPlaybackToken, retry)
            }
        }
        player?.let {
            it.playWhenReady = false
            saveCurrentPosition()
        }
    }

    fun stop() {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        saveCurrentPosition()
        ownership.begin("", MediaType.TRACK)
        cancelPendingPlaybackWork()
        player?.stop()
    }

    fun seekTo(position: Long) {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        cancelCrossfade()
        if (webPlaybackActive()) { SpotifyWebPlayer.seekTo(position); return }
        player?.seekTo(position)
        saveCurrentPosition()
    }

    fun release() {
        com.music.spotui.playback.RadioPreviewInterruption.mainCommand()
        saveCurrentPosition()
        ownership.begin("", MediaType.TRACK)
        cancelPendingPlaybackWork()
        player?.release()
        player = null
    }

    fun getDuration(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.durationMs
        return player?.duration ?: 0L
    }

    fun getCurrentPosition(): Long {
        if (webPlaybackActive()) return SpotifyWebPlayer.positionMs
        return player?.currentPosition ?: 0L
    }

    fun isPrepared(): Boolean {
        val playerState = player?.playbackState
        return playerState != null && playerState != ExoPlayer.STATE_IDLE && playerState != ExoPlayer.STATE_ENDED
    }

    // ── Crossfade + DJ-style mixing ──
    // The end of the current track is blended into the start of the next over a user-set
    // window (Settings). A second, transient ExoPlayer plays the incoming track while the
    // primary fades out; volumes follow an equal-power (cos/sin) curve so total loudness
    // stays constant. In DJ mode, the outgoing track is low-passed (treble drops out) and the
    // incoming track high-passed (bass fills in) via per-player [CrossfadeFilterAudioProcessor]s,
    // swept on an S-curve — like a real DJ mixer. When the blend finishes the secondary player
    // is promoted to primary and the media session is re-bound to it via [onPlayerSwapped].
    private const val CF_LPF_START_HZ = 20000f
    private const val CF_LPF_END_HZ = 200f
    private const val CF_HPF_START_HZ = 2000f
    private const val CF_HPF_END_HZ = 20f
    private const val CF_SIGMOID_K = 6f

    @Volatile private var appCtx: Context? = null
    @Volatile private var boundState: CurrentSongState? = null
    @Volatile private var currentPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var secondaryPlayer: ExoPlayer? = null
    @Volatile private var secondaryPlayerFilter: com.music.spotui.audio.CrossfadeFilterAudioProcessor? = null
    @Volatile private var isCrossfading = false
    @Volatile private var crossfadeGeneration = 0L
    @Volatile private var crossfadeJob: kotlinx.coroutines.Job? = null
    @Volatile private var positionWatchJob: kotlinx.coroutines.Job? = null
    @Volatile private var pendingNextSong: com.music.spotui.data.entity.SongsModel? = null
    @Volatile private var pendingNextSongIdx: Int = 0

    /** Notified (on the main thread) when the active ExoPlayer instance changes after a
     *  crossfade, so the media session can re-bind to the promoted player. */
    @Volatile var onPlayerSwapped: ((ExoPlayer) -> Unit)? = null

    /** Give the player access to the shared queue/now-playing state so it can advance the
     *  app's notion of "current track" itself when a crossfade fires. Called once at startup. */
    fun bindState(state: CurrentSongState) {
        boundState = state
        player?.let(::syncPlayingState)
    }

    fun isCrossfadeActive(): Boolean = isCrossfading

    private fun sigmoid(t: Float): Float = 1.0f / (1.0f + exp(-CF_SIGMOID_K * (t - 0.5f)))

    private fun expInterpolate(start: Float, end: Float, t: Float): Float {
        if (start <= 0f || end <= 0f) return end
        return exp(ln(start) + (ln(end) - ln(start)) * t).toFloat()
    }

    /** Cancel an in-flight crossfade and tear down the secondary player, restoring the
     *  primary to full volume with its filter disabled. Safe to call when not crossfading. */
    private fun cancelCrossfade() {
        crossfadeGeneration++
        crossfadeJob?.cancel()
        crossfadeJob = null
        currentPlayerFilter?.enabled = false
        secondaryPlayerFilter?.enabled = false
        runCatching { secondaryPlayer?.release() }
        secondaryPlayer = null
        secondaryPlayerFilter = null
        player?.volume = 1f
        isCrossfading = false
        pendingNextSong = null
    }

    data class PlaybackPosition(
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean
    )

    suspend fun getPlaybackPosition(): PlaybackPosition {
        if (webPlaybackActive()) {
            return PlaybackPosition(
                positionMs = SpotifyWebPlayer.positionMs,
                durationMs = SpotifyWebPlayer.durationMs,
                isPlaying = SpotifyWebPlayer.isPlaying
            )
        }
        val p = player ?: return PlaybackPosition(0L, 0L, false)
        return withContext(Dispatchers.Main) {
            val isPlaying = runCatching { p.isPlaying }.getOrDefault(false)
            val pos = runCatching { p.currentPosition }.getOrDefault(0L)
            val dur = runCatching { p.duration }.getOrDefault(0L)
            PlaybackPosition(positionMs = pos, durationMs = dur, isPlaying = isPlaying)
        }
    }

    @Volatile private var positionListener: ((positionMs: Long, durationMs: Long) -> Unit)? = null

    fun setPositionListener(listener: ((positionMs: Long, durationMs: Long) -> Unit)?) {
        positionListener = listener
    }

    /** (Re)start the loop that watches playback position and fires a crossfade as the
     *  current track approaches its end. */
    private var posSaveTick = 0
    private val historyRecorder = com.music.spotui.playback.PlaybackHistoryRecorder()

    private fun startPositionWatch(token: String = currentPlaybackToken) {
        if (!ownership.isCurrent(token)) return
        positionWatchJob?.cancel()
        posSaveTick = 0
        positionWatchJob = scope.launch(Dispatchers.Main) {
            while (isActive && ownership.isCurrent(token)) {
                delay(250)
                val ctx = appCtx ?: continue
                val status = getPlaybackPosition()
                ownership.runIfCurrent(token) {
                    val owner = ownership.positionOwner(token) ?: return@runIfCurrent
                    if (!ownsPlayerItem(token)) return@runIfCurrent
                    historyRecorder.onPlayingSnapshot(ownership, token, ownsPlayerItem(token), status.isPlaying) { song ->
                        com.music.spotui.data.preferences.addListeningHistory(
                            ctx, com.music.spotui.data.preferences.HistoryEntry.fromSong(song),
                        )
                    }
                    if (++posSaveTick % 4 == 0 && !webPlaybackActive() &&
                        status.isPlaying && status.positionMs > 0
                    ) {
                        saveCurrentPosition()
                    }
                    if (!status.isPlaying || status.durationMs <= 0 || status.positionMs < 0) return@runIfCurrent
                    positionListener?.invoke(status.positionMs, status.durationMs)
                    // External DirectAudio never starts the music crossfade machinery.
                    if (!ownership.isCurrent(token) || owner.external || owner.mediaType != MediaType.TRACK || isCrossfading) return@runIfCurrent
                    val crossfadeMs = com.music.spotui.data.preferences.getCrossfadeMs(ctx)
                    if (crossfadeMs <= 0) return@runIfCurrent
                    val state = boundState ?: return@runIfCurrent
                    if (state.repeat.value) return@runIfCurrent
                    if (status.positionMs >= status.durationMs - crossfadeMs) triggerCrossfade(ctx, crossfadeMs)
                }
            }
        }
    }

    private fun ownsCrossfade(token: String, generation: Long): Boolean =
        ownership.isCurrent(token) && isCrossfading && crossfadeGeneration == generation

    /** Resolution and the fade share one cancellable job and one owner. */
    private fun triggerCrossfade(ctx: Context, configuredMs: Int) {
        if (isCrossfading) return
        val owner = ownership.active ?: return
        if (owner.external || owner.mediaType != MediaType.TRACK) return
        val state = boundState ?: return
        val q = state.queue.value
        val cur = q.indexOfFirst { it.id == state.songId.value }
        if (cur < 0 || cur >= q.size - 1) return
        val nextSong = q[cur + 1]
        if (nextSong.isPodcast()) return
        val token = owner.token
        val generation = ++crossfadeGeneration
        val nextToken = java.util.UUID.randomUUID().toString()
        isCrossfading = true
        crossfadeJob = scope.launch {
            try {
                val nextUrl = resolveStreamUrl(nextSong.url, ctx, forPlayback = false)
                if (nextUrl == null) {
                    withContext(Dispatchers.Main) {
                        if (ownsCrossfade(token, generation)) cancelCrossfade()
                    }
                    return@launch
                }
                val remaining = withContext(Dispatchers.Main) {
                    if (!ownsCrossfade(token, generation)) return@withContext null
                    val p = player ?: return@withContext null
                    val d = p.duration
                    if (d > 0 && p.currentPosition >= 0) d - p.currentPosition else configuredMs.toLong()
                } ?: return@launch
                val effectiveMs = minOf(configuredMs.toLong(), remaining).coerceAtLeast(1000L).toInt()
                val djMode = com.music.spotui.data.preferences.isCrossfadeDjMode(ctx)
                withContext(Dispatchers.Main) {
                    ownership.runIfCurrent(token) {
                        if (!ownsCrossfade(token, generation)) return@runIfCurrent
                        pendingNextSong = nextSong
                        pendingNextSongIdx = cur + 1
                        setNowPlayingMeta(nextSong.title, nextSong.singer, nextSong.coverUri)
                        val (sp, sf) = createPlayerWithFilter(ctx, handleAudioFocus = false)
                        secondaryPlayer = sp
                        secondaryPlayerFilter = sf
                        sp.setMediaItem(buildMediaItem(nextUrl, streamMimeType(nextUrl, sourceCache[nextSong.url].orEmpty()), nextToken))
                        sp.seekTo(com.music.spotui.data.preferences.loadPlaybackPosition(ctx, nextSong))
                        sp.prepare()
                        sp.volume = 0f
                        sp.playWhenReady = true
                    }
                }
                performCrossfade(effectiveMs, djMode, token, generation, nextToken)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (ownsCrossfade(token, generation)) {
                        Log.e(TAG, "crossfade failed", e)
                        cancelCrossfade()
                    }
                }
            }
        }
    }

    private suspend fun performCrossfade(
        effectiveMs: Int, djMode: Boolean, token: String, generation: Long, nextToken: String,
    ) {
        val steps = 50
        val delayPerStep = (effectiveMs / steps).coerceAtLeast(20)
        withContext(Dispatchers.Main) {
            if (!ownsCrossfade(token, generation)) return@withContext
            if (djMode) {
                currentPlayerFilter?.apply {
                    filterType = com.music.spotui.audio.BiquadFilter.FilterType.LOW_PASS
                    cutoffFrequencyHz = CF_LPF_START_HZ; enabled = true
                }
                secondaryPlayerFilter?.apply {
                    filterType = com.music.spotui.audio.BiquadFilter.FilterType.HIGH_PASS
                    cutoffFrequencyHz = CF_HPF_START_HZ; enabled = true
                }
            }
        }
        for (step in 0..steps) {
            if (!ownsCrossfade(token, generation)) return
            val progress = step.toFloat() / steps
            val angle = (progress * PI / 2).toFloat()
            withContext(Dispatchers.Main) {
                ownership.runIfCurrent(token) {
                    if (!ownsCrossfade(token, generation)) return@runIfCurrent
                    player?.volume = cos(angle)
                    secondaryPlayer?.volume = sin(angle)
                    if (djMode) {
                        val fp = sigmoid(progress)
                        currentPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_LPF_START_HZ, CF_LPF_END_HZ, fp)
                        secondaryPlayerFilter?.cutoffFrequencyHz = expInterpolate(CF_HPF_START_HZ, CF_HPF_END_HZ, fp)
                    }
                }
            }
            delay(delayPerStep.toLong())
        }
        finalizeCrossfade(token, generation, nextToken)
    }

    private suspend fun finalizeCrossfade(token: String, generation: Long, nextToken: String) {
        withContext(Dispatchers.Main) {
            ownership.runIfCurrent(token) {
                if (!ownsCrossfade(token, generation)) return@runIfCurrent
                val incoming = secondaryPlayer ?: return@runIfCurrent
                val next = pendingNextSong ?: return@runIfCurrent
                val old = player
                saveCurrentPosition(completed = true)
                ownership.begin(next.url, next.mediaType, next, token = nextToken)
                qualityUpgradeJob?.cancel()
                errorRecoveryJob?.cancel()
                currentPlayerFilter?.enabled = false
                secondaryPlayerFilter?.enabled = false
                player = incoming
                currentPlayerFilter = secondaryPlayerFilter
                secondaryPlayer = null
                secondaryPlayerFilter = null
                incoming.volume = 1f
                incoming.setAudioAttributes(buildAudioAttributes(), /* handleAudioFocus = */ true)
                incoming.setHandleAudioBecomingNoisy(true)
                runCatching { old?.stop(); old?.release() }
                isCrossfading = false
                ownership.activate(nextToken)
                val context = appCtx
                retryPlayback.set(nextToken, context?.let { ctx ->
                    { playSong(next.url, ctx, next, null, null) }
                })
                currentSource = sourceCache[next.url] ?: currentSource
                currentQuality = qualityCache[next.url].orEmpty()
                boundState?.updateSongState(
                    next.coverUri, next.title, next.singer, true,
                    next.id, pendingNextSongIdx, next.album,
                )
                pendingNextSong = null
                syncPlayingState(incoming)
                onPlayerSwapped?.invoke(incoming)
                startPositionWatch(nextToken)
            }
        }
    }

    // ── Sleep timer ──
    // Pauses playback after a delay. A new call replaces any pending timer;
    // passing 0 (or calling cancelSleepTimer) clears it.
    @Volatile private var sleepJob: kotlinx.coroutines.Job? = null
    @Volatile var sleepTimerEndAt: Long = 0L
        private set

    fun setSleepTimer(durationMillis: Long) {
        sleepJob?.cancel()
        if (durationMillis <= 0L) {
            sleepTimerEndAt = 0L
            return
        }
        sleepTimerEndAt = System.currentTimeMillis() + durationMillis
        sleepJob = scope.launch {
            kotlinx.coroutines.delay(durationMillis)
            withContext(Dispatchers.Main) { pause() }
            sleepTimerEndAt = 0L
        }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        sleepTimerEndAt = 0L
    }
}
