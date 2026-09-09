package com.music.spotui.util

import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.RecentItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Central Kosher Whitelist Manager.
 *
 * Enforces airtight kosher image filtering across SpotUI:
 * - Default: All images are blocked and rendered with a sleek music placeholder.
 * - Whitelisted: Artists and tracks on the whitelist have their artwork allowed.
 * - Auto-sync: Fetches the latest whitelist from GitHub raw in the background and caches it locally.
 */
object KosherWhitelistManager {

    private const val REMOTE_WHITELIST_URL =
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/whitelist.json"
    private const val CACHE_FILE_NAME = "whitelist_cache.json"

    // In-memory whitelists (O(1) lookups)
    private val whitelistedArtistIds = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedArtistNames = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedTrackIds = ConcurrentHashMap.newKeySet<String>()

    // Dynamically registered allowed image URLs (so that once a song/artist is verified, its image loads anywhere)
    private val allowedImageUrls = ConcurrentHashMap.newKeySet<String>()

    // Compose state to trigger recomposition when the whitelist updates from network
    private val _versionState = mutableIntStateOf(0)
    val versionState: State<Int> get() = _versionState

    private var initialized = false

    /**
     * Initializes the manager:
     * 1. Loads bundled assets/whitelist.json
     * 2. Overlays cached whitelist_cache.json (if present)
     * 3. Fetches the latest whitelist from GitHub in the background
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            // Step 1: Load bundled assets
            runCatching {
                app.assets.open("whitelist.json").use { stream ->
                    val jsonStr = stream.bufferedReader().readText()
                    parseWhitelistJson(jsonStr, isRemote = false)
                }
            }

            // Step 2: Load local cache from app files directory
            val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                runCatching {
                    val cachedStr = cacheFile.readText()
                    parseWhitelistJson(cachedStr, isRemote = false)
                }
            }

            // Step 3: Fetch latest from GitHub raw in background
            syncWithRemote(app)
        }
    }

    /**
     * Synchronizes whitelist with remote GitHub repository.
     */
    fun syncWithRemote(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var updated = false
            try {
                val url = URL(REMOTE_WHITELIST_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    useCaches = false
                    setRequestProperty("User-Agent", "SpotUI-Kosher/1.0")
                }

                if (conn.responseCode in 200..299) {
                    val remoteJson = conn.inputStream.bufferedReader().readText()
                    if (remoteJson.isNotBlank() && remoteJson.contains("artists")) {
                        // Persist to local cache
                        val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
                        cacheFile.writeText(remoteJson)

                        // Update in-memory sets
                        parseWhitelistJson(remoteJson, isRemote = true)
                        updated = true
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            withContext(Dispatchers.Main) {
                if (updated) {
                    _versionState.intValue += 1
                }
                onComplete?.invoke(updated)
            }
        }
    }

    private fun normalize(str: String?): String {
        return str?.trim()?.lowercase(Locale.ROOT) ?: ""
    }

    /**
     * Parses the JSON structure into the in-memory sets.
     */
    @Synchronized
    private fun parseWhitelistJson(jsonStr: String, isRemote: Boolean) {
        try {
            val root = JSONObject(jsonStr)

            // Artists
            val artistsArray = root.optJSONArray("artists") ?: JSONArray()
            for (i in 0 until artistsArray.length()) {
                val obj = artistsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = normalize(obj.optString("name"))
                if (id.isNotBlank()) whitelistedArtistIds.add(id)
                if (name.isNotBlank()) whitelistedArtistNames.add(name)
            }

            // Tracks
            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracksArray.length()) {
                val obj = tracksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isNotBlank()) whitelistedTrackIds.add(id)
            }

            // Blocked tracks (exceptions)
            val blockedArray = root.optJSONArray("blocked_tracks") ?: JSONArray()
            for (i in 0 until blockedArray.length()) {
                val obj = blockedArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isNotBlank()) blockedTrackIds.add(id)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Checks if an artist is whitelisted by ID or name.
     */
    fun isArtistWhitelisted(artistId: String? = null, artistName: String? = null): Boolean {
        val cleanId = artistId?.trim() ?: ""
        if (cleanId.isNotBlank() && whitelistedArtistIds.contains(cleanId)) {
            return true
        }

        val normName = normalize(artistName)
        if (normName.isNotBlank()) {
            if (whitelistedArtistNames.contains(normName)) return true

            // Check comma-separated artists (e.g. "Ishay Ribo, Akiva")
            if (artistName != null && artistName.contains(",")) {
                val parts = artistName.split(",")
                for (p in parts) {
                    val normPart = normalize(p)
                    if (normPart.isNotBlank() && whitelistedArtistNames.contains(normPart)) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Checks if a track is whitelisted.
     * Rules:
     * 1. If in blocked_tracks -> always false (blocked).
     * 2. If trackId is in whitelistedTrackIds -> true.
     * 3. If the artist of this track is whitelisted -> true.
     */
    fun isTrackWhitelisted(
        trackId: String? = null,
        trackTitle: String? = null,
        artistName: String? = null
    ): Boolean {
        val cleanId = trackId?.trim() ?: ""
        if (cleanId.isNotBlank() && blockedTrackIds.contains(cleanId)) {
            return false
        }
        if (cleanId.isNotBlank() && whitelistedTrackIds.contains(cleanId)) {
            return true
        }
        if (isArtistWhitelisted(null, artistName)) {
            return true
        }
        return false
    }

    /**
     * Checks if a [SongsModel] is whitelisted, and if so, registers its cover URL as allowed.
     */
    fun isSongWhitelisted(song: SongsModel?): Boolean {
        if (song == null) return false
        val allowed = isTrackWhitelisted(
            trackId = song.spotifyTrackId,
            trackTitle = song.title,
            artistName = song.singer
        )
        if (allowed && song.coverUri.isNotBlank()) {
            allowImageUrl(song.coverUri)
        }
        return allowed
    }

    /**
     * Checks if an [ArtistsModel] is whitelisted, and if so, registers its image URL as allowed.
     */
    fun isArtistModelWhitelisted(artist: ArtistsModel?): Boolean {
        if (artist == null) return false
        val allowed = isArtistWhitelisted(artist.id, artist.name)
        if (allowed && artist.coverUri.isNotBlank()) {
            allowImageUrl(artist.coverUri)
        }
        return allowed
    }

    /**
     * Checks if an [AlbumsModel] is whitelisted, and if so, registers its cover URL as allowed.
     */
    fun isAlbumWhitelisted(album: AlbumsModel?): Boolean {
        if (album == null) return false
        val allowed = isArtistWhitelisted(null, album.artists)
        if (allowed && album.coverUri.isNotBlank()) {
            allowImageUrl(album.coverUri)
        }
        return allowed
    }

    /**
     * Checks if a [HomeItem] is whitelisted, and if so, registers its image URL as allowed.
     */
    fun isHomeItemWhitelisted(item: HomeItem?): Boolean {
        if (item == null) return false
        val allowed = when (item) {
            is HomeItem.Artist -> isArtistWhitelisted(item.id, item.name)
            is HomeItem.Album -> isArtistWhitelisted(null, item.artists.ifBlank { item.subtitle })
            is HomeItem.Playlist -> false
        }
        if (allowed && item.imageUrl.isNotBlank()) {
            allowImageUrl(item.imageUrl)
        }
        return allowed
    }

    /**
     * Checks if a RecentItem is whitelisted.
     */
    fun isRecentItemWhitelisted(recent: RecentItem?): Boolean {
        if (recent == null) return false
        val allowed = when (recent.type) {
            "artist" -> isArtistWhitelisted(recent.key, recent.name)
            "song" -> isTrackWhitelisted(recent.spotifyTrackId.ifBlank { recent.key }, recent.name, recent.singer)
            "album" -> isArtistWhitelisted(null, recent.singer)
            else -> false
        }
        if (allowed && recent.image.isNotBlank()) {
            allowImageUrl(recent.image)
        }
        return allowed
    }

    /**
     * Dynamically registers an image URL as allowed.
     */
    fun allowImageUrl(url: String?) {
        val clean = url?.trim() ?: return
        if (clean.isNotBlank()) {
            allowedImageUrls.add(clean)
        }
    }

    /**
     * Checks if an image URL is allowed.
     */
    fun isUrlAllowed(url: String?): Boolean {
        val clean = url?.trim() ?: return false
        return allowedImageUrls.contains(clean)
    }

    /**
     * Evaluates any model passed to GlideImage (typically a String URL).
     */
    fun isImageAllowed(model: Any?): Boolean {
        if (model == null) return false
        return when (model) {
            is String -> isUrlAllowed(model)
            else -> false
        }
    }

    /**
     * Returns current in-memory count of whitelisted artists.
     */
    fun getWhitelistedArtistsCount(): Int = whitelistedArtistNames.size + whitelistedArtistIds.size

    /**
     * Exports current whitelist as JSON string (useful for Admin mode or debugging).
     */
    fun exportWhitelistJson(): String {
        val root = JSONObject()
        root.put("version", 1)

        val artistsArr = JSONArray()
        for (id in whitelistedArtistIds) {
            artistsArr.put(JSONObject().apply {
                put("id", id)
                put("name", "")
            })
        }
        for (name in whitelistedArtistNames) {
            artistsArr.put(JSONObject().apply {
                put("id", "")
                put("name", name)
            })
        }
        root.put("artists", artistsArr)

        val tracksArr = JSONArray()
        for (id in whitelistedTrackIds) {
            tracksArr.put(JSONObject().apply { put("id", id) })
        }
        root.put("tracks", tracksArr)

        val blockedArr = JSONArray()
        for (id in blockedTrackIds) {
            blockedArr.put(JSONObject().apply { put("id", id) })
        }
        root.put("blocked_tracks", blockedArr)

        return root.toString(2)
    }
}
