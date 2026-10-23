package com.music.spotui.data.preferences

import android.content.Context
import com.music.spotui.data.entity.LibraryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Explicit local library choices survive guest mode and delayed remote refreshes. */
data class CollectionChoice(val entry: LibraryEntry, val saved: Boolean)

internal fun collectionKey(entry: LibraryEntry) = "${if (entry.isPlaylist) "playlist" else "album"}:${entry.spotifyId}"
internal fun validCollection(entry: LibraryEntry): Boolean =
    entry.spotifyId.matches(Regex("[A-Za-z0-9]{22}")) ||
        (entry.isPlaylist && entry.spotifyId.matches(Regex("youtube:[A-Za-z0-9_-]+")))

internal fun mergeCollectionChoices(remote: List<LibraryEntry>, choices: List<CollectionChoice>): List<LibraryEntry> {
    val exact = choices.filter { validCollection(it.entry) }.associateBy { collectionKey(it.entry) }
    val remaining = remote.filter { collectionKey(it) !in exact }
    val (shortcuts, catalog) = remaining.partition { it.spotifyId in setOf("liked", "liked-songs", "downloaded", "downloaded-offline") }
    return shortcuts + exact.values.filter { it.saved }.map { it.entry } + catalog
}

internal fun encodeCollectionChoices(choices: List<CollectionChoice>): String = JSONArray().apply {
    choices.forEach { choice -> put(JSONObject().apply {
        val entry = choice.entry
        put("id", entry.spotifyId); put("playlist", entry.isPlaylist); put("name", entry.name)
        put("subtitle", entry.subtitle); put("cover", entry.coverUri); put("artists", entry.artists)
        put("saved", choice.saved)
    }) }
}.toString()

internal fun decodeCollectionChoices(raw: String): List<CollectionChoice> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { index ->
        val obj = array.optJSONObject(index) ?: return@mapNotNull null
        val entry = LibraryEntry(obj.optString("id"), obj.optString("name"), obj.optString("subtitle"),
            obj.optString("cover"), obj.optBoolean("playlist"), obj.optString("artists"))
        if (validCollection(entry)) CollectionChoice(entry, obj.optBoolean("saved")) else null
    }
}.getOrDefault(emptyList())

private val collectionRevision = MutableStateFlow(0L)
val savedCollectionsRevision = collectionRevision.asStateFlow()
private fun collectionChoices(context: Context) = decodeCollectionChoices(context
    .getSharedPreferences("SavedCollections", Context.MODE_PRIVATE).getString("choices", "[]").orEmpty())

fun withSavedCollections(context: Context, remote: List<LibraryEntry>): List<LibraryEntry> =
    mergeCollectionChoices(remote, collectionChoices(context))

fun isCollectionSaved(context: Context, entry: LibraryEntry): Boolean =
    collectionChoices(context).lastOrNull { collectionKey(it.entry) == collectionKey(entry) }?.saved
        ?: getCachedLibraryEntries(context).any { collectionKey(it) == collectionKey(entry) }

@Synchronized
fun setCollectionSaved(context: Context, entry: LibraryEntry, saved: Boolean) {
    if (!validCollection(entry)) return
    val choices = collectionChoices(context).filterNot { collectionKey(it.entry) == collectionKey(entry) } + CollectionChoice(entry, saved)
    context.getSharedPreferences("SavedCollections", Context.MODE_PRIVATE).edit()
        .putString("choices", encodeCollectionChoices(choices)).apply()
    cacheLibraryEntries(context, mergeCollectionChoices(getCachedLibraryEntries(context), choices))
    com.music.spotui.data.api.Api.HomeCache.library = null
    collectionRevision.value++
}
