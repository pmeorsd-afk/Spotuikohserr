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

    fun hasAccessibilityPermission(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${context.packageName}/${com.music.spotui.service.WazeAccessibilityService::class.java.canonicalName}"
        val expectedShort = "${context.packageName}/.service.WazeAccessibilityService"
        return enabledServices.contains(expected) || enabledServices.contains(expectedShort)
    }

    fun requestAccessibilityPermission(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Checks if Waze is currently in the foreground.
     */
    fun isWazeInForeground(context: Context): Boolean {
        if (!hasUsageStatsPermission(context)) {
            return false
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val now = System.currentTimeMillis()
        val events = runCatching { usm.queryEvents(now - 30_000, now) }.getOrNull()

        if (events != null) {
            val event = UsageEvents.Event()
            var latestTimestamp = 0L
            var latestPkg: String? = null

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED || type == 1) {
                    if (event.timeStamp >= latestTimestamp) {
                        latestTimestamp = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            }

            if (latestPkg != null) {
                lastKnownForegroundPackage = latestPkg
                return latestPkg == WAZE_PACKAGE
            }
        }

        if (lastKnownForegroundPackage != null) {
            return lastKnownForegroundPackage == WAZE_PACKAGE
        }

        // Fallback: check highest lastTimeUsed
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000, now)
        val mostRecent = stats?.maxByOrNull { it.lastTimeUsed }
        return mostRecent?.packageName == WAZE_PACKAGE
    }
}
