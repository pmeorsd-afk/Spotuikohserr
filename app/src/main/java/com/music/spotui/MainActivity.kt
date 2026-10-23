package com.music.spotui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.music.spotui.data.preferences.WazeResumeContract
import com.music.spotui.data.entity.MediaType
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.PlaybackSnapshotCodec
import com.music.spotui.playback.WazeResumeResolver
import com.music.spotui.di.CurrentSongState
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.notification.PlaybackService
import com.music.spotui.ui.theme.SpotuiTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var currentSongState: CurrentSongState

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val wazeHandler = Handler(Looper.getMainLooper())
    private var returnToWazeRunnable: Runnable? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    @OptIn(UnstableApi::class)
    @RequiresApi(Build.VERSION_CODES.S)
    override fun onCreate(savedInstanceState: Bundle?){

        super.onCreate(savedInstanceState)
        this.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

        handleWazeResume(intent)
        if (!com.music.spotui.BuildConfig.IS_ADMIN) {
            com.music.spotui.util.KosherWhitelistManager.syncFromAdminProvider(this)
        }

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
    }

    override fun onResume() {
        super.onResume()
        if (!com.music.spotui.BuildConfig.IS_ADMIN) {
            com.music.spotui.util.KosherWhitelistManager.syncFromAdminProvider(this)
        }
        com.music.spotui.util.KosherWhitelistManager.syncWithRemote(this)
        com.music.spotui.util.AppTelemetryManager.onAppForegrounded(this)
        com.music.spotui.util.PresenceCoordinator.setUiForeground(true)
    }

    override fun onPause() {
        super.onPause()
        com.music.spotui.util.AppTelemetryManager.onAppBackgrounded(this)
    }

    override fun onStop() {
        super.onStop()
        com.music.spotui.util.PresenceCoordinator.setUiForeground(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleWazeResume(intent)
    }

    private fun handleWazeResume(intent: Intent?) {
        intent ?: return
        if (!intent.getBooleanExtra("waze_resume_return_to_waze", false) &&
            !intent.getBooleanExtra(WazeResumeContract.EXTRA_RETURN_TO_WAZE, false)) return

        // Close/collapse the expanded mini-player so it returns to the floating button when returning to Waze
        com.music.spotui.service.WazeScreenAccessibilityService.collapseMiniPlayer()

        // Consume once: configuration recreation must not replay an old resume Intent.
        intent.removeExtra(WazeResumeContract.EXTRA_RETURN_TO_WAZE)
        val rawSnapshot = intent.getStringExtra(WazeResumeContract.EXTRA_PLAYBACK_SNAPSHOT)
        val exact = rawSnapshot?.let(PlaybackSnapshotCodec::decode)
        val rawType = intent.getStringExtra(WazeResumeContract.EXTRA_MEDIA_TYPE)
        val requestedType = rawType?.let { runCatching { MediaType.valueOf(it) }.getOrNull() }
        val saved = com.music.spotui.data.preferences.loadLastPlayback(this)?.let { (song, position) ->
            PlaybackSnapshot(song, position)
        }
        val ownerToken = SongPlayer.currentPlaybackToken
        val plan = WazeResumeResolver.resolve(
            WazeResumeResolver.Request(
                exact = exact,
                mediaType = requestedType,
                malformed = (rawSnapshot != null && exact == null) || (rawType != null && requestedType == null),
            ),
            persisted = saved,
            active = SongPlayer.ownedPlaybackSnapshot(),
        )
        val handled = WazeResumeResolver.dispatch(
            plan,
            resumeActive = { playback ->
                if (SongPlayer.resumeOwnedPlayback(ownerToken)) commitWazePlayback(playback)
            },
            startTrack = ::startWazePlayback,
            startPodcast = ::startWazePlayback,
        )
        if (!handled) android.util.Log.w("WazeResume", "Resume rejected: missing or conflicting media identity")
        returnToWazeRunnable?.let { wazeHandler.removeCallbacks(it) }
        val r = Runnable { returnToWaze() }
        returnToWazeRunnable = r
        wazeHandler.postDelayed(r, 1400L)
    }

    private fun startWazePlayback(playback: PlaybackSnapshot) {
        // The exact model enters SongPlayer's existing typed route. Commit UI only after preparation.
        SongPlayer.resumeResolvedPlayback(playback.song, playback.positionMs, this) {
            commitWazePlayback(playback)
        }
    }

    private fun commitWazePlayback(playback: PlaybackSnapshot) {
        val song = playback.song
        currentSongState.commitPlayback(
            com.music.spotui.playback.PlaybackQueueIdentity.select(song, currentSongState.queue.value)
        )
        com.music.spotui.data.preferences.saveLastPosition(this, playback.positionMs)
    }

    private fun returnToWaze() {
        if (isFinishing || isDestroyed) return
        var launched = false

        // 1. דרך PackageManager
        try {
            packageManager.getLaunchIntentForPackage("com.waze")?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(it)
                launched = true
            }
        } catch (_: Exception) {}

        // 2. דרך URI Scheme waze://
        if (!launched) {
            try {
                val uriIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("waze://")).apply {
                    setPackage("com.waze")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(uriIntent)
                launched = true
            } catch (_: Exception) {}
        }

        // 3. Fallback ישיר
        if (!launched) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("waze://")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                })
            } catch (_: Exception) {}
        }

        moveTaskToBack(true)
    }

    override fun onDestroy() {
        returnToWazeRunnable?.let { wazeHandler.removeCallbacks(it) }
        super.onDestroy()
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
