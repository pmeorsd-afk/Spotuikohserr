package com.music.spotui.util

import android.content.ClipData
import android.content.ClipboardManager
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Data representation of a whitelisted artist.
 */
data class WhitelistArtistEntry(
    val id: String = "",
    val name: String = "",
    val notes: String = "approved"
)

/**
 * Data representation of a whitelisted or blocked track.
 */
data class WhitelistTrackEntry(
    val id: String = "",
    val title: String = "",
    val artist: String = ""
)

/**
 * Central Kosher Whitelist Manager.
 *
 * Enforces airtight kosher image filtering across SpotUI:
 * - Default: All images are blocked and rendered with a sleek music placeholder.
 * - Whitelisted: Artists and tracks on the whitelist have their artwork allowed.
 * - Auto-sync: Fetches the latest whitelist from GitHub raw in the background and caches it locally.
 * - Admin Mode: Allows adding/removing artists & tracks and exporting updated whitelist.json.
 */
object KosherWhitelistManager {

    private const val REMOTE_WHITELIST_URL =
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/whitelist.json"
    private const val CACHE_FILE_NAME = "whitelist_cache.json"

    // In-memory structured entry lists
    private val artistEntries = CopyOnWriteArrayList<WhitelistArtistEntry>()
    private val trackEntries = CopyOnWriteArrayList<WhitelistTrackEntry>()
    private val blockedTrackEntries = CopyOnWriteArrayList<WhitelistTrackEntry>()

    // In-memory lookup sets (O(1) lookups)
    private val whitelistedArtistIds = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedArtistNames = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedTrackIds = ConcurrentHashMap.newKeySet<String>()

    // Dynamically registered allowed image URLs
    private val allowedImageUrls = ConcurrentHashMap.newKeySet<String>()

    // Compose state to trigger recomposition when the whitelist updates
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
                    parseWhitelistJson(jsonStr)
                }
            }

            // Step 2: Load local cache from app files directory if present
            val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                runCatching {
                    val cachedStr = cacheFile.readText()
                    parseWhitelistJson(cachedStr)
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
                        parseWhitelistJson(remoteJson)
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
     * Parses the JSON structure into in-memory collections and lookup sets.
     */
    @Synchronized
    private fun parseWhitelistJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)

            val newArtistEntries = mutableListOf<WhitelistArtistEntry>()
            val newArtistIds = mutableSetOf<String>()
            val newArtistNames = mutableSetOf<String>()

            val artistsArray = root.optJSONArray("artists") ?: JSONArray()
            for (i in 0 until artistsArray.length()) {
                val obj = artistsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val notes = obj.optString("notes", "approved")
                if (id.isNotBlank() || name.isNotBlank()) {
                    newArtistEntries.add(WhitelistArtistEntry(id, name, notes))
                    if (id.isNotBlank()) newArtistIds.add(id)
                    if (name.isNotBlank()) newArtistNames.add(normalize(name))
                }
            }

            val newTrackEntries = mutableListOf<WhitelistTrackEntry>()
            val newTrackIds = mutableSetOf<String>()
            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracksArray.length()) {
                val obj = tracksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                val artist = obj.optString("artist").trim()
                if (id.isNotBlank()) {
                    newTrackEntries.add(WhitelistTrackEntry(id, title, artist))
                    newTrackIds.add(id)
                }
            }

            val newBlockedEntries = mutableListOf<WhitelistTrackEntry>()
            val newBlockedIds = mutableSetOf<String>()
            val blockedArray = root.optJSONArray("blocked_tracks") ?: JSONArray()
            for (i in 0 until blockedArray.length()) {
                val obj = blockedArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                val artist = obj.optString("artist").trim()
                if (id.isNotBlank()) {
                    newBlockedEntries.add(WhitelistTrackEntry(id, title, artist))
                    newBlockedIds.add(id)
                }
            }

            artistEntries.clear()
            artistEntries.addAll(newArtistEntries)
            whitelistedArtistIds.clear()
            whitelistedArtistIds.addAll(newArtistIds)
            whitelistedArtistNames.clear()
            whitelistedArtistNames.addAll(newArtistNames)

            trackEntries.clear()
            trackEntries.addAll(newTrackEntries)
            whitelistedTrackIds.clear()
            whitelistedTrackIds.addAll(newTrackIds)

            blockedTrackEntries.clear()
            blockedTrackEntries.addAll(newBlockedEntries)
            blockedTrackIds.clear()
            blockedTrackIds.addAll(newBlockedIds)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Checks if an artist is explicitly present in the whitelist JSON (regardless of Admin mode).
     * Used by Admin UI toggles to accurately display "Approve" vs "Remove".
     */
    fun isArtistInWhitelist(artistId: String? = null, artistName: String? = null): Boolean {
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
     * Checks if a track is explicitly present in the whitelist JSON (regardless of Admin mode).
     * Used by Admin UI toggles to accurately display "Approve" vs "Remove".
     */
    fun isTrackInWhitelist(
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
        if (isArtistInWhitelist(null, artistName)) {
            return true
        }
        return false
    }

    /**
     * Checks if an artist is allowed for display.
     * In Admin mode, ALWAYS returns true so admin can view images for verification.
     */
    fun isArtistWhitelisted(artistId: String? = null, artistName: String? = null): Boolean {
        if (com.music.spotui.BuildConfig.IS_ADMIN) return true
        return isArtistInWhitelist(artistId, artistName)
    }

    /**
     * Checks if a track is allowed for display.
     * In Admin mode, ALWAYS returns true so admin can view images for verification.
     */
    fun isTrackWhitelisted(
        trackId: String? = null,
        trackTitle: String? = null,
        artistName: String? = null
    ): Boolean {
        if (com.music.spotui.BuildConfig.IS_ADMIN) return true
        return isTrackInWhitelist(trackId, trackTitle, artistName)
    }

    /**
     * Checks if a [SongsModel] is allowed, and if so, registers its cover URL as allowed.
     */
    fun isSongWhitelisted(song: SongsModel?): Boolean {
        if (song == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (song.coverUri.isNotBlank()) allowImageUrl(song.coverUri)
            return true
        }
        val allowed = isTrackInWhitelist(
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
     * Checks if an [ArtistsModel] is allowed, and if so, registers its image URL as allowed.
     */
    fun isArtistModelWhitelisted(artist: ArtistsModel?): Boolean {
        if (artist == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (artist.coverUri.isNotBlank()) allowImageUrl(artist.coverUri)
            return true
        }
        val allowed = isArtistInWhitelist(artist.id, artist.name)
        if (allowed && artist.coverUri.isNotBlank()) {
            allowImageUrl(artist.coverUri)
        }
        return allowed
    }

    /**
     * Checks if an [AlbumsModel] is allowed, and if so, registers its cover URL as allowed.
     */
    fun isAlbumWhitelisted(album: AlbumsModel?): Boolean {
        if (album == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (album.coverUri.isNotBlank()) allowImageUrl(album.coverUri)
            return true
        }
        val allowed = isArtistInWhitelist(null, album.artists)
        if (allowed && album.coverUri.isNotBlank()) {
            allowImageUrl(album.coverUri)
        }
        return allowed
    }

    /**
     * Checks if a [HomeItem] is allowed, and if so, registers its image URL as allowed.
     */
    fun isHomeItemWhitelisted(item: HomeItem?): Boolean {
        if (item == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (item.imageUrl.isNotBlank()) allowImageUrl(item.imageUrl)
            return true
        }
        val allowed = when (item) {
            is HomeItem.Artist -> isArtistInWhitelist(item.id, item.name)
            is HomeItem.Album -> isArtistInWhitelist(null, item.artists.ifBlank { item.subtitle })
            is HomeItem.Playlist -> false
        }
        if (allowed && item.imageUrl.isNotBlank()) {
            allowImageUrl(item.imageUrl)
        }
        return allowed
    }

    /**
     * Checks if a RecentItem is allowed.
     */
    fun isRecentItemWhitelisted(recent: RecentItem?): Boolean {
        if (recent == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (recent.image.isNotBlank()) allowImageUrl(recent.image)
            return true
        }
        val allowed = when (recent.type) {
            "artist" -> isArtistInWhitelist(recent.key, recent.name)
            "song" -> isTrackInWhitelist(recent.spotifyTrackId.ifBlank { recent.key }, recent.name, recent.singer)
            "album" -> isArtistInWhitelist(null, recent.singer)
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
        if (com.music.spotui.BuildConfig.IS_ADMIN) return true
        val clean = url?.trim() ?: return false
        return allowedImageUrls.contains(clean)
    }

    /**
     * Evaluates any model passed to GlideImage (typically a String URL).
     */
    fun isImageAllowed(model: Any?): Boolean {
        if (model == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) return true
        return when (model) {
            is String -> isUrlAllowed(model)
            else -> false
        }
    }

    // ==========================================
    // Admin Operations
    // ==========================================

    /**
     * Adds an artist to the whitelist and persists to local cache.
     */
    @Synchronized
    fun addArtist(context: Context, id: String = "", name: String = "", notes: String = "approved"): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        if (cleanId.isBlank() && cleanName.isBlank()) return false

        val norm = normalize(cleanName)
        val alreadyExists = (cleanId.isNotBlank() && whitelistedArtistIds.contains(cleanId)) ||
                (norm.isNotBlank() && whitelistedArtistNames.contains(norm))
        if (alreadyExists) return false

        val entry = WhitelistArtistEntry(cleanId, cleanName, notes)
        artistEntries.add(0, entry)
        if (cleanId.isNotBlank()) whitelistedArtistIds.add(cleanId)
        if (norm.isNotBlank()) whitelistedArtistNames.add(norm)

        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Removes an artist from the whitelist and persists to local cache.
     */
    @Synchronized
    fun removeArtist(context: Context, id: String = "", name: String = ""): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        val norm = normalize(cleanName)

        val removed = artistEntries.removeAll { entry ->
            (cleanId.isNotBlank() && entry.id.equals(cleanId, ignoreCase = true)) ||
                    (norm.isNotBlank() && normalize(entry.name) == norm)
        }
        if (cleanId.isNotBlank()) whitelistedArtistIds.remove(cleanId)
        if (norm.isNotBlank()) whitelistedArtistNames.remove(norm)

        if (removed) {
            saveToCache(context)
            _versionState.intValue += 1
        }
        return removed
    }

    /**
     * Adds a track to the whitelist and persists to local cache.
     */
    @Synchronized
    fun addTrack(context: Context, id: String, title: String = "", artist: String = ""): Boolean {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return false
        if (whitelistedTrackIds.contains(cleanId)) return false

        blockedTrackIds.remove(cleanId)
        blockedTrackEntries.removeAll { it.id == cleanId }

        trackEntries.add(0, WhitelistTrackEntry(cleanId, title.trim(), artist.trim()))
        whitelistedTrackIds.add(cleanId)

        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Removes a track from the whitelist and persists to local cache.
     * If the track's artist is whitelisted, adds the track to blocked_tracks so it is specifically excluded.
     */
    @Synchronized
    fun removeTrack(context: Context, id: String, title: String = "", artist: String = ""): Boolean {
        val cleanId = id.trim()
        if (cleanId.isBlank()) return false
        val removed = trackEntries.removeAll { it.id == cleanId }
        whitelistedTrackIds.remove(cleanId)

        if (isArtistInWhitelist(null, artist)) {
            if (!blockedTrackIds.contains(cleanId)) {
                blockedTrackIds.add(cleanId)
                blockedTrackEntries.add(0, WhitelistTrackEntry(cleanId, title.trim(), artist.trim()))
            }
        }

        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Returns the list of whitelisted artists.
     */
    fun getWhitelistedArtists(): List<WhitelistArtistEntry> = artistEntries.toList()

    /**
     * Returns the list of whitelisted tracks.
     */
    fun getWhitelistedTracks(): List<WhitelistTrackEntry> = trackEntries.toList()

    /**
     * Returns total count of whitelisted artists.
     */
    fun getWhitelistedArtistsCount(): Int = artistEntries.size

    /**
     * Returns total count of whitelisted tracks.
     */
    fun getWhitelistedTracksCount(): Int = trackEntries.size

    /**
     * Saves current in-memory whitelist to local cache file.
     */
    fun saveToCache(context: Context) {
        val app = context.applicationContext
        try {
            val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
            val json = exportWhitelistJson()
            cacheFile.writeText(json)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Copies the formatted JSON to the Android clipboard.
     */
    fun copyJsonToClipboard(context: Context): Boolean {
        return try {
            val json = exportWhitelistJson()
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("whitelist.json", json)
            clipboard?.setPrimaryClip(clip)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Exports current whitelist as JSON string matching whitelist.json schema.
     */
    fun exportWhitelistJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("last_updated", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

        val artistsArr = JSONArray()
        for (entry in artistEntries) {
            artistsArr.put(JSONObject().apply {
                put("id", entry.id)
                put("name", entry.name)
                put("notes", entry.notes.ifBlank { "approved" })
            })
        }
        root.put("artists", artistsArr)

        val tracksArr = JSONArray()
        for (entry in trackEntries) {
            tracksArr.put(JSONObject().apply {
                put("id", entry.id)
                if (entry.title.isNotBlank()) put("title", entry.title)
                if (entry.artist.isNotBlank()) put("artist", entry.artist)
            })
        }
        root.put("tracks", tracksArr)

        val blockedArr = JSONArray()
        for (entry in blockedTrackEntries) {
            blockedArr.put(JSONObject().apply {
                put("id", entry.id)
                if (entry.title.isNotBlank()) put("title", entry.title)
                if (entry.artist.isNotBlank()) put("artist", entry.artist)
            })
        }
        root.put("blocked_tracks", blockedArr)

        return root.toString(2)
    }
}
