package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.preferences.*
import com.music.spotui.ui.library.LibraryFilter
import com.music.spotui.ui.library.libraryItems
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class PodcastFollowingPersistenceTest {
    private val values = mutableMapOf<String, String>()
    private var writes = 0
    private val files = mutableSetOf<String>()
    private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString" -> values[args!![0]] ?: args[1]
            "edit" -> editor()
            else -> error("Unexpected operation ${method.name}")
        }
    } as SharedPreferences
    private val context = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences { files += name; return prefs }
    }
    private fun editor(): SharedPreferences.Editor {
        val pending = mutableMapOf<String, String>()
        return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { pending[args!![0] as String] = args[1] as String; proxy }
                "apply" -> { values.putAll(pending); writes++; null }
                else -> error("Unexpected operation ${method.name}")
            }
        } as SharedPreferences.Editor
    }
    private fun model(id: String) = PodcastModel(id, "Show $id", "", "cover-$id")

    @Test fun doneCommitsMultipleSelectionsWithOneWriteAndOneRevision() {
        val before = podcastFollowRevision.value
        applyPodcastFollowEdits(context, mapOf("a" to (model("a") to true), "b" to (model("b") to true)))
        assertEquals(setOf("a", "b"), getFollowedShows(context).map { it.showId }.toSet())
        assertEquals(1, writes)
        assertEquals(before + 1, podcastFollowRevision.value)
        assertEquals(setOf("PodcastFollowPref"), files)
    }
    @Test fun donePreservesConcurrentFollowAndExistingBookmarkMetadata() {
        val existing = FollowedPodcastShow("a", "Show a", "cover-a", 123, "guid")
        followPodcast(context, existing)
        followPodcast(context, model("external").id, "External", "external-cover")
        applyPodcastFollowEdits(context, mapOf("b" to (model("b") to true)))
        val loaded = getFollowedShows(context)
        assertEquals(existing, loaded.single { it.showId == "a" })
        assertEquals(setOf("a", "external", "b"), loaded.map { it.showId }.toSet())
    }
    @Test fun unchangedOrCancelledDraftDoesNotWriteOrNotify() {
        followPodcast(context, "a", "Show a", "cover-a")
        val count = writes; val revision = podcastFollowRevision.value
        applyPodcastFollowEdits(context, emptyMap())
        applyPodcastFollowEdits(context, mapOf("a" to (model("a") to true)))
        assertEquals(count, writes); assertEquals(revision, podcastFollowRevision.value)
    }
    @Test fun removingOneSelectionDoesNotClearOtherFollows() {
        followPodcast(context, "a", "Show a")
        followPodcast(context, "b", "Show b")
        applyPodcastFollowEdits(context, mapOf("a" to (model("a") to false)))
        assertEquals(listOf("b"), getFollowedShows(context).map { it.showId })
    }

    @Test fun libraryObserverLoadsExistingFollowsOnEveryNewSubscription() = runBlocking {
        val stored = FollowedPodcastShow("existing", "Existing show", "cover", 123, "episode-guid")
        followPodcast(context, stored)
        assertEquals(listOf(stored), observeFollowedShows(context).first())
        // Reopening Library must read the durable snapshot without another follow action.
        assertEquals(listOf(stored), observeFollowedShows(context).first())
        assertEquals(1, writes)
        assertEquals(setOf("PodcastFollowPref"), files)
    }

    @Test fun showFollowAndUnfollowUpdateOpenLibraryAndPersistRemoval() = runBlocking {
        val renderedKeys = mutableListOf<List<String>>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeFollowedShows(context).collect { shows ->
                renderedKeys += libraryItems(emptyList(), shows, emptyList(), LibraryFilter.ALL, "").map { it.key }
            }
        }
        try {
            assertEquals(emptyList<String>(), renderedKeys.last())
            followPodcast(context, "a", "Show a", "cover-a")
            yield()
            assertEquals(listOf("collection:new-episodes", "show:a"), renderedKeys.last())
            assertTrue(isPodcastFollowed(context, "a"))

            followPodcast(context, "b", "Show b", "cover-b")
            yield()
            assertEquals(listOf("collection:new-episodes", "show:b", "show:a"), renderedKeys.last())
            assertTrue(unfollowPodcast(context, "a"))
            yield()
            assertEquals(listOf("collection:new-episodes", "show:b"), renderedKeys.last())
            assertFalse(isPodcastFollowed(context, "a"))
            assertEquals(listOf("b"), observeFollowedShows(context).first().map { it.showId })
            assertTrue(unfollowPodcast(context, "b"))
            yield()
            assertTrue(renderedKeys.last().isEmpty())
            assertTrue(observeFollowedShows(context).first().isEmpty())
            followPodcast(context, "again", "Follow again")
            yield()
            assertEquals(listOf("collection:new-episodes", "show:again"), renderedKeys.last())
            assertEquals(setOf("PodcastFollowPref"), files)
        } finally {
            observer.cancelAndJoin()
        }
    }

    @Test fun chooserEditsAndRepeatedFollowShareLibraryStateWithoutDuplicates() = runBlocking {
        followPodcast(context, "keep", "Keep")
        followPodcast(context, "remove", "Remove")
        var visible: List<String> = emptyList()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            observeFollowedShows(context).collect { shows ->
                visible = libraryItems(emptyList(), shows, emptyList(), LibraryFilter.PODCASTS, "").map { it.key }
            }
        }
        try {
            applyPodcastFollowEdits(context, mapOf("remove" to (model("remove") to false), "add" to (model("add") to true)))
            yield()
            assertEquals(setOf("collection:new-episodes", "show:keep", "show:add"), visible.toSet())
            assertFalse(isPodcastFollowed(context, "remove"))
            assertTrue(isPodcastFollowed(context, "add"))
            followPodcast(context, "add", "Show add", "cover-add")
            yield()
            assertEquals(3, visible.size)
            assertEquals(setOf("collection:new-episodes", "show:keep", "show:add"), visible.toSet())
            assertEquals(setOf("PodcastFollowPref"), files)
        } finally {
            observer.cancelAndJoin()
        }
    }
}
