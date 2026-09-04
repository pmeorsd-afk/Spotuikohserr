package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

/** How WazeScreenAccessibilityService reaches the Hilt-managed CurrentSongState singleton. An
 *  accessibility service is instantiated by the accessibility framework itself rather than
 *  through a bind/start call, so @AndroidEntryPoint's usual code-gen path doesn't apply here -
 *  EntryPointAccessors is Hilt's documented way to fetch a singleton from any context. */
@EntryPoint
@InstallIn(SingletonComponent::class)
private interface WazeCurrentSongStateEntryPoint {
    fun currentSongState(): CurrentSongState
}

/**
 * The only component the Waze integration needs at runtime.
 *
 * It does two jobs that used to be split across two components ([WazeOverlayService], now
 * removed, and this class):
 *  - reads Waze's own window content to tell the map screen apart from a menu / search /
 *    settings screen (unchanged from before);
 *  - draws the floating button and mini player, using TYPE_ACCESSIBILITY_OVERLAY windows added
 *    through this service's own WindowManager.
 *
 * That window type needs no "draw over other apps" permission, and this service alone can also
 * tell whether Waze is in the foreground at all (rootInActiveWindow's package), so the separate
 * usage-stats permission is gone too. An enabled accessibility service is also kept alive far
 * more reliably by the OS - and by aggressive OEM battery managers - than a generic foreground
 * service was, which is the main reason this single permission replaces the previous three.
 *
 * Waze exposes no API for "what screen am I on", so the map/menu split is a heuristic over the
 * accessibility tree. If the button ever shows on the wrong Waze screen (or stays hidden on the
 * map), flip [DEBUG_LOG_TREE] to true, reinstall, run `adb logcat -s WazeScreen` while that
 * screen is open, and add whatever text/description/id identifies it to [MAP_LANDMARKS] or
 * [MENU_MARKERS] below.
 */
class WazeScreenAccessibilityService : AccessibilityService(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // ---- detection state ----
    private var lastWazeActivity: String? = null
    private var lastTreeLogAt = 0L
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

    /** Safety net in case Waze stops emitting events (static screen, missed event...). */
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
                val cls = event.className?.toString()
                if (pkg == WAZE_PACKAGE && cls != null && isActivityClass(pkg, cls)) {
                    lastWazeActivity = cls
                }
                scheduleEvaluation(0)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scheduleEvaluation(0)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Waze streams content changes while the map moves; throttle instead of
                // evaluating on every single one.
                if (event.packageName?.toString() == WAZE_PACKAGE) scheduleEvaluation(EVAL_THROTTLE_MS)
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
        val result = try {
            detect()
        } catch (t: Throwable) {
            Log.w(TAG, "detection failed", t)
            Detection(WazeScreenState.NOT_WAZE, "error: ${t.message}")
        }
        WazeScreenMonitor.state = result.state
        WazeScreenMonitor.reason = result.reason
        applyVisibility(result.state)
    }

    private data class Detection(val state: WazeScreenState, val reason: String)

    private fun detect(): Detection {
        val root = rootInActiveWindow
        val foreground = root?.packageName?.toString()
        if (root == null || foreground != WAZE_PACKAGE) {
            return Detection(WazeScreenState.NOT_WAZE, "foreground: ${foreground ?: "unknown"}")
        }

        var keyboardVisible = false
        var wazeAppWindows = 0
        for (window in windows ?: emptyList()) {
            when (window.type) {
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> keyboardVisible = true
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val windowRoot = window.root ?: continue
                    if (windowRoot.packageName?.toString() == WAZE_PACKAGE) wazeAppWindows++
                    windowRoot.recycleCompat()
                }
                else -> Unit
            }
        }
        if (keyboardVisible) return menu("keyboard is open")
        if (wazeAppWindows > 1) return menu("dialog / popup window ($wazeAppWindows windows)")

        lastWazeActivity?.let { cls ->
            val hint = NON_MAP_ACTIVITY_HINTS.firstOrNull { cls.contains(it, ignoreCase = true) }
            if (hint != null) return menu("activity: ${cls.substringAfterLast('.')}")
        }

        val scan = scanTree(root)
        scan.focusedInput?.let { return menu("text input focused: $it") }
        scan.menuMarker?.let { return menu("menu marker: \"$it\"") }
        scan.landmark?.let { return map("map landmark: \"$it\"") }

        // Nothing conclusive: strict by design - never show the button on an unrecognised
        // Waze screen.
        return menu("no map landmark found")
    }

    private fun menu(reason: String) = Detection(WazeScreenState.MENU, reason)
    private fun map(reason: String) = Detection(WazeScreenState.MAP, reason)

    private class ScanResult {
        var focusedInput: String? = null
        var menuMarker: String? = null
        var landmark: String? = null
    }

    /** Breadth-first walk over Waze's window, capped at [MAX_NODES] so it stays cheap. */
    private fun scanTree(root: AccessibilityNodeInfo): ScanResult {
        val result = ScanResult()

        val now = SystemClock.uptimeMillis()
        val log = DEBUG_LOG_TREE && now - lastTreeLogAt > TREE_LOG_INTERVAL_MS
        if (log) {
            lastTreeLogAt = now
            Log.d(TAG_TREE, "---- Waze window tree (activity=$lastWazeActivity) ----")
        }

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.addLast(root to 0)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            val cls = node.className?.toString().orEmpty()
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val id = node.viewIdResourceName
            val visible = node.isVisibleToUser

            if (log) {
                Log.d(
                    TAG_TREE,
                    "  ".repeat(depth) + cls.substringAfterLast('.') +
                        (id?.let { " id=${it.substringAfter('/')}" } ?: "") +
                        (text?.takeIf { it.isNotEmpty() }?.let { " text=\"$it\"" } ?: "") +
                        (desc?.takeIf { it.isNotEmpty() }?.let { " desc=\"$it\"" } ?: "") +
                        (if (visible) "" else " [hidden]") +
                        (if (node.isFocused) " [focused]" else "")
                )
            }

            if (visible) {
                if (result.focusedInput == null && node.isFocused && (node.isEditable || cls.endsWith("EditText"))) {
                    result.focusedInput = id?.substringAfter('/') ?: cls.substringAfterLast('.')
                }
                if (result.menuMarker == null) {
                    result.menuMarker = matchText(text, desc, MENU_MARKERS)
                }
                if (result.landmark == null) {
                    result.landmark = matchText(text, desc, MAP_LANDMARKS) ?: matchId(id, MAP_LANDMARKS)
                }
            }

            // A decisive "hide" signal was found and we're not logging: stop early.
            if (!log && (result.focusedInput != null || result.menuMarker != null)) break

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.addLast(it to depth + 1) }
            }
            if (node !== root) node.recycleCompat()
        }
        return result
    }

    /** Exact (case-insensitive) match of text or contentDescription against the list. */
    private fun matchText(text: String?, desc: String?, patterns: List<String>): String? {
        for (candidate in arrayOf(text, desc)) {
            if (candidate.isNullOrBlank()) continue
            patterns.firstOrNull { candidate.equals(it, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /** Substring match of the view id ("com.waze:id/report_button" -> "report_button"). */
    private fun matchId(id: String?, patterns: List<String>): String? {
        if (id.isNullOrEmpty()) return null
        val local = id.substringAfter('/')
        return patterns.firstOrNull { it.length >= 4 && local.contains(it, ignoreCase = true) }
    }

    @Suppress("DEPRECATION")
    private fun isActivityClass(pkg: String, cls: String): Boolean = try {
        packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun AccessibilityNodeInfo.recycleCompat() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) recycle()
    }

    // ==================================================================================
    // Show / hide
    // ==================================================================================

    private fun applyVisibility(state: WazeScreenState) {
        val enabledInSettings = isWazeOverlayEnabled(applicationContext)
        val shouldShow = enabledInSettings && state == WazeScreenState.MAP
        if (shouldShow) {
            if (!isButtonAdded) showButton()
        } else {
            // Left Waze entirely -> always close, even if the mini player is open. Still
            // inside Waze but on a menu screen -> keep an open mini player as-is so a brief
            // detection flicker doesn't yank it away mid-interaction.
            val leftWazeEntirely = state == WazeScreenState.NOT_WAZE
            if (isButtonAdded && (leftWazeEntirely || !isPlayerVisible)) {
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

        // A previous hide's fade-out animation might still be running (fast menu<->map
        // flicker) - finish it immediately so there is never more than one button at once.
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

        // Start fully transparent and slightly smaller, then animate in.
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
        private const val TAG_TREE = "WazeScreen"
        private const val MAX_NODES = 600
        private const val TREE_LOG_INTERVAL_MS = 2000L
        private const val EVAL_THROTTLE_MS = 250L
        private const val SAFETY_INTERVAL_MS = 2000L
        private const val DRAG_THRESHOLD = 8f
        private const val SHOW_ANIM_MS = 150L
        private const val HIDE_ANIM_MS = 120L

        /**
         * Off by default. Flip to true, reinstall, and run `adb logcat -s WazeScreen` on a test
         * device to see every visible node on the current Waze screen while calibrating - then
         * flip back to false. Left on, it writes anything visible in Waze (addresses,
         * searches...) to Logcat.
         */
        private const val DEBUG_LOG_TREE = false

        /** Fragments of Waze activity class names that are clearly not the map. */
        private val NON_MAP_ACTIVITY_HINTS = listOf(
            "Settings", "Search", "Login", "Onboard", "Carpool", "Profile",
            "Share", "Planned", "Address", "Favorite", "History", "Web",
        )

        /** Texts / descriptions / view-id fragments that only exist on Waze's map screen. */
        private val MAP_LANDMARKS = listOf(
            "Report", "Where to?", "My Waze", "Recenter",
            "דיווח", "לאן נוסעים?", "לאן?", "הווייז שלי",
        )

        /** Texts / descriptions that only exist on menu-like Waze screens. */
        private val MENU_MARKERS = listOf(
            "Back", "Navigate up", "Settings", "חזרה", "חזור", "הגדרות",
        )

        @Volatile
        var instance: WazeScreenAccessibilityService? = null
            private set

        /** Called from MainActivity when returning from the Waze-resume flow. */
        fun collapseMiniPlayer() {
            instance?.hideMiniPlayer()
        }
    }
}
