package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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
import com.music.spotui.data.preferences.isWazeOverlayEnabled
import com.music.spotui.di.CurrentSongState
import com.music.spotui.ui.overlay.WazeOverlayView
import com.music.spotui.ui.overlay.WazeOverlayPlacement
import com.music.spotui.ui.overlay.WazeOverlayVisibility
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
 * Shows a bounded, movable button over Waze. Observable input/menu/modal windows suppress
 * the overlay without touching playback. Our own accessibility windows are not app navigation.
 */
class WazeScreenAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // ---- detection state ----
    private var evaluationScheduled = false

    // ---- overlay state ----
    private var windowManager: WindowManager? = null
    private var buttonView: View? = null
    private var playerView: ComposeView? = null
    private var isButtonAdded = false
    private var isPlayerVisible = false
    private var isAnimating = false
    private var cachedCurrentSongState: CurrentSongState? = null
    private data class ButtonGeometry(val bounds: WazeOverlayPlacement.Bounds, val size: Int, val landscape: Boolean)
    private var buttonGeometry: ButtonGeometry? = null
    private val positionPrefs by lazy { getSharedPreferences("waze_overlay_position_v2", MODE_PRIVATE) }

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        buttonGeometry = null
        scheduleEvaluation(0)
    }

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
        val decision = try {
            WazeOverlayVisibility.decide(inspectScreen())
        } catch (t: Throwable) {
            Log.w(TAG, "detection failed", t)
            WazeOverlayVisibility.Decision.NOT_WAZE
        }
        WazeScreenMonitor.state = when (decision) {
            WazeOverlayVisibility.Decision.MAP -> WazeScreenState.MAP
            WazeOverlayVisibility.Decision.INPUT_OR_MENU -> WazeScreenState.MENU
            WazeOverlayVisibility.Decision.NOT_WAZE -> WazeScreenState.NOT_WAZE
        }
        WazeScreenMonitor.reason = decision.name
        try { applyVisibility(decision == WazeOverlayVisibility.Decision.MAP) }
        catch (e: Exception) {
            Log.w(TAG, "Overlay window unavailable", e)
            hideImmediately()
        }
    }

    private fun inspectScreen(): WazeOverlayVisibility.Signals {
        val visibleWindows = windows.orEmpty()
        // Focus may belong to the keyboard or our player overlay. Inspect the underlying
        // top APPLICATION window, not a remembered package from an old accessibility event.
        val topApp = visibleWindows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }
        val root = topApp?.root ?: rootInActiveWindow ?: return WazeOverlayVisibility.Signals(false)
        try {
            if (root.packageName?.toString() != WAZE_PACKAGE) return WazeOverlayVisibility.Signals(false)
            val otherActiveWindow = visibleWindows.any { window ->
                window.isActive && window.type != AccessibilityWindowInfo.TYPE_APPLICATION &&
                    window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                    !isOwnOverlay(window)
            }
            if (otherActiveWindow) return WazeOverlayVisibility.Signals(false)
            val keyboard = visibleWindows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            val wazeWindows = visibleWindows.count { window ->
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) false else {
                    val node = window.root
                    try { node?.packageName?.toString() == WAZE_PACKAGE } finally { node?.recycleCompat() }
                }
            }
            var focusedEditor = false
            var dialog = false
            val titles = mutableListOf<String>()
            val pending = java.util.ArrayDeque<AccessibilityNodeInfo>()
            pending.add(AccessibilityNodeInfo.obtain(root))
            var visited = 0
            try {
                while (pending.isNotEmpty() && visited++ < 240) {
                    val node = pending.removeFirst()
                    try {
                        if (!node.isVisibleToUser) continue
                        focusedEditor = focusedEditor || (node.isEditable && node.isFocused)
                        dialog = dialog || node.className?.toString()?.endsWith("Dialog") == true
                        if (Build.VERSION.SDK_INT >= 28) {
                            node.paneTitle?.toString()?.let(titles::add)
                            if (node.isHeading) node.text?.toString()?.let(titles::add)
                        }
                        for (i in 0 until minOf(node.childCount, 240 - visited - pending.size)) {
                            node.getChild(i)?.let(pending::addLast)
                        }
                    } finally { node.recycleCompat() }
                }
            } finally { pending.forEach { it.recycleCompat() } }
            return WazeOverlayVisibility.Signals(true, keyboard, dialog || wazeWindows > 1, focusedEditor, titles)
        } finally { root.recycleCompat() }
    }

    private fun isOwnOverlay(window: AccessibilityWindowInfo): Boolean {
        if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false
        if (window.title?.toString() in setOf("WazeMiniPlayerButton", "WazeMiniPlayerCard")) return true
        val root = window.root
        return try { root?.packageName?.toString() == packageName } finally { root?.recycleCompat() }
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
            else updateButtonGeometry()
        } else {
            if (isButtonAdded || isPlayerVisible) hideImmediately()
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

    private fun geometry(): ButtonGeometry {
        val manager = requireNotNull(windowManager)
        val density = resources.displayMetrics.density
        val safe = if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val inset = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            Rect(inset.left, inset.top, metrics.bounds.width() - inset.right, metrics.bounds.height() - inset.bottom)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION") manager.defaultDisplay.getRealMetrics(metrics)
            fun systemDimen(name: String): Int {
                val id = resources.getIdentifier(name, "dimen", "android")
                return if (id == 0) 0 else resources.getDimensionPixelSize(id)
            }
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val insets = buttonView?.rootWindowInsets
            Rect(insets?.systemWindowInsetLeft ?: 0,
                insets?.systemWindowInsetTop ?: systemDimen("status_bar_height"),
                metrics.widthPixels - (insets?.systemWindowInsetRight ?: if (landscape) systemDimen("navigation_bar_width") else 0),
                metrics.heightPixels - (insets?.systemWindowInsetBottom ?: if (landscape) 0 else systemDimen("navigation_bar_height")))
                .also { rect ->
                    if (Build.VERSION.SDK_INT >= 28) insets?.displayCutout?.let { cutout ->
                        rect.left = maxOf(rect.left, cutout.safeInsetLeft)
                        rect.top = maxOf(rect.top, cutout.safeInsetTop)
                        rect.right = minOf(rect.right, metrics.widthPixels - cutout.safeInsetRight)
                        rect.bottom = minOf(rect.bottom, metrics.heightPixels - cutout.safeInsetBottom)
                    }
                }
        }
        val frame = WazeOverlayPlacement.frame(safe.left, safe.top, safe.right, safe.bottom,
            (54 * density).toInt(), (8 * density).toInt())
        return ButtonGeometry(frame.bounds, frame.size, resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    private fun savedPosition(geometry: ButtonGeometry): WazeOverlayPlacement.Point {
        val key = if (geometry.landscape) "landscape" else "portrait"
        val saved = runCatching { if (positionPrefs.contains("${key}_x") && positionPrefs.contains("${key}_y"))
            WazeOverlayPlacement.Fraction(positionPrefs.getFloat("${key}_x", 1f), positionPrefs.getFloat("${key}_y", 0f)) else null
        }.getOrNull()
        // Fresh/legacy installations start physically right, below the map's upper controls.
        // Old START-relative pixel coordinates are ambiguous under RTL and are not imported.
        return WazeOverlayPlacement.restore(saved, geometry.bounds, (125 * resources.displayMetrics.density).toInt())
    }

    private fun savePosition(params: WindowManager.LayoutParams, geometry: ButtonGeometry) {
        val fraction = WazeOverlayPlacement.save(WazeOverlayPlacement.Point(params.x, params.y), geometry.bounds)
        val key = if (geometry.landscape) "landscape" else "portrait"
        positionPrefs.edit().putFloat("${key}_x", fraction.x).putFloat("${key}_y", fraction.y).apply()
    }

    private fun updateButtonGeometry() {
        val view = buttonView ?: return
        val current = geometry()
        if (current == buttonGeometry) return
        val params = view.layoutParams as WindowManager.LayoutParams
        val point = savedPosition(current)
        params.width = current.size
        params.height = current.size
        params.x = point.x
        params.y = point.y
        windowManager?.updateViewLayout(view, params)
        buttonGeometry = current
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun showButton() {
        if (isButtonAdded || windowManager == null) return

        val density = resources.displayMetrics.density
        val initialGeometry = geometry()
        val buttonSize = initialGeometry.size
        val iconSize = minOf((34 * density).toInt(), buttonSize)

        val buttonParams = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            title = "WazeMiniPlayerButton"
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            val point = savedPosition(initialGeometry)
            x = point.x
            y = point.y
        }

        val buttonFrame = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
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
        var gestureGeometry = initialGeometry
        val dragThreshold = ViewConfiguration.get(this).scaledTouchSlop

        buttonFrame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTouchX = event.rawX
                    startTouchY = event.rawY
                    startParamX = buttonParams.x
                    startParamY = buttonParams.y
                    gestureGeometry = geometry()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (geometry() != gestureGeometry) return@setOnTouchListener true
                    val dx = event.rawX - startTouchX
                    val dy = event.rawY - startTouchY
                    if (abs(dx) > dragThreshold || abs(dy) > dragThreshold) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val point = WazeOverlayPlacement.clamp(WazeOverlayPlacement.Point(
                            (startParamX + dx).toInt(), (startParamY + dy).toInt()), gestureGeometry.bounds)
                        buttonParams.x = point.x
                        buttonParams.y = point.y
                        windowManager?.updateViewLayout(buttonFrame, buttonParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (geometry() != gestureGeometry) return@setOnTouchListener true
                    if (isDragging) {
                        savePosition(buttonParams, gestureGeometry)
                    } else {
                        toggleMiniPlayer()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging && geometry() == gestureGeometry) savePosition(buttonParams, gestureGeometry)
                    isDragging = false
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
            buttonGeometry = initialGeometry
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

    /** Input/menu transitions remove touch interception immediately, without stopping audio. */
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
        buttonGeometry = null
        isAnimating = false
    }

    companion object {
        private const val WAZE_PACKAGE = "com.waze"
        private const val TAG = "WazeScreenService"
        private const val EVAL_THROTTLE_MS = 250L
        private const val SAFETY_INTERVAL_MS = 2000L
        private const val SHOW_ANIM_MS = 150L

        @Volatile
        var instance: WazeScreenAccessibilityService? = null
            private set

        /** Called from MainActivity when returning from the Waze-resume flow. */
        fun collapseMiniPlayer() {
            instance?.hideMiniPlayer()
        }
    }
}
