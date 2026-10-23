package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ListeningHistoryMigrationTest {
    private val track = SongsModel(1, "Track", "Album", "Artist", "cover", "youtube:abcdefghijk")
    private val episode = track.copy(id = 2, title = "Episode", mediaType = MediaType.PODCAST_EPISODE,
        url = "episode:show:guid", podcastShowId = "show")
    private val legacy = HistoryEntry(1, 3, "Old item", "Artist")
    private val stores = mutableMapOf<String, MemoryPreferences>()
    private val accessed = mutableListOf<String>()
    private val context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            accessed += name
            return stores.getOrPut(name) { MemoryPreferences() }.prefs
        }
    }
    private fun seed(entries: List<HistoryEntry>): MemoryPreferences = MemoryPreferences().also {
        it.values["entries"] = ListeningHistoryLog.encode(entries)
        stores["ListeningHistory"] = it
    }

    @Test fun firstReadRemovesLegacyButKeepsExactTrackAndEpisodeInOrder() {
        val known = listOf(HistoryEntry.fromSong(episode, 30), HistoryEntry.fromSong(track, 20))
        val store = seed(listOf(known[0], legacy, known[1]))
        assertEquals(known, getListeningHistory(context))
        assertEquals(known, ListeningHistoryLog.decode(store.values["entries"] as String))
        assertEquals(1, store.writes)
        assertEquals(true, store.values["typed_identity_cleanup_v1"])
    }

    @Test fun legacyOnlyUpgradeStartsWithEmptyHistory() {
        seed(listOf(legacy))
        assertTrue(getListeningHistory(context).isEmpty())
    }

    @Test fun repeatedReadsAndNewListensNeverRepeatCleanup() {
        val store = seed(listOf(legacy))
        getListeningHistory(context)
        val newItem = HistoryEntry.fromSong(track, 40)
        addListeningHistory(context, newItem)
        val writesAfterPlay = store.writes
        repeat(3) { assertEquals(listOf(newItem), getListeningHistory(context)) }
        assertEquals(writesAfterPlay, store.writes)
    }

    @Test fun freshInstallMarksMigrationBeforeFirstListen() {
        assertTrue(getListeningHistory(context).isEmpty())
        val store = stores.getValue("ListeningHistory")
        assertFalse(store.values.containsKey("entries"))
        val newItem = HistoryEntry.fromSong(episode, 50)
        addListeningHistory(context, newItem)
        assertEquals(listOf(newItem), getListeningHistory(context))
        assertEquals(2, store.writes)
    }

    @Test fun playlistLikedResumeAndUnrelatedKeysRemainUntouched() {
        val protectedFiles = listOf("SpotUI_CustomPlaylists", "LikedSongs", "LikedSongs_Data", "LikedAlbums",
            "PlaybackState", "PlaybackProgress", "local_listening_tracker")
        protectedFiles.forEach { name ->
            stores[name] = MemoryPreferences().also { it.values["data"] = "keep $name" }
        }
        val store = seed(listOf(legacy))
        store.values["unrelated-history-setting"] = "keep"
        getListeningHistory(context)
        assertEquals(listOf("ListeningHistory"), accessed)
        protectedFiles.forEach { name ->
            assertEquals(mapOf("data" to "keep $name"), stores.getValue(name).values)
            assertEquals(0, stores.getValue(name).writes)
        }
        assertEquals("keep", store.values["unrelated-history-setting"])
    }

    @Test fun conflictingTypedEntryRemovedWhileValidEpisodeSurvives() {
        val good = HistoryEntry.fromSong(episode, 3)
        seed(listOf(good, HistoryEntry.fromSong(track.copy(url = "spotify:episode:1234567890123456789012"), 2)))
        assertEquals(listOf(good), getListeningHistory(context))
    }

    @Test fun brokenLegacyJsonIsCleanedAndNewEntriesCanBeSaved() {
        val store = seed(emptyList())
        store.values["entries"] = "invalid legacy JSON"
        assertTrue(getListeningHistory(context).isEmpty())
        val good = HistoryEntry.fromSong(episode, 3)
        addListeningHistory(context, good)
        assertEquals(listOf(good), getListeningHistory(context))
    }

    @Test fun removingHistoryDoesNotRemoveMigrationMarker() {
        val store = seed(listOf(legacy))
        getListeningHistory(context)
        clearListeningHistory(context)
        val writes = store.writes
        assertTrue(getListeningHistory(context).isEmpty())
        assertEquals(writes, store.writes)
        assertEquals(true, store.values["typed_identity_cleanup_v1"])
    }

    /** Executes the real preference adapter. Unexpected operations (including clear) fail the test. */
    private class MemoryPreferences {
        val values = mutableMapOf<String, Any>()
        var writes = 0
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference operation: ${method.name}")
            }
        } as SharedPreferences

        private fun editor(): SharedPreferences.Editor {
            val pending = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean" -> { pending[args!![0] as String] = args[1]; proxy }
                    "remove" -> { pending[args!![0] as String] = null; proxy }
                    "apply" -> {
                        pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        writes++
                        null
                    }
                    else -> error("Unexpected editor operation: ${method.name}")
                }
            } as SharedPreferences.Editor
        }
    }
}
