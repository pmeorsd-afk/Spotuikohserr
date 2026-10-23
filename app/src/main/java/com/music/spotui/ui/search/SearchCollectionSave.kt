package com.music.spotui.ui.search

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.*
import com.music.spotui.data.api.SpotifySync

internal fun AlbumsModel.searchLibraryEntry() = LibraryEntry(spotifyId, name,
    "${if (type == "single") "סינגל" else "אלבום"} • $artists", coverUri, false, artists)

internal fun RecentItem.searchLibraryEntry(): LibraryEntry? = when (type) {
    "album" -> LibraryEntry(collectionId, name, "אלבום • $singer", image, false, singer)
    "playlist" -> LibraryEntry(key, name, "פלייליסט • $singer", image, true)
    else -> null
}?.takeIf(::validCollection)

@Composable
internal fun rememberCollectionSave(entry: LibraryEntry?): Pair<Boolean, (() -> Unit)?> {
    val context = LocalContext.current
    val revision by savedCollectionsRevision.collectAsState()
    val target = entry?.takeIf(::validCollection)
    val saved = remember(target, revision) { target != null && isCollectionSaved(context, target) }
    val action: (() -> Unit)? = target?.let { collection -> {
        val next = !isCollectionSaved(context, collection)
        setCollectionSaved(context, collection, next)
        if (!collection.spotifyId.startsWith("youtube:")) {
            if (collection.isPlaylist) SpotifySync.setPlaylistSaved(context, collection.spotifyId, next)
            else SpotifySync.setAlbumSaved(context, collection.spotifyId, next)
        }
        android.widget.Toast.makeText(context, if (next) "נוסף לספרייה" else "הוסר מהספרייה", android.widget.Toast.LENGTH_SHORT).show()
    } }
    return saved to action
}
