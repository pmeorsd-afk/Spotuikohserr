package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WazeAccessibility"
        var instance: WazeAccessibilityService? = null
            private set

        private val MENU_KEYWORDS = listOf(
            // Hebrew
            "חיפוש", "הגדרות", "דיווח", "מסלולים", "מועדפים", "היסטוריה",
            "לאן נוסעים", "תכנון נסיעה", "החשבון שלי", "קולות והנחיות",
            "מפה ותצוגה", "חניה", "דלק", "מצלמות", "מכשול", "משטרה",
            "תנועה", "תאונה", "סכנה", "מזג אוויר", "נתיב חסום", "איפה לחנות",
            // English
            "search", "settings", "reports", "routes", "favorites", "history",
            "where to", "plan a drive", "account", "voice & sound",
            "map display", "parking", "gas", "police", "hazard", "traffic",
            "accident", "closure", "cameras"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "WazeAccessibilityService connected.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.waze") return

        val rootNode = rootInActiveWindow ?: return
        try {
            val isMenuOpen = checkIsMenuOrDrawerOpen(rootNode)
            WazeOverlayService.setMenuState(isMenuOpen)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Waze menu state", e)
        } finally {
            try {
                rootNode.recycle()
            } catch (_: Exception) {}
        }
    }

    private fun checkIsMenuOrDrawerOpen(root: AccessibilityNodeInfo): Boolean {
        // 1. Check for active text inputs (Search Bar active)
        if (hasSearchInput(root)) {
            return true
        }

        // 2. Check for menu / drawer / settings / report keywords
        for (keyword in MENU_KEYWORDS) {
            val nodes = root.findAccessibilityNodeInfosByText(keyword)
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

    private fun hasSearchInput(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString() ?: ""
        if (className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true)
        ) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val hasInput = hasSearchInput(child)
            try {
                child.recycle()
            } catch (_: Exception) {}
            if (hasInput) return true
        }
        return false
    }

    override fun onInterrupt() {
        Log.d(TAG, "WazeAccessibilityService interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        WazeOverlayService.setMenuState(false)
        Log.d(TAG, "WazeAccessibilityService destroyed.")
    }
}
