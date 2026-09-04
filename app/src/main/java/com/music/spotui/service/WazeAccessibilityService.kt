package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WazeAccessibility"
        private const val WAZE_PACKAGE = "com.waze"
        private const val HIDE_DEBOUNCE_MS = 200L

        // Primary and fallback view IDs that only exist on Waze's main map screen
        private val MAP_ANCHOR_IDS = listOf(
            "com.waze:id/mute_button",
            "com.waze:id/sound_button",
            "com.waze:id/audio_button",
            "com.waze:id/audio_player_button",
            "com.waze:id/floating_sound_button",
            "com.waze:id/spotify_button",
            "com.waze:id/audio_kit_button",
            "com.waze:id/speedometer",
            "com.waze:id/speedometer_view",
            "com.waze:id/main_map_view",
            "com.waze:id/map_view"
        )

        private val MAP_ANCHOR_DESCRIPTIONS = listOf(
            "צליל", "רמקול", "השתק", "השתקת צליל", "הפעלת צליל",
            "sound", "mute", "speaker", "audio", "unmute"
        )

        var instance: WazeAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pendingHide: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "WazeAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val pkg = event.packageName?.toString()
        if (pkg != WAZE_PACKAGE) {
            scheduleMenuState(open = true)
            return
        }

        val root = rootInActiveWindow ?: run {
            scheduleMenuState(open = true)
            return
        }

        val isMapScreen = isMainMapScreen(root)
        try {
            root.recycle()
        } catch (_: Exception) {}

        if (isMapScreen) {
            cancelPendingHide()
            // הכפתור נמצא => מסך המפה הראשי => מציגים
            WazeOverlayService.setMenuState(false)
        } else {
            // הכפתור לא נמצא => תפריט/חלון אחר מכסה => מסתירים (עם דיליי קטן למניעת הבהוב)
            scheduleMenuState(open = true)
        }
    }

    private fun isMainMapScreen(root: AccessibilityNodeInfo): Boolean {
        // 1. בדיקה לפי IDs של כפתור הרמקול/מפה
        for (id in MAP_ANCHOR_IDS) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    try {
                        n.recycle()
                    } catch (_: Exception) {}
                }
                return true
            }
        }

        // 2. בדיקה לפי Content Description
        for (desc in MAP_ANCHOR_DESCRIPTIONS) {
            val nodes = root.findAccessibilityNodeInfosByText(desc)
            if (!nodes.isNullOrEmpty()) {
                for (n in nodes) {
                    try {
                        n.recycle()
                    } catch (_: Exception) {}
                }
                return true
            }
        }

        return false
    }

    private fun scheduleMenuState(open: Boolean) {
        cancelPendingHide()
        pendingHide = Runnable {
            WazeOverlayService.setMenuState(open)
        }.also {
            handler.postDelayed(it, HIDE_DEBOUNCE_MS)
        }
    }

    private fun cancelPendingHide() {
        pendingHide?.let { handler.removeCallbacks(it) }
        pendingHide = null
    }

    override fun onInterrupt() {
        Log.d(TAG, "WazeAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        cancelPendingHide()
        WazeOverlayService.setMenuState(false)
        Log.d(TAG, "WazeAccessibilityService destroyed.")
    }
}
