package com.music.spotui.data.local

import android.content.Context
import android.content.SharedPreferences
import com.music.spotui.data.entity.SongsModel
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

data class TrackListenStat(
    val trackId: String,
    val title: String,
    val artist: String,
    val coverUri: String,
    val playCount: Int,
    val completedCount: Int,
    val lastPlayedAt: Long
) {
    fun toSongModel(): SongsModel {
        return SongsModel(
            id = trackId.hashCode() and 0x7fffffff,
            title = title,
            album = "",
            singer = artist,
            coverUri = coverUri,
            url = com.music.spotui.di.SongPlayer.buildSpotifyPlayQuery(trackId, title, artist),
            spotifyTrackId = trackId
        )
    }
}

data class ArtistListenStat(
    val artistName: String,
    val playCount: Int,
    val completedCount: Int,
    val lastPlayedAt: Long,
    val score: Double = 0.0
)

@Singleton
class LocalListeningTracker @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val PREFS_NAME = "local_listening_tracker"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_ARTISTS = "artists"

        private const val HALF_LIFE_DAYS = 14.0
        private const val DAY_MS = 86_400_000.0
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var currentSessionKey: String = ""

    @Volatile
    private var playRecordedForCurrentSession = false

    @Volatile
    private var completionRecordedForCurrentSession = false

    /**
     * Call whenever a NEW song starts playing.
     * Resets the 30s and 75% guards for this new session.
     */
    @Synchronized
    fun onSongStarted(song: SongsModel?) {
        if (song == null) {
            currentSessionKey = ""
            playRecordedForCurrentSession = false
            completionRecordedForCurrentSession = false
            return
        }

        currentSessionKey = sessionKey(song)
        playRecordedForCurrentSession = false
        completionRecordedForCurrentSession = false
    }

    /**
     * Records one play for the current playback session (milestone: >= 30 seconds).
     */
    @Synchronized
    fun recordPlay(song: SongsModel) {
        val key = sessionKey(song)
        if (key.isBlank()) return

        if (currentSessionKey != key) {
            currentSessionKey = key
            playRecordedForCurrentSession = false
            completionRecordedForCurrentSession = false
        }

        if (playRecordedForCurrentSession) return

        val now = System.currentTimeMillis()
        val tracks = loadTracks().toMutableMap()
        val existing = tracks[key]

        tracks[key] = TrackListenStat(
            trackId = key,
            title = song.title,
            artist = song.singer,
            coverUri = song.coverUri,
            playCount = (existing?.playCount ?: 0) + 1,
            completedCount = existing?.completedCount ?: 0,
            lastPlayedAt = now
        )

        saveTracks(tracks)
        updateArtists(song = song, now = now, completion = false)

        playRecordedForCurrentSession = true
    }

    /**
     * Records one completion for the current playback session (milestone: >= 75% duration).
     */
    @Synchronized
    fun recordCompletion(song: SongsModel) {
        val key = sessionKey(song)
        if (key.isBlank()) return

        if (currentSessionKey != key) {
            currentSessionKey = key
            playRecordedForCurrentSession = false
            completionRecordedForCurrentSession = false
        }

        if (completionRecordedForCurrentSession) return

        val now = System.currentTimeMillis()
        val tracks = loadTracks().toMutableMap()
        val existing = tracks[key]

        tracks[key] = TrackListenStat(
            trackId = key,
            title = song.title,
            artist = song.singer,
            coverUri = song.coverUri,
            playCount = existing?.playCount ?: 0,
            completedCount = (existing?.completedCount ?: 0) + 1,
            lastPlayedAt = maxOf(existing?.lastPlayedAt ?: 0L, now)
        )

        saveTracks(tracks)
        updateArtists(song = song, now = now, completion = true)

        completionRecordedForCurrentSession = true
    }

    /**
     * Returns the strongest artists weighted by recency decay (14-day half life):
     * score = (playCount + completedCount * 1.5) * exp(-ageDays * ln(2) / 14)
     */
    @Synchronized
    fun topArtists(limit: Int): List<ArtistListenStat> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()

        return loadArtists()
            .values
            .map { stat ->
                val score = calculateScore(
                    playCount = stat.playCount,
                    completedCount = stat.completedCount,
                    lastPlayedAt = stat.lastPlayedAt,
                    now = now
                )
                stat.copy(score = score)
            }
            .sortedByDescending { it.score }
            .take(limit)
    }

    /**
     * Most recently played tracks.
     */
    @Synchronized
    fun recentTracks(limit: Int): List<TrackListenStat> {
        if (limit <= 0) return emptyList()

        return loadTracks()
            .values
            .sortedByDescending { it.lastPlayedAt }
            .take(limit)
    }

    @Synchronized
    fun clearAll() {
        prefs.edit()
            .remove(KEY_TRACKS)
            .remove(KEY_ARTISTS)
            .apply()

        currentSessionKey = ""
        playRecordedForCurrentSession = false
        completionRecordedForCurrentSession = false
    }

    private fun sessionKey(song: SongsModel): String {
        val spId = song.spotifyTrackId
            .trim()
            .removePrefix("spotify:track:")
            .substringBefore("?")
            .substringBefore("#")
            .substringBefore("|")
            .trim()

        if (spId.isNotBlank()) return spId
        return song.url.trim()
    }

    private fun calculateScore(
        playCount: Int,
        completedCount: Int,
        lastPlayedAt: Long,
        now: Long
    ): Double {
        if (lastPlayedAt <= 0L) return 0.0

        val ageDays = (now - lastPlayedAt).coerceAtLeast(0L) / DAY_MS
        val recency = exp(-ageDays * ln(2.0) / HALF_LIFE_DAYS)

        return (playCount + completedCount * 1.5) * recency
    }

    private fun updateArtists(
        song: SongsModel,
        now: Long,
        completion: Boolean
    ) {
        val artistNames = parseArtists(song.singer)
        if (artistNames.isEmpty()) return

        val artists = loadArtists().toMutableMap()

        artistNames.forEach { artistName ->
            val normalized = normalizeArtistName(artistName)
            if (normalized.isBlank()) return@forEach

            val existing = artists[normalized]

            artists[normalized] = ArtistListenStat(
                artistName = artistName.trim(),
                playCount = if (completion) {
                    existing?.playCount ?: 0
                } else {
                    (existing?.playCount ?: 0) + 1
                },
                completedCount = if (completion) {
                    (existing?.completedCount ?: 0) + 1
                } else {
                    existing?.completedCount ?: 0
                },
                lastPlayedAt = maxOf(existing?.lastPlayedAt ?: 0L, now)
            )
        }

        saveArtists(artists)
    }

    private fun parseArtists(value: String?): List<String> {
        val text = value?.trim() ?: return emptyList()
        if (text.isBlank()) return emptyList()

        return text
            .split(Regex("[,&/]|\\bfeat\\.?\\b|\\bft\\.?\\b", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeArtistName(value: String): String {
        return java.text.Normalizer
            .normalize(value, java.text.Normalizer.Form.NFKC)
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace("\uFEFF", "")
            .replace("\u00A0", " ")
            .trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private fun loadTracks(): Map<String, TrackListenStat> {
        val raw = prefs.getString(KEY_TRACKS, null) ?: return emptyMap()

        return try {
            val array = JSONArray(raw)
            val result = mutableMapOf<String, TrackListenStat>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val trackId = obj.optString("trackId").trim()
                if (trackId.isBlank()) continue

                result[trackId] = TrackListenStat(
                    trackId = trackId,
                    title = obj.optString("title"),
                    artist = obj.optString("artist"),
                    coverUri = obj.optString("coverUri"),
                    playCount = obj.optInt("playCount", 0),
                    completedCount = obj.optInt("completedCount", 0),
                    lastPlayedAt = obj.optLong("lastPlayedAt", 0L)
                )
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveTracks(tracks: Map<String, TrackListenStat>) {
        val array = JSONArray()

        tracks.values.forEach { stat ->
            array.put(
                JSONObject().apply {
                    put("trackId", stat.trackId)
                    put("title", stat.title)
                    put("artist", stat.artist)
                    put("coverUri", stat.coverUri)
                    put("playCount", stat.playCount)
                    put("completedCount", stat.completedCount)
                    put("lastPlayedAt", stat.lastPlayedAt)
                }
            )
        }

        prefs.edit().putString(KEY_TRACKS, array.toString()).apply()
    }

    private fun loadArtists(): Map<String, ArtistListenStat> {
        val raw = prefs.getString(KEY_ARTISTS, null) ?: return emptyMap()

        return try {
            val array = JSONArray(raw)
            val result = mutableMapOf<String, ArtistListenStat>()

            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val key = obj.optString("key").trim()
                if (key.isBlank()) continue

                result[key] = ArtistListenStat(
                    artistName = obj.optString("artistName"),
                    playCount = obj.optInt("playCount", 0),
                    completedCount = obj.optInt("completedCount", 0),
                    lastPlayedAt = obj.optLong("lastPlayedAt", 0L)
                )
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveArtists(artists: Map<String, ArtistListenStat>) {
        val array = JSONArray()

        artists.forEach { (key, stat) ->
            array.put(
                JSONObject().apply {
                    put("key", key)
                    put("artistName", stat.artistName)
                    put("playCount", stat.playCount)
                    put("completedCount", stat.completedCount)
                    put("lastPlayedAt", stat.lastPlayedAt)
                }
            )
        }

        prefs.edit().putString(KEY_ARTISTS, array.toString()).apply()
    }
}
