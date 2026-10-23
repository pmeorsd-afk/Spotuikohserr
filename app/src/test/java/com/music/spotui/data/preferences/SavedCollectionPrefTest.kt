package com.music.spotui.data.preferences

import com.music.spotui.data.entity.LibraryEntry
import org.junit.Assert.*
import org.junit.Test

class SavedCollectionPrefTest {
    private val album = LibraryEntry("a".repeat(22), "Same name", "אלבום", "cover", false, "Artist")
    @Test fun guestSaveSurvivesEncodingAndEmptyRemoteRefresh() {
        val restored = decodeCollectionChoices(encodeCollectionChoices(listOf(CollectionChoice(album, true))))
        assertEquals(listOf(album), mergeCollectionChoices(emptyList(), restored))
    }
    @Test fun exactIdentityKeepsSameNamedAlbumsAndPlaylistDistinct() {
        val other = album.copy(spotifyId = "b".repeat(22))
        val playlist = album.copy(isPlaylist = true)
        val result = mergeCollectionChoices(listOf(other, playlist, album), listOf(CollectionChoice(album, true)))
        assertEquals(3, result.size)
        assertEquals(setOf(album, other, playlist), result.toSet())
    }
    @Test fun removalCannotBeResurrectedByStaleRemoteResponse() {
        assertTrue(mergeCollectionChoices(listOf(album), listOf(CollectionChoice(album, false))).isEmpty())
    }
    @Test fun latestChoiceWinsWithoutDuplicatingRemoteEntry() {
        assertEquals(listOf(album), mergeCollectionChoices(listOf(album),
            listOf(CollectionChoice(album, false), CollectionChoice(album, true))))
    }
    @Test fun hashedIdsAndNamesAreNeverUsedAsCatalogIdentities() {
        for (id in listOf("1234", "Same name", "", "spotify:track:" + "a".repeat(22))) {
            assertFalse(validCollection(album.copy(spotifyId = id)))
            assertTrue(mergeCollectionChoices(emptyList(), listOf(CollectionChoice(album.copy(spotifyId = id), true))).isEmpty())
        }
        assertFalse(validCollection(album.copy(spotifyId = "youtube:PLabc")))
        assertTrue(validCollection(album.copy(spotifyId = "youtube:PLabc", isPlaylist = true)))
    }
    @Test fun systemShortcutsRemainFirstAndCorruptionDoesNotInventEntries() {
        val liked = album.copy(spotifyId = "liked-songs", isPlaylist = true)
        assertEquals(listOf(liked, album), mergeCollectionChoices(listOf(liked), listOf(CollectionChoice(album, true))))
        assertTrue(decodeCollectionChoices("broken json").isEmpty())
        assertTrue(decodeCollectionChoices("""[{"name":"unknown"}]""").isEmpty())
    }
}
