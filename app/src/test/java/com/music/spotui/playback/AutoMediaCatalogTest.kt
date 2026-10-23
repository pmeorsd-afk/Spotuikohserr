package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AutoMediaCatalogTest {
    private val spotifyId = "1234567890abcdefghijkl"
    private val music = SongsModel(17, "Track", "Album", "Artist", "", "spotify:track:$spotifyId|Track Artist", spotifyId)
    private val podcast = music.copy(title = "Episode", url = "episode:$spotifyId", mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show")

    @Test fun persistedBrowseSelectionSurvivesRecreation() {
        var disk: String? = null
        val first = AutoMediaCatalog({ disk }, { disk = it }).register("recent", listOf(music, podcast))
        val recreated = AutoMediaCatalog({ disk }, { disk = it })
        assertEquals(music, recreated.find(first[0].mediaId)?.song)
        assertEquals(podcast, recreated.find(first[1].mediaId)?.song)
    }

    @Test fun numericIdCollisionDoesNotChangeMediaType() {
        val entries = AutoMediaCatalog({ null }, {}).register("recent", listOf(music, podcast))
        assertEquals(2, entries.size)
        assertNotEquals(entries[0].mediaId, entries[1].mediaId)
    }

    @Test fun sameTrackInTwoPlaylistsKeepsSeparateQueueContexts() {
        val catalog = AutoMediaCatalog({ null }, {})
        val a = catalog.register("playlist/a", listOf(music)).single()
        val b = catalog.register("playlist/b", listOf(music)).single()
        assertNotEquals(a.mediaId, b.mediaId)
        assertEquals("playlist/a", catalog.find(a.mediaId)?.parent)
    }

    @Test fun titleChangesDoNotChangeStableBrowseId() {
        assertEquals(AutoMediaCatalog.id("recent", music), AutoMediaCatalog.id("recent", music.copy(title = "Renamed")))
    }

    @Test fun internalEpisodeKeyRetainsColonsAndUrlFragments() {
        val internal = podcast.copy(url = "episode:show:https://rss.test/item:123?x=y#z", spotifyTrackId = "")
        var disk: String? = null
        val id = AutoMediaCatalog({ disk }, { disk = it }).register("show/show", listOf(internal)).single().mediaId
        assertEquals(internal, AutoMediaCatalog({ disk }, {}).find(id)?.song)
    }

    @Test fun unknownAndCorruptIdsNeverChooseAnotherItem() {
        val catalog = AutoMediaCatalog({ "not json" }, {})
        catalog.register("recent", listOf(music))
        assertNull(catalog.find("auto/unknown"))
        assertNull(catalog.find("song/17"))
    }

    @Test fun tamperedSnapshotCannotReuseIssuedId() {
        var disk: String? = null
        val id = AutoMediaCatalog({ disk }, { disk = it }).register("recent", listOf(music)).single().mediaId
        disk = disk!!.replace(spotifyId, "abcdefghijkl1234567890")
        assertNull(AutoMediaCatalog({ disk }, {}).find(id))
    }

    @Test fun legacyMetadataAndContradictoryTypeAreNotAdvertisedPlayable() {
        val invalid = listOf(music.copy(url = ""), podcast.copy(mediaType = MediaType.TRACK),
            podcast.copy(spotifyTrackId = "spotify:track:$spotifyId"))
        assertTrue(AutoMediaCatalog({ null }, {}).register("recent", invalid).isEmpty())
    }

    @Test fun boundedCatalogEvictsOldestWithoutReassigningIds() {
        var disk: String? = null
        val catalog = AutoMediaCatalog({ disk }, { disk = it }, capacity = 2)
        val old = catalog.register("old", listOf(music)).single().mediaId
        catalog.register("recent", listOf(music, podcast))
        val restored = AutoMediaCatalog({ disk }, {}, capacity = 2)
        assertNull(restored.find(old))
        assertEquals(2, restored.songs().size)
    }

    @Test fun browsingSearchIncludesBothTypesAndDeduplicatesContexts() {
        val found = AutoMediaCatalog.search("Artist", listOf(music, music, podcast))
        assertEquals(listOf(music, podcast), found)
    }

    @Test fun voiceRequiresExactUniqueTitle() {
        assertNull(AutoMediaCatalog.voiceSelection("Tra", listOf(music)))
        assertNull(AutoMediaCatalog.voiceSelection("Track", listOf(music, podcast.copy(title = "Track"))))
        assertEquals(music, AutoMediaCatalog.voiceSelection(" track ", listOf(music, music)))
        assertNull(AutoMediaCatalog.voiceSelection("", listOf(music)))
    }

    @Test fun explicitPagesDoNotDuplicateOrOverflow() {
        val items = (0..120).toList()
        assertEquals(items, (0..2).flatMap { AutoMediaCatalog.page(items, it, 50) })
        assertTrue(AutoMediaCatalog.page(items, Int.MAX_VALUE, Int.MAX_VALUE).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { AutoMediaCatalog.page(items, -1, 50) }
        assertThrows(IllegalArgumentException::class.java) { AutoMediaCatalog.page(items, 0, 0) }
    }

    @Test fun recreatedCarPodcastUsesDirectAudioAndSavedPositionWithNewOwnership() = runBlocking {
        var disk: String? = null
        val browseId = AutoMediaCatalog({ disk }, { disk = it }).register("recent", listOf(podcast)).single().mediaId
        val bookmarks = mutableMapOf<String, Long>()
        val progress = PlaybackProgressStore({ bookmarks[it] }, { key, value -> bookmarks[key] = value })
        progress.save(podcast, 45_000)
        val restored = AutoMediaCatalog({ disk }, {}).find(browseId)!!.song
        val owner = PlaybackOwnership()
        val old = owner.begin(music.url, music.mediaType, music)
        val requests = ExternalPlaybackRequests({ owner.current.token }, { song, id ->
            owner.begin(song.url, song.mediaType, song, external = true, mediaId = id)
        }, { song ->
            assertEquals(MediaType.PODCAST_EPISODE, song.mediaType)
            assertEquals(spotifyId, song.spotifyTrackId)
            PlaybackSource.DirectAudio("https://rss.test/audio.mp3")
        })
        val prepared = requests.podcast(requests.begin(), restored, progress.position(restored))
        assertEquals(45_000L, prepared.positionMs)
        assertFalse(owner.runIfCurrent(old.token) { fail("Old music committed") })
        assertTrue(owner.activate(prepared.ticket.token, prepared.ticket.mediaId))
        val selection = PlaybackQueueIdentity.select(restored)
        assertEquals(owner.active?.song, selection.queue[selection.index])
    }
}
