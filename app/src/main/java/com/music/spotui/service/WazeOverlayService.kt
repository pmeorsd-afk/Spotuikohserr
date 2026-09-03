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
import com.music.spotui.data.preferences.isWazeOverlayEnabled
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
    private var isExpandedState = false
    private var currentLayoutParams: WindowManager.LayoutParams? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcherJob: Job? = null

    companion object {
        private const val TAG = "WazeOverlayService"
        private const val CHANNEL_ID = "waze_overlay_channel"
        private const val NOTIFICATION_ID = 2048

        @Volatile
        private var dynamicX: Int? = null
        @Volatile
        private var dynamicY: Int? = null
        @Volatile
        private var dynamicWidth: Int? = null
        @Volatile
        private var dynamicHeight: Int? = null

        @Volatile
        private var activeServiceInstance: WazeOverlayService? = null

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

        fun updateDynamicPosition(x: Int, y: Int, width: Int, height: Int) {
            dynamicX = x
            dynamicY = y
            dynamicWidth = width
            dynamicHeight = height

            activeServiceInstance?.applyDynamicBounds(x, y, width, height)
        }

        fun onWazeActiveWithoutBounds() {
            activeServiceInstance?.applyDefaultBoundsIfShowing()
        }

        fun onWazeLeft() {
            activeServiceInstance?.hideOverlay()
        }
    }

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
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
                    val enabledInSettings = isWazeOverlayEnabled(applicationContext)
                    val hasOverlay = WazeDetector.hasOverlayPermission(applicationContext)
                    val isAccessibility = WazeAccessibilityService.isAccessibilityServiceEnabled(applicationContext)
                    val isWazeActive = WazeDetector.isWazeInForeground(applicationContext) ||
                        (isAccessibility && WazeAccessibilityService.instance != null)

                    if (enabledInSettings && hasOverlay && isWazeActive) {
                        if (!isViewAdded) {
                            showOverlay()
                        }
                    } else if (!isWazeActive) {
                        hideOverlay()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in WazeWatcher", e)
                }
                delay(1200)
            }
        }
    }

    private fun getLayoutParams(expanded: Boolean): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        return if (expanded) {
            WindowManager.LayoutParams(
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
                x = 0
                y = 0
            }
        } else {
            val curX = dynamicX
            val curY = dynamicY
            val curW = dynamicWidth
            val curH = dynamicHeight

            if (curX != null && curY != null && curW != null && curH != null && curW > 0 && curH > 0) {
                // Exact real-time measured bounds from AccessibilityService!
                WindowManager.LayoutParams(
                    curW,
                    curH,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = curX
                    y = curY
                }
            } else {
                // Fallback geometry
                val sizePx = (48 * density).toInt()
                val defaultMarginX = (14 * density).toInt()
                val defaultMarginY = (140 * density).toInt()

                WindowManager.LayoutParams(
                    sizePx,
                    sizePx,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    },
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.RIGHT
                    x = defaultMarginX
                    y = defaultMarginY
                }
            }
        }
    }

    fun applyDynamicBounds(x: Int, y: Int, width: Int, height: Int) {
        if (!isWazeOverlayEnabled(this) || !WazeDetector.hasOverlayPermission(this)) return

        if (!isViewAdded) {
            showOverlay()
            return
        }

        if (isExpandedState || overlayView == null || windowManager == null) return

        val lp = getLayoutParams(false)
        currentLayoutParams = lp
        try {
            windowManager?.updateViewLayout(overlayView, lp)
            Log.d(TAG, "Dynamic bounds applied: x=$x, y=$y, w=$width, h=$height")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating dynamic layout bounds", e)
        }
    }

    fun applyDefaultBoundsIfShowing() {
        if (!isViewAdded && isWazeOverlayEnabled(this) && WazeDetector.hasOverlayPermission(this)) {
            showOverlay()
        }
    }

    private fun showOverlay() {
        if (isViewAdded || windowManager == null) return

        isExpandedState = false
        val layoutParams = getLayoutParams(false)
        currentLayoutParams = layoutParams

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@WazeOverlayService)
            setViewTreeSavedStateRegistryOwner(this@WazeOverlayService)
            setContent {
                WazeOverlayView(
                    currentSongState = currentSongState,
                    onExpandChanged = { expanded ->
                        setExpanded(expanded)
                    }
                )
            }
        }

        try {
            windowManager?.addView(view, layoutParams)
            overlayView = view
            isViewAdded = true
            Log.d(TAG, "Waze overlay added to window with dynamic layout")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
        }
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpandedState == expanded || !isViewAdded || overlayView == null || windowManager == null) return
        isExpandedState = expanded
        try {
            val lp = getLayoutParams(expanded)
            currentLayoutParams = lp
            windowManager?.updateViewLayout(overlayView, lp)
            Log.d(TAG, "Overlay layout updated to expanded=$expanded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update layout params", e)
        }
    }

    fun hideOverlay() {
        if (!isViewAdded || overlayView == null || windowManager == null) return
        try {
            windowManager?.removeView(overlayView)
            overlayView = null
            isViewAdded = false
            isExpandedState = false
            currentLayoutParams = null
            Log.d(TAG, "Waze overlay removed from window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove overlay view", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeServiceInstance = null
        watcherJob?.cancel()
        serviceScope.cancel()
        hideOverlay()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
