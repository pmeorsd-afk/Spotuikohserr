package com.music.spotui.ui.collections

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.di.SongPlayer
import com.music.spotui.playback.*
import kotlinx.coroutines.*

@Composable
internal fun RadioPreviewScreen(presentation: CollectionPresentation, songs: List<SongsModel>, onBack: () -> Unit, onMore: (SongsModel) -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Dedicated scope is closed after restoring main audio, not before cleanup on disposal.
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val session = remember {
        RadioPreviewSession(scope, RadioPreviewMainPlayback(), RadioPreviewPlayer(context, scope)) { song ->
            withContext(Dispatchers.IO) { SongPlayer.resolveRadioPreview(song, context) }
        }
    }
    val state by session.state.collectAsState()
    DisposableEffect(session, lifecycle) {
        val interrupt: () -> Unit = { session.interrupt() }
        RadioPreviewInterruption.listener = interrupt
        val mainPlayer = SongPlayer.exoPlayer
        val mainListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { session.checkMainPlayback() }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) session.interrupt()
            }
        }
        mainPlayer?.addListener(mainListener)
        // Also protect the resolution interval, before the temporary ExoPlayer exists.
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) session.interrupt()
            }
        }
        ContextCompat.registerReceiver(context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) session.interrupt() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mainPlayer?.removeListener(mainListener)
            context.unregisterReceiver(noisy)
            if (RadioPreviewInterruption.listener === interrupt) RadioPreviewInterruption.listener = null
            session.close()
            scope.cancel()
        }
    }
    LaunchedEffect(session) { while (isActive) { session.checkMainPlayback(); delay(50) } }
    BackHandler(onBack = onBack)
    RadioPreviewContent(presentation.title, rememberCollectionColor(presentation.cover, presentation.artworkAllowed, true),
        songs.filterNot { it.isPodcast() }, state, onBack, session::toggle, onMore)
}
