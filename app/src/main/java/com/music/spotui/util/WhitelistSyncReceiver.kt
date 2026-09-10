package com.music.spotui.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WhitelistSyncReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_WHITELIST_SYNC) {
            val json = intent.getStringExtra("whitelist_json") ?: return
            if (json.isNotBlank()) {
                KosherWhitelistManager.applyExternalWhitelist(context, json)
            }
        }
    }

    companion object {
        const val ACTION_WHITELIST_SYNC = "com.music.spotui.ACTION_WHITELIST_SYNC"
    }
}
