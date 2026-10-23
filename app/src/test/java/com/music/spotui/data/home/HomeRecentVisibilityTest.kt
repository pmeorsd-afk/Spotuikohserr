package com.music.spotui.data.home

import com.music.spotui.data.entity.*
import com.music.spotui.ui.screens.visibleHomeSections
import org.junit.Assert.*
import org.junit.Test

class HomeRecentVisibilityTest {
    private val recent = HomeSection(HomeSectionIds.RECENTLY_PLAYED, "לאחרונה", items = listOf(HomeItem.LikedSongs(0)))
    private val radio = HomeSection(HomeSectionIds.POPULAR_RADIO, "רדיו", items = listOf(HomeItem.Playlist("radio", "", "", "id")))
    private val track = HomeItem.Track(SongsModel().copy(title = "Exact track", url = "youtube:exact"))

    @Test fun guestHasNoEmptyRecentlyHeaderLikedCardOrGap() {
        assertEquals(listOf(radio), visibleHomeSections(listOf(radio, recent)))
        assertTrue(visibleHomeSections(listOf(recent.copy(items = emptyList()))).isEmpty())
    }
    @Test fun firstSuccessfulListenRevealsExactItemWithoutRestart() {
        val old = HomeFeedModel(sections = listOf(radio, recent))
        assertEquals(listOf(radio), visibleHomeSections(old.sections))
        val next = old.copy(sections = listOf(radio, recent.copy(items = recent.items + track)))
        val published = HomeRecommendationPolicy.keepVisibleDiscovery(next, old)
        assertEquals(next.sections, visibleHomeSections(published.sections))
        assertSame(track, visibleHomeSections(published.sections).last().items.last())
    }
    @Test fun firstLikeRevealsShelfAndLastRemovalHidesItWhenNoHistory() {
        val source = HomeFeedModel(sections = listOf(radio, recent))
        val saved = HomeDiscoveryPresentation.withLikedCount(source, 1)
        assertEquals(2, visibleHomeSections(saved.sections).size)
        assertEquals(listOf(radio), visibleHomeSections(HomeDiscoveryPresentation.withLikedCount(saved, 0).sections))
        assertEquals(2, source.sections.size) // Hidden data remains available for the next reactive update.
    }
    @Test fun savedPlaylistWithoutListeningStillCountsAsPersonalContent() {
        val withSaved = recent.copy(items = recent.items + HomeItem.Playlist("mine", "", "", "playlist"))
        assertEquals(listOf(withSaved), visibleHomeSections(listOf(withSaved)))
    }
    @Test fun podcastListeningAlsoRevealsRecentlyAndRetainsIdentity() {
        val episode = track.copy(song = track.song.copy(mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show"))
        val section = recent.copy(items = recent.items + episode)
        assertSame(episode, visibleHomeSections(listOf(section)).single().items.last())
    }
    @Test fun otherShelvesAndTheirOrderAreNotChanged() {
        val empty = HomeSection("other", "Other", items = emptyList())
        assertEquals(listOf(empty, radio), visibleHomeSections(listOf(empty, recent, radio)))
    }
}
