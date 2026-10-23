package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.data.preferences.getPersonalPodcastRating
import com.music.spotui.data.preferences.savePersonalPodcastRating
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PodcastPersonalRatingTest {
    private val values = mutableMapOf<String, Any>()
    private val files = mutableSetOf<String>()
    private var writes = 0
    private var commitSucceeds = true
    private var storageThrows = false
    private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        if (storageThrows) throw IllegalStateException("Storage unavailable")
        when (method.name) {
            "getInt" -> (values[args!![0]] ?: args[1]) as Int
            "edit" -> editor()
            else -> error("Unexpected operation ${method.name}")
        }
    } as SharedPreferences
    private fun newContext() = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences { files += name; return prefs }
    }
    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, Int>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putInt" -> { pending[args!![0] as String] = args[1] as Int; proxy }
                "commit" -> { writes++; if (commitSucceeds) values.putAll(pending); commitSucceeds }
                else -> error("Unexpected operation ${method.name}")
            }
        } as SharedPreferences.Editor
    }

    @Test fun selectedRatingPersistsAcrossReaderRecreationAndCanBeEdited() {
        assertNull(getPersonalPodcastRating(newContext(), "show-a"))
        assertTrue(savePersonalPodcastRating(newContext(), "show-a", 1))
        assertEquals(1, getPersonalPodcastRating(newContext(), "show-a"))
        assertTrue(savePersonalPodcastRating(newContext(), "show-a", 5))
        assertEquals(5, getPersonalPodcastRating(newContext(), "show-a"))
        assertEquals(2, writes)
        assertEquals(setOf("PodcastPersonalRatings"), files)
    }

    @Test fun editingOneShowPreservesOtherShowsAndDoesNotTouchLibraryStores() {
        assertTrue(savePersonalPodcastRating(newContext(), "a", 2))
        assertTrue(savePersonalPodcastRating(newContext(), "b", 4))
        assertTrue(savePersonalPodcastRating(newContext(), "a", 3))
        assertEquals(3, getPersonalPodcastRating(newContext(), "a"))
        assertEquals(4, getPersonalPodcastRating(newContext(), "b"))
        assertEquals(setOf("PodcastPersonalRatings"), files)
        assertEquals(2, values.size)
    }

    @Test fun missingIdentityAndInvalidRatingsAreRejectedWithoutWriting() {
        listOf("", " ", "unknown").forEach {
            assertFalse(savePersonalPodcastRating(newContext(), it, 3))
            assertNull(getPersonalPodcastRating(newContext(), it))
        }
        listOf(Int.MIN_VALUE, -1, 0, 6, Int.MAX_VALUE).forEach {
            assertFalse(savePersonalPodcastRating(newContext(), "a", it))
        }
        assertEquals(0, writes)
        assertTrue(files.isEmpty())
    }

    @Test fun failedCommitIsReportedAsFailureInsteadOfSuccessfulRating() {
        assertTrue(savePersonalPodcastRating(newContext(), "a", 2))
        commitSucceeds = false
        assertFalse(savePersonalPodcastRating(newContext(), "a", 5))
        assertEquals(2, getPersonalPodcastRating(newContext(), "a"))
    }

    @Test fun malformedStoredRatingOrUnavailableStoreDoesNotCrashMenu() {
        values["show:a"] = 9
        assertNull(getPersonalPodcastRating(newContext(), "a"))
        values["show:a"] = "old format"
        assertNull(getPersonalPodcastRating(newContext(), "a"))
        storageThrows = true
        assertNull(getPersonalPodcastRating(newContext(), "a"))
        assertFalse(savePersonalPodcastRating(newContext(), "a", 4))
    }
}
