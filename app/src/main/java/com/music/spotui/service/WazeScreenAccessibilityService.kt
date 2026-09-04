package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.music.spotui.utils.WazeScreenMonitor
import com.music.spotui.utils.WazeScreenState

/**
 * Reads Waze's own window content to tell its map screen apart from a menu / search /
 * settings screen, and publishes the result to [WazeScreenMonitor].
 *
 * This service draws nothing and does not touch the floating button, its logo, or the mini
 * player - [WazeOverlayService] still owns all of that untouched. It only adds one extra
 * condition to [WazeOverlayService]'s existing show/hide check: show the button only while
 * [WazeScreenMonitor.state] is MAP.
 *
 * [WazeScreenMonitor.state] defaults to MAP (and falls back to MAP if this service is ever
 * turned off), so until the user enables it in Settings > Accessibility, behaviour is exactly
 * what it was before: the button follows Waze being in the foreground only. Enabling this
 * service narrows that further to the map screen specifically.
 *
 * Waze exposes no API for "what screen am I on", so this is a heuristic over the accessibility
 * tree. If the button ever shows on the wrong Waze screen (or stays hidden on the map), flip
 * [DEBUG_LOG_TREE] to true, reinstall, run `adb logcat -s WazeScreen` while that screen is
 * open, and add whatever text/description/id identifies it to [MAP_LANDMARKS] or
 * [MENU_MARKERS] below.
 */
class WazeScreenAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastWazeActivity: String? = null
    private var lastTreeLogAt = 0L
    private var evaluationScheduled = false

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
        resetToFallback("service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        resetToFallback("service destroyed")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun resetToFallback(reason: String) {
        WazeScreenMonitor.state = WazeScreenState.MAP
        WazeScreenMonitor.reason = reason
    }

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
            // Fail open: a bug here must not permanently hide a button that used to work.
            Detection(WazeScreenState.MAP, "error: ${t.message}")
        }
        WazeScreenMonitor.state = result.state
        WazeScreenMonitor.reason = result.reason
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

        // Nothing conclusive: strict by design, same as before - never show the button on an
        // unrecognised Waze screen.
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

    companion object {
        private const val WAZE_PACKAGE = "com.waze"
        private const val TAG = "WazeScreenService"
        private const val TAG_TREE = "WazeScreen"
        private const val MAX_NODES = 600
        private const val TREE_LOG_INTERVAL_MS = 2000L
        private const val EVAL_THROTTLE_MS = 250L
        private const val SAFETY_INTERVAL_MS = 2000L

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
    }
}
