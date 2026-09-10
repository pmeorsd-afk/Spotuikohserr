package com.music.spotui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * Data representation of a whitelisted or blocked track.
 */
data class WhitelistTrackEntry(
    val id: String = "",
    val title: String = "",
    val artist: String = ""
)

/**
 * Data representation of a whitelisted artist (preserved for schema compatibility).
 */
data class WhitelistArtistEntry(
    val id: String = "",
    val name: String = "",
    val notes: String = "approved"
)

/**
 * Central Kosher Whitelist Manager.
 *
 * 100% Strict Whitelist by Track ID:
 * - Default: All album covers & artwork are strictly blocked across the entire app.
 * - Allowed: A track's artwork is allowed ONLY if that specific track has been approved.
 * - No artist-level inheritance (eliminates duet / album leak risks completely).
 * - Real-time IPC sync: When Admin approves/blocks a track, it immediately pushes the update to SpotUI-Kosher on the device.
 */
object KosherWhitelistManager {

    private const val REMOTE_WHITELIST_URL =
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/whitelist.json"
    private const val CACHE_FILE_NAME = "whitelist_cache.json"

    // In-memory structured entry lists
    private val trackEntries = CopyOnWriteArrayList<WhitelistTrackEntry>()
    private val blockedTrackEntries = CopyOnWriteArrayList<WhitelistTrackEntry>()
    private val artistEntries = CopyOnWriteArrayList<WhitelistArtistEntry>()

    // In-memory lookup sets (O(1) lookups)
    private val whitelistedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val whitelistedTrackKeys = ConcurrentHashMap.newKeySet<String>() // "title|artist" normalized
    private val blockedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedTrackKeys = ConcurrentHashMap.newKeySet<String>() // "title|artist" normalized

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
     * 3. In User mode: queries ContentProvider from Admin app on same device if available
     * 4. Fetches the latest whitelist from GitHub in the background
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

            // Step 3: If in User mode, query Admin app on the same device for instant local sync
            if (!com.music.spotui.BuildConfig.IS_ADMIN) {
                syncFromAdminProvider(app)
            }

            // Step 4: Fetch latest from GitHub raw in background
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
                    if (remoteJson.isNotBlank() && remoteJson.contains("version")) {
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

    fun trackKey(title: String?, artist: String?): String {
        val t = normalize(title)
        val a = normalize(artist)
        return if (t.isNotBlank() && a.isNotBlank()) "$t|$a" else ""
    }

    /**
     * Parses the JSON structure into in-memory collections and lookup sets.
     */
    @Synchronized
    fun parseWhitelistJson(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)

            val newTrackEntries = mutableListOf<WhitelistTrackEntry>()
            val newTrackIds = mutableSetOf<String>()
            val newTrackKeys = mutableSetOf<String>()

            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracksArray.length()) {
                val obj = tracksArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                val artist = obj.optString("artist").trim()
                if (id.isNotBlank() || (title.isNotBlank() && artist.isNotBlank())) {
                    newTrackEntries.add(WhitelistTrackEntry(id, title, artist))
                    if (id.isNotBlank()) newTrackIds.add(id)
                    val key = trackKey(title, artist)
                    if (key.isNotBlank()) newTrackKeys.add(key)
                }
            }

            val newBlockedEntries = mutableListOf<WhitelistTrackEntry>()
            val newBlockedIds = mutableSetOf<String>()
            val newBlockedKeys = mutableSetOf<String>()

            val blockedArray = root.optJSONArray("blocked_tracks") ?: JSONArray()
            for (i in 0 until blockedArray.length()) {
                val obj = blockedArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val title = obj.optString("title").trim()
                val artist = obj.optString("artist").trim()
                if (id.isNotBlank() || (title.isNotBlank() && artist.isNotBlank())) {
                    newBlockedEntries.add(WhitelistTrackEntry(id, title, artist))
                    if (id.isNotBlank()) newBlockedIds.add(id)
                    val key = trackKey(title, artist)
                    if (key.isNotBlank()) newBlockedKeys.add(key)
                }
            }

            val newArtistEntries = mutableListOf<WhitelistArtistEntry>()
            val artistsArray = root.optJSONArray("artists") ?: JSONArray()
            for (i in 0 until artistsArray.length()) {
                val obj = artistsArray.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val notes = obj.optString("notes", "approved")
                if (id.isNotBlank() || name.isNotBlank()) {
                    newArtistEntries.add(WhitelistArtistEntry(id, name, notes))
                }
            }

            trackEntries.clear()
            trackEntries.addAll(newTrackEntries)
            whitelistedTrackIds.clear()
            whitelistedTrackIds.addAll(newTrackIds)
            whitelistedTrackKeys.clear()
            whitelistedTrackKeys.addAll(newTrackKeys)

            blockedTrackEntries.clear()
            blockedTrackEntries.addAll(newBlockedEntries)
            blockedTrackIds.clear()
            blockedTrackIds.addAll(newBlockedIds)
            blockedTrackKeys.clear()
            blockedTrackKeys.addAll(newBlockedKeys)

            artistEntries.clear()
            artistEntries.addAll(newArtistEntries)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
        val key = trackKey(trackTitle, artistName)

        // 1. Explicitly blocked check
        if (cleanId.isNotBlank() && blockedTrackIds.contains(cleanId)) {
            return false
        }
        if (key.isNotBlank() && blockedTrackKeys.contains(key)) {
            return false
        }

        // 2. Explicitly whitelisted track check (by ID or Title+Artist)
        if (cleanId.isNotBlank() && whitelistedTrackIds.contains(cleanId)) {
            return true
        }
        if (key.isNotBlank() && whitelistedTrackKeys.contains(key)) {
            return true
        }

        // Strictly Track ID only: No automatic artist-level inheritance!
        return false
    }

    /**
     * Compatibility helper: In Track ID Only mode, artists are not automatically whitelisted.
     */
    fun isArtistInWhitelist(artistId: String? = null, artistName: String? = null): Boolean {
        return false
    }

    /**
     * Checks if an artist is allowed for display.
     * In Admin mode, ALWAYS returns true so admin can view images for verification.
     */
    fun isArtistWhitelisted(artistId: String? = null, artistName: String? = null): Boolean {
        if (com.music.spotui.BuildConfig.IS_ADMIN) return true
        return false
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
        val effectiveId = song.spotifyTrackId.ifBlank { song.url }
        val allowed = isTrackInWhitelist(
            trackId = effectiveId,
            trackTitle = song.title,
            artistName = song.singer
        )
        if (allowed && song.coverUri.isNotBlank()) {
            allowImageUrl(song.coverUri)
        }
        return allowed
    }

    /**
     * ArtistsModel in Track ID Only mode is blocked for users, open for admin.
     */
    fun isArtistModelWhitelisted(artist: ArtistsModel?): Boolean {
        if (artist == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (artist.coverUri.isNotBlank()) allowImageUrl(artist.coverUri)
            return true
        }
        return false
    }

    /**
     * AlbumsModel in Track ID Only mode is blocked for users, open for admin.
     */
    fun isAlbumWhitelisted(album: AlbumsModel?): Boolean {
        if (album == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (album.coverUri.isNotBlank()) allowImageUrl(album.coverUri)
            return true
        }
        return false
    }

    /**
     * HomeItem in Track ID Only mode is blocked for users, open for admin.
     */
    fun isHomeItemWhitelisted(item: HomeItem?): Boolean {
        if (item == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (item.imageUrl.isNotBlank()) allowImageUrl(item.imageUrl)
            return true
        }
        return false
    }

    /**
     * RecentItem check: only allowed if it's a song and that song is whitelisted.
     */
    fun isRecentItemWhitelisted(recent: RecentItem?): Boolean {
        if (recent == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (recent.image.isNotBlank()) allowImageUrl(recent.image)
            return true
        }
        val allowed = if (recent.type == "song") {
            isTrackInWhitelist(
                trackId = recent.spotifyTrackId.ifBlank { recent.key },
                trackTitle = recent.name,
                artistName = recent.singer
            )
        } else {
            false
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
     * Adds a track to the whitelist and persists to local cache + broadcasts to User app.
     */
    @Synchronized
    fun addTrack(context: Context, id: String, title: String = "", artist: String = ""): Boolean {
        val cleanId = id.trim()
        val key = trackKey(title, artist)
        if (cleanId.isBlank() && key.isBlank()) return false

        // Remove from blocked sets
        if (cleanId.isNotBlank()) {
            blockedTrackIds.remove(cleanId)
        }
        if (key.isNotBlank()) {
            blockedTrackKeys.remove(key)
        }
        blockedTrackEntries.removeAll {
            (cleanId.isNotBlank() && it.id == cleanId) ||
                    (key.isNotBlank() && trackKey(it.title, it.artist) == key)
        }

        // Add to whitelisted sets
        if (cleanId.isNotBlank()) {
            whitelistedTrackIds.add(cleanId)
        }
        if (key.isNotBlank()) {
            whitelistedTrackKeys.add(key)
        }

        // Avoid duplicate entry in trackEntries
        val alreadyInList = trackEntries.any {
            (cleanId.isNotBlank() && it.id == cleanId) ||
                    (key.isNotBlank() && trackKey(it.title, it.artist) == key)
        }
        if (!alreadyInList) {
            trackEntries.add(0, WhitelistTrackEntry(cleanId, title.trim(), artist.trim()))
        }

        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Removes a track from the whitelist and adds it to blocked_tracks so it is strictly excluded.
     */
    @Synchronized
    fun removeTrack(context: Context, id: String, title: String = "", artist: String = ""): Boolean {
        val cleanId = id.trim()
        val key = trackKey(title, artist)
        if (cleanId.isBlank() && key.isBlank()) return false

        // Remove from whitelisted sets
        if (cleanId.isNotBlank()) {
            whitelistedTrackIds.remove(cleanId)
        }
        if (key.isNotBlank()) {
            whitelistedTrackKeys.remove(key)
        }
        trackEntries.removeAll {
            (cleanId.isNotBlank() && it.id == cleanId) ||
                    (key.isNotBlank() && trackKey(it.title, it.artist) == key)
        }

        // Add to blocked sets
        if (cleanId.isNotBlank()) {
            blockedTrackIds.add(cleanId)
        }
        if (key.isNotBlank()) {
            blockedTrackKeys.add(key)
        }

        val alreadyInBlockedList = blockedTrackEntries.any {
            (cleanId.isNotBlank() && it.id == cleanId) ||
                    (key.isNotBlank() && trackKey(it.title, it.artist) == key)
        }
        if (!alreadyInBlockedList) {
            blockedTrackEntries.add(0, WhitelistTrackEntry(cleanId, title.trim(), artist.trim()))
        }

        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Adds an artist to artistEntries (for schema compatibility and admin record keeping).
     */
    @Synchronized
    fun addArtist(context: Context, id: String = "", name: String = ""): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        if (cleanId.isBlank() && cleanName.isBlank()) return false
        val already = artistEntries.any {
            (cleanId.isNotBlank() && it.id.equals(cleanId, ignoreCase = true)) ||
                    (cleanName.isNotBlank() && it.name.equals(cleanName, ignoreCase = true))
        }
        if (!already) {
            artistEntries.add(0, WhitelistArtistEntry(cleanId, cleanName, "approved"))
            saveToCache(context)
            _versionState.intValue += 1
            return true
        }
        return false
    }

    /**
     * Removes an artist from artistEntries.
     */
    @Synchronized
    fun removeArtist(context: Context, id: String = "", name: String = ""): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        val removed = artistEntries.removeAll { entry ->
            (cleanId.isNotBlank() && entry.id.equals(cleanId, ignoreCase = true)) ||
                    (cleanName.isNotBlank() && entry.name.equals(cleanName, ignoreCase = true))
        }
        if (removed) {
            saveToCache(context)
            _versionState.intValue += 1
        }
        return removed
    }

    /**
     * Returns the list of whitelisted artists.
     */
    fun getWhitelistedArtists(): List<WhitelistArtistEntry> = artistEntries.toList()

    /**
     * Returns total count of whitelisted artists.
     */
    fun getWhitelistedArtistsCount(): Int = artistEntries.size

    /**
     * Returns the list of whitelisted tracks.
     */
    fun getWhitelistedTracks(): List<WhitelistTrackEntry> = trackEntries.toList()

    /**
     * Returns total count of whitelisted tracks.
     */
    fun getWhitelistedTracksCount(): Int = trackEntries.size

    /**
     * Returns the list of blocked tracks.
     */
    fun getBlockedTracks(): List<WhitelistTrackEntry> = blockedTrackEntries.toList()

    /**
     * Returns total count of blocked tracks.
     */
    fun getBlockedTracksCount(): Int = blockedTrackEntries.size

    /**
     * Saves current in-memory whitelist to local cache file.
     * When running in Admin mode, immediately pushes the update to the Kosher user app on the device.
     */
    fun saveToCache(context: Context) {
        val app = context.applicationContext
        try {
            val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
            val json = exportWhitelistJson()
            cacheFile.writeText(json)

            // If running in Admin mode, instantly push update to Kosher user app!
            if (com.music.spotui.BuildConfig.IS_ADMIN) {
                // 1. Send explicit broadcast to com.music.spotui
                val syncIntent = Intent(WhitelistSyncReceiver.ACTION_WHITELIST_SYNC).apply {
                    setPackage("com.music.spotui")
                    putExtra("whitelist_json", json)
                }
                app.sendBroadcast(syncIntent)

                // 2. Notify ContentProvider URI
                runCatching {
                    app.contentResolver.notifyChange(
                        KosherWhitelistProvider.CONTENT_URI_ADMIN,
                        null
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Pulls the latest whitelist from the Admin app's ContentProvider (if installed on the same device).
     */
    fun syncFromAdminProvider(context: Context): Boolean {
        return try {
            val uri = Uri.parse("content://com.music.spotui.admin.provider.whitelist/whitelist")
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val json = it.getString(0)
                    if (!json.isNullOrBlank() && json.contains("version")) {
                        applyExternalWhitelist(context, json)
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Applies an externally received whitelist JSON string (from Broadcast or ContentProvider).
     * Caches to local disk and triggers immediate UI recomposition.
     */
    fun applyExternalWhitelist(context: Context, jsonStr: String) {
        val app = context.applicationContext
        try {
            val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
            cacheFile.writeText(jsonStr)
            parseWhitelistJson(jsonStr)
            CoroutineScope(Dispatchers.Main).launch {
                _versionState.intValue += 1
            }
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
