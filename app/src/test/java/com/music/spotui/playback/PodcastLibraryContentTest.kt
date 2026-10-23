package com.music.spotui.playback

import com.music.spotui.data.entity.ArtistsModel
import com.music.spotui.data.entity.LibraryEntry
import com.music.spotui.data.preferences.FollowedPodcastShow
import com.music.spotui.ui.library.LibraryFilter
import com.music.spotui.ui.library.LibraryItem
import com.music.spotui.ui.library.libraryItems
import com.music.spotui.ui.navigation.showRoute
import org.junit.Assert.*
import org.junit.Test

class PodcastLibraryContentTest {
    private fun saved(id: String, playlist: Boolean = true) =
        LibraryEntry(id, "Music $id", "Owner", "music-cover-$id", playlist)
    private fun show(id: String, name: String = "Show $id") =
        FollowedPodcastShow(id, name, "show-cover-$id", 123L)
    private val artist = ArtistsModel("Artist", "artist-cover", "shared")

    @Test fun mixedLibraryKeepsShortcutsAndMusicOrderWithDistinctEntityKeys() {
        val music = listOf(saved("liked-songs"), saved("downloaded-offline"), saved("shared"), saved("shared", false))
        val items = libraryItems(music, listOf(show("shared"), show("older")), listOf(artist), LibraryFilter.ALL, "")
        assertEquals(listOf("playlist:liked-songs", "playlist:downloaded-offline", "collection:new-episodes", "show:shared",
            "show:older", "playlist:shared", "album:shared", "artist:shared"), items.map { it.key })
        assertEquals(music, items.filterIsInstance<LibraryItem.Saved>().map { it.entry })
    }

    @Test fun podcastFilterDoesNotDependOnMusicAndOpensExactShow() {
        val followed = show("exactShowId", "Same title")
        val items = libraryItems(emptyList(), listOf(followed), listOf(artist), LibraryFilter.PODCASTS, "")
        val item = items.filterIsInstance<LibraryItem.Podcast>().single()
        assertEquals(followed, item.followed)
        assertEquals("exactShowId", item.show.id)
        assertEquals(followed.imageUrl, item.show.coverUri)
        assertTrue(showRoute(item.followed.showId, item.followed.name).startsWith("show/exactShowId?"))
    }

    @Test fun existingFiltersDoNotLeakPodcastShows() {
        val playlist = saved("p")
        val album = saved("a", false)
        fun filtered(filter: LibraryFilter) =
            libraryItems(listOf(playlist, album), listOf(show("s")), listOf(artist), filter, "")
        assertEquals(listOf(LibraryItem.Saved(playlist)), filtered(LibraryFilter.PLAYLISTS))
        assertEquals(listOf(LibraryItem.Saved(album)), filtered(LibraryFilter.ALBUMS))
        assertEquals(listOf(LibraryItem.Artist(artist)), filtered(LibraryFilter.ARTISTS))
    }

    @Test fun searchingHebrewAndEnglishRetainsExactIdentity() {
        val shows = listOf(show("he", "אור החכמה"), show("en", "The Butcher Shop"))
        fun search(query: String) = libraryItems(emptyList(), shows, emptyList(), LibraryFilter.ALL, query).map { it.key }
        assertEquals(listOf("show:he"), search("  חכמה  "))
        assertEquals(listOf("show:en"), search("BUTCHER"))
        assertTrue(search("missing").isEmpty())
    }

    @Test fun duplicateNamesRemainSeparateWhileDuplicateOrBlankIdsDoNotCreateInvalidRows() {
        val shows = listOf(show("a", "Same name"), show("b", "Same name"), show("a", "Same name"), show(" "))
        val items = libraryItems(emptyList(), shows, emptyList(), LibraryFilter.ALL, "")
        assertEquals(listOf("collection:new-episodes", "show:a", "show:b"), items.map { it.key })
    }

    @Test fun staleRemoteMusicSnapshotCannotRestoreAnUnfollowedShow() {
        val music = listOf(saved("same-id"), saved("album", false))
        val before = libraryItems(music, listOf(show("same-id")), emptyList(), LibraryFilter.ALL, "")
        val after = libraryItems(music, emptyList(), emptyList(), LibraryFilter.ALL, "")
        assertTrue(before.any { it is LibraryItem.Podcast })
        assertFalse(after.any { it is LibraryItem.Podcast })
        assertFalse(after.contains(LibraryItem.NewEpisodes))
        assertEquals(music, after.filterIsInstance<LibraryItem.Saved>().map { it.entry })
    }
    @Test fun savedEpisodesAppearInAllPodcastAndPlaylistFiltersWithoutReplacingShows() {
        listOf(LibraryFilter.ALL, LibraryFilter.PODCASTS, LibraryFilter.PLAYLISTS).forEach { filter ->
            val items = libraryItems(listOf(saved("liked-songs")), listOf(show("podcast")), emptyList(), filter, "", 3)
            assertEquals(3, items.filterIsInstance<LibraryItem.Episodes>().single().count)
        }
        listOf(LibraryFilter.ALBUMS, LibraryFilter.ARTISTS).forEach { filter ->
            assertTrue(libraryItems(emptyList(), emptyList(), emptyList(), filter, "", 3).isEmpty())
        }
    }
    @Test fun savedEpisodeCollectionSupportsSearchAndDisappearsWhenEmpty() {
        assertTrue(libraryItems(emptyList(), emptyList(), emptyList(), LibraryFilter.ALL, "", 0).isEmpty())
        listOf(" הפרקים ", "EPISODES").forEach { query ->
            assertEquals(listOf("collection:saved-episodes"),
                libraryItems(emptyList(), emptyList(), emptyList(), LibraryFilter.ALL, query, 2).map { it.key })
        }
        assertTrue(libraryItems(emptyList(), emptyList(), emptyList(), LibraryFilter.ALL, "other", 2).isEmpty())
    }

    @Test fun newEpisodesRequiresAValidFollowAndHasOneStableCollectionIdentity() {
        fun collections(shows: List<FollowedPodcastShow>) =
            libraryItems(emptyList(), shows, emptyList(), LibraryFilter.ALL, "").filter { it is LibraryItem.NewEpisodes }
        assertTrue(collections(emptyList()).isEmpty())
        assertTrue(collections(listOf(show(" "))).isEmpty())
        val once = collections(listOf(show("a")))
        assertEquals(listOf(LibraryItem.NewEpisodes), once)
        assertEquals(once, collections(listOf(show("a"), show("a"), show("b"))))
    }

    @Test fun newEpisodesCanBeFoundWithoutMatchingAnyIndividualShowName() {
        for (filter in listOf(LibraryFilter.ALL, LibraryFilter.PODCASTS)) {
            for (query in listOf(" פרקים חדשים ", "NEW EPISODES")) {
                assertEquals(listOf(LibraryItem.NewEpisodes),
                    libraryItems(emptyList(), listOf(show("a")), emptyList(), filter, query))
            }
        }
        for (filter in listOf(LibraryFilter.PLAYLISTS, LibraryFilter.ALBUMS, LibraryFilter.ARTISTS)) {
            assertTrue(libraryItems(emptyList(), listOf(show("a")), emptyList(), filter, "").isEmpty())
        }
    }

    @Test fun sameNamedPlaylistCannotReplaceTheAutomaticCollectionOrKeepItAfterUnfollow() {
        val playlist = saved("new-episodes").copy(name = "פרקים חדשים")
        val followed = libraryItems(listOf(playlist), listOf(show("a")), emptyList(), LibraryFilter.ALL, "פרקים חדשים")
        assertEquals(listOf("collection:new-episodes", "playlist:new-episodes"), followed.map { it.key })
        assertEquals(listOf(LibraryItem.Saved(playlist)), followed.filterIsInstance<LibraryItem.Saved>())
        val unfollowed = libraryItems(listOf(playlist), emptyList(), emptyList(), LibraryFilter.ALL, "פרקים חדשים")
        assertEquals(listOf(LibraryItem.Saved(playlist)), unfollowed)
    }
}
