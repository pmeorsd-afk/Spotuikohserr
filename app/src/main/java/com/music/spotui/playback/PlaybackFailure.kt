package com.music.spotui.playback

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.TimeoutCancellationException

internal enum class PlaybackFailure(val message: String) {
    TIMEOUT("Playback took too long. Check your connection and tap Play to retry."),
    DNS("Cannot reach the audio server. Check your connection or VPN and retry."),
    TLS("A secure audio connection could not be established. Check your network and retry."),
    HTTP("The audio server refused the request. Check your connection or VPN and retry."),
    NETWORK("Could not load audio. Check your connection and tap Play to retry."),
    UNAVAILABLE("Audio is unavailable. Tap Play to retry or choose another track.");

    companion object {
        fun from(error: Throwable?): PlaybackFailure {
            val seen = HashSet<Throwable>()
            var cause = error
            while (cause != null && seen.add(cause)) {
                when (cause) {
                    is TimeoutCancellationException, is SocketTimeoutException -> return TIMEOUT
                    is UnknownHostException -> return DNS
                    is SSLException -> return TLS
                    is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> return HTTP
                }
                cause = cause.cause
            }
            return if (error == null) UNAVAILABLE else NETWORK
        }
    }
}
