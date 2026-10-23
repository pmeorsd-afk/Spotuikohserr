package com.music.spotui.ui.library

import android.content.Context
import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPlaylistTrack
import com.music.spotui.data.api.SpotifyTokenProvider
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.CustomPlaylistStore
import com.music.spotui.data.preferences.remoteSavedItemUri
import com.music.spotui.data.preferences.remoteSavedMembershipStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

internal data class SavePlaylist(val id: String, val name: String, val count: Int, val cover: String, val local: Boolean)

internal fun savedItemSpotifyUri(song: SongsModel): String? = remoteSavedItemUri(song)

internal fun localSavePlaylists(context: Context) = CustomPlaylistStore.getPlaylists(context).map {
    SavePlaylist(it.id, it.name, it.songKeys.size, it.coverUri, true)
}

/** Names are not identity: same-name playlists remain separate, pagination includes every page. */
internal fun mergeSavePlaylists(local: List<SavePlaylist>, remote: List<SavePlaylist>): List<SavePlaylist> =
    (local + remote).distinctBy { it.id }

internal suspend fun remoteSavePlaylists(context: Context, onPage: suspend (List<SavePlaylist>) -> Unit) {
    val membership = remoteSavedMembershipStore(context)
    check(SpotifyTokenProvider.ensureToken(context.applicationContext)) { "Spotify authentication unavailable" }
    val me = Spotify.me().getOrThrow().id
    val all = mutableListOf<SavePlaylist>()
    var offset = 0
    do {
        val page = Spotify.myPlaylists(limit = 50, offset = offset).getOrThrow()
        check(page.limit > 0 && page.offset == offset) { "Playlist pagination did not advance" }
        all += page.items.filter { it.id.isNotBlank() && (it.owner?.id == me || it.collaborative) }
            .map { SavePlaylist(it.id, it.name, it.tracks?.total ?: 0, it.images.firstOrNull()?.url.orEmpty(), false) }
        onPage(all.distinctBy { it.id })
        offset += page.limit
    } while (offset < page.total)
    membership?.retainPlaylists(all.map { it.id }.toSet())
}

private suspend fun remotePlaylistItems(context: Context, id: String): List<SpotifyPlaylistTrack> {
    check(SpotifyTokenProvider.ensureToken(context.applicationContext)) { "Spotify authentication unavailable" }
    val all = mutableListOf<SpotifyPlaylistTrack>()
    var offset = 0
    do {
        val page = Spotify.playlistPage(id, limit = 100, offset = offset).getOrThrow()
        all += page.tracks.items
        if (page.consumedItems == 0) { check(offset >= page.tracks.total) { "Incomplete playlist response" }; break }
        offset += page.consumedItems
    } while (offset < page.tracks.total)
    return all
}

internal suspend fun remoteSaveMembership(context: Context, id: String, song: SongsModel): Boolean {
    val membership = remoteSavedMembershipStore(context)
    val uri = requireNotNull(savedItemSpotifyUri(song))
    return remotePlaylistItems(context, id).any { it.track?.uri == uri }.also { membership?.record(id, song, it) }
}

internal suspend fun setRemoteSaveMembership(context: Context, id: String, song: SongsModel, selected: Boolean): Boolean {
    try {
        val membership = remoteSavedMembershipStore(context)
        val uri = savedItemSpotifyUri(song) ?: return false
        val matches = remotePlaylistItems(context, id).filter { it.track?.uri == uri }
        val success = if (selected) matches.isNotEmpty() || Spotify.addTracksToPlaylist(id, listOf(uri)).isSuccess
        else if (matches.isEmpty()) true
        else {
            // All occurrences need a real playlist-scoped uid, otherwise do not report removal.
            val refs = matches.map { Spotify.PlaylistItemRef(uri, it.uid ?: return false) }
            Spotify.removeTracksFromPlaylist(id, refs).isSuccess
        }
        if (success) membership?.record(id, song, selected)
        return success
    } catch (_: TimeoutCancellationException) { return false }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { return false }
}
