package com.music.spotui.data.preferences

import android.content.Context

object WhatsNewPref {
    private const val PREF_NAME = "spotui_whats_new_prefs"
    private const val KEY_SEEN_PREFIX = "seen_whats_new_redesign_v"
    private const val KEY_WAZE_PENDING = "pending_waze_intro"
    private const val KEY_PENDING_SHOW = "pending_show_whats_new"

    fun shouldShow(context: Context, versionCode: Int = com.music.spotui.BuildConfig.VERSION_CODE): Boolean {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val pending = sp.getBoolean(KEY_PENDING_SHOW, false)
        val seen = sp.getBoolean("$KEY_SEEN_PREFIX$versionCode", false)
        return pending || !seen
    }

    fun markSeen(context: Context, versionCode: Int = com.music.spotui.BuildConfig.VERSION_CODE, offerWaze: Boolean = false) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit()
            .putBoolean("$KEY_SEEN_PREFIX$versionCode", true)
            .putBoolean(KEY_PENDING_SHOW, false)
            .putBoolean(KEY_WAZE_PENDING, offerWaze)
            .apply()
    }

    fun setPendingShow(context: Context) {
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        sp.edit().putBoolean(KEY_PENDING_SHOW, true).apply()
    }

    fun shouldOfferWaze(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getBoolean(KEY_WAZE_PENDING, false)

    fun finishWazeOffer(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().putBoolean(KEY_WAZE_PENDING, false).apply()
    }
}
