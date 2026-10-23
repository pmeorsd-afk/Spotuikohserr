package com.music.spotui.ui.notification

import android.content.Context
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.AutoBrowseCatalog
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.HomeArtworkPermissions

/** Apply the same entity-specific artwork rules as the phone at display and read time. */
internal object AutoArtworkPolicy {
    fun allowed(entry: AutoBrowseCatalog.Entry, context: Context? = null): Boolean =
        HomeArtworkPermissions.isApproved(entry.artwork) || when (entry.kind) {
        AutoBrowseCatalog.Kind.ARTIST -> KosherWhitelistManager.isArtistModelWhitelisted(
            ArtistsModel(entry.title, entry.artwork, entry.id))
        AutoBrowseCatalog.Kind.SHOW -> KosherWhitelistManager.isPodcastShowWhitelisted(
            PodcastModel(entry.id, entry.title, entry.subtitle, entry.artwork))
        AutoBrowseCatalog.Kind.ALBUM, AutoBrowseCatalog.Kind.PLAYLIST ->
            KosherWhitelistManager.isLibraryEntryWhitelisted(LibraryEntry(
                entry.id, entry.title, entry.subtitle, entry.artwork,
                entry.kind == AutoBrowseCatalog.Kind.PLAYLIST, entry.artist,
            ), context)
    }

    /** Explicit Home-cover expansion to car browse cards, never the shared Now Playing surface. */
    fun allowedBrowseSong(song: SongsModel): Boolean =
        HomeArtworkPermissions.isApproved(song.coverUri) || KosherWhitelistManager.isSongWhitelisted(song)

    fun matchesVersion(version: String?, artwork: String): Boolean =
        version == null || version == HomeArtworkPermissions.key(artwork)
}
