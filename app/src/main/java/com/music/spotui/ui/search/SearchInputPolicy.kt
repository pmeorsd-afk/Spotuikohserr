package com.music.spotui.ui.search

import org.json.JSONArray
import java.util.Locale

/** Normalize only requests, never the editable value or its trailing spaces/caret. */
internal fun searchRequestText(text: String) = text.trim().replace(Regex("\\s+"), " ")

internal class LatestSearchRequest {
    private var generation = 0L
    @Synchronized fun begin(): Long = ++generation
    @Synchronized fun publish(ticket: Long, action: () -> Unit) {
        if (ticket == generation) action()
    }
}

internal object SearchSuggestions {
    fun request(query: String): okhttp3.Request = okhttp3.Request.Builder()
        .url("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=he&gl=IL&q=" +
            java.net.URLEncoder.encode(searchRequestText(query), "UTF-8"))
        .header("User-Agent", "Mozilla/5.0").build()

    fun fetch(client: okhttp3.OkHttpClient, query: String): List<String> = client.newCall(request(query)).execute().use {
        if (!it.isSuccessful) emptyList() else parse(it.body?.string().orEmpty(), query)
    }
    fun parse(body: String, query: String): List<String> = runCatching {
        val response = JSONArray(body)
        if (!searchRequestText(response.optString(0)).equals(searchRequestText(query), true)) return emptyList()
        val suggestions = response.optJSONArray(1) ?: return emptyList()
        (0 until suggestions.length()).mapNotNull { suggestions.opt(it) as? String }
            .map(::searchRequestText).filter { it.isNotBlank() && it.length <= 120 }
            .distinctBy { it.lowercase(Locale.ROOT) }.take(5)
    }.getOrDefault(emptyList())
}
