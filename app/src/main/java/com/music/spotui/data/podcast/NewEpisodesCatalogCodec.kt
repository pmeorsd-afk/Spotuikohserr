package com.music.spotui.data.podcast

import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import org.json.JSONArray
import org.json.JSONObject

/** Separate, versioned full-feed snapshot; never changes Home's bounded discovery cache. */
internal object NewEpisodesCatalogCodec {
    fun encode(catalog: PodcastCatalog): String = JSONObject().apply {
        put("version", 1); put("updatedAt", catalog.updatedAt)
        put("showId", catalog.show.id); put("showName", catalog.show.name)
        put("publisher", catalog.show.publisher); put("cover", catalog.show.coverUri)
        put("episodes", JSONArray().apply {
            catalog.episodes.forEach { ep -> put(JSONObject().apply {
                put("id", ep.id); put("numericId", ep.numericId); put("title", ep.title)
                put("description", ep.description); put("pubDate", ep.pubDate)
                put("duration", ep.durationMs); put("cover", ep.coverUri); put("url", ep.playUrl)
                put("showId", ep.showId); put("showName", ep.showName); put("publisher", ep.publisher)
                put("spotifyId", ep.spotifyEpisodeId)
            }) }
        })
    }.toString()

    fun decode(raw: String?, expectedShowId: String): PodcastCatalog? = runCatching {
        if (raw.isNullOrBlank() || raw.length > 4_000_000) return null
        val root = JSONObject(raw)
        if (root.optInt("version") != 1 || root.getString("showId") != expectedShowId) return null
        val show = PodcastModel(expectedShowId, root.getString("showName"), root.optString("publisher"), root.optString("cover"))
        val array = root.getJSONArray("episodes")
        val episodes = (0 until array.length()).mapNotNull { index -> runCatching {
            val ep = array.getJSONObject(index)
            PodcastEpisodeUiModel(id = ep.getString("id"), numericId = ep.getInt("numericId"), title = ep.getString("title"),
                description = ep.optString("description").takeIf { it.isNotBlank() },
                pubDate = ep.optString("pubDate").takeIf { it.isNotBlank() }, durationMs = ep.optLong("duration").coerceAtLeast(0),
                coverUri = ep.optString("cover"), playUrl = ep.getString("url"), showId = ep.getString("showId"),
                showName = ep.getString("showName"), publisher = ep.optString("publisher"), spotifyEpisodeId = ep.optString("spotifyId"))
                .takeIf { it.showId == expectedShowId && it.hasExactIdentity() }
        }.getOrNull() }.distinctBy(NewEpisodesProjection::key)
        PodcastCatalog(show, episodes, root.getLong("updatedAt"))
    }.getOrNull()
}
