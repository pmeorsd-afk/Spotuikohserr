package com.music.spotui.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.music.spotui.di.SongPlayer
import kotlinx.coroutines.*

/** Screen-owned player; intentionally never registered with MediaSession or CurrentSongState. */
internal class RadioPreviewPlayer(context: Context, private val scope: CoroutineScope) : PreviewOutput {
    private val context = context.applicationContext
    private var player: ExoPlayer? = null
    private var watch: Job? = null

    override fun load(source: RadioPreviewSource, window: PreviewWindow, positionMs: Long, events: PreviewOutput.Events) {
        stop()
        val p = SongPlayer.createRadioPreviewPlayer(context)
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (player === p) events.playing(isPlaying)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === p && playbackState == Player.STATE_ENDED) events.ended()
            }
            override fun onPlayerError(error: PlaybackException) { if (player === p) events.failed() }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player === p && !playWhenReady && (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ||
                        reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) events.interrupted()
            }
            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                if (player === p && playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE) events.interrupted()
            }
        })
        val item = MediaItem.Builder().setUri(source.uri)
            .apply { source.mimeType?.let { setMimeType(it) } }
            .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(window.startMs).setEndPositionMs(window.endMs).build()).build()
        p.setMediaItem(item, positionMs)
        p.prepare()
        p.play()
        watch = scope.launch {
            var lastPosition = positionMs
            var stalledForMs = 0L
            while (isActive && player === p) {
                events.position(p.currentPosition)
                stalledForMs = if (p.currentPosition != lastPosition) 0 else stalledForMs + 50
                lastPosition = p.currentPosition
                if (stalledForMs >= 20_000) { events.failed(); break }
                delay(50)
            }
        }
    }

    override fun stop() {
        watch?.cancel(); watch = null
        val old = player
        player = null // callbacks during release cannot affect a later preview
        old?.release()
    }
    override fun positionMs(): Long? = player?.currentPosition
}

internal class RadioPreviewMainPlayback : PreviewMainPlayback {
    override fun identity() = SongPlayer.currentPlaybackToken
    override fun wantsToPlay() = SongPlayer.exoPlayer?.playWhenReady == true || SongPlayer.isPlaying()
    override fun pause() = RadioPreviewInterruption.fromPreview { SongPlayer.pause() }
    override fun resume() = RadioPreviewInterruption.fromPreview { SongPlayer.play() }
}
