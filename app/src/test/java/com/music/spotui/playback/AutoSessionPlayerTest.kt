package com.music.spotui.playback

import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.RepeatMode
import com.music.spotui.ui.notification.AutoSessionPlayer
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AutoSessionPlayerTest {
    private class FakeBase {
        val listeners = linkedSetOf<Player.Listener>()
        var repeatWrites = 0
        var metadata = MediaMetadata.Builder().setTitle("Owned base item").build()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "addListener" -> { listeners.add(args!![0] as Player.Listener); null }
                "removeListener" -> { listeners.remove(args!![0] as Player.Listener); null }
                "getMediaMetadata" -> metadata
                "getRepeatMode" -> Player.REPEAT_MODE_OFF
                "setRepeatMode" -> { repeatWrites++; null }
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; else -> null }
            }
        } as Player
    }

    @Test fun controllerRepeatChangesUseAppQueueStateAndNeverLoopTheSingleBaseItem() {
        val base = FakeBase()
        val state = CurrentSongState()
        val wrapper = AutoSessionPlayer(base.player, { null }, { when (state.repeatMode.value) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        } }, { state.setRepeatMode(when (it) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }) })
        wrapper.repeatMode = Player.REPEAT_MODE_ALL
        assertEquals(RepeatMode.ALL, state.repeatMode.value)
        assertEquals(Player.REPEAT_MODE_ALL, wrapper.repeatMode)
        assertEquals(RepeatMode.ONE, state.toggleRepeatMode())
        assertEquals(Player.REPEAT_MODE_ONE, wrapper.repeatMode)
        assertEquals(RepeatMode.OFF, state.toggleRepeatMode())
        assertEquals(RepeatMode.ALL, state.toggleRepeatMode())
        assertEquals(0, base.repeatWrites)
    }

    @Test fun metadataProjectionNotifiesOnceAndRemovedControllerGetsNoCallbacks() {
        val base = FakeBase()
        var projected: MediaMetadata? = MediaMetadata.Builder().setTitle("Resolved item").setArtist("Artist").build()
        val wrapper = AutoSessionPlayer(base.player, { projected }, { Player.REPEAT_MODE_OFF }, {})
        val seen = mutableListOf<MediaMetadata>()
        val eventPlayers = mutableListOf<Player>()
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { seen += mediaMetadata }
            override fun onEvents(player: Player, events: Player.Events) { eventPlayers += player }
        }
        wrapper.addListener(listener)
        wrapper.addListener(listener)
        assertEquals(1, base.listeners.size)
        wrapper.refreshPresentation()
        wrapper.refreshPresentation()
        assertEquals(listOf(projected), seen)
        assertSame(wrapper, eventPlayers.single())
        projected = null // Ownership moved; never keep the previous track's artwork/artist.
        wrapper.refreshPresentation()
        assertSame(base.metadata, seen.last())
        wrapper.removeListener(listener)
        assertTrue(base.listeners.isEmpty())
        base.metadata = MediaMetadata.Builder().setTitle("Next item").build()
        wrapper.refreshPresentation()
        assertEquals(2, seen.size)
    }

    @Test fun nativeTransportCallbacksReachTheSessionBeforeTheEventsSnapshot() {
        val base = FakeBase()
        val wrapper = AutoSessionPlayer(base.player, { null }, { Player.REPEAT_MODE_OFF }, {})
        val seen = mutableListOf<String>()
        var sessionState = Player.STATE_IDLE
        var sessionPlayWhenReady = false
        wrapper.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                sessionState = playbackState
                seen += "state:$playbackState"
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                sessionPlayWhenReady = playWhenReady
                seen += "ready:$playWhenReady:$reason"
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { seen += "playing:$isPlaying" }
            override fun onEvents(player: Player, events: Player.Events) {
                assertSame(wrapper, player)
                assertEquals(Player.STATE_READY, sessionState)
                assertTrue(sessionPlayWhenReady)
                seen += "events"
            }
        })
        val upstream = base.listeners.single()
        upstream.onPlaybackStateChanged(Player.STATE_BUFFERING)
        upstream.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        upstream.onPlaybackStateChanged(Player.STATE_READY)
        upstream.onIsPlayingChanged(true)
        upstream.onEvents(base.player, Player.Events(androidx.media3.common.FlagSet.Builder()
            .add(Player.EVENT_PLAYBACK_STATE_CHANGED).add(Player.EVENT_PLAY_WHEN_READY_CHANGED).build()))
        assertEquals(listOf("state:2", "ready:true:1", "state:3", "playing:true", "events"), seen)
    }

    @Test fun everyUnmodifiedJavaDefaultCallbackIsForwardedExactlyOnce() {
        val base = FakeBase()
        val wrapper = AutoSessionPlayer(base.player, { null }, { Player.REPEAT_MODE_OFF }, {})
        val seen = mutableListOf<Pair<java.lang.reflect.Method, Array<out Any?>?>>()
        val listener = Proxy.newProxyInstance(Player.Listener::class.java.classLoader,
            arrayOf(Player.Listener::class.java)) { _, method, args ->
            when (method.name) {
                "hashCode" -> 17
                "equals" -> false
                else -> { seen += method to args; null }
            }
        } as Player.Listener
        wrapper.addListener(listener)
        val upstream = base.listeners.single()
        // Java's default methods are no-ops. Kotlin `Player.Listener by listener` silently
        // inherits them instead of forwarding, including callbacks Media3 uses to cache state.
        val callbacks = Player.Listener::class.java.methods.filter {
            it.isDefault && it.name !in setOf("onEvents", "onMediaMetadataChanged", "onRepeatModeChanged")
        }
        assertTrue(callbacks.size >= 30)
        callbacks.forEach { method ->
            val args = method.parameterTypes.map<Class<*>, Any?> { type -> when (type) {
                java.lang.Boolean.TYPE -> true
                java.lang.Integer.TYPE -> 3
                java.lang.Long.TYPE -> 123L
                java.lang.Float.TYPE -> 0.75f
                else -> null // Opaque payload: this adapter must pass through, never dereference it.
            } }.toTypedArray()
            seen.clear()
            method.invoke(upstream, *args)
            assertEquals("Missing/duplicate callback: $method", 1, seen.size)
            // Media3's own ForwardingPlayer maps the deprecated loading callback to its replacement.
            val expected = if (method.name == "onLoadingChanged")
                Player.Listener::class.java.getMethod("onIsLoadingChanged", java.lang.Boolean.TYPE)
            else method
            assertEquals(expected, seen.single().first)
            assertArrayEquals(args, seen.single().second ?: emptyArray())
        }
        wrapper.removeListener(listener)
        assertTrue(base.listeners.isEmpty())
    }
}
