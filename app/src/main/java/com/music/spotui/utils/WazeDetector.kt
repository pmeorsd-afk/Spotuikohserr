package com.music.spotui.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

object WazeDetector {

    const val WAZE_PACKAGE = "com.waze"

    /**
     * Whether the "Waze Screen Detector" accessibility service is enabled. This is the only
     * permission the Waze integration needs: the service both detects Waze's map/menu state
     * and draws the floating button, through a window type that needs no separate "draw over
     * other apps" permission, and it can tell whether Waze is foreground at all on its own -
     * so the overlay and usage-stats permissions this object used to also check for are gone.
     */
    fun hasScreenDetectorPermission(context: Context): Boolean {
        val component = ComponentName(context, com.music.spotui.service.WazeScreenAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    fun requestScreenDetectorPermission(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
