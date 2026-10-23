package com.music.spotui.data.home

import android.content.Context
import com.music.spotui.data.entity.HomeItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Isolated, cookie-free catalog reads. Never changes the shared Spotify session or playback client. */
@Singleton
class PublicSpotifyHomeSource @Inject constructor(@ApplicationContext context: Context) {
    private val directory = File(context.cacheDir, "home_public_catalog_v1")
    private val http = PublicSpotifyCatalogHttp()
    private val retryAfter = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val homeMutex = Mutex()

    /** Disk-only startup path; never waits for a public website response. */
    internal fun cachedHome(): PublicHomeCatalog {
        val stored = runCatching { JSONObject(File(directory, "home.json").readText()) }.getOrNull()
        val age = System.currentTimeMillis() - (stored?.optLong("at") ?: 0)
        return stored?.optJSONObject("data")?.takeIf { age in 0..7 * 86_400_000L }
            ?.let(PublicSpotifyHomeParser::home) ?: PublicHomeCatalog()
    }

    internal suspend fun home(): PublicHomeCatalog = homeMutex.withLock { load("home", "/intl-he", {
        it.optJSONObject("home")?.optJSONObject("data")
    }) { PublicSpotifyHomeParser.home(it).let { data -> data.albums.isNotEmpty() || data.radio.isNotEmpty() } }
        ?.let(PublicSpotifyHomeParser::home) ?: PublicHomeCatalog() }

    internal suspend fun track(id: String): CatalogTrack? {
        if (!HomeRecommendationPolicy.validId(id)) return null
        return entity("track", id) { PublicSpotifyHomeParser.track(it, id) != null }
            ?.let { PublicSpotifyHomeParser.track(it, id) }
    }

    internal suspend fun artistPlaylists(id: String): List<HomeItem.Playlist> {
        if (!HomeRecommendationPolicy.validId(id)) return emptyList()
        return entity("artist", id) { it.optString("uri") == "spotify:artist:$id" && it.optString("__typename") == "Artist" }
            ?.let { PublicSpotifyHomeParser.artistPlaylists(it, id) }.orEmpty()
    }

    internal suspend fun playlist(id: String): CatalogPlaylist? {
        if (!HomeRecommendationPolicy.validId(id)) return null
        return entity("playlist", id) { PublicSpotifyHomeParser.playlist(it, id) != null }
            ?.let { PublicSpotifyHomeParser.playlist(it, id) }
    }

    private suspend fun entity(kind: String, id: String, valid: (JSONObject) -> Boolean) =
        load("${kind}_$id", "/intl-he/$kind/$id", {
            it.optJSONObject("entities")?.optJSONObject("items")?.optJSONObject("spotify:$kind:$id")
        }, valid)

    private suspend fun load(key: String, path: String, extract: (JSONObject) -> JSONObject?,
                             valid: (JSONObject) -> Boolean): JSONObject? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val file = File(directory, "$key.json")
        val stored = runCatching { JSONObject(file.readText()) }.getOrNull()
        val age = now - (stored?.optLong("at") ?: 0)
        val previous = stored?.optJSONObject("data")?.takeIf { age in 0..7 * 86_400_000L && valid(it) }
        if (previous != null && age < 6 * 3_600_000L) return@withContext previous
        if (now < (retryAfter[key] ?: 0L)) return@withContext previous
        try {
            coroutineContext.ensureActive()
            val page = http.read(path)
            coroutineContext.ensureActive()
            val data = extract(page)?.takeIf(valid) ?: error("Catalog schema or identity changed")
            directory.mkdirs()
            val temporary = File(directory, "$key.tmp")
            temporary.writeText(JSONObject().put("at", now).put("data", data).toString())
            if (!temporary.renameTo(file)) temporary.delete()
            // Only public entity metadata is cached, never sessions, cookies or page scripts.
            if ((directory.listFiles()?.size ?: 0) > 160) directory.listFiles().orEmpty()
                .filter { it.extension == "json" }.sortedBy { it.lastModified() }.take(20).forEach { it.delete() }
            data
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            android.util.Log.w("HomeCatalog", "$key unavailable: ${error.message}")
            retryAfter[key] = now + 5 * 60_000L
            previous
        }
    }
}

internal object PublicSpotifyHomeParser {
    private val stateScript = Regex("<script\\b(?=[^>]*\\bid=[\"']initialState[\"'])[^>]*>(.*?)</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val radioName = Regex("(?:^|[\\s•|:–-])(?:radio|רדיו)(?:$|[\\s•|:–-])", RegexOption.IGNORE_CASE)
    private const val ALBUMS = "spotify:section:0JQ5DAnM3wGh0gz1MXnu3B"
    private const val RADIO = "spotify:section:0JQ5DAnM3wGh0gz1MXnu4h"
    private const val CHARTS = "spotify:section:0JQ5DAzQHECxDlYNI6xD1g"

    fun page(html: String): JSONObject? = runCatching {
        val encoded = stateScript.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val state = JSONObject(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
        state.takeIf { it.optJSONObject("session")?.optBoolean("isAnonymous", false) == true }
    }.getOrNull()

    fun home(data: JSONObject): PublicHomeCatalog {
        fun items(uri: String): List<JSONObject> = data.optJSONArray("sections").objects()
            .singleOrNull { it.optString("uri") == uri }?.optJSONArray("items").objects()
        val albums = items(ALBUMS).mapNotNull { item ->
            val id = id(item.optString("uri"), "album") ?: return@mapNotNull null
            val name = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HomeItem.Album(name, item.optString("imageUrl"), "", spotifyId = id)
        }.distinctBy { it.spotifyId }
        fun playlists(uri: String) = items(uri).mapNotNull { item ->
            val id = id(item.optString("uri"), "playlist") ?: return@mapNotNull null
            val name = item.optString("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            HomeItem.Playlist(name, item.optString("imageUrl"), "", id)
        }.distinctBy { it.id }
        return PublicHomeCatalog(albums, playlists(RADIO), playlists(CHARTS))
    }

    fun track(data: JSONObject, expectedId: String): CatalogTrack? {
        if (data.optString("__typename") != "Track" || id(data.optString("uri"), "track") != expectedId) return null
        if (data.has("id") && data.optString("id") != expectedId) return null
        val artists = listOf("artists", "firstArtist", "otherArtists").flatMap {
            data.optJSONObject(it)?.optJSONArray("items").objects()
        }.mapNotNull { artist ->
            val artistId = id(artist.optString("uri"), "artist") ?: return@mapNotNull null
            val name = artist.optJSONObject("profile")?.optString("name").orEmpty()
            CatalogArtist(artistId, name)
        }.distinctBy { it.id }
        return CatalogTrack(expectedId, artists).takeIf { artists.isNotEmpty() }
    }

    fun artistPlaylists(data: JSONObject, expectedId: String): List<HomeItem.Playlist> {
        if (data.optString("uri") != "spotify:artist:$expectedId" || data.optString("__typename") != "Artist") return emptyList()
        return data.optJSONObject("relatedContent")?.optJSONObject("featuringV2")?.optJSONArray("items").objects()
            .mapNotNull { it.optJSONObject("data") }.mapNotNull(::playlistItem).distinctBy { it.id }
    }

    fun playlist(data: JSONObject, expectedId: String): CatalogPlaylist? {
        val item = playlistItem(data)?.takeIf { it.id == expectedId } ?: return null
        val content = data.optJSONObject("content") ?: return null
        val tracks = content.optJSONArray("items").objects().mapNotNull { wrapper ->
            val entry = wrapper.optJSONObject("itemV2")?.optJSONObject("data") ?: return@mapNotNull null
            val trackId = id(entry.optString("uri"), "track") ?: return@mapNotNull null
            track(entry, trackId)
        }.distinctBy { it.id }
        return CatalogPlaylist(item, tracks, radioName.containsMatchIn(item.name)).takeIf { tracks.isNotEmpty() }
    }

    private fun playlistItem(data: JSONObject): HomeItem.Playlist? {
        if (data.optString("__typename") != "Playlist") return null
        val id = id(data.optString("uri"), "playlist") ?: return null
        if (data.has("id") && data.optString("id") != id) return null
        val name = data.optString("name").takeIf { it.isNotBlank() } ?: return null
        val sources = data.optJSONObject("images")?.optJSONArray("items").objects()
            .flatMap { it.optJSONArray("sources").objects() }
        val image = sources.maxByOrNull { it.optInt("width", 0) }?.optString("url").orEmpty()
        val owner = data.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name").orEmpty()
        return HomeItem.Playlist(name, image, owner.takeIf { it.isNotBlank() }?.let { "Playlist • $it" }.orEmpty(), id)
    }

    private fun id(uri: String, kind: String): String? = uri.removePrefix("spotify:$kind:")
        .takeIf { uri.startsWith("spotify:$kind:") && HomeRecommendationPolicy.validId(it) }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList()
        else (0 until length()).mapNotNull { optJSONObject(it) }
}
