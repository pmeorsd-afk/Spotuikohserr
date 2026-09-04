package com.music.spotui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import android.content.Intent
import androidx.lifecycle.lifecycleScope
import com.music.spotui.data.preferences.WazeResumeContract
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.notification.PlaybackService
import com.music.spotui.ui.theme.SpotuiTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var currentSongState: CurrentSongState

    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?){

        super.onCreate(savedInstanceState)
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        // Ask for notification permission (Android 13+) so the media notification shows.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Connect a controller to bootstrap the MediaSessionService: this brings up
        // the system media notification and keeps playback alive in the background.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()

        if (com.music.spotui.data.preferences.isWazeOverlayEnabled(this) &&
            com.music.spotui.utils.WazeDetector.hasOverlayPermission(this)) {
            com.music.spotui.service.WazeOverlayService.start(this)
        }


        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        setContent {

            SpotuiTheme {
                // A surface container using the 'background' color from the theme
                    App()

                // New-release check (GitHub): prompts Upgrade / Dismiss / Don't show again.
                com.music.spotui.ui.components.UpdatePrompt()
            }
        }

        // Experimental Spotify web-player engine: attach its hidden WebView AFTER
        // setContent so the Compose content view doesn't replace/orphan it (an
        // orphaned WebView gets a 0×0 viewport and Spotify won't render/navigate).
        if (com.music.spotui.data.api.SpotifySession.spDc(this).isNotBlank() &&
            com.music.spotui.data.api.SpotifySession.spDc(this) != "anonymous"
        ) {
            com.music.spotui.di.SpotifyWebPlayer.attach(this)
        }

        handleWazeResumeIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWazeResumeIntent(intent)
    }

    private fun handleWazeResumeIntent(intent: Intent?) {
        intent ?: return
        if (!intent.getBooleanExtra(WazeResumeContract.EXTRA_RETURN_TO_WAZE, false)) return

        val songId = intent.getIntExtra(WazeResumeContract.EXTRA_SONG_ID, -1)
        val title = intent.getStringExtra(WazeResumeContract.EXTRA_TITLE)
        if (songId <= 0 || title.isNullOrBlank()) return
        val singer = intent.getStringExtra(WazeResumeContract.EXTRA_SINGER).orEmpty()
        val album = intent.getStringExtra(WazeResumeContract.EXTRA_ALBUM).orEmpty()
        val coverUri = intent.getStringExtra(WazeResumeContract.EXTRA_COVER_URI).orEmpty()

        val url = SongPlayer.buildSpotifyPlayQuery(songId.toString(), title, singer)
        currentSongState.updateSongState(
            coverUri = coverUri,
            title = title,
            singer = singer,
            playingState = true,
            songId = songId,
            songIndex = -1,
            album = album,
        )
        SongPlayer.playSong(url, this)

        lifecycleScope.launch {
            delay(1200)
            packageManager.getLaunchIntentForPackage("com.waze")?.let { startActivity(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        SongPlayer.release()
    }
}


