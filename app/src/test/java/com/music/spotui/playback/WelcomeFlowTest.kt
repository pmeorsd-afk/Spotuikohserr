package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.data.preferences.WhatsNewPref
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class WelcomeFlowTest {
    private val values = mutableMapOf<String, Boolean>()
    private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getBoolean" -> values[args!![0]] ?: args[1]
            "edit" -> editor()
            else -> error(method.name)
        }
    } as SharedPreferences
    private fun context() = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int) = prefs
    }
    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Boolean>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putBoolean" -> { pending[args!![0] as String] = args[1] as Boolean; proxy }
                "apply" -> { values.putAll(pending); null }
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
    }
    @Test fun olderWelcomeDoesNotHideNewAndroidAutoAnnouncement() {
        values["seen_whats_new_v28"] = true
        assertTrue(WhatsNewPref.shouldShow(context(), 28))
        WhatsNewPref.markSeen(context(), 28)
        assertFalse(WhatsNewPref.shouldShow(context(), 28))
    }
    @Test fun continuationSurvivesRecreationWithoutRepeatingWelcome() {
        WhatsNewPref.markSeen(context(), 28, offerWaze = true)
        assertFalse(WhatsNewPref.shouldShow(context(), 28))
        assertTrue(WhatsNewPref.shouldOfferWaze(context()))
        WhatsNewPref.finishWazeOffer(context())
        assertFalse(WhatsNewPref.shouldOfferWaze(context()))
        assertFalse(WhatsNewPref.shouldShow(context(), 28))
    }
    @Test fun dismissalOrAlreadyEnabledWazeDoesNotQueueOffer() {
        WhatsNewPref.markSeen(context(), 28, offerWaze = false)
        assertFalse(WhatsNewPref.shouldOfferWaze(context()))
    }
    @Test fun loginPendingAndFutureReleasesStillShowTheirWelcome() {
        WhatsNewPref.markSeen(context(), 28)
        WhatsNewPref.setPendingShow(context())
        assertTrue(WhatsNewPref.shouldShow(context(), 28))
        WhatsNewPref.markSeen(context(), 28)
        assertFalse(WhatsNewPref.shouldShow(context(), 28))
        assertTrue(WhatsNewPref.shouldShow(context(), 29))
    }
}
