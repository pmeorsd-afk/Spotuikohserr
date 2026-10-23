package com.music.spotui.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.session.MediaButtonReceiver
import com.music.spotui.playback.ResumeMediaButtonPolicy

/** Media3 performs service discovery and handles Android's background-start exemption. */
class ResumeMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
        if (!ResumeMediaButtonPolicy.accepts(event.action, event.keyCode, event.repeatCount)) return
        MediaButtonReceiver().onReceive(context, Intent(intent).putExtra(EXTRA_COLD_PLAY, true))
    }

    companion object {
        internal const val EXTRA_COLD_PLAY = "spotui.MEDIA_BUTTON_RESUMPTION"
    }
}
