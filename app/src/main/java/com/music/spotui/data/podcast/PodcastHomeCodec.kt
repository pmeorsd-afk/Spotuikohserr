package com.music.spotui.data.podcast

import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.entity.PodcastModel
import org.json.JSONArray
import org.json.JSONObject

internal object PodcastHomeCodec {
    fun encode(catalogs: List<PodcastCatalog>): String = JSONObject().apply {
        put("version", 1)
        put("catalogs", JSONArray().apply {
            catalogs.takeLast(24).forEach { catalog -> put(JSONObject().apply {
                put("timestamp", catalog.updatedAt)
                put("show", JSONObject().apply {
                    put("id", catalog.show.id); put("name", catalog.show.name)
                    put("publisher", catalog.show.publisher); put("cover", catalog.show.coverUri)
                    put("topics", JSONArray(catalog.show.topics))
                })
                put("episodes", JSONArray().apply {
                    catalog.episodes.take(8).forEach { ep -> put(JSONObject().apply {
                        put("id", ep.id); put("numericId", ep.numericId); put("title", ep.title)
                        put("description", ep.description?.take(4000)); put("date", ep.pubDate)
                        put("dateText", ep.pubDateFormatted); put("duration", ep.durationMs)
                        put("durationText", ep.durationFormatted); put("metadata", ep.formattedMetadata)
                        put("cover", ep.coverUri); put("url", ep.playUrl); put("showId", ep.showId)
                        put("showName", ep.showName); put("publisher", ep.publisher)
                        put("spotifyId", ep.spotifyEpisodeId)
                    }) }
                })
            }) }
        })
    }.toString()

    fun decode(raw: String?): List<PodcastCatalog> = runCatching {
        if (raw.isNullOrBlank() || raw.length > 2_000_000) return emptyList()
        val root = JSONObject(raw)
        if (root.optInt("version") != 1) return emptyList()
        val catalogs = root.getJSONArray("catalogs")
        (0 until minOf(catalogs.length(), 24)).mapNotNull { index -> runCatching {
            val obj = catalogs.getJSONObject(index); val s = obj.getJSONObject("show")
            val topics = s.optJSONArray("topics") ?: JSONArray()
            val show = PodcastModel(s.getString("id"), s.getString("name"), s.optString("publisher"), s.optString("cover"),
                topics = (0 until topics.length()).map { topics.getString(it) })
            if (show.id.isBlank() || show.name.isBlank()) return@mapNotNull null
            val list = obj.getJSONArray("episodes")
            val episodes = (0 until minOf(list.length(), 8)).mapNotNull { n -> runCatching {
                val e = list.getJSONObject(n)
                PodcastEpisodeUiModel(
                    id = e.getString("id"), numericId = e.getInt("numericId"), title = e.getString("title"),
                    description = e.optString("description"), pubDate = e.optString("date"),
                    pubDateFormatted = e.optString("dateText"), durationMs = e.optLong("duration").coerceAtLeast(0),
                    durationFormatted = e.optString("durationText"), formattedMetadata = e.optString("metadata"),
                    coverUri = e.optString("cover"), playUrl = e.getString("url"), showId = e.getString("showId"),
                    showName = e.getString("showName"), publisher = e.optString("publisher"), spotifyEpisodeId = e.optString("spotifyId"),
                ).takeIf { it.showId == show.id && it.hasExactIdentity() }
            }.getOrNull() }.distinctBy { it.id }
            PodcastCatalog(show, episodes, obj.getLong("timestamp"))
        }.getOrNull() }
    }.getOrDefault(emptyList())
}
