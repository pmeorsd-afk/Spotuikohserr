package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

private const val PREF_LEGACY = "LikedSongs"
private const val PREF_DATA = "LikedSongs_Data"

private val _likedSongsRevision = MutableStateFlow(0L)
val likedSongsRevision: StateFlow<Long> = _likedSongsRevision.asStateFlow()

@Synchronized
fun notifyLikedSongsChanged() {
    _likedSongsRevision.value += 1L
}

fun canonicalKey(song: SongsModel): String {
    if (song.spotifyTrackId.isNotBlank()) return "spotify:${song.spotifyTrackId}"
    if (song.url.isNotBlank() && (song.url.startsWith("youtube:") || song.url.startsWith("yt:"))) return song.url
    if (song.title.isNotBlank() && song.singer.isNotBlank()) {
        return "meta:${song.title.trim().lowercase()}|${song.singer.trim().lowercase()}"
    }
    return "id:${song.id}"
}

private fun SongsModel.toJson(likedAt: Long = System.currentTimeMillis()): String = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("album", album)
    put("singer", singer)
    put("coverUri", coverUri)
    put("url", url)
    put("spotifyTrackId", spotifyTrackId)
    put("explicit", explicit)
    put("durationMs", durationMs)
    put("mediaType", mediaType.name)
    put("podcastShowId", podcastShowId)
    put("likedAt", likedAt)
}.toString()

private fun parseLikedSongWithTime(json: String): Pair<SongsModel, Long>? = runCatching {
    val o = JSONObject(json)
    val song = SongsModel(
        id = o.getInt("id"),
        title = o.getString("title"),
        album = o.optString("album"),
        singer = o.getString("singer"),
        coverUri = o.optString("coverUri"),
        url = o.getString("url"),
        spotifyTrackId = o.optString("spotifyTrackId"),
        explicit = o.optBoolean("explicit", false),
        durationMs = o.optInt("durationMs", 0),
        mediaType = if (o.optString("mediaType") == "PODCAST_EPISODE" || o.optString("url").startsWith("episode:"))
            com.music.spotui.data.entity.MediaType.PODCAST_EPISODE else com.music.spotui.data.entity.MediaType.TRACK,
        podcastShowId = o.optString("podcastShowId"),
    )
    val likedAt = o.optLong("likedAt", 0L)
    song to likedAt
}.getOrNull()

private fun parseLikedSong(json: String): SongsModel? = parseLikedSongWithTime(json)?.first

fun addLikedSong(context: Context, song: SongsModel) {
    if (song.isPodcast()) { saveEpisode(context, song); return }
    if (song.title.isBlank() && song.singer.isBlank() && song.url.isBlank()) return

    // 1. Write to legacy prefs for backward compatibility
    val legacy = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    val legEditor = legacy.edit()
    legEditor.putString(song.id.toString(), song.id.toString())
    if (song.spotifyTrackId.isNotBlank()) {
        legEditor.putString(song.spotifyTrackId, song.spotifyTrackId)
    }
    legEditor.apply()

    // 2. Write full model to LikedSongs_Data
    val dataPrefs = context.getSharedPreferences(PREF_DATA, Context.MODE_PRIVATE)
    val key = canonicalKey(song)
    preserveLegacyEpisode(context, dataPrefs.getString(key, null))
    dataPrefs.edit().putString(key, song.toJson()).apply()

    notifyLikedSongsChanged()
}

fun removeLikedSong(context: Context, song: SongsModel) {
    if (song.isPodcast()) { removeSavedEpisode(context, song); return }
    val legacy = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    val legEditor = legacy.edit()
    legEditor.remove(song.id.toString())
    if (song.spotifyTrackId.isNotBlank()) {
        legEditor.remove(song.spotifyTrackId)
    }
    legEditor.apply()

    val dataPrefs = context.getSharedPreferences(PREF_DATA, Context.MODE_PRIVATE)
    val key = canonicalKey(song)
    val editor = dataPrefs.edit()
    listOf(key, "id:${song.id}", "spotify:${song.spotifyTrackId}", song.url).forEach { candidate ->
        if (dataPrefs.getString(candidate, null)?.let(::parseLikedSong)?.isPodcast() != true) editor.remove(candidate)
    }

    val normTitle = song.title.trim().lowercase()
    val normSinger = song.singer.trim().lowercase()
    for ((k, v) in dataPrefs.all) {
        val s = (v as? String)?.let(::parseLikedSong) ?: continue
        if (s.isPodcast()) continue
        if (s.id == song.id ||
            (song.spotifyTrackId.isNotBlank() && s.spotifyTrackId == song.spotifyTrackId) ||
            (normTitle.isNotBlank() && normSinger.isNotBlank() &&
             s.title.trim().lowercase() == normTitle && s.singer.trim().lowercase() == normSinger)
        ) {
            editor.remove(k)
        }
    }
    editor.apply()

    notifyLikedSongsChanged()
}

fun saveLocalLikedSongs(context: Context, songs: List<SongsModel>) {
    val dataPrefs = context.getSharedPreferences(PREF_DATA, Context.MODE_PRIVATE)
    val legacy = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    val dataEditor = dataPrefs.edit()
    val legEditor = legacy.edit()

    val baseTime = System.currentTimeMillis()
    songs.forEachIndexed { index, song ->
        if (song.isPodcast()) { saveEpisode(context, song); return@forEachIndexed }
        legEditor.putString(song.id.toString(), song.id.toString())
        if (song.spotifyTrackId.isNotBlank()) {
            legEditor.putString(song.spotifyTrackId, song.spotifyTrackId)
        }
        val key = canonicalKey(song)
        if (!dataPrefs.contains(key) || dataPrefs.getString(key, null)?.let(::parseLikedSong)?.isPodcast() == true) {
            preserveLegacyEpisode(context, dataPrefs.getString(key, null))
            dataEditor.putString(key, song.toJson(baseTime - index * 1000L))
        }
    }
    dataEditor.apply()
    legEditor.apply()

    notifyLikedSongsChanged()
}

fun getLocallyLikedSongs(context: Context): List<SongsModel> {
    val dataPrefs = context.getSharedPreferences(PREF_DATA, Context.MODE_PRIVATE)
    val parsed = dataPrefs.all.values.mapNotNull { v ->
        (v as? String)?.let(::parseLikedSongWithTime)
    }
    return parsed
        .filterNot { it.first.isPodcast() }
        .sortedByDescending { it.second }
        .map { it.first }
        .distinctBy { canonicalKey(it) }
}

fun getLikedSongsCount(context: Context): Int {
    val local = getLocallyLikedSongs(context)
    if (local.isNotEmpty()) {
        return local.size
    }
    val legacy = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    val episodeIds = legacySavedEpisodes(context).map { it.first.id }.toSet()
    return (legacy.all.keys.mapNotNull { it.toIntOrNull() }.toSet() - episodeIds).size
}

fun isSongLiked(
    context: Context,
    songId: String,
    spotifyTrackId: String = "",
    url: String = "",
    title: String = "",
    singer: String = "",
): Boolean {
    val legacy = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    val dataPrefs = context.getSharedPreferences(PREF_DATA, Context.MODE_PRIVATE)
    val parsed = dataPrefs.all.values.mapNotNull { (it as? String)?.let(::parseLikedSong) }
    val episodeOnlyIds = parsed.filter { it.isPodcast() }.flatMap { listOf(it.id.toString(), it.spotifyTrackId) }.toSet() -
        parsed.filterNot { it.isPodcast() }.flatMap { listOf(it.id.toString(), it.spotifyTrackId) }.toSet()
    if (songId.isNotBlank() && songId !in episodeOnlyIds && legacy.contains(songId)) return true
    if (spotifyTrackId.isNotBlank() && spotifyTrackId !in episodeOnlyIds && legacy.contains(spotifyTrackId)) return true
    fun containsMusic(key: String) = dataPrefs.contains(key) && dataPrefs.getString(key, null)?.let(::parseLikedSong)?.isPodcast() != true
    if (spotifyTrackId.isNotBlank() && containsMusic("spotify:$spotifyTrackId")) return true
    if (url.isNotBlank() && containsMusic(url)) return true
    if (title.isNotBlank() && singer.isNotBlank()) {
        val metaKey = "meta:${title.trim().lowercase()}|${singer.trim().lowercase()}"
        if (containsMusic(metaKey)) return true
    }
    if (songId.isNotBlank() && containsMusic("id:$songId")) return true

    val normTitle = title.trim().lowercase()
    val normSinger = singer.trim().lowercase()
    for (v in dataPrefs.all.values) {
        val s = (v as? String)?.let(::parseLikedSong) ?: continue
        if (s.isPodcast()) continue
        if (songId.isNotBlank() && s.id.toString() == songId) return true
        if (spotifyTrackId.isNotBlank() && s.spotifyTrackId == spotifyTrackId) return true
        if (url.isNotBlank() && s.url == url) return true
        if (normTitle.isNotBlank() && normSinger.isNotBlank() &&
            s.title.trim().lowercase() == normTitle && s.singer.trim().lowercase() == normSinger
        ) {
            return true
        }
    }
    return false
}

fun isSongLiked(context: Context, song: SongsModel): Boolean =
    if (song.isPodcast()) isEpisodeSaved(context, song)
    else isSongLiked(context, song.id.toString(), song.spotifyTrackId, song.url, song.title, song.singer)

fun isSongLiked(context: Context, songId: String): Boolean =
    isSongLiked(context, songId = songId, spotifyTrackId = "", url = "", title = "", singer = "")

fun addLikedSongId(context: Context, songId: String) {
    val sharedPreferences = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    sharedPreferences.edit().putString(songId, songId).apply()
    notifyLikedSongsChanged()
}

fun removeLikedSongId(context: Context, songId: String) {
    val legacy = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    legacy.edit().remove(songId).apply()

    val dataPrefs = context.getSharedPreferences(PREF_DATA, Context.MODE_PRIVATE)
    val editor = dataPrefs.edit()
    listOf("id:$songId", songId).forEach { key ->
        if (dataPrefs.getString(key, null)?.let(::parseLikedSong)?.isPodcast() != true) editor.remove(key)
    }
    for ((k, v) in dataPrefs.all) {
        val s = (v as? String)?.let(::parseLikedSong) ?: continue
        if (s.isPodcast()) continue
        if (s.id.toString() == songId || s.spotifyTrackId == songId) {
            editor.remove(k)
        }
    }
    editor.apply()
    notifyLikedSongsChanged()
}

fun getLikedSongIds(context: Context): Set<Int> {
    val sharedPreferences = context.getSharedPreferences(PREF_LEGACY, Context.MODE_PRIVATE)
    val fromLegacy = sharedPreferences.all.keys.mapNotNull { it.toIntOrNull() }.toSet()
    val fromData = getLocallyLikedSongs(context).map { it.id }.toSet()
    val episodes = legacySavedEpisodes(context).map { it.first.id }.toSet()
    return (fromLegacy - episodes) + fromData
}

fun getSongsByIds(songIds: Set<Int>, songs: List<SongsModel>): List<SongsModel> {
    return songs.filter { song -> song.id in songIds }
}
