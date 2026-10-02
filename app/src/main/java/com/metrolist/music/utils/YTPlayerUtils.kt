/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_65_10
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_MUSIC
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS_RECENT
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            Timber.tag("YT_HTTP").d("--> REQ [${req.method}] ${req.url}\nHeaders:\n${req.headers}")
            val resp = chain.proceed(req)
            Timber.tag("YT_HTTP").d("<-- RESP ${resp.code} for ${req.url}\nResp Headers:\n${resp.headers}")
            resp
        }
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    // Fast anonymous streaming path like BitChord:
    // ANDROID_VR_1_65_10 returns direct unthrottled audio streams (format.url present) in ~150-200ms
    // without requiring login, PoTokens, or NewPipe watch-page HTML scraping.
    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_1_65_10

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_1_43_32,
        IOS,
        IOS_RECENT,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,
        ANDROID_MUSIC,
        MOBILE,
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(TAG).d("=== PLAYER RESPONSE FOR PLAYBACK ===")
        Timber.tag(TAG).d("videoId: $videoId")
        Timber.tag(TAG).d("playlistId: $playlistId")
        Timber.tag(TAG).d("audioQuality: $audioQuality")

        if (YouTube.visitorData.isNullOrBlank()) {
            YouTube.visitorData = YouTube.visitorData().getOrNull()
        }

        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true
        Timber.tag(TAG).d("Content type detection (preliminary):")
        Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")

        val isLoggedIn = YouTube.cookie
            ?.split(';')
            ?.any { it.substringBefore('=').trim() == "SAPISID" } == true
        Timber.tag(TAG).d("Authentication status: ${if (isLoggedIn) "LOGGED_IN" else "ANONYMOUS"}")

        // Lazy signature timestamp: native clients (ANDROID_VR, IOS) do not need signature timestamps,
        // so we don't block the fast-path by hitting NewPipeExtractor's watch-page scraper.
        var signatureTimestampResult: SignatureTimestampResult? = null
        fun getSigTimestamp(): SignatureTimestampResult {
            if (signatureTimestampResult == null) {
                signatureTimestampResult = getSignatureTimestampOrNull(videoId)
                Timber.tag(logTag).d("Signature timestamp: ${signatureTimestampResult?.timestamp}")
            }
            return signatureTimestampResult!!
        }

        // Generate PoToken only for a web client. The native fast path does not
        // use one and must remain anonymous.
        var poToken: PoTokenResult? = null
        // Web BotGuard binds its streaming token to VISITOR_DATA. DATASYNC_ID is
        // an account synchronization identifier and produces a token that the
        // googlevideo CDN rejects even though minting itself succeeds.
        val sessionId = YouTube.visitorData?.takeIf { it.isNotBlank() }
        val mainClientNeedsPoToken = MAIN_CLIENT.useWebPoTokens
        if (mainClientNeedsPoToken && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }
        // If MAIN_CLIENT needs a PoToken but we couldn't get one (WebView missing, JS
        // blocked, network hostile), WEB_REMIX will return streams that 403 on play.
        // Skip it and go straight to the fallback chain.
        val skipMainClient = mainClientNeedsPoToken && poToken == null
        if (skipMainClient) {
            Timber.tag(TAG).w("PoToken unavailable — skipping MAIN_CLIENT and using fallback chain directly")
        }

        // Try MAIN_CLIENT (native fast path, ~200ms)
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        var initialResponseClient = MAIN_CLIENT
        var mainPlayerResponse = YouTube.player(
            videoId,
            playlistId,
            MAIN_CLIENT,
            signatureTimestamp = if (MAIN_CLIENT.useSignatureTimestamp) getSigTimestamp().timestamp else null,
            poToken = poToken?.playerRequestPoToken,
            authenticated = false,
        ).getOrNull()
        if (mainPlayerResponse == null) {
            // An obsolete/refused identity can fail at HTTP level before a
            // playability body exists. BitChord walks on instead of aborting.
            for (candidate in STREAM_FALLBACK_CLIENTS) {
                val response = YouTube.player(
                    videoId,
                    playlistId,
                    candidate,
                    signatureTimestamp = if (candidate.useSignatureTimestamp) getSigTimestamp().timestamp else null,
                    authenticated = false,
                ).getOrNull()
                if (response != null) {
                    initialResponseClient = candidate
                    mainPlayerResponse = response
                    break
                }
            }
        }
        var mainResponse = mainPlayerResponse ?: throw Exception("Every YouTube player endpoint failed")

        // Debug uploaded track response
        if (isUploadedTrack || playlistId?.contains("MLPT") == true) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainResponse.playabilityStatus.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainResponse.videoDetails?.title}, videoId=${mainResponse.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainResponse.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // BitChord's restricted-content fallback is the listener's real
            // signed-in browser session, not a cookie attached to a native app.
            Timber.tag(logTag).d("Restricted content, using authenticated WEB_REMIX")
            Timber.tag(TAG).i("Restricted: authenticated WEB_REMIX for videoId=$videoId")

            // The native main client does not need a PoToken, so the eager
            // generation above intentionally skips it. WEB_REMIX does need one:
            // without both its player-request and streaming-data tokens YouTube
            // returns valid-looking signed URLs which the CDN rejects with 403.
            if (poToken == null && sessionId != null) {
                try {
                    Timber.tag(logTag).d("Generating PoToken for restricted WEB_REMIX")
                    poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    Timber.tag(logTag).d("Restricted WEB_REMIX PoToken available: ${poToken != null}")
                } catch (e: Exception) {
                    Timber.tag(logTag).e(e, "Restricted WEB_REMIX PoToken generation failed")
                }
            }
            val creatorResponse = YouTube.player(
                videoId,
                playlistId,
                WEB_REMIX,
                getSigTimestamp().timestamp,
                poToken?.playerRequestPoToken,
                authenticated = true,
            ).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_REMIX authenticated response is playable")
                mainResponse = creatorResponse
                usedAgeRestrictedClient = WEB_REMIX
            }
        }

        // If we still don't have a valid response, throw

        val audioConfig = mainResponse.playerConfig?.audioConfig
        val videoDetails = mainResponse.videoDetails
        val playbackTracking = mainResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        val retryMainPlayerResponse: PlayerResponse? = mainResponse

        // Check current status
        val currentStatus = mainResponse.playabilityStatus.status
        val isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Timber.tag(TAG)
                .i("Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = mainResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        // For private tracks: use TVHTML5 (index 1) with PoToken + n-transform
        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            // Reuse the authenticated response above immediately. Previously it was
            // discarded and all anonymous clients ran before WEB_CREATOR was fetched again.
            usedAgeRestrictedClient != null -> -1
            isPrivateTrack -> STREAM_FALLBACK_CLIENTS.indexOf(TVHTML5).coerceAtLeast(0)
            isAgeRestricted -> 0
            skipMainClient -> 0  // MAIN_CLIENT streams unplayable without PoToken
            // Use the already-fetched ANDROID_MUSIC response first.
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = usedAgeRestrictedClient ?: initialResponseClient
                streamPlayerResponse = retryMainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content if client needs it
                val clientSigTimestamp = if (wasOriginallyAgeRestricted || !client.useSignatureTimestamp) null else getSigTimestamp().timestamp
                streamPlayerResponse =
                    YouTube.player(
                        videoId,
                        playlistId,
                        client,
                        clientSigTimestamp,
                        clientPoToken,
                        authenticated = false,
                    ).getOrNull()
            }

            // YouTube content substitution guard: a client with a mismatched
            // session can return a playable response for a DIFFERENT video (the
            // classic "plays the wrong song" bug). Never accept streams whose
            // videoId doesn't match what we asked for.
            val returnedVideoId = streamPlayerResponse?.videoDetails?.videoId
            if (returnedVideoId != null && returnedVideoId != videoId) {
                Timber.tag(TAG).w(
                    "Client ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName} " +
                        "returned WRONG video: $returnedVideoId != $videoId — skipping",
                )
                continue
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // Skip NewPipe for age-restricted content (NewPipe doesn't use our auth)
                // Keep the URL minted by this exact client/session. Replacing it
                // with NewPipe here breaks the client/header contract; extraction
                // remains the final fallback after the complete client walk.
                val responseToUse = streamPlayerResponse

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(
                    format,
                    videoId,
                    responseToUse,
                    skipNewPipe = wasOriginallyAgeRestricted,
                )
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    client
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }
                streamUrl = patchClientVersion(streamUrl, currentClient.clientVersion)

                // Check if this is a privately owned track
                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"
                val musicVideoType = streamPlayerResponse.videoDetails?.musicVideoType

                Timber.tag(TAG).d("=== N-TRANSFORM DECISION ===")
                Timber.tag(TAG).d("Content type analysis:")
                Timber.tag(TAG).d("  musicVideoType: $musicVideoType")
                Timber.tag(TAG).d("  isPrivatelyOwnedTrack: $isPrivatelyOwnedTrack")
                Timber.tag(TAG).d("  isUploadedTrack (from playlistId): $isUploadedTrack")
                Timber.tag(TAG).d("  wasOriginallyAgeRestricted: $wasOriginallyAgeRestricted")
                Timber.tag(TAG).d("Client analysis:")
                Timber.tag(TAG).d("  currentClient: ${currentClient.clientName}")
                Timber.tag(TAG).d("  useWebPoTokens: ${currentClient.useWebPoTokens}")

                // Apply n-transform and PoToken for web clients OR for private tracks (including TVHTML5)
                val needsNTransform = currentClient.useWebPoTokens ||
                    currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                    isPrivatelyOwnedTrack

                Timber.tag(TAG).d("N-transform decision:")
                Timber.tag(TAG).d("  needsNTransform: $needsNTransform")
                Timber.tag(TAG).d("  Reason: useWebPoTokens=${currentClient.useWebPoTokens}, " +
                    "clientInList=${currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5")}, " +
                    "isPrivatelyOwnedTrack=$isPrivatelyOwnedTrack")

                if (needsNTransform) {
                    try {
                        Timber.tag(TAG).d("Applying n-transform to stream URL...")
                        Timber.tag(TAG).d("  Original URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  Original URL preview: ${streamUrl.take(100)}...")

                        val originalUrl = streamUrl
                        // Use the same live player.js WebView that solved `s`. The
                        // NewPipe regex currently cannot identify this player's n
                        // function and silently returns the unchanged URL, which the
                        // CDN rejects with 403 even when the signature itself is valid.
                        streamUrl = CipherDeobfuscator.transformNParamInUrl(streamUrl)

                        Timber.tag(TAG).d("  Transformed URL length: ${streamUrl.length}")
                        Timber.tag(TAG).d("  URL changed: ${originalUrl != streamUrl}")

                        // Append pot= parameter with streaming data poToken
                        val needsPoToken = (currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null
                        Timber.tag(TAG).d("PoToken decision:")
                        Timber.tag(TAG).d("  needsPoToken: $needsPoToken")
                        Timber.tag(TAG).d("  hasStreamingDataPoToken: ${poToken?.streamingDataPoToken != null}")

                        if (needsPoToken) {
                            Timber.tag(TAG).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in streamUrl) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                            Timber.tag(TAG).d("  Final URL length (with pot): ${streamUrl.length}")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "N-transform or pot append failed: ${e.message}")
                        Timber.tag(TAG).e("Stack trace: ${e.stackTraceToString().take(500)}")
                        // Continue with original URL
                    }
                } else {
                    Timber.tag(TAG).d("Skipping n-transform (not required for this client/content)")
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Check if this is a privately owned track (uploaded song)
                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (isPrivatelyOwned) {
                    Timber.tag(logTag).d("Skipping validation for privately owned track: ${currentClient.clientName}")
                    println("[PLAYBACK_DEBUG] Using stream without validation for PRIVATELY_OWNED_TRACK")
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=true")
                    break
                }

                if (validateStatus(streamUrl, format.contentLength)) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    // Log for release builds
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")
                    streamUrl = null
                    format = null
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        // Failsafe: if player clients produced no validated stream, fall back to NewPipe progressive audio streams (unthrottled, continuous, no 1 MiB boundary).
        if (streamUrl == null || format == null) {
            Timber.tag(logTag).w("Player clients produced no validated stream for videoId=$videoId — invoking NewPipe progressive failsafe")
            val newPipeStreams = YouTube.getNewPipeStreamUrls(videoId)
            if (newPipeStreams.isNotEmpty()) {
                val preferredItags = when (audioQuality) {
                    AudioQuality.HIGH -> listOf(251, 140, 250, 249, 139)
                    AudioQuality.LOW -> listOf(249, 139, 250, 140, 251)
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) listOf(250, 140, 249, 139, 251) else listOf(251, 140, 250, 249, 139)
                }
                val selectedStream = preferredItags.firstNotNullOfOrNull { itag ->
                    newPipeStreams.find { it.first == itag }
                } ?: newPipeStreams.first()

                val npUrl = selectedStream.second
                val npFormat = mainResponse.streamingData?.adaptiveFormats?.find { it.itag == selectedStream.first }
                    ?: PlayerResponse.StreamingData.Format(
                        itag = selectedStream.first,
                        url = npUrl,
                        mimeType = if (selectedStream.first in listOf(249, 250, 251)) "audio/webm; codecs=\"opus\"" else "audio/mp4",
                        bitrate = if (selectedStream.first == 251) 160000 else 128000,
                        contentLength = null,
                        lastModified = 0L,
                        loudnessDb = null,
                    )
                format = npFormat
                streamUrl = npUrl
                streamExpiresInSeconds = streamExpiresInSeconds ?: 21600
                Timber.tag(TAG).i("Playback: client=NewPipeProgressive, videoId=$videoId, itag=${selectedStream.first}")
            }
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url - all clients and NewPipe failsafe failed")
            throw Exception("Could not find stream url")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl.take(100)}...")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val adaptiveAudio = playerResponse.streamingData?.adaptiveFormats?.filter { it.isAudio }
        val format = adaptiveAudio
            ?.filter { it.isOriginal }
            ?.maxByOrNull {
                (if (!it.url.isNullOrEmpty()) 500000 else 0) +
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
            } ?: adaptiveAudio?.maxByOrNull { (if (!it.url.isNullOrEmpty()) 500000 else 0) + it.bitrate }
              ?: playerResponse.streamingData?.formats?.firstOrNull { it.isAudio }
              ?: playerResponse.streamingData?.formats?.firstOrNull()

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}, directUrl=${!format.url.isNullOrEmpty()}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    private const val AUTH_BOUNDARY_BYTES = 1024L * 1024L // 1 MiB
    private const val PROBE_READ_BYTES = 16L * 1024L // 16 KiB

    /**
     * Checks if the stream url returns a successful status.
     * Probes past the 1 MiB boundary with the real chunk size and verifies bytes,
     * so clients that 403 past 1 MiB (e.g. anonymous ANDROID_VR) are detected
     * and rejected immediately (<100ms) rather than failing mid-playback.
     */
    private fun validateStatus(url: String, contentLength: Long?): Boolean {
        val totalLength = contentLength ?: android.net.Uri.parse(url).getQueryParameter("clen")?.toLongOrNull()
        Timber.tag(logTag).d("Validating stream URL status (totalLength=$totalLength)")
        try {
            val start = if (totalLength != null && totalLength > AUTH_BOUNDARY_BYTES + PROBE_READ_BYTES) {
                AUTH_BOUNDARY_BYTES
            } else {
                0L
            }
            val rangeBytes = com.music.spotui.playback.ChunkedDataSource.rangeBytesFor(url)
            val end = minOf(start + rangeBytes, totalLength ?: Long.MAX_VALUE) - 1
            val range = "bytes=$start-$end"
            val requestBuilder = okhttp3.Request.Builder()
                .get()
                .url(url)
                .addHeader("Range", range)
            // A googlevideo URL is bound to the client identity that minted it.
            YouTubeClient.forStreamUrl(url).mediaHeaders().forEach { (name, value) ->
                requestBuilder.header(name, value)
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            response.use {
                val code = response.code
                if (code in listOf(401, 403, 404, 410)) {
                    Timber.tag(logTag).w("Stream URL probe refused with HTTP $code for range $range")
                    return false
                }
                val mediaType = response.header("Content-Type").orEmpty().lowercase()
                val isSuccess = response.isSuccessful || code == 416 || code == 405
                val isHtmlError = mediaType.contains("text/html") || mediaType.contains("application/json")
                if (!isSuccess || isHtmlError) {
                    Timber.tag(logTag).w("Stream URL probe failed: code=$code type=$mediaType")
                    return false
                }
                val body = response.body
                val hasBytes = body?.source()?.request(minOf(PROBE_READ_BYTES, body.contentLength().takeIf { it > 0 } ?: PROBE_READ_BYTES)) == true
                Timber.tag(logTag).d("Stream URL validation: code=$code type=$mediaType range=$range hasBytes=$hasBytes accepted=$hasBytes")
                return hasBytes
            }
        } catch (e: java.io.IOException) {
            Timber.tag(logTag).w(e, "Stream URL probe failed (IO)")
            return false
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using YouTube player JavaScript")
            val jsSolvedUrl = NewPipeExtractor.getSignatureUrl(format, videoId)
            if (jsSolvedUrl != null) {
                Timber.tag(logTag).d("Signature solved via YouTube player JavaScript")
                return jsSolvedUrl
            }

            Timber.tag(logTag).d("Player-JS signature failed, trying bundled cipher fallback")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")

        }

        // Skip only the unrelated anonymous extraction/substitution fallbacks.
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping anonymous extraction for authenticated content")
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    /** Keep the CDN URL aligned with the client version that minted it. */
    private fun patchClientVersion(url: String, clientVersion: String): String =
        if ("cver=" in url) {
            url.replace(Regex("cver=[^&]+"), "cver=$clientVersion")
        } else url

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }
}
