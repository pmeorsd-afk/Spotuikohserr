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

object WazeDetector {

    const val WAZE_PACKAGE = "com.waze"

    @Volatile
    private var lastKnownForegroundPackage: String? = null
    @Volatile
    private var lastEventTimestamp: Long = 0L

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
     * Checks if Waze is currently in the foreground with state persistence.
     */
    fun isWazeInForeground(context: Context): Boolean {
        if (!hasUsageStatsPermission(context)) {
            return false
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val queryStart = if (lastEventTimestamp > 0 && now - lastEventTimestamp < 120_000) {
            lastEventTimestamp
        } else {
            now - 60_000
        }

        val events = runCatching { usm.queryEvents(queryStart, now) }.getOrNull()
        if (events != null) {
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == 1) {
                    if (event.timeStamp >= lastEventTimestamp) {
                        lastEventTimestamp = event.timeStamp
                        lastKnownForegroundPackage = event.packageName
                    }
                }
            }
        }

        if (lastKnownForegroundPackage != null) {
            return lastKnownForegroundPackage == WAZE_PACKAGE
        }

        // Fallback: Check highest lastTimeUsed in recent usage stats
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now)
        val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
        if (mostRecent != null) {
            lastKnownForegroundPackage = mostRecent.packageName
            return mostRecent.packageName == WAZE_PACKAGE
        }

        return false
    }
}
