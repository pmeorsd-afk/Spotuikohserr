package com.music.spotui.data.home

import com.music.spotui.data.entity.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeFeedRecoveryTest {
    private fun id(n: Int) = n.toString().padStart(22, '0')
    private fun playlist(n: Int) = HomeItem.Playlist("Playlist $n", "", "", id(n))
    private val recents = HomeSection(HomeSectionIds.RECENTLY_PLAYED, "לאחרונה", items = listOf(HomeItem.LikedSongs(3)))
    private val mixes = HomeSection(HomeSectionIds.TOP_MIXES, "מיקסים", items = listOf(playlist(1)))
    private val radio = HomeSection(HomeSectionIds.POPULAR_RADIO, "רדיו פופולרי", items = listOf(playlist(2)))
    private val albums = HomeSection(HomeSectionIds.POPULAR_ALBUMS, "אלבומים וסינגלים פופולריים",
        items = listOf(HomeItem.Album("Album", "", "", spotifyId = id(3))))
    private val history = HomeSection(HomeSectionIds.HISTORY_BASED, "History", items = listOf(playlist(4)))

    @Test fun localHomeIsDeliveredBeforeAnUnfinishedNetworkRequest() = runBlocking {
        val pending = CompletableDeferred<HomeFeedModel>()
        val visible = mutableListOf<HomeFeedModel>()
        val local = HomeFeedModel(sections = listOf(mixes, recents))
        val remote = HomeFeedModel(sections = listOf(mixes, radio, recents, albums))
        val job = async(start = CoroutineStart.UNDISPATCHED) {
            HomeFeedDelivery.refresh({ local }, { pending.await() }, visible::add)
        }
        assertEquals(listOf(local), visible)
        assertFalse(job.isCompleted)
        pending.complete(remote)
        assertEquals(remote, job.await())
        assertEquals(listOf(local, remote), visible)
    }

    @Test fun failedRemoteLoadLeavesPublishedLocalHomeIntact() = runBlocking {
        val local = HomeFeedModel(sections = listOf(recents, radio))
        val visible = mutableListOf<HomeFeedModel>()
        val failure = runCatching { HomeFeedDelivery.refresh({ local }, { throw IOException("offline") }, visible::add) }
        assertTrue(failure.isFailure)
        assertEquals(listOf(local), visible)
    }

    @Test fun firstRealCatalogUsesCanonicalOrderAfterLocalRecents() {
        val next = HomeFeedModel(sections = listOf(mixes, radio, recents, albums))
        assertEquals(next, HomeRecommendationPolicy.keepVisibleDiscovery(next, HomeFeedModel(sections = listOf(recents))))
    }

    @Test fun lateRadioHistoryAndAlbumsAppearDuringSameVisitWithoutMovingExistingRows() {
        val visible = HomeFeedModel(sections = listOf(mixes, recents))
        val replacementMixes = mixes.copy(items = listOf(playlist(99)))
        val next = HomeFeedModel(sections = listOf(replacementMixes, radio, recents, history, albums))
        val merged = HomeRecommendationPolicy.keepVisibleDiscovery(next, visible)
        assertEquals(visible.sections, merged.sections.take(2))
        assertEquals(listOf(radio, history, albums), merged.sections.drop(2))
        assertEquals(merged, HomeRecommendationPolicy.keepVisibleDiscovery(next, merged))
    }

    @Test fun anEmptyVisibleShelfDoesNotSuppressItsFirstContent() {
        val visible = HomeFeedModel(sections = listOf(mixes, radio.copy(items = emptyList())))
        val merged = HomeRecommendationPolicy.keepVisibleDiscovery(visible.copy(sections = listOf(mixes, radio)), visible)
        assertEquals(radio, merged.sections[1])
    }

    @Test fun failedPublicRefreshRetainsVerifiedRadioAndAlbums() {
        val old = HomeFeedModel(sections = listOf(mixes, radio, recents, albums))
        val patch = HomeDiscoveryPresentation.apply(old, PublicHomeCatalog(), PersonalHomeCatalog())
        assertTrue(patch.sections.containsAll(listOf(radio, albums)))
        val partial = HomeFeedModel(sections = listOf(mixes, recents))
        val refresh = HomeDiscoveryPresentation.retainKnownGeneral(partial, old)
        assertEquals(listOf(mixes, radio, recents, albums), refresh.sections)
    }

    @Test fun freshCatalogReplacesOldGeneralItemsAndLegacyAlbumsStayExcluded() {
        val old = HomeFeedModel(sections = listOf(radio, albums))
        val updatedAlbums = albums.copy(items = listOf(HomeItem.Album("New", "", "", spotifyId = id(8))))
        val fresh = HomeFeedModel(sections = listOf(radio, updatedAlbums))
        assertEquals(fresh, HomeDiscoveryPresentation.retainKnownGeneral(fresh, old))
        val migrated = HomeDiscoveryPresentation.migrateLegacy(old)
        assertTrue(HomeDiscoveryPresentation.retainKnownGeneral(HomeFeedModel(), migrated).sections.isEmpty())
    }

    @Test fun clearingTasteCannotRetainPersonalRadioAsPopularRadio() {
        val old = HomeFeedModel(sections = listOf(radio.copy(title = "רדיו בשבילכם"), history))
        val cleared = HomeDiscoveryPresentation.apply(old, PublicHomeCatalog(), PersonalHomeCatalog())
        assertTrue(cleared.sections.none { it.id == HomeSectionIds.POPULAR_RADIO || it.id == HomeSectionIds.HISTORY_BASED })
        assertTrue(HomeDiscoveryPresentation.retainKnownGeneral(HomeFeedModel(), old).sections.isEmpty())
    }

    @Test fun aSmallVerifiedPoolStillHasARecentHistoryShelfDespiteMixOverlap() {
        val artist = CatalogArtist(id(50), "Exact artist")
        val candidate = CatalogPlaylist(playlist(1), listOf(CatalogTrack(id(10), listOf(artist)),
            CatalogTrack(id(11), listOf(artist))), radio = false)
        val taste = HomeTaste(mapOf(artist.id to 1.0), mapOf(artist.id to 1.0))
        val result = HomeRecommendationPolicy.compose(taste, listOf(candidate))
        assertEquals(listOf(candidate.item), result.mixes)
        assertEquals(listOf(candidate.item), result.history)
        assertTrue(HomeRecommendationPolicy.compose(taste, listOf(candidate.copy(tracks = candidate.tracks.take(1)))).history.isEmpty())
    }
}
