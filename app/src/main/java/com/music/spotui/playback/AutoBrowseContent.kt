package com.music.spotui.playback

import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.PodcastModel

/** Car folders retain the source entity's real identity; display names are never lookup keys. */
internal object AutoBrowseContent {
    fun album(album: AlbumsModel): AutoBrowseCatalog.Entry? = AutoBrowseCatalog.Entry(
        kind = AutoBrowseCatalog.Kind.ALBUM,
        id = album.spotifyId,
        title = album.name,
        subtitle = album.artists,
        artwork = album.coverUri,
        artist = album.artists,
    ).takeIf { it.isValid() }

    fun artist(artist: ArtistsModel): AutoBrowseCatalog.Entry? = AutoBrowseCatalog.Entry(
        kind = AutoBrowseCatalog.Kind.ARTIST,
        id = artist.id,
        title = artist.name,
        artwork = artist.coverUri,
        artist = artist.name,
    ).takeIf { it.isValid() }

    fun collection(collection: LibraryEntry): AutoBrowseCatalog.Entry? = AutoBrowseCatalog.Entry(
        kind = if (collection.isPlaylist) AutoBrowseCatalog.Kind.PLAYLIST else AutoBrowseCatalog.Kind.ALBUM,
        id = collection.spotifyId,
        title = collection.name,
        subtitle = collection.subtitle,
        artwork = collection.coverUri,
        artist = collection.artists,
    ).takeIf { it.isValid() }

    fun show(show: PodcastModel): AutoBrowseCatalog.Entry? = AutoBrowseCatalog.Entry(
        kind = AutoBrowseCatalog.Kind.SHOW,
        id = show.id,
        title = show.name,
        subtitle = show.publisher,
        artwork = show.coverUri,
    ).takeIf { it.isValid() }

    fun homeFolder(item: HomeItem): AutoBrowseCatalog.Entry? = when (item) {
        is HomeItem.Album -> AutoBrowseCatalog.Entry(
            kind = AutoBrowseCatalog.Kind.ALBUM,
            id = item.spotifyId,
            title = item.name,
            subtitle = item.subtitle,
            artwork = item.imageUrl,
            artist = item.artists,
        )
        is HomeItem.Artist -> AutoBrowseCatalog.Entry(
            kind = AutoBrowseCatalog.Kind.ARTIST,
            id = item.id,
            title = item.name,
            artwork = item.imageUrl,
            artist = item.name,
        )
        is HomeItem.Playlist -> AutoBrowseCatalog.Entry(
            kind = AutoBrowseCatalog.Kind.PLAYLIST,
            id = item.id,
            title = item.name,
            subtitle = item.subtitle,
            artwork = item.imageUrl,
        )
        is HomeItem.Track, is HomeItem.LikedSongs -> null
    }?.takeIf { it.isValid() }
}
