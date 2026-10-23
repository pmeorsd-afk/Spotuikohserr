package com.music.spotui.playback

import com.music.spotui.data.entity.*
import com.music.spotui.data.podcast.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PodcastHomeTest {
    private fun show(id: String = "show") = PodcastModel(id, "Show $id", "Publisher", "cover", topics = listOf("Science"))
    private fun episode(show: String = "show", id: Int = 1, date: String? = "2026-10-14") = PodcastEpisodeUiModel(
        id = "episode:$show:guid-$id", numericId = id, title = "Same title", description = "Description", pubDate = date,
        durationMs = 120_000, coverUri = "episode-cover", playUrl = "episode:$show:guid-$id", showId = show, showName = "Show $show",
    )
    private fun catalog(id: String = "show", count: Int = 8) = PodcastCatalog(show(id), (1..count).map { episode(id, it) }, 1000)

    @Test fun codecRetainsExactEpisodeFieldsAndTopics() {
        val source = listOf(catalog())
        assertEquals(source, PodcastHomeCodec.decode(PodcastHomeCodec.encode(source)))
        val song = PodcastHomeCodec.decode(PodcastHomeCodec.encode(source)).single().episodes.single { it.numericId == 2 }.toSongModel()
        assertEquals(MediaType.PODCAST_EPISODE, song.mediaType)
        assertEquals("episode:show:guid-2", song.url)
    }
    @Test fun codecRejectsCorruptAndMismatchedIdentities() {
        assertTrue(PodcastHomeCodec.decode("broken").isEmpty())
        assertTrue(PodcastHomeCodec.decode("{\"version\":2}").isEmpty())
        val bad = catalog().copy(episodes = listOf(episode().copy(playUrl = "episode:other:guid-1")))
        assertTrue(PodcastHomeCodec.decode(PodcastHomeCodec.encode(listOf(bad))).single().episodes.isEmpty())
    }
    @Test fun codecBoundsDiskSnapshot() {
        val source = (1..30).map { catalog("show$it", 20) }
        val decoded = PodcastHomeCodec.decode(PodcastHomeCodec.encode(source))
        assertEquals(24, decoded.size)
        assertTrue(decoded.all { it.episodes.size == 8 })
    }
    @Test fun recommendationsAreStableWithinSessionAndDiverse() {
        val source = listOf(catalog("a"), catalog("b"), catalog("c"))
        val first = PodcastHomeRanking.build(source, PodcastHomeSignals(), 42, 1_800_000_000_000)
        assertEquals(first, PodcastHomeRanking.build(source, PodcastHomeSignals(), 42, 1_800_000_000_000))
        assertEquals(6, first.recommended.size)
        assertTrue(first.recommended.groupBy { it.showId }.values.all { it.size <= 2 })
        assertNotEquals(first.recommended, PodcastHomeRanking.build(source, PodcastHomeSignals(), 43, 1_800_000_000_000).recommended)
    }
    @Test fun exactCompletionAndResumeExcludeOnlyThatEpisode() {
        val a = catalog("a"); val b = catalog("b")
        val done = PodcastHomeRanking.key(a.episodes[0])!!
        val resume = PodcastHomeRanking.key(b.episodes[0])!!
        val sections = PodcastHomeRanking.build(listOf(a, b), PodcastHomeSignals(completed = setOf(done), continuing = setOf(resume)), 1, 1000)
        assertEquals(listOf(b.episodes[0]), sections.continuing)
        assertFalse(sections.recommended.any { PodcastHomeRanking.key(it) == done || PodcastHomeRanking.key(it) == resume })
        val all = sections.continuing + sections.recommended + sections.latest
        assertEquals(all.size, all.distinctBy { it.id }.size)
    }
    @Test fun followingIsCurrentAndDateLessItemsAreNeverCalledNew() {
        val a = catalog("a").copy(episodes = listOf(episode("a", 1, null), episode("a", 2, "2026-10-10"), episode("a", 3, "2026-10-14")))
        val result = PodcastHomeRanking.build(listOf(a, catalog("b")), PodcastHomeSignals(followed = setOf("a")), 5, 1000)
        assertEquals(listOf(3, 2, 1), result.following.map { it.numericId })
        assertTrue(result.latest.all { it.showId == "a" && it.pubDate != null })
        val removed = PodcastHomeRanking.build(listOf(a), PodcastHomeSignals(), 5, 1000)
        assertTrue(removed.following.isEmpty())
    }
    @Test fun emptyCatalogDoesNotInventSections() {
        assertEquals(PodcastHomeSections(), PodcastHomeRanking.build(emptyList(), PodcastHomeSignals(), 1, 1000))
    }
    @Test fun savedOlderEpisodeCanResumeWithoutBeingInLatestCatalogPage() {
        val older = episode("older", 99, null)
        val signals = PodcastHomeSignals(continuing = setOf(PodcastHomeRanking.key(older)!!))
        val result = PodcastHomeRanking.build(emptyList(), signals, 1, 1000, listOf(older))
        assertEquals(listOf(older), result.continuing)
        assertTrue(result.latest.isEmpty()); assertTrue(result.recommended.isEmpty())
    }
    @Test fun chooserEditsPreserveUnrelatedConcurrentFollows() {
        val result = PodcastFollowEdits.apply(listOf(show("a"), show("external")), mapOf("a" to (show("a") to false), "b" to (show("b") to true)))
        assertEquals(setOf("external", "b"), result.map { it.id }.toSet())
        assertEquals(listOf(show("a")), PodcastFollowEdits.apply(listOf(show("a")), emptyMap()))
    }
    @Test fun chooserRejectsMismatchedIdsAndDeduplicates() {
        val current = listOf(show("a"))
        assertEquals(current, PodcastFollowEdits.apply(current, mapOf("a" to (show("other") to false))))
        assertEquals(current, PodcastFollowEdits.apply(current, mapOf("a" to (show("a") to true))))
    }
    @Test fun loaderSharesRequestsAndUsesFreshCache() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val calls = AtomicInteger(); val release = CompletableDeferred<Unit>()
            val loader = PodcastCatalogLoader(scope, now = { 1000 }) { calls.incrementAndGet(); release.await(); listOf(episode()) }
            val readers = (1..5).map { async { loader.load(show()) } }
            yield(); release.complete(Unit)
            assertTrue(readers.awaitAll().all { it.catalog?.episodes?.size == 1 })
            loader.load(show())
            assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }
    @Test fun loaderHasAtMostTwoConcurrentFetches() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val active = AtomicInteger(); val maximum = AtomicInteger()
            val loader = PodcastCatalogLoader(scope) { s ->
                val count = active.incrementAndGet(); maximum.updateAndGet { maxOf(it, count) }
                try { delay(30); listOf(episode(s.id)) } finally { active.decrementAndGet() }
            }
            (1..8).map { async { loader.load(show("s$it")) } }.awaitAll()
            assertEquals(2, maximum.get())
        } finally { scope.cancel() }
    }
    @Test fun loaderRetainsStaleContentWhenRefreshFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val old = catalog()
            val loader = PodcastCatalogLoader(scope, listOf(old), now = { 4_000_000 }) { error("offline") }
            val result = loader.load(show())
            assertTrue(result.failed); assertEquals(old, result.catalog)
        } finally { scope.cancel() }
    }
    @Test fun oneCancelledReaderDoesNotCancelSharedCatalog() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val calls = AtomicInteger()
            val loader = PodcastCatalogLoader(scope) { calls.incrementAndGet(); started.complete(Unit); release.await(); listOf(episode()) }
            val first = async { loader.load(show()) }; started.await()
            val second = async { loader.load(show()) }; yield()
            first.cancelAndJoin(); release.complete(Unit)
            assertNotNull(second.await().catalog); assertEquals(1, calls.get())
        } finally { scope.cancel() }
    }
    @Test fun loaderBoundsEpisodesRejectsForeignShowAndPersists() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var saved = emptyList<PodcastCatalog>()
            val loader = PodcastCatalogLoader(scope, persist = { saved = it }) {
                listOf(episode("foreign")) + (1..20).map { episode(id = it) }
            }
            val loaded = loader.load(show()).catalog!!
            assertEquals(8, loaded.episodes.size)
            assertTrue(loaded.episodes.all { it.showId == "show" })
            assertEquals(loaded, saved.single())
        } finally { scope.cancel() }
    }
}
