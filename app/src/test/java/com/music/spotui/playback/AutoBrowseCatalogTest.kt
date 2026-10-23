package com.music.spotui.playback

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class AutoBrowseCatalogTest {
    @Test fun exposingOnePageOfALargeLibraryKeepsEveryReturnedFolderAfterRecreation() {
        var disk: String? = null
        var writes = 0
        val catalog = AutoBrowseCatalog({ disk }, { disk = it; writes++ }, capacity = 50)
        val library = (0 until 900).map { album.copy(id = "album-$it") }
        val page = AutoMediaCatalog.page(library, 0, 50)
        assertEquals(page, catalog.registerAll(page))
        assertEquals(1, writes)
        val restored = AutoBrowseCatalog({ disk }, {}, capacity = 50)
        page.forEach { assertEquals(it, restored.find(it.mediaId)) }
        assertNull(restored.find(library[50].mediaId))
    }

    @Test fun batchRejectsInvalidAndDuplicateEntriesWithoutEvictingItsOwnReturnedTokens() {
        val catalog = AutoBrowseCatalog({ null }, {}, capacity = 2)
        val second = album.copy(id = "second")
        val returned = catalog.registerAll(listOf(album.copy(id = ""), album, album, second, album.copy(id = "third")))
        assertEquals(listOf(album, second), returned)
        returned.forEach { assertEquals(it, catalog.find(it.mediaId)) }
    }

    private val album = AutoBrowseCatalog.Entry(
        kind = AutoBrowseCatalog.Kind.ALBUM,
        id = "1234567890abcdefghijkl",
        title = "Album",
        subtitle = "Artist",
        artwork = "https://example.test/album.jpg",
        artist = "Artist",
    )

    @Test fun typedIdentitiesRemainDistinctEvenWithTheSameId() {
        val catalog = AutoBrowseCatalog({ null }, {})
        val entries = AutoBrowseCatalog.Kind.values().map { kind ->
            catalog.register(album.copy(kind = kind))!!
        }
        assertEquals(4, entries.map { it.mediaId }.toSet().size)
        entries.forEach { assertEquals(it, catalog.find(it.mediaId)) }
    }

    @Test fun foldersAndTheirMetadataSurviveRecreation() {
        var disk: String? = null
        val catalog = AutoBrowseCatalog({ disk }, { disk = it })
        val entries = AutoBrowseCatalog.Kind.values().map { kind ->
            catalog.register(album.copy(kind = kind))!!
        }
        val recreated = AutoBrowseCatalog({ disk }, {})
        entries.forEach { assertEquals(it, recreated.find(it.mediaId)) }
    }

    @Test fun metadataUpdatesRetainIdentityAndReplaceTheSavedSnapshot() {
        var disk: String? = null
        val catalog = AutoBrowseCatalog({ disk }, { disk = it })
        val original = catalog.register(album)!!
        val updated = album.copy(title = "Renamed", subtitle = "New subtitle", artwork = "https://example.test/new.jpg")
        assertEquals(original.mediaId, catalog.register(updated)?.mediaId)
        assertEquals(1, JSONArray(disk!!).length())
        assertEquals(updated, AutoBrowseCatalog({ disk }, {}).find(original.mediaId))
    }

    @Test fun tamperedItemIdCannotReuseTheSavedMediaId() {
        var disk: String? = null
        val original = AutoBrowseCatalog({ disk }, { disk = it }).register(album)!!
        val rows = JSONArray(disk!!)
        rows.getJSONObject(0).put("id", "different-id")
        val restored = AutoBrowseCatalog({ rows.toString() }, {})
        assertNull(restored.find(original.mediaId))
        assertNull(restored.find(album.copy(id = "different-id").mediaId))
    }

    @Test fun tamperedTypeCannotBecomeAnotherCollection() {
        var disk: String? = null
        val original = AutoBrowseCatalog({ disk }, { disk = it }).register(album)!!
        val rows = JSONArray(disk!!)
        rows.getJSONObject(0).put("kind", "SHOW")
        val restored = AutoBrowseCatalog({ rows.toString() }, {})
        assertNull(restored.find(original.mediaId))
        assertNull(restored.find(album.copy(kind = AutoBrowseCatalog.Kind.SHOW).mediaId))
    }

    @Test fun invalidRowsDoNotPreventRestoringOtherValidRows() {
        var disk: String? = null
        val catalog = AutoBrowseCatalog({ disk }, { disk = it })
        catalog.register(album)
        val show = album.copy(kind = AutoBrowseCatalog.Kind.SHOW)
        catalog.register(show)
        val rows = JSONArray(disk!!)
        rows.getJSONObject(0).put("kind", "UNKNOWN")
        val restored = AutoBrowseCatalog({ rows.toString() }, {})
        assertNull(restored.find(album.mediaId))
        assertEquals(show, restored.find(show.mediaId))
    }

    @Test fun nonStringIdentityAndBlankRestoredTitlesAreRejected() {
        var disk: String? = null
        val catalog = AutoBrowseCatalog({ disk }, { disk = it })
        catalog.register(album)
        val show = album.copy(kind = AutoBrowseCatalog.Kind.SHOW)
        catalog.register(show)
        val rows = JSONArray(disk!!)
        rows.getJSONObject(0).put("id", 12345)
        rows.getJSONObject(1).put("title", " ")
        val restored = AutoBrowseCatalog({ rows.toString() }, {})
        assertNull(restored.find(album.mediaId))
        assertNull(restored.find(show.mediaId))
    }

    @Test fun corruptAndUnknownIdsNeverResolveByTitleOrChooseAnotherEntry() {
        val catalog = AutoBrowseCatalog({ "not json" }, {})
        catalog.register(album)
        assertNull(catalog.find("auto-folder/unknown"))
        assertNull(catalog.find(album.id))
        assertNull(catalog.find(album.title))
    }

    @Test fun missingIdentityOrTitleIsNotPersisted() {
        var writes = 0
        val catalog = AutoBrowseCatalog({ null }, { writes++ })
        assertNull(catalog.register(album.copy(id = "")))
        assertNull(catalog.register(album.copy(id = " \n")))
        assertNull(catalog.register(album.copy(title = "")))
        assertNull(catalog.register(album.copy(title = " \t")))
        assertEquals(0, writes)
    }

    @Test fun boundedCatalogEvictsOldestRegistrationWithoutReassigningIt() {
        var disk: String? = null
        val catalog = AutoBrowseCatalog({ disk }, { disk = it }, capacity = 2)
        val old = catalog.register(album)!!
        val artist = catalog.register(album.copy(kind = AutoBrowseCatalog.Kind.ARTIST))!!
        val show = catalog.register(album.copy(kind = AutoBrowseCatalog.Kind.SHOW))!!
        val recreated = AutoBrowseCatalog({ disk }, {}, capacity = 2)
        assertNull(recreated.find(old.mediaId))
        assertEquals(artist, recreated.find(artist.mediaId))
        assertEquals(show, recreated.find(show.mediaId))
    }

    @Test fun reregisteredFolderRetainsItsPlaceWhenOlderUnrefreshedEntryIsEvicted() {
        val catalog = AutoBrowseCatalog({ null }, {}, capacity = 2)
        val artist = album.copy(kind = AutoBrowseCatalog.Kind.ARTIST)
        catalog.register(album)
        catalog.register(artist)
        catalog.register(album)
        catalog.register(album.copy(kind = AutoBrowseCatalog.Kind.SHOW))
        assertEquals(album, catalog.find(album.mediaId))
        assertNull(catalog.find(artist.mediaId))
    }

    @Test fun restoringWithSmallerCapacityKeepsNewestEntries() {
        var disk: String? = null
        val catalog = AutoBrowseCatalog({ disk }, { disk = it })
        catalog.register(album)
        val show = album.copy(kind = AutoBrowseCatalog.Kind.SHOW)
        catalog.register(show)
        val recreated = AutoBrowseCatalog({ disk }, {}, capacity = 1)
        assertNull(recreated.find(album.mediaId))
        assertEquals(show, recreated.find(show.mediaId))
    }

    @Test fun zeroCapacityIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { AutoBrowseCatalog({ null }, {}, capacity = 0) }
    }
}
