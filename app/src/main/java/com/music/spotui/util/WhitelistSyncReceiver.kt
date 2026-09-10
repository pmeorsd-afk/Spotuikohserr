package com.music.spotui.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WhitelistSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WHITELIST_SYNC) {
            android.util.Log.d("WHITELIST_SYNC", "Broadcast ACTION_WHITELIST_SYNC received! Syncing from Admin ContentProvider...")
            val synced = KosherWhitelistManager.syncFromAdminProvider(context)
            if (!synced) {
                // Fallback: check if JSON was passed in Intent extra
                val fallbackJson = intent.getStringExtra("whitelist_json")
                if (!fallbackJson.isNullOrBlank()) {
                    android.util.Log.d("WHITELIST_SYNC", "Provider sync did not succeed, falling back to intent payload")
                    KosherWhitelistManager.applyExternalWhitelist(context, fallbackJson)
                }
            }
        }
    }

    companion object {
        const val ACTION_WHITELIST_SYNC = "com.music.spotui.ACTION_WHITELIST_SYNC"
        const val PERMISSION_READ_WHITELIST = "com.music.spotui.permission.READ_WHITELIST"
    }
}
