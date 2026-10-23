package com.music.spotui.data.home

import com.music.spotui.data.entity.*
import com.music.spotui.data.local.TrackListenStat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeDiscoveryCacheTest {
    private val now = 1_800_000_000_000L
    private fun id(n: Int) = n.toString().padStart(22, '0')
    private val history = listOf(TrackListenStat(id(1), "One", "Artist", "", 2, 0, now),
        TrackListenStat(id(2), "Two", "Artist", "", 1, 0, now))
    private val item = HomeItem.Playlist("Real playlist", "https://art.test/exact", "", id(10))
    private fun stored(age: Long = 0) = JSONObject().put("version", 1).put("at", now-age)
        .put("rankingVersion", 2)
        .put("signature", HomeRecommendationPolicy.fingerprint(history)).put("seeds", JSONArray(history.map { it.trackId }))
        .put("mixes", JSONArray().put(HomeFeedItemCodec.encode(item)))

    @Test fun recreationPreservesExactPlaylistAndCover() {
        val serialized = stored().toString()
        assertEquals(item, HomeDiscoveryCache.decode(JSONObject(serialized), history, now)!!.mixes.single())
    }
    @Test fun offlineCanUseVerifiedCatalogForUpToSevenDays() {
        assertNotNull(HomeDiscoveryCache.decode(stored(3*86_400_000L), history, now))
        assertNull(HomeDiscoveryCache.decode(stored(8*86_400_000L), history, now))
    }
    @Test fun clearingHistoryImmediatelyRemovesPersonalRecommendations() {
        assertNull(HomeDiscoveryCache.decode(stored(), emptyList(), now))
        assertFalse(HomeDiscoveryCache.fresh(stored(), emptyList(), now))
    }
    @Test fun unrelatedHistoryCannotReusePreviousProfileEvenWithinCooldown() {
        val other = history.mapIndexed { index, stat -> stat.copy(trackId = id(index+20)) }
        assertNull(HomeDiscoveryCache.decode(stored(), other, now))
        assertFalse(HomeDiscoveryCache.fresh(stored(), other, now))
    }
    @Test fun onlyOneRemainingSeedIsNotEnoughToReuseOldTaste() {
        assertNull(HomeDiscoveryCache.decode(stored(), history.take(1), now))
    }
    @Test fun sameHistoryDoesNotRefetchOnEveryHomeVisit() {
        assertTrue(HomeDiscoveryCache.fresh(stored(5*3_600_000L), history, now))
        assertFalse(HomeDiscoveryCache.fresh(stored(6*3_600_000L), history, now))
    }
    @Test fun newListeningUpdatesAfterCooldown() {
        val changed = history.map { it.copy(playCount = it.playCount+1) }
        assertTrue(HomeDiscoveryCache.fresh(stored(10*60_000L), changed, now))
        assertFalse(HomeDiscoveryCache.fresh(stored(31*60_000L), changed, now))
    }
    @Test fun futureOrUnknownVersionCacheDoesNotHideRefresh() {
        assertNull(HomeDiscoveryCache.decode(stored(-1000), history, now))
        assertNull(HomeDiscoveryCache.decode(stored().put("version", 99), history, now))
    }
    @Test fun oldRankingRefreshesImmediatelyButRemainsUsableOffline() {
        val legacy = stored().apply { remove("rankingVersion") }
        assertNotNull(HomeDiscoveryCache.decode(legacy, history, now))
        assertFalse(HomeDiscoveryCache.fresh(legacy, history, now))
    }
    @Test fun cachedEpisodesAndAlbumsCannotBecomeMixes() {
        val root = stored().put("mixes", JSONArray().put(HomeFeedItemCodec.encode(HomeItem.Album("Album", "", "", spotifyId = id(1)))))
        assertTrue(HomeDiscoveryCache.decode(root, history, now)!!.mixes.isEmpty())
    }
    @Test fun updatingLikedCountPreservesPreparedDiscoverySnapshot() {
        val prepared = HomeFeedModel(sections = listOf(HomeSection(HomeSectionIds.TOP_MIXES, "Prepared", items = listOf(item)),
            HomeSection(HomeSectionIds.RECENTLY_PLAYED, "Recent", items = listOf(HomeItem.LikedSongs(1)))))
        val updated = HomeDiscoveryPresentation.withLikedCount(prepared, 2)
        assertEquals(prepared.sections[0], updated.sections[0])
        assertEquals(2, (updated.sections[1].items.single() as HomeItem.LikedSongs).count)
    }
}
