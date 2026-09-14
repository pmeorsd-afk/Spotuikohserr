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
    val artist: String = "",
    val status: String = "approved"
)

/**
 * Data representation of an artist with canonical name and aliases.
 */
data class WhitelistArtistEntry(
    val id: String = "",
    val canonicalName: String = "",
    val aliases: List<String> = emptyList(),
    val status: String = "approved",
    val notes: String = ""
) {
    // Backwards compatibility for Admin UI and legacy callers
    val name: String get() = canonicalName.ifBlank { aliases.firstOrNull() ?: "" }
}

/**
 * Central Kosher Whitelist Manager.
 *
 * Schema v2 Single Source of Truth Architecture:
 * - Remote GitHub is the authoritative source of truth.
 * - Replace (not merge) in-memory state on every update.
 * - Spotify ID is primary canonical key, with aliases for multi-language matching.
 * - Status flag: "approved" or "blocked" per entity (no separate lists).
 */
object KosherWhitelistManager {

    private const val PRIMARY_WHITELIST_URL =
        "https://script.google.com/macros/s/AKfycbxjKBX2VHdyKfkih9EOgTOs5C08iFKqOEOaSeis1Ov1NZPBjR2HEVtMX-aAEricAXpPJw/exec"
    private const val FALLBACK_WHITELIST_URL =
        "https://raw.githubusercontent.com/pmeorsd-afk/Spotuikohserr/main/whitelist.json"
    private const val CACHE_FILE_NAME = "whitelist_cache.json"

    // In-memory structured entry lists
    private val artistEntries = CopyOnWriteArrayList<WhitelistArtistEntry>()
    private val trackEntries = CopyOnWriteArrayList<WhitelistTrackEntry>()

    // In-memory lookup sets (O(1) lookups)
    private val approvedArtistIds = ConcurrentHashMap.newKeySet<String>()
    private val approvedArtistNames = ConcurrentHashMap.newKeySet<String>()
    private val blockedArtistIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedArtistNames = ConcurrentHashMap.newKeySet<String>()

    private val approvedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val approvedTrackKeys = ConcurrentHashMap.newKeySet<String>()
    private val blockedTrackIds = ConcurrentHashMap.newKeySet<String>()
    private val blockedTrackKeys = ConcurrentHashMap.newKeySet<String>()

    // Dynamically registered allowed image URLs
    private val allowedImageUrls = ConcurrentHashMap.newKeySet<String>()

    // Compose state to trigger recomposition when the whitelist updates
    private val _versionState = mutableIntStateOf(0)
    val versionState: State<Int> get() = _versionState

    // Monotonic remote version tracker
    @Volatile
    private var currentVersion: Long = 0L
    val currentWhitelistVersion: Long get() = currentVersion

    private var initialized = false

    /**
     * Initializes the manager:
     * 1. If cache exists, loads snapshot.
     * 2. Else loads bundled assets/whitelist.json as seed.
     * 3. Fetches the latest authoritative whitelist from Apps Script / GitHub.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            val cacheFile = File(app.filesDir, CACHE_FILE_NAME)
            if (cacheFile.exists()) {
                runCatching {
                    replaceStateFromJson(cacheFile.readText())
                }
            } else {
                runCatching {
                    app.assets.open("whitelist.json").use { stream ->
                        replaceStateFromJson(stream.bufferedReader().readText())
                    }
                }
            }

            if (!com.music.spotui.BuildConfig.IS_ADMIN) {
                syncFromAdminProvider(app)
            }

            syncWithRemote(app)
        }
    }

    private fun fetchJsonWithRedirects(urlString: String, maxRedirects: Int = 5): String? {
        var currentUrl = urlString
        for (i in 0 until maxRedirects) {
            try {
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "SpotUI-Kosher/2.2")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    if (!loc.isNullOrBlank()) {
                        currentUrl = loc
                        continue
                    }
                }
                if (code in 200..299) {
                    val text = conn.inputStream.bufferedReader().readText()
                    if (text.isNotBlank() && (text.contains("artists") || text.contains("version"))) {
                        return text
                    }
                }
                return null
            } catch (e: Exception) {
                e.printStackTrace()
                return null
            }
        }
        return null
    }

    /**
     * Synchronizes whitelist:
     * 1. Tries Google Apps Script Web App (0-second instant cache reflection).
     * 2. Falls back to raw.githubusercontent.com if Apps Script is unreachable.
     * Replaces local memory and cache with fresh state.
     */
    fun syncWithRemote(context: Context, onComplete: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var updated = false
            try {
                // 1. נסה קודם כל את Google Apps Script (0 שניות השהייה, ללא CDN Cache)
                val primaryJson = fetchJsonWithRedirects(PRIMARY_WHITELIST_URL)
                val finalJson = if (!primaryJson.isNullOrBlank()) {
                    primaryJson
                } else {
                    // 2. Fallback ל-GitHub raw במקרה של כשל
                    fetchJsonWithRedirects("$FALLBACK_WHITELIST_URL?t=${System.currentTimeMillis()}")
                }

                if (!finalJson.isNullOrBlank()) {
                    updated = replaceStateFromJson(finalJson)
                    if (updated) {
                        runCatching {
                            File(app.filesDir, CACHE_FILE_NAME).writeText(finalJson)
                        }
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

    fun canonicalTrackId(rawId: String?): String {
        if (rawId.isNullOrBlank()) return ""
        var value = rawId.trim()

        if (value.startsWith("spotify:track:", ignoreCase = true)) {
            value = value.removePrefix("spotify:track:")
                .substringBefore("|")
                .substringBefore("?")
                .substringBefore("#")
                .trim()
        } else if (value.contains("open.spotify.com/track/", ignoreCase = true)) {
            value = value.substringAfter("/track/")
                .substringBefore("?")
                .substringBefore("#")
                .substringBefore("/")
                .substringBefore("|")
                .trim()
        } else if (value.startsWith("youtube:", ignoreCase = true)) {
            val afterPrefix = value.removePrefix("youtube:").trim()
            value = "youtube:" + afterPrefix.substringBefore("|").substringBefore("?").substringBefore("#").trim()
        } else if (value.contains("|")) {
            val beforePipe = value.substringBefore("|").trim()
            if (beforePipe.matches(Regex("^[a-zA-Z0-9]{22}$"))) {
                value = beforePipe
            }
        }

        return value.trim()
    }

    fun canonicalTrackId(song: SongsModel?): String {
        if (song == null) return ""
        val spotifyId = canonicalTrackId(song.spotifyTrackId)
        if (spotifyId.isNotBlank()) return spotifyId
        return canonicalTrackId(song.url)
    }

    fun normalizeText(value: String?): String {
        if (value == null) return ""
        return java.text.Normalizer
            .normalize(value, java.text.Normalizer.Form.NFKC)
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", " ")
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
    }

    private fun normalize(str: String?): String {
        return normalizeText(str)
    }

    fun trackKey(title: String?, artist: String?): String {
        val t = normalizeText(title)
        val a = normalizeText(artist)
        return if (t.isNotBlank() && a.isNotBlank()) "$t|$a" else ""
    }

    /**
     * Replaces the entire in-memory state and rebuilds all lookup indexes from a JSON string.
     */
    @Synchronized
    fun replaceStateFromJson(jsonStr: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            if (!root.has("artists") && !root.has("tracks")) return false

            val remoteVersion = root.optLong("version", -1L)
            if (remoteVersion > 0 && remoteVersion < currentVersion) {
                android.util.Log.w("KosherWhitelist", "Ignoring stale remote version $remoteVersion < local $currentVersion")
                return false
            }

            val parsedArtists = mutableListOf<WhitelistArtistEntry>()
            val artistsArray = root.optJSONArray("artists") ?: JSONArray()
            for (i in 0 until artistsArray.length()) {
                val obj = artistsArray.optJSONObject(i) ?: continue
                val rawId = obj.optString("id").trim()
                val id = canonicalTrackId(rawId).ifBlank { rawId }
                val canonicalName = obj.optString("canonical_name").ifBlank { obj.optString("name") }.trim()
                val status = obj.optString("status", "approved").trim()
                val notes = obj.optString("notes", "").trim()

                val aliases = mutableListOf<String>()
                val aliasesArr = obj.optJSONArray("aliases")
                if (aliasesArr != null) {
                    for (j in 0 until aliasesArr.length()) {
                        val alias = aliasesArr.optString(j).trim()
                        if (alias.isNotBlank()) aliases.add(alias)
                    }
                }
                if (canonicalName.isNotBlank() && !aliases.contains(canonicalName)) {
                    aliases.add(0, canonicalName)
                }

                if (id.isNotBlank() || canonicalName.isNotBlank() || aliases.isNotEmpty()) {
                    parsedArtists.add(WhitelistArtistEntry(id, canonicalName, aliases, status, notes))
                }
            }

            // Legacy blocked_artists array migration fallback
            val legacyBlockedArr = root.optJSONArray("blocked_artists")
            if (legacyBlockedArr != null) {
                for (i in 0 until legacyBlockedArr.length()) {
                    val obj = legacyBlockedArr.optJSONObject(i) ?: continue
                    val rawId = obj.optString("id").trim()
                    val id = canonicalTrackId(rawId).ifBlank { rawId }
                    val name = obj.optString("name").trim()
                    if (id.isNotBlank() || name.isNotBlank()) {
                        val existing = parsedArtists.find {
                            (id.isNotBlank() && it.id == id) || (name.isNotBlank() && it.canonicalName == name)
                        }
                        if (existing != null) {
                            parsedArtists.remove(existing)
                            parsedArtists.add(existing.copy(status = "blocked"))
                        } else {
                            parsedArtists.add(WhitelistArtistEntry(id, name, listOf(name), "blocked", "legacy"))
                        }
                    }
                }
            }

            val parsedTracks = mutableListOf<WhitelistTrackEntry>()
            val tracksArray = root.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until tracksArray.length()) {
                val obj = tracksArray.optJSONObject(i) ?: continue
                val rawId = obj.optString("id").trim()
                val id = canonicalTrackId(rawId).ifBlank { rawId }
                val title = obj.optString("title").trim()
                val artist = obj.optString("artist").trim()
                val status = obj.optString("status", "approved").trim()
                if (id.isNotBlank() || (title.isNotBlank() && artist.isNotBlank())) {
                    parsedTracks.add(WhitelistTrackEntry(id, title, artist, status))
                }
            }

            // Legacy blocked_tracks array migration fallback
            val legacyBlockedTracksArr = root.optJSONArray("blocked_tracks")
            if (legacyBlockedTracksArr != null) {
                for (i in 0 until legacyBlockedTracksArr.length()) {
                    val obj = legacyBlockedTracksArr.optJSONObject(i) ?: continue
                    val rawId = obj.optString("id").trim()
                    val id = canonicalTrackId(rawId).ifBlank { rawId }
                    val title = obj.optString("title").trim()
                    val artist = obj.optString("artist").trim()
                    if (id.isNotBlank() || (title.isNotBlank() && artist.isNotBlank())) {
                        val existing = parsedTracks.find {
                            (id.isNotBlank() && it.id == id) ||
                                    (title.isNotBlank() && it.title == title && it.artist == artist)
                        }
                        if (existing != null) {
                            parsedTracks.remove(existing)
                            parsedTracks.add(existing.copy(status = "blocked"))
                        } else {
                            parsedTracks.add(WhitelistTrackEntry(id, title, artist, "blocked"))
                        }
                    }
                }
            }

            rebuildIndexes(parsedArtists, parsedTracks)
            if (remoteVersion > 0) {
                currentVersion = remoteVersion
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    private fun rebuildIndexes(newArtists: List<WhitelistArtistEntry>, newTracks: List<WhitelistTrackEntry>) {
        allowedImageUrls.clear()

        approvedArtistIds.clear()
        approvedArtistNames.clear()
        blockedArtistIds.clear()
        blockedArtistNames.clear()

        for (a in newArtists) {
            val id = canonicalTrackId(a.id).ifBlank { a.id }.trim()
            val names = mutableListOf<String>()
            if (a.canonicalName.isNotBlank()) names.add(a.canonicalName)
            names.addAll(a.aliases)

            val isApproved = a.status.equals("approved", ignoreCase = true)
            if (isApproved) {
                if (id.isNotBlank()) approvedArtistIds.add(id)
                names.forEach { name ->
                    val norm = normalizeText(name)
                    if (norm.isNotBlank()) approvedArtistNames.add(norm)
                }
            } else {
                if (id.isNotBlank()) blockedArtistIds.add(id)
                names.forEach { name ->
                    val norm = normalizeText(name)
                    if (norm.isNotBlank()) blockedArtistNames.add(norm)
                }
            }
        }

        approvedTrackIds.clear()
        approvedTrackKeys.clear()
        blockedTrackIds.clear()
        blockedTrackKeys.clear()

        for (t in newTracks) {
            val rawId = t.id.trim()
            val id = canonicalTrackId(rawId).ifBlank { rawId }
            val key = trackKey(t.title, t.artist)
            val isApproved = t.status.equals("approved", ignoreCase = true)

            if (isApproved) {
                if (id.isNotBlank()) approvedTrackIds.add(id)
                if (rawId.isNotBlank() && rawId != id) approvedTrackIds.add(rawId)
                if (key.isNotBlank()) approvedTrackKeys.add(key)
            } else {
                if (id.isNotBlank()) blockedTrackIds.add(id)
                if (rawId.isNotBlank() && rawId != id) blockedTrackIds.add(rawId)
                if (key.isNotBlank()) blockedTrackKeys.add(key)
            }
        }

        artistEntries.clear()
        artistEntries.addAll(newArtists)
        trackEntries.clear()
        trackEntries.addAll(newTracks)
    }

    /**
     * Checks if an artist is in the whitelist.
     * Canonical Spotify ID takes priority, followed by Name/Alias lookup.
     */
    fun isArtistInWhitelist(artistId: String? = null, artistName: String? = null): Boolean {
        val cleanId = artistId?.trim() ?: ""
        val norm = normalize(artistName)

        // 1. Authoritative ID check
        if (cleanId.isNotBlank()) {
            if (blockedArtistIds.contains(cleanId)) return false
            if (approvedArtistIds.contains(cleanId)) return true
        }

        // 2. Name / Aliases check
        if (norm.isNotBlank()) {
            if (blockedArtistNames.contains(norm)) return false
            if (approvedArtistNames.contains(norm)) return true
        }

        return false
    }

    /**
     * Duet-safe artist check: Returns true ONLY if ALL artists participating in the track
     * are individually whitelisted.
     */
    fun areAllArtistsInWhitelist(artistString: String?): Boolean {
        val str = artistString?.trim() ?: return false
        if (str.isBlank()) return false
        val parts = str.split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.isEmpty()) return false
        return parts.all { isArtistInWhitelist(null, it) }
    }

    /**
     * Checks if a track is explicitly present in the whitelist JSON (regardless of Admin mode).
     */
    fun isTrackInWhitelist(
        trackId: String? = null,
        trackTitle: String? = null,
        artistName: String? = null
    ): Boolean {
        val rawId = trackId?.trim() ?: ""
        val cId = canonicalTrackId(rawId)
        val key = trackKey(trackTitle, artistName)

        // 1. Explicitly blocked track check
        if (cId.isNotBlank() && blockedTrackIds.contains(cId)) return false
        if (rawId.isNotBlank() && blockedTrackIds.contains(rawId)) return false
        if (key.isNotBlank() && blockedTrackKeys.contains(key)) return false

        // 2. Explicitly whitelisted track check
        if (cId.isNotBlank() && approvedTrackIds.contains(cId)) return true
        if (rawId.isNotBlank() && approvedTrackIds.contains(rawId)) return true
        if (key.isNotBlank() && approvedTrackKeys.contains(key)) return true

        // 3. Duet-safe artist whitelist: ALL artists must be approved!
        if (areAllArtistsInWhitelist(artistName)) {
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
        val effectiveId = canonicalTrackId(song).ifBlank { song.spotifyTrackId.ifBlank { song.url } }
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

    fun isTrackAllowed(song: SongsModel?): Boolean = isSongWhitelisted(song)

    /**
     * Checks if an [ArtistsModel] is allowed for display.
     */
    fun isArtistModelWhitelisted(artist: ArtistsModel?): Boolean {
        if (artist == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (artist.coverUri.isNotBlank()) allowImageUrl(artist.coverUri)
            return true
        }
        val allowed = isArtistInWhitelist(artist.id.takeIf { it.isNotBlank() }, artist.name)
        if (allowed && artist.coverUri.isNotBlank()) {
            allowImageUrl(artist.coverUri)
        }
        return allowed
    }

    /**
     * Checks if an [AlbumsModel] is allowed for display.
     */
    fun isAlbumWhitelisted(album: AlbumsModel?): Boolean {
        if (album == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (album.coverUri.isNotBlank()) allowImageUrl(album.coverUri)
            return true
        }
        val allowed = if (album.artists.isNotBlank()) {
            areAllArtistsInWhitelist(album.artists) || isUrlAllowed(album.coverUri)
        } else {
            isUrlAllowed(album.coverUri)
        }
        if (allowed && album.coverUri.isNotBlank()) {
            allowImageUrl(album.coverUri)
        }
        return allowed
    }

    /**
     * Checks if a [HomeItem] is allowed for display.
     */
    fun isHomeItemWhitelisted(item: HomeItem?): Boolean {
        if (item == null) return false
        if (com.music.spotui.BuildConfig.IS_ADMIN) {
            if (item.imageUrl.isNotBlank()) allowImageUrl(item.imageUrl)
            return true
        }
        val allowed = when (item) {
            is HomeItem.Artist -> isArtistInWhitelist(null, item.name)
            is HomeItem.Album -> areAllArtistsInWhitelist(item.subtitle)
            is HomeItem.Playlist -> false
            is HomeItem.Track -> isSongWhitelisted(item.song)
            is HomeItem.LikedSongs -> true
        }
        if (allowed && item.imageUrl.isNotBlank()) {
            allowImageUrl(item.imageUrl)
        }
        return allowed
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
        val rawId = id.trim()
        val cId = canonicalTrackId(rawId)
        val cleanId = cId.ifBlank { rawId }
        val key = trackKey(title, artist)
        if (cleanId.isBlank() && key.isBlank()) return false

        val updated = trackEntries.toMutableList()
        val existing = updated.find {
            (cleanId.isNotBlank() && (it.id == cleanId || it.id == rawId)) ||
                    (key.isNotBlank() && trackKey(it.title, it.artist) == key)
        }
        if (existing != null) {
            updated.remove(existing)
            updated.add(0, existing.copy(status = "approved"))
        } else {
            updated.add(0, WhitelistTrackEntry(cleanId, title.trim(), artist.trim(), "approved"))
        }

        rebuildIndexes(artistEntries, updated)
        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Removes a track from the whitelist (marks as blocked).
     */
    @Synchronized
    fun removeTrack(context: Context, id: String, title: String = "", artist: String = ""): Boolean {
        val rawId = id.trim()
        val cId = canonicalTrackId(rawId)
        val cleanId = cId.ifBlank { rawId }
        val key = trackKey(title, artist)
        if (cleanId.isBlank() && key.isBlank()) return false

        val updated = trackEntries.toMutableList()
        val existing = updated.find {
            (cleanId.isNotBlank() && (it.id == cleanId || it.id == rawId)) ||
                    (key.isNotBlank() && trackKey(it.title, it.artist) == key)
        }
        if (existing != null) {
            updated.remove(existing)
            updated.add(0, existing.copy(status = "blocked"))
        } else {
            updated.add(0, WhitelistTrackEntry(cleanId, title.trim(), artist.trim(), "blocked"))
        }

        rebuildIndexes(artistEntries, updated)
        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Adds an artist to artistEntries and marks as approved.
     */
    @Synchronized
    fun addArtist(context: Context, id: String = "", name: String = ""): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        if (cleanId.isBlank() && cleanName.isBlank()) return false
        val norm = normalize(cleanName)

        val updated = artistEntries.toMutableList()
        val existing = updated.find {
            (cleanId.isNotBlank() && it.id.equals(cleanId, ignoreCase = true)) ||
                    (cleanName.isNotBlank() && it.canonicalName.equals(cleanName, ignoreCase = true)) ||
                    (norm.isNotBlank() && it.aliases.any { a -> normalize(a) == norm })
        }

        if (existing != null) {
            updated.remove(existing)
            val newAliases = existing.aliases.toMutableList()
            if (cleanName.isNotBlank() && !newAliases.any { normalize(it) == norm }) {
                newAliases.add(cleanName)
            }
            updated.add(0, existing.copy(
                id = cleanId.ifBlank { existing.id },
                canonicalName = existing.canonicalName.ifBlank { cleanName },
                aliases = newAliases,
                status = "approved"
            ))
        } else {
            updated.add(0, WhitelistArtistEntry(
                id = cleanId,
                canonicalName = cleanName,
                aliases = if (cleanName.isNotBlank()) listOf(cleanName) else emptyList(),
                status = "approved",
                notes = "added locally"
            ))
        }

        rebuildIndexes(updated, trackEntries)
        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    /**
     * Removes an artist from whitelist (marks as blocked).
     */
    @Synchronized
    fun removeArtist(context: Context, id: String = "", name: String = ""): Boolean {
        val cleanId = id.trim()
        val cleanName = name.trim()
        if (cleanId.isBlank() && cleanName.isBlank()) return false
        val norm = normalize(cleanName)

        val updated = artistEntries.toMutableList()
        val existing = updated.find {
            (cleanId.isNotBlank() && it.id.equals(cleanId, ignoreCase = true)) ||
                    (cleanName.isNotBlank() && it.canonicalName.equals(cleanName, ignoreCase = true)) ||
                    (norm.isNotBlank() && it.aliases.any { a -> normalize(a) == norm })
        }

        if (existing != null) {
            updated.remove(existing)
            val newAliases = existing.aliases.toMutableList()
            if (cleanName.isNotBlank() && !newAliases.any { normalize(it) == norm }) {
                newAliases.add(cleanName)
            }
            updated.add(0, existing.copy(
                id = cleanId.ifBlank { existing.id },
                canonicalName = existing.canonicalName.ifBlank { cleanName },
                aliases = newAliases,
                status = "blocked"
            ))
        } else {
            updated.add(0, WhitelistArtistEntry(
                id = cleanId,
                canonicalName = cleanName,
                aliases = if (cleanName.isNotBlank()) listOf(cleanName) else emptyList(),
                status = "blocked",
                notes = "blocked locally"
            ))
        }

        rebuildIndexes(updated, trackEntries)
        saveToCache(context)
        _versionState.intValue += 1
        return true
    }

    fun getWhitelistedArtists(): List<WhitelistArtistEntry> =
        artistEntries.filter { it.status.equals("approved", ignoreCase = true) }

    fun getWhitelistedArtistsCount(): Int = getWhitelistedArtists().size

    fun getBlockedArtists(): List<WhitelistArtistEntry> =
        artistEntries.filter { it.status.equals("blocked", ignoreCase = true) }

    fun getBlockedArtistsCount(): Int = getBlockedArtists().size

    fun getWhitelistedTracks(): List<WhitelistTrackEntry> =
        trackEntries.filter { it.status.equals("approved", ignoreCase = true) }

    fun getWhitelistedTracksCount(): Int = getWhitelistedTracks().size

    fun getBlockedTracks(): List<WhitelistTrackEntry> =
        trackEntries.filter { it.status.equals("blocked", ignoreCase = true) }

    fun getBlockedTracksCount(): Int = getBlockedTracks().size

    private fun saveToCache(context: Context) {
        try {
            val json = exportWhitelistJson()
            File(context.filesDir, CACHE_FILE_NAME).writeText(json)
            if (com.music.spotui.BuildConfig.IS_ADMIN) {
                broadcastToUserApp(context, json)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun broadcastToUserApp(context: Context, jsonStr: String) {
        try {
            val intent = Intent(WhitelistSyncReceiver.ACTION_WHITELIST_SYNC).apply {
                putExtra("whitelist_json", jsonStr)
                setPackage("com.music.spotui")
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            context.sendBroadcast(intent, WhitelistSyncReceiver.PERMISSION_READ_WHITELIST)
            runCatching {
                context.contentResolver.notifyChange(KosherWhitelistProvider.CONTENT_URI_ADMIN, null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun syncFromAdminProvider(context: Context): Boolean {
        return try {
            val uri = KosherWhitelistProvider.CONTENT_URI_ADMIN
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(KosherWhitelistProvider.COLUMN_WHITELIST_JSON),
                null,
                null,
                null
            )
            cursor?.use {
                if (!it.moveToFirst()) {
                    android.util.Log.d("WHITELIST_SYNC", "Admin provider query returned empty cursor")
                    return false
                }
                val colIndex = it.getColumnIndex(KosherWhitelistProvider.COLUMN_WHITELIST_JSON).takeIf { idx -> idx >= 0 } ?: 0
                val json = it.getString(colIndex)
                if (!json.isNullOrBlank() && (json.contains("artists") || json.contains("version"))) {
                    android.util.Log.d("WHITELIST_SYNC", "Whitelist loaded successfully from Admin provider (${json.length} bytes)")
                    applyExternalWhitelist(context, json)
                    return true
                }
            }
            android.util.Log.d("WHITELIST_SYNC", "Admin provider: cursor null or empty")
            false
        } catch (e: SecurityException) {
            android.util.Log.e("WHITELIST_SYNC", "Permission denied accessing Admin provider", e)
            false
        } catch (e: Exception) {
            android.util.Log.e("WHITELIST_SYNC", "Provider read failed", e)
            false
        }
    }

    fun debugWhitelistSync(context: Context): String {
        val sb = StringBuilder()
        val app = context.applicationContext
        val uri = KosherWhitelistProvider.CONTENT_URI_ADMIN
        try {
            val cursor = app.contentResolver.query(uri, null, null, null, null)
            sb.appendLine("Cursor is null: ${cursor == null}")
            cursor?.use {
                sb.appendLine("Cursor columnCount: ${it.columnCount}")
                sb.appendLine("Cursor columnNames: ${it.columnNames.joinToString()}")
                if (it.moveToFirst()) {
                    val jsonIndex = it.getColumnIndex(KosherWhitelistProvider.COLUMN_WHITELIST_JSON)
                    val json = if (jsonIndex >= 0) it.getString(jsonIndex) else null
                    sb.appendLine("Cursor moveToFirst: TRUE, jsonLength: ${json?.length}")
                } else {
                    sb.appendLine("Cursor moveToFirst: FALSE (empty result)")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        }
        return sb.toString()
    }

    fun applyExternalWhitelist(context: Context, jsonStr: String) {
        val app = context.applicationContext
        try {
            val updated = replaceStateFromJson(jsonStr)
            if (updated) {
                runCatching {
                    File(app.filesDir, CACHE_FILE_NAME).writeText(jsonStr)
                }
                CoroutineScope(Dispatchers.Main).launch {
                    _versionState.intValue += 1
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

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

    fun exportWhitelistJson(): String {
        val root = JSONObject()
        root.put("schema_version", 2)
        root.put("version", 2)
        root.put("last_updated", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

        val artistsArr = JSONArray()
        for (entry in artistEntries) {
            artistsArr.put(JSONObject().apply {
                put("id", entry.id)
                put("canonical_name", entry.canonicalName)
                val aliasesArr = JSONArray()
                entry.aliases.forEach { aliasesArr.put(it) }
                put("aliases", aliasesArr)
                put("status", entry.status)
                if (entry.notes.isNotBlank()) put("notes", entry.notes)
            })
        }
        root.put("artists", artistsArr)

        val tracksArr = JSONArray()
        for (entry in trackEntries) {
            tracksArr.put(JSONObject().apply {
                put("id", entry.id)
                if (entry.title.isNotBlank()) put("title", entry.title)
                if (entry.artist.isNotBlank()) put("artist", entry.artist)
                put("status", entry.status)
            })
        }
        root.put("tracks", tracksArr)

        return root.toString(2)
    }
}
