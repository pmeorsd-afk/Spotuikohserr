package com.music.spotui.playback

import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.podcast.*
import com.music.spotui.ui.podcast.exactDownloadEnclosure
import com.music.spotui.ui.podcast.exactSpotifyEpisodeId
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class NewEpisodesFeedTest {
    private fun episode(show: String = "a", guid: String = "guid", date: String? = "2026-10-18") =
        PodcastEpisodeUiModel(id = "episode:$show:$guid", numericId = 17, title = "אותו שם", pubDate = date,
            playUrl = "episode:$show:$guid", showId = show, showName = "Show $show", durationMs = 120_000)
    private fun catalog(show: String, vararg episodes: PodcastEpisodeUiModel) =
        PodcastCatalog(PodcastModel(show, "Show $show", "Publisher", "cover"), episodes.toList(), 1234)
    private fun project(catalogs: List<PodcastCatalog>, followed: Set<String> = setOf("a"),
        history: List<PodcastEpisodeUiModel> = emptyList(), completed: Set<String> = emptySet(),
        listening: (PodcastEpisodeUiModel) -> EpisodeListening = { EpisodeListening() }) =
        NewEpisodesProjection.build(catalogs, followed, history, completed, listening)

    @Test fun onlyFollowedCatalogsSortedByPublicationIncludingMoreThanHomeLimit() {
        val episodes = (1..20).map { episode(guid = "$it", date = "2026-10-${it.toString().padStart(2, '0')}") }
        val feed = project(listOf(catalog("a", *episodes.toTypedArray()), catalog("b", episode("b"))))
        assertEquals(episodes.reversed(), feed.episodes)
        assertTrue(project(listOf(catalog("a", *episodes.toTypedArray())), followed = emptySet()).episodes.isEmpty())
    }
    @Test fun freshEpisodeAppearsAboveExistingEpisodesAndUnknownDatesStayLast() {
        val old = episode(guid = "old", date = "2026-10-17")
        val undated = episode(guid = "undated", date = null)
        val latest = episode(guid = "new")
        assertEquals(listOf(latest, old, undated), project(listOf(catalog("a", undated, old, latest))).episodes)
    }
    @Test fun continueIncludesOnlyTwoMostRecentPartialEpisodesEvenWithoutAnyFollows() {
        val recent = (1..4).map { episode("unfollowed", "$it") }
        val feed = project(emptyList(), followed = emptySet(), history = recent) { EpisodeListening(1, 120_000) }
        assertEquals(recent.take(2), feed.continuing.map { it.episode })
        assertTrue(feed.episodes.isEmpty())
    }
    @Test fun historySkipsUnstartedCompletedAndDuplicatesBeforeApplyingLimit() {
        val a = episode(guid = "a"); val b = episode(guid = "b"); val c = episode(guid = "c"); val d = episode(guid = "d")
        val feed = project(emptyList(), history = listOf(a, b, c, c, d)) {
            when (it) { a -> EpisodeListening(0, 100); b -> EpisodeListening(100, 100, true)
                else -> EpisodeListening(10, 100) }
        }
        assertEquals(listOf(c, d), feed.continuing.map { it.episode })
    }
    @Test fun unknownLengthPartialPlaybackIsNotDiscardedOrGivenInventedPercentage() {
        val ep = episode().copy(durationMs = 0)
        val resume = project(emptyList(), history = listOf(ep)) { EpisodeListening(3000, 0) }.continuing.single()
        assertEquals(ep, resume.episode)
        assertEquals(0f, resume.listening.fraction)
    }
    @Test fun displayedContinuingEpisodeIsNotDuplicatedInFeedAndRetainsRichMetadata() {
        val rich = episode().copy(description = "Description")
        val feed = project(listOf(catalog("a", rich)), history = listOf(episode().copy(pubDate = null))) { EpisodeListening(10, 100) }
        assertEquals(rich, feed.continuing.single().episode)
        assertTrue(feed.episodes.isEmpty())
    }
    @Test fun completionRemovesExactIdentityWithoutNumericOrTitleCollisions() {
        val a = episode(); val b = episode("b")
        val feed = project(listOf(catalog("a", a), catalog("b", b)), setOf("a", "b"), completed = setOf(NewEpisodesProjection.key(a)!!))
        assertEquals(listOf(b), feed.episodes)
    }
    @Test fun naturalCompletionAndExplicitCompletionBothHideContinuingAndFeed() {
        val a = episode(); val b = episode(guid = "b")
        val feed = project(listOf(catalog("a", a, b)), history = listOf(a, b), completed = setOf(NewEpisodesProjection.key(a)!!)) {
            EpisodeListening(0, 100, it == b)
        }
        assertTrue(feed.episodes.isEmpty()); assertTrue(feed.continuing.isEmpty())
    }
    @Test fun mismatchedShowAndPlaybackIdentityAreNotAdmitted() {
        val wrong = episode().copy(playUrl = "episode:b:guid")
        assertTrue(project(listOf(catalog("a", wrong, episode("b")))).episodes.isEmpty())
    }
    @Test fun dateSectionsRespectCalendarDaysAndDoNotInventDates() {
        val now = LocalDate.of(2026, 10, 18)
        val zone = ZoneId.of("UTC")
        fun section(day: String?) = NewEpisodesProjection.section(day, now, zone)
        assertEquals("היום", section("2026-10-18")); assertEquals("אתמול", section("2026-10-17"))
        assertEquals("השבוע", section("2026-10-15")); assertEquals("מוקדם יותר", section("2026-09-12"))
        assertEquals("פרקים נוספים", section(null)); assertEquals("בקרוב", section("2026-10-19"))
    }
    @Test fun fullCatalogSurvivesCacheReopeningWithExactGuidIncludingColons() {
        val episodes = (1..40).map { episode(guid = "https://feed.test/id:$it?foo=bar") }
        val source = catalog("a", *episodes.toTypedArray())
        val restored = NewEpisodesCatalogCodec.decode(NewEpisodesCatalogCodec.encode(source), "a")!!
        assertEquals(source, restored)
        assertNull(NewEpisodesCatalogCodec.decode(NewEpisodesCatalogCodec.encode(source), "other"))
        assertNull(NewEpisodesCatalogCodec.decode("bad", "a"))
    }
    @Test fun cachedEntriesCannotSubstituteAnotherShowOrUrl() {
        val source = catalog("a", episode(), episode("b"), episode(guid = "x").copy(playUrl = "song/music"))
        assertEquals(listOf(episode()), NewEpisodesCatalogCodec.decode(NewEpisodesCatalogCodec.encode(source), "a")!!.episodes)
    }
    @Test fun undoIsExactDurableAndExpiresAtSixSeconds() {
        var clock = 1000L
        val disk = mutableMapOf<String, String>()
        fun store() = EpisodeCompletionStore({ disk[it] }, { key, value -> if (value == null) disk.remove(key) else disk[key] = value; true }, { clock })
        val undo = store().complete(episode())!!
        assertEquals(7000L, undo.expiresAt)
        clock = 6999
        assertTrue(store().undo(undo)); assertTrue(disk.isEmpty())
        val next = store().complete(episode())!!
        clock = next.expiresAt
        assertFalse(store().undo(next)); assertTrue(disk.containsKey(next.key))
    }
    @Test fun undoRestoresTheSameFeedAndDoesNotAlterProgressOrAnotherCompletion() {
        val a = episode(); val b = episode(guid = "b")
        val disk = mutableMapOf<String, String>()
        val store = EpisodeCompletionStore({ disk[it] }, { key, value -> if (value == null) disk.remove(key) else disk[key] = value; true }, { 0 })
        val bookmarks = mapOf(a.id to EpisodeListening(30, 100), b.id to EpisodeListening(40, 100))
        fun feed() = project(listOf(catalog("a", a, b)), history = listOf(a, b), completed = disk.keys) { bookmarks.getValue(it.id) }
        val before = feed()
        val aUndo = store.complete(a)!!
        assertEquals(listOf(b), feed().continuing.map { it.episode })
        assertTrue(store.undo(aUndo)); assertEquals(before, feed())
        val aAgain = store.complete(a)!!; store.complete(b)
        assertTrue(store.undo(aAgain)); assertEquals(listOf(a), feed().continuing.map { it.episode })
        assertEquals(30L, feed().continuing.single().listening.positionMs)
    }
    @Test fun staleUndoCannotEraseNewDecisionAndFailedWriteDoesNotOfferUndo() {
        val disk = mutableMapOf<String, String>()
        val store = EpisodeCompletionStore({ disk[it] }, { key, value -> if (value == null) disk.remove(key) else disk[key] = value; true }, { 0 })
        val old = store.complete(episode())!!
        disk[old.key] = "new-decision"
        assertFalse(store.undo(old)); assertEquals("new-decision", disk[old.key])
        assertNull(EpisodeCompletionStore({ null }, { _, _ -> false }, { 0 }).complete(episode()))
        assertNull(store.complete(episode().copy(playUrl = "song/17")))
    }
    @Test fun codeOnlyUsesTheExactSpotifyEpisodeAndNeverItsShowId() {
        val id = "1234567890123456789012"
        val ep = episode().copy(id = "spotify:episode:$id", playUrl = "spotify:episode:$id", spotifyEpisodeId = id)
        assertEquals(id, exactSpotifyEpisodeId(ep))
        assertNull(exactSpotifyEpisodeId(episode().copy(showId = id)))
        assertNull(exactSpotifyEpisodeId(ep.copy(spotifyEpisodeId = "abcdefghijklmnopqrstuv")))
    }
    @Test fun downloadRequiresUniqueExactGuidAndAudioEnclosure() {
        val rss = PodcastRssEpisode("guid", "same title", null, 120_000, "https://audio.test/file.mp3", "audio/mpeg", null)
        assertEquals(rss, exactDownloadEnclosure(episode(), listOf(rss)))
        assertNull(exactDownloadEnclosure(episode(), listOf(rss.copy(guid = "other"))))
        assertNull(exactDownloadEnclosure(episode(), listOf(rss, rss)))
        assertNull(exactDownloadEnclosure(episode(), listOf(rss.copy(enclosureType = "video/mp4"))))
        assertNull(exactDownloadEnclosure(episode(), listOf(rss.copy(enclosureUrl = "file:///private/file.mp3"))))
    }
}
