package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.music.spotui.R
import com.music.spotui.data.preferences.getWazeButtonX
import com.music.spotui.data.preferences.getWazeButtonY
import com.music.spotui.data.preferences.isWazeOverlayEnabled
import com.music.spotui.data.preferences.setWazeButtonPosition
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.overlay.WazeOverlayView
import com.music.spotui.utils.WazeScreenMonitor
import com.music.spotui.utils.WazeScreenState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** How WazeScreenAccessibilityService reaches the Hilt-managed CurrentSongState singleton. */
@EntryPoint
@InstallIn(SingletonComponent::class)
private interface WazeCurrentSongStateEntryPoint {
    fun currentSongState(): CurrentSongState
}

/**
 * Accessibility service for Waze integration.
 * Shows the floating Spotify button and mini player overlay exclusively when Waze is in the foreground,
 * without any hiding restrictions within Waze screens.
 */
class WazeScreenAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // ---- detection state ----
    private var currentForegroundPackage: String? = null
    private var evaluationScheduled = false

    // ---- overlay state ----
    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    /** View mid fade-out before removal - see hideOverlay(). */
    private var fadingOutButtonView: View? = null
    private var playerView: ComposeView? = null
    private var isButtonAdded = false
    private var isPlayerVisible = false
    private var isAnimating = false
    private var cachedCurrentSongState: CurrentSongState? = null

    private val evaluationRunnable = Runnable {
        evaluationScheduled = false
        evaluate()
    }

    /** Safety net in case of missed window events. */
    private val safetyTick = object : Runnable {
        override fun run() {
            scheduleEvaluation(0)
            handler.postDelayed(this, SAFETY_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        windowManager = getSystemService(WindowManager::class.java)
        handler.removeCallbacks(safetyTick)
        handler.postDelayed(safetyTick, SAFETY_INTERVAL_MS)
        scheduleEvaluation(0)
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (!pkg.isNullOrBlank()) {
                    currentForegroundPackage = pkg
                }
                scheduleEvaluation(0)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleEvaluation(0)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (event.packageName?.toString() == WAZE_PACKAGE) {
                    scheduleEvaluation(EVAL_THROTTLE_MS)
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        handler.removeCallbacksAndMessages(null)
        hideImmediately()
        serviceScope.cancel()
        WazeScreenMonitor.state = WazeScreenState.NOT_WAZE
        WazeScreenMonitor.reason = "accessibility service stopped"
        if (instance === this) instance = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }

    // ==================================================================================
    // Detection
    // ==================================================================================

    private fun scheduleEvaluation(delayMs: Long) {
        if (evaluationScheduled && delayMs > 0) return
        handler.removeCallbacks(evaluationRunnable)
        evaluationScheduled = true
        handler.postDelayed(evaluationRunnable, delayMs)
    }

    private fun evaluate() {
        val isWaze = try {
            isWazeInForeground()
        } catch (t: Throwable) {
            Log.w(TAG, "detection failed", t)
            false
        }
        val state = if (isWaze) WazeScreenState.MAP else WazeScreenState.NOT_WAZE
        WazeScreenMonitor.state = state
        WazeScreenMonitor.reason = if (isWaze) "Waze is foreground" else "Not in Waze"
        applyVisibility(isWaze)
    }

    private fun isWazeInForeground(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        }
        val rootPkg = root?.packageName?.toString()
        root?.recycleCompat()

        if (rootPkg == WAZE_PACKAGE) return true

        // If rootInActiveWindow belongs to another package (e.g. launcher, spotui), it's definitely not Waze
        if (!rootPkg.isNullOrBlank() && rootPkg != WAZE_PACKAGE) {
            return false
        }

        // Check active application windows in case rootInActiveWindow was null
        try {
            val appWindows = windows
            if (appWindows != null) {
                for (window in appWindows) {
                    if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        val windowRoot = window.root
                        val pkg = windowRoot?.packageName?.toString()
                        windowRoot?.recycleCompat()
                        if (pkg == WAZE_PACKAGE && (window.isActive || window.isFocused)) {
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // Fallback to last recorded event package if still active
        return currentForegroundPackage == WAZE_PACKAGE
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    // ==================================================================================
    // Show / hide
    // ==================================================================================

    private fun applyVisibility(isWaze: Boolean) {
        val enabledInSettings = isWazeOverlayEnabled(applicationContext)
        val shouldShow = enabledInSettings && isWaze
        if (shouldShow) {
            if (!isButtonAdded) showButton()
        } else {
            if (isButtonAdded) {
                hideOverlay()
            }
        }
    }

    private fun currentSongState(): CurrentSongState {
        cachedCurrentSongState?.let { return it }
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            WazeCurrentSongStateEntryPoint::class.java,
        )
        return entryPoint.currentSongState().also { cachedCurrentSongState = it }
    }

    private fun getStatusBarHeightPx(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.currentWindowMetrics
            val insets = metrics?.windowInsets?.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            if (insets != null && insets.top > 0) return insets.top
        }
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (isButtonAdded || windowManager == null) return

        fadingOutButtonView?.let { old ->
            old.animate().cancel()
            try {
                windowManager?.removeView(old)
            } catch (_: Exception) {
            }
            fadingOutButtonView = null
        }

        val density = resources.displayMetrics.density
        val buttonSize = (54 * density).toInt()
        val iconSize = (34 * density).toInt()

        val buttonParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WazeMiniPlayerButton"
            val statusBarH = getStatusBarHeightPx()
            val savedX = getWazeButtonX(applicationContext, -1)
            val savedY = getWazeButtonY(applicationContext, -1)
            if (savedX != -1 && savedY != -1) {
                x = savedX
                y = savedY
            } else {
                x = (14 * density).toInt()
                y = statusBarH + (125 * density).toInt()
            }
        }

        val buttonFrame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
            }
            elevation = 6f * density
        }

        val iconView = ImageView(this).apply {
            setImageResource(R.drawable.ic_spotify_waze)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val iconLp = FrameLayout.LayoutParams(iconSize, iconSize).apply {
            gravity = Gravity.CENTER
        }
        buttonFrame.addView(iconView, iconLp)

        var startTouchX = 0f
        var startTouchY = 0f
        var startParamX = 0
        var startParamY = 0
        var isDragging = false

        buttonFrame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startParamX = buttonParams.x
                    startParamY = buttonParams.y
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (abs(dx) > DRAG_THRESHOLD || abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true
                    }
                    if (isDragging) {
                        buttonParams.x = (startParamX + dx).toInt()
                        buttonParams.y = (startParamY + dy).toInt()
                        windowManager?.updateViewLayout(buttonFrame, buttonParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        setWazeButtonPosition(applicationContext, buttonParams.x, buttonParams.y)
                    } else {
                        toggleMiniPlayer()
                    }
                    true
                }
                else -> false
            }
        }

        buttonFrame.alpha = 0f
        buttonFrame.scaleX = 0.8f
        buttonFrame.scaleY = 0.8f

        try {
            windowManager?.addView(buttonFrame, buttonParams)
            buttonView = buttonFrame
            isButtonAdded = true
            buttonFrame.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(SHOW_ANIM_MS)
                .setInterpolator(DecelerateInterpolator())
                .start()
            Log.d(TAG, "Waze floating button added.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add button view", e)
        }
    }

    private fun toggleMiniPlayer() {
        if (isAnimating) return
        if (isPlayerVisible) hideMiniPlayer() else showMiniPlayer()
    }

    private fun showMiniPlayer() {
        if (isPlayerVisible || windowManager == null) return
        isAnimating = true

        val playerParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "WazeMiniPlayerCard"
            x = 0
            y = 0
        }

        val pView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@WazeScreenAccessibilityService)
            setViewTreeSavedStateRegistryOwner(this@WazeScreenAccessibilityService)
            setContent {
                WazeOverlayView(
                    currentSongState = currentSongState(),
                    isExpanded = true,
                    onExpandChanged = { expanded ->
                        if (!expanded) hideMiniPlayer()
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
        val view = buttonView ?: return
        if (!isButtonAdded || windowManager == null) return

        buttonView = null
        isButtonAdded = false
        fadingOutButtonView = view

        view.animate()
            .alpha(0f)
            .scaleX(0.8f)
            .scaleY(0.8f)
            .setDuration(HIDE_ANIM_MS)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (fadingOutButtonView === view) fadingOutButtonView = null
                try {
                    windowManager?.removeView(view)
                    Log.d(TAG, "Waze button removed from window")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove button view", e)
                }
            }
            .start()
    }

    /** No-animation teardown for when the service itself is being destroyed/unbound. */
    private fun hideImmediately() {
        if (isPlayerVisible) {
            try {
                windowManager?.removeView(playerView)
            } catch (_: Exception) {
            }
            playerView = null
            isPlayerVisible = false
        }
        buttonView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        buttonView = null
        isButtonAdded = false
        fadingOutButtonView?.let { old ->
            try {
                windowManager?.removeView(old)
            } catch (_: Exception) {
            }
        }
        fadingOutButtonView = null
    }

    companion object {
        private const val WAZE_PACKAGE = "com.waze"
        private const val TAG = "WazeScreenService"
        private const val EVAL_THROTTLE_MS = 250L
        private const val SAFETY_INTERVAL_MS = 2000L
        private const val DRAG_THRESHOLD = 8f
        private const val SHOW_ANIM_MS = 150L
        private const val HIDE_ANIM_MS = 120L

        @Volatile
        var instance: WazeScreenAccessibilityService? = null
            private set

        /** Called from MainActivity when returning from the Waze-resume flow. */
        fun collapseMiniPlayer() {
            instance?.hideMiniPlayer()
        }
    }
}
