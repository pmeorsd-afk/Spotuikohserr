package com.music.spotui.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
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
import com.music.spotui.data.preferences.*
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.overlay.WazeOverlayView
import com.music.spotui.utils.WazeDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class WazeOverlayService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    @Inject
    lateinit var currentSongState: CurrentSongState

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    private var playerView: ComposeView? = null

    private var isButtonAdded = false
    private var isPlayerVisible = false
    private var isAnimating = false
    private var isMenuOrKeyboardOpen = false

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcherJob: Job? = null

    companion object {
        private const val TAG = "WazeOverlayService"
        private const val CHANNEL_ID = "waze_overlay_channel"
        private const val NOTIFICATION_ID = 2048
        private const val DRAG_THRESHOLD = 8f

        @Volatile
        private var currentInstance: WazeOverlayService? = null

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

        fun setMenuState(isMenuOpen: Boolean) {
            currentInstance?.updateMenuVisibility(isMenuOpen)
        }
    }

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
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
                    val hasUsage = WazeDetector.hasUsageStatsPermission(applicationContext)
                    val isWazeActive = WazeDetector.isWazeInForeground(applicationContext)

                    if (enabledInSettings && hasOverlay && hasUsage && isWazeActive) {
                        if (!isButtonAdded) {
                            showOverlay()
                        }
                    } else {
                        if (isButtonAdded && !isPlayerVisible) {
                            hideOverlay()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in WazeWatcher", e)
                }
                delay(800)
            }
        }
    }

    fun updateMenuVisibility(isMenuOpen: Boolean) {
        if (isMenuOrKeyboardOpen == isMenuOpen) return
        isMenuOrKeyboardOpen = isMenuOpen

        serviceScope.launch(Dispatchers.Main) {
            val view = buttonView ?: return@launch
            if (isMenuOpen) {
                view.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction {
                        if (isMenuOrKeyboardOpen) {
                            view.visibility = View.GONE
                        }
                    }
                    .start()
            } else {
                view.visibility = View.VISIBLE
                view.animate()
                    .alpha(1f)
                    .setDuration(220)
                    .start()
            }
        }
    }

    private fun getStatusBarHeightPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val insets = wm?.currentWindowMetrics?.windowInsets
            val statusInsets = insets?.getInsets(WindowInsets.Type.statusBars())
            if (statusInsets != null && statusInsets.top > 0) return statusInsets.top
        }
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resId > 0) return resources.getDimensionPixelSize(resId)
        val density = resources.displayMetrics.density
        return (24 * density).toInt()
    }

    private fun dpToPx(dp: Float): Float = dp * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    private fun showOverlay() {
        if (isButtonAdded || windowManager == null) return

        val density = resources.displayMetrics.density
        val buttonSize = (54 * density).toInt()
        val iconSize = (34 * density).toInt()
        val statusBarH = getStatusBarHeightPx()

        val savedX = getWazeButtonX(this, -1)
        val savedY = getWazeButtonY(this, -1)

        val posX = if (savedX >= 0) savedX else dpToPx(10f).toInt()
        val posY = if (savedY >= 0) savedY else statusBarH + dpToPx(144f).toInt()

        val buttonParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = posX
            y = posY
        }

        // Native 54dp Circular Spotify Button View (instant hardware touch response)
        val buttonFrame = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(buttonSize, buttonSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(android.graphics.Color.WHITE)
            }
            elevation = dpToPx(6f)
            if (isMenuOrKeyboardOpen) {
                alpha = 0f
                visibility = View.GONE
            }
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_spotify_waze)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val lp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
        }
        buttonFrame.addView(iconView)

        // Setup Drag & Tap from Technical Guide
        var startX = 0f
        var startY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        buttonFrame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startParamX = buttonParams.x
                    startParamY = buttonParams.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        // gravity = TOP | END -> x הוא מהקצה הימני, y מלמעלה
                        buttonParams.x = (startParamX - dx).toInt()
                        buttonParams.y = (startParamY + dy).toInt()
                        try {
                            windowManager?.updateViewLayout(buttonFrame, buttonParams)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        setWazeButtonPosition(this, buttonParams.x, buttonParams.y)
                        Log.d(TAG, "Saved position: x=${buttonParams.x}, y=${buttonParams.y}")
                    } else {
                        toggleMiniPlayer()
                    }
                    true
                }
                else -> false
            }
        }

        // Global Layout / Soft Keyboard Listener (Instant auto-hide on keyboard/search)
        val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            val r = Rect()
            buttonFrame.getWindowVisibleDisplayFrame(r)
            val screenHeight = buttonFrame.rootView.height
            if (screenHeight > 0) {
                val keypadHeight = screenHeight - r.bottom
                val isKeyboardShowing = keypadHeight > screenHeight * 0.15
                if (isKeyboardShowing) {
                    updateMenuVisibility(true)
                }
            }
        }
        buttonFrame.viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener)

        try {
            windowManager?.addView(buttonFrame, buttonParams)
            buttonView = buttonFrame
            isButtonAdded = true
            Log.d(TAG, "Waze 54dp floating button added.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add button view", e)
        }
    }

    private fun toggleMiniPlayer() {
        if (isAnimating) return
        if (isPlayerVisible) {
            hideMiniPlayer()
        } else {
            showMiniPlayer()
        }
    }

    private fun showMiniPlayer() {
        if (isPlayerVisible || windowManager == null) return
        isAnimating = true

        val playerParams = WindowManager.LayoutParams(
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

        val pView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@WazeOverlayService)
            setViewTreeSavedStateRegistryOwner(this@WazeOverlayService)
            setContent {
                WazeOverlayView(
                    currentSongState = currentSongState,
                    isExpanded = true,
                    onExpandChanged = { expanded ->
                        if (!expanded) {
                            hideMiniPlayer()
                        }
                    }
                )
            }
        }

        try {
            if (playerView == null) {
                windowManager?.addView(pView, playerParams)
                playerView = pView
            }
            isPlayerVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show mini player", e)
        } finally {
            serviceScope.launch {
                delay(380)
                isAnimating = false
            }
        }
    }

    private fun hideMiniPlayer() {
        if (!isPlayerVisible || playerView == null || windowManager == null) return
        isAnimating = true

        try {
            windowManager?.removeView(playerView)
            playerView = null
            isPlayerVisible = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hide mini player", e)
        } finally {
            serviceScope.launch {
                delay(320)
                isAnimating = false
            }
        }
    }

    private fun hideOverlay() {
        hideMiniPlayer()
        if (!isButtonAdded || buttonView == null || windowManager == null) return
        try {
            windowManager?.removeView(buttonView)
            buttonView = null
            isButtonAdded = false
            Log.d(TAG, "Waze button removed from window")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove button view", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (currentInstance == this) {
            currentInstance = null
        }
        watcherJob?.cancel()
        serviceScope.cancel()
        hideOverlay()

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
