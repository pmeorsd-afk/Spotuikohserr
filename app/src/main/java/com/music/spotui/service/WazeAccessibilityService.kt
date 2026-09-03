package com.music.spotui.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class WazeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "WazeAccessibility"
        const val WAZE_PACKAGE = "com.waze"

        @Volatile
        var instance: WazeAccessibilityService? = null
            private set

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedServiceName = "${context.packageName}/${WazeAccessibilityService::class.java.name}"
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(expectedServiceName, ignoreCase = true) ||
                    componentName.contains(WazeAccessibilityService::class.java.simpleName)
                ) {
                    return true
                }
            }
            return false
        }

        fun requestAccessibilityPermission(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "WazeAccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        if (pkg != WAZE_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                // User navigated away from Waze
                WazeOverlayService.onWazeLeft()
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val root = rootInActiveWindow ?: return
            val bounds = findWazeReferenceBounds(root)
            if (bounds != null) {
                WazeOverlayService.updateDynamicPosition(
                    x = bounds.x,
                    y = bounds.y,
                    width = bounds.width,
                    height = bounds.height
                )
            } else {
                WazeOverlayService.onWazeActiveWithoutBounds()
            }
        }
    }

    data class TargetBounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private fun findWazeReferenceBounds(root: AccessibilityNodeInfo): TargetBounds? {
        val dm = resources.displayMetrics
        val screenWidth = dm.widthPixels
        val screenHeight = dm.heightPixels

        // 1. Try finding by ID
        val candidateIds = listOf(
            "com.waze:id/mute_button",
            "com.waze:id/sound_button",
            "com.waze:id/speaker_button",
            "com.waze:id/audio_button",
            "com.waze:id/btn_sound",
            "com.waze:id/btn_mute",
            "com.waze:id/sound_toggle"
        )
        for (id in candidateIds) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            if (!nodes.isNullOrEmpty()) {
                val node = nodes[0]
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val gap = (rect.height() * 0.22f).toInt()
                    return TargetBounds(
                        x = rect.left,
                        y = rect.bottom + gap,
                        width = rect.width(),
                        height = rect.height()
                    )
                }
            }
        }

        // 2. Try finding by text / contentDescription
        val candidateKeywords = listOf(
            "קול", "צליל", "רמקול", "השתק", "שמע",
            "Sound", "Mute", "Speaker", "Audio", "Unmute", "Volume"
        )
        for (kw in candidateKeywords) {
            val nodes = root.findAccessibilityNodeInfosByText(kw)
            if (!nodes.isNullOrEmpty()) {
                for (node in nodes) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() in 50..300 && rect.height() in 50..300) {
                        val gap = (rect.height() * 0.22f).toInt()
                        return TargetBounds(
                            x = rect.left,
                            y = rect.bottom + gap,
                            width = rect.width(),
                            height = rect.height()
                        )
                    }
                }
            }
        }

        // 3. Smart Geometric Fallback: Collect round/square action buttons in top area of Waze
        val topButtons = mutableListOf<Rect>()
        collectCandidateButtons(root, topButtons, screenWidth, screenHeight)

        if (topButtons.size >= 2) {
            // Sort vertically by top coordinate
            topButtons.sortBy { it.top }
            // The top 2 buttons in the column are [♫] and [🔊]
            val btn1 = topButtons[0]
            val btn2 = topButtons[1]

            // Calculate exact gap between button 1 and button 2
            val measuredGap = (btn2.top - btn1.bottom).coerceIn(4, 50)
            val btnWidth = btn2.width()
            val btnHeight = btn2.height()

            return TargetBounds(
                x = btn2.left,
                y = btn2.bottom + measuredGap,
                width = btnWidth,
                height = btnHeight
            )
        } else if (topButtons.size == 1) {
            val btn = topButtons[0]
            val gap = (btn.height() * 0.22f).toInt()
            return TargetBounds(
                x = btn.left,
                y = btn.bottom + gap,
                width = btn.width(),
                height = btn.height()
            )
        }

        return null
    }

    private fun collectCandidateButtons(
        node: AccessibilityNodeInfo?,
        outList: MutableList<Rect>,
        screenWidth: Int,
        screenHeight: Int
    ) {
        if (node == null) return

        val rect = Rect()
        node.getBoundsInScreen(rect)

        val w = rect.width()
        val h = rect.height()

        // Filter: circular/square button in the top 35% of the screen, on the right or left edge
        if (w in 60..260 && h in 60..260) {
            val aspect = w.toFloat() / h.toFloat()
            if (aspect in 0.75f..1.35f && rect.top in 10..(screenHeight * 0.35).toInt()) {
                val isNearEdge = rect.right >= screenWidth * 0.70 || rect.left <= screenWidth * 0.30
                if (isNearEdge && (node.isClickable || outList.none { it.contains(rect) || rect.contains(it) })) {
                    // Check if not already added a duplicate rect
                    if (outList.none { Math.abs(it.centerX() - rect.centerX()) < 15 && Math.abs(it.centerY() - rect.centerY()) < 15 }) {
                        outList.add(Rect(rect))
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            collectCandidateButtons(node.getChild(i), outList, screenWidth, screenHeight)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "WazeAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }
}
