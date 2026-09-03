package com.music.spotui.utils

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import com.music.spotui.data.preferences.isWazeAlwaysShowEnabled

object WazeDetector {

    const val WAZE_PACKAGE = "com.waze"

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun requestOverlayPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun requestUsageStatsPermission(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Checks if Waze is currently in the foreground using UsageEvents.
     */
    fun isWazeInForeground(context: Context): Boolean {
        if (!hasUsageStatsPermission(context)) {
            // Fallback: If usage stats permission not granted, check if always-show mode is enabled
            return isWazeAlwaysShowEnabled(context)
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 15000, now)
        val event = UsageEvents.Event()
        var lastForegroundPackage: String? = null
        var lastEventTime = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == 1 || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (event.timeStamp >= lastEventTime) {
                    lastEventTime = event.timeStamp
                    lastForegroundPackage = event.packageName
                }
            }
        }

        if (lastForegroundPackage != null) {
            return lastForegroundPackage == WAZE_PACKAGE
        }

        // Fallback: Check highest lastTimeUsed in recent usage stats
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now)
        val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
        return mostRecent?.packageName == WAZE_PACKAGE
    }
}
