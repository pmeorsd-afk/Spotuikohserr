package com.music.spotui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.music.spotui.R
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.overlay.WazeOverlayView
import com.music.spotui.utils.WazeDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class WazeOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    @Inject
    lateinit var currentSongState: CurrentSongState

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var isViewAdded = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcherJob: Job? = null

    companion object {
        private const val TAG = "WazeOverlayService"
        private const val CHANNEL_ID = "waze_overlay_channel"
        private const val NOTIFICATION_ID = 2048

        fun start(context: Context) {
            val intent = Intent(context, WazeOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WazeOverlayService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        startForegroundNotification()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        startWazeWatcher()
    }

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Waze Music Integration",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running Waze floating music player"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpotUI Waze Player")
            .setContentText("מוכן לשליטה במוזיקה מתוך Waze")
            .setSmallIcon(R.drawable.ic_playing)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startWazeWatcher() {
        watcherJob?.cancel()
        watcherJob = serviceScope.launch {
            while (isActive) {
                try {
                    val isWazeActive = WazeDetector.isWazeInForeground(applicationContext)
                    val hasOverlay = WazeDetector.hasOverlayPermission(applicationContext)

                    if (isWazeActive && hasOverlay) {
                        showOverlay()
                    } else {
                        hideOverlay()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in WazeWatcher", e)
                }
                delay(1200) // Poll every 1.2 seconds
            }
        }
    }

    private fun showOverlay() {
        if (isViewAdded || windowManager == null) return

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@WazeOverlayService)
            setViewTreeSavedStateRegistryOwner(this@WazeOverlayService)
            setContent {
                WazeOverlayView(
                    currentSongState = currentSongState,
                    onCloseService = { hideOverlay() }
                )
            }
        }

        try {
            windowManager?.addView(view, layoutParams)
            overlayView = view
            isViewAdded = true
            Log.d(TAG, "Waze overlay added to window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun hideOverlay() {
        if (!isViewAdded || overlayView == null || windowManager == null) return
        try {
            windowManager?.removeView(overlayView)
            overlayView = null
            isViewAdded = false
            Log.d(TAG, "Waze overlay removed from window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watcherJob?.cancel()
        serviceScope.cancel()
        hideOverlay()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
