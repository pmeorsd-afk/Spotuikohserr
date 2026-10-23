package com.music.spotui.playback

import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class AutoBrowseContentTest {
    private val album = AlbumsModel(12345, "Artist", "https://example.test/cover.jpg", "Album", "2026", spotifyId = "exact-album-id")

    @Test fun albumUsesTheSpotifyIdentityInsteadOfTheNumericUiHash() {
        val entry = AutoBrowseContent.album(album)!!
        assertEquals(AutoBrowseCatalog.Kind.ALBUM, entry.kind)
        assertEquals("exact-album-id", entry.id)
        assertEquals("Album", entry.title)
        assertEquals("Artist", entry.subtitle)
        assertEquals("Artist", entry.artist)
        assertEquals(album.coverUri, entry.artwork)
        assertNotEquals(album.id.toString(), entry.id)
    }

    @Test fun albumWithoutExactIdIsSkippedEvenWithAUsableTitleAndNumericId() {
        assertNull(AutoBrowseContent.album(album.copy(spotifyId = "")))
        assertNull(AutoBrowseContent.album(album.copy(spotifyId = " \t")))
    }

    @Test fun differentAlbumIdentitiesWithTheSameDisplayTitleStayDistinct() {
        val first = AutoBrowseContent.album(album)!!
        val second = AutoBrowseContent.album(album.copy(spotifyId = "other-album-id"))!!
        assertEquals(first.title, second.title)
        assertNotEquals(first.mediaId, second.mediaId)
    }

    @Test fun artistIdentityAndArtworkRemainExact() {
        val source = ArtistsModel("RAM", "https://example.test/artist.jpg", "exact-artist-id")
        val entry = AutoBrowseContent.artist(source)!!
        assertEquals(AutoBrowseCatalog.Kind.ARTIST, entry.kind)
        assertEquals(source.id, entry.id)
        assertEquals(source.name, entry.title)
        assertEquals(source.name, entry.artist)
        assertEquals(source.coverUri, entry.artwork)
        assertNull(AutoBrowseContent.artist(source.copy(id = "")))
    }

    @Test fun libraryCollectionRetainsItsOriginalTypeAndFields() {
        val source = LibraryEntry("collection-id", "Collection", "Subtitle", "https://example.test/item.jpg", false, "Credited artist")
        val albumEntry = AutoBrowseContent.collection(source)!!
        val playlistEntry = AutoBrowseContent.collection(source.copy(isPlaylist = true))!!
        assertEquals(AutoBrowseCatalog.Kind.ALBUM, albumEntry.kind)
        assertEquals(AutoBrowseCatalog.Kind.PLAYLIST, playlistEntry.kind)
        assertNotEquals(albumEntry.mediaId, playlistEntry.mediaId)
        assertEquals(source.spotifyId, albumEntry.id)
        assertEquals(source.subtitle, albumEntry.subtitle)
        assertEquals(source.artists, albumEntry.artist)
        assertEquals(source.coverUri, albumEntry.artwork)
    }

    @Test fun customPlaylistIdsArePreservedWithoutSpotifyIdGuessing() {
        val source = LibraryEntry("custom:favorite-playlist/2026", "My playlist", "Local", "", true)
        assertEquals(source.spotifyId, AutoBrowseContent.collection(source)?.id)
        assertNull(AutoBrowseContent.collection(source.copy(spotifyId = "")))
    }

    @Test fun showRetainsItsPublisherAndNeverBecomesAnArtist() {
        val source = PodcastModel("show-id", "A podcast", "Publisher", "https://example.test/show.jpg")
        val entry = AutoBrowseContent.show(source)!!
        assertEquals(AutoBrowseCatalog.Kind.SHOW, entry.kind)
        assertEquals(source.id, entry.id)
        assertEquals(source.name, entry.title)
        assertEquals(source.publisher, entry.subtitle)
        assertEquals(source.coverUri, entry.artwork)
        assertEquals("", entry.artist)
        assertNull(AutoBrowseContent.show(source.copy(id = "")))
    }

    @Test fun homeAlbumsRetainSeparateSubtitleAndArtistCredit() {
        val source = HomeItem.Album("Album", "https://example.test/cover.jpg", "Recommended album", "Artist", "album-id")
        val entry = AutoBrowseContent.homeFolder(source)!!
        assertEquals(AutoBrowseCatalog.Kind.ALBUM, entry.kind)
        assertEquals(source.spotifyId, entry.id)
        assertEquals(source.subtitle, entry.subtitle)
        assertEquals(source.artists, entry.artist)
        assertEquals(source.imageUrl, entry.artwork)
        assertNull(AutoBrowseContent.homeFolder(source.copy(spotifyId = "")))
    }

    @Test fun homeArtistAndPlaylistWithTheSameIdAndTitleRemainTyped() {
        val artist = AutoBrowseContent.homeFolder(HomeItem.Artist("Same title", "artist.jpg", "same-id"))!!
        val playlist = AutoBrowseContent.homeFolder(HomeItem.Playlist("Same title", "playlist.jpg", "Playlist subtitle", "same-id"))!!
        assertEquals(AutoBrowseCatalog.Kind.ARTIST, artist.kind)
        assertEquals(AutoBrowseCatalog.Kind.PLAYLIST, playlist.kind)
        assertNotEquals(artist.mediaId, playlist.mediaId)
        assertEquals("Playlist subtitle", playlist.subtitle)
        assertNull(AutoBrowseContent.homeFolder(HomeItem.Artist("Artist", "", "")))
        assertNull(AutoBrowseContent.homeFolder(HomeItem.Playlist("Playlist", "", "", "")))
    }

    @Test fun exactTrackAndEpisodeHomeItemsNeverTurnIntoAlbumOrShowFolders() {
        val track = SongsModel(12, "Track", "Album", "Artist", "", "spotify:track:track-id", "track-id")
        val episode = track.copy(title = "Episode", mediaType = MediaType.PODCAST_EPISODE, url = "episode:episode-id", spotifyTrackId = "episode-id", podcastShowId = "show-id")
        assertNull(AutoBrowseContent.homeFolder(HomeItem.Track(track)))
        assertNull(AutoBrowseContent.homeFolder(HomeItem.Track(episode)))
        assertNull(AutoBrowseContent.homeFolder(HomeItem.LikedSongs(10)))
    }

    @Test fun blankTitlesAreNotAdvertisedDespiteKnownIdentities() {
        assertNull(AutoBrowseContent.album(album.copy(name = " ")))
        assertNull(AutoBrowseContent.artist(ArtistsModel("", "", "artist-id")))
        assertNull(AutoBrowseContent.collection(LibraryEntry("id", "\t", "", "", true)))
        assertNull(AutoBrowseContent.show(PodcastModel("show-id", "", "Publisher", "")))
        assertNull(AutoBrowseContent.homeFolder(HomeItem.Playlist("", "", "", "playlist-id")))
    }
}
