package com.music.spotui.ui.notification

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.FlagSet

/** Session presentation only. The underlying stream, timeline and playback ownership are untouched. */
internal open class AutoSessionPlayer(
    base: Player,
    private val metadata: () -> MediaMetadata?,
    private val repeat: () -> Int,
    private val changeRepeat: (Int) -> Unit,
) : ForwardingPlayer(base) {
    private val listeners = linkedMapOf<Player.Listener, Player.Listener>()
    private var lastMetadata: MediaMetadata? = null
    private var lastRepeat = repeat()

    override fun getMediaMetadata(): MediaMetadata = metadata() ?: super.getMediaMetadata()
    override fun getRepeatMode(): Int = repeat()
    // ExoPlayer holds one resolved item. Setting ALL on that item would incorrectly loop one song.
    override fun setRepeatMode(repeatMode: Int) = changeRepeat(repeatMode)

    override fun addListener(listener: Player.Listener) {
        if (listener in listeners) return
        val delegate = object : ForwardingPlayerListener(listener) {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                listener.onMediaMetadataChanged(getMediaMetadata())
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                listener.onRepeatModeChanged(getRepeatMode())
            }
            override fun onEvents(player: Player, events: Player.Events) {
                listener.onEvents(this@AutoSessionPlayer, events)
            }
        }
        listeners[listener] = delegate
        super.addListener(delegate)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    /** Notify Media3 of changes originating in app state, including phone-side Repeat changes. */
    fun refreshPresentation() {
        val currentMetadata = mediaMetadata
        val currentRepeat = repeatMode
        val changedMetadata = currentMetadata != lastMetadata
        val changedRepeat = currentRepeat != lastRepeat
        if (!changedMetadata && !changedRepeat) return
        lastMetadata = currentMetadata
        lastRepeat = currentRepeat
        val events = Player.Events(FlagSet.Builder().apply {
            if (changedMetadata) add(Player.EVENT_MEDIA_METADATA_CHANGED)
            if (changedRepeat) add(Player.EVENT_REPEAT_MODE_CHANGED)
        }.build())
        listeners.keys.toList().forEach { listener ->
            if (changedMetadata) listener.onMediaMetadataChanged(currentMetadata)
            if (changedRepeat) listener.onRepeatModeChanged(currentRepeat)
            listener.onEvents(this, events)
        }
    }
}
