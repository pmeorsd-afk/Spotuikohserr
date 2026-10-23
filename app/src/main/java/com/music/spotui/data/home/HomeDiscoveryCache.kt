package com.music.spotui.data.home

import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.local.TrackListenStat
import org.json.JSONObject

internal object HomeDiscoveryCache {
    const val RANKING_VERSION = 2
    fun decode(root: JSONObject?, history: List<TrackListenStat>, now: Long): PersonalHomeCatalog? {
        if (root == null || root.optInt("version") != 1) return null
        val age = now - root.optLong("at")
        if (age !in 0..7 * 86_400_000L) return null
        val seeds = root.optJSONArray("seeds") ?: return null
        val ids = (0 until seeds.length()).map { seeds.optString(it) }.toSet()
        if (!HomeRecommendationPolicy.ready(history.filter { it.trackId in ids })) return null
        fun items(key: String): List<HomeItem.Playlist> {
            val array = root.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let {
                HomeFeedItemCodec.decode(it) as? HomeItem.Playlist
            } }.filter { HomeRecommendationPolicy.validId(it.id) && it.name.isNotBlank() }.distinctBy { it.id }
        }
        return PersonalHomeCatalog(items("mixes"), items("radio"), items("history"))
    }

    fun fresh(root: JSONObject?, history: List<TrackListenStat>, now: Long): Boolean {
        if (decode(root, history, now) == null) return false
        // Recompute old overlap-filtered history shelves, retaining their cached content while offline.
        if (root!!.optInt("rankingVersion", 1) != RANKING_VERSION) return false
        val age = now - root.optLong("at")
        return age < 30 * 60_000L ||
            (root.optString("signature") == HomeRecommendationPolicy.fingerprint(history) && age < 6 * 3_600_000L)
    }
}
