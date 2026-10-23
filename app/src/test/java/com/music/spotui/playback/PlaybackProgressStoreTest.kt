package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Properties

class PlaybackProgressStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val idA = "1234567890abcdefghijkl"
    private val idB = "abcdefghijkl1234567890"
    private val a = SongsModel(1, "A", "Album", "Artist", "", "spotify:track:$idA|A Artist", idA)
    private val b = a.copy(id = 2, title = "B", spotifyTrackId = idB, url = "spotify:track:$idB|B Artist")
    private val episode = a.copy(mediaType = MediaType.PODCAST_EPISODE, url = "episode:$idA", podcastShowId = "show")
    private val values = mutableMapOf<String, Long>()
    private fun store() = PlaybackProgressStore(values::get) { key, value -> values[key] = value }

    @Test fun returningToTrackAfterAnotherTrackIgnoresOldPerSongPositions() {
        val store = store()
        values[PlaybackProgressStore.key(a)!!] = 7_250
        values[PlaybackProgressStore.key(b)!!] = 18_500
        assertEquals(0L, store.position(a, legacy = PlaybackSnapshot(b, 18_500)))
        assertEquals(0L, store.position(b, legacy = PlaybackSnapshot(a, 7_250)))
        assertEquals(0L, store.position(a))
    }

    @Test fun returningToPodcastAfterAnotherPodcastRestoresEachPosition() {
        val other = episode.copy(spotifyTrackId = idB, url = "episode:$idB")
        store().save(episode, 52_000)
        store().save(other, 8_000)
        assertEquals(52_000L, store().position(episode))
        assertEquals(8_000L, store().position(other))
    }

    @Test fun trackAndEpisodeWithSameSpotifyAndUiIdsNeverShareProgress() {
        store().save(a, 20_000)
        store().save(episode, 150_000)
        assertEquals(20_000L, store().position(a, legacy = PlaybackSnapshot(a, 20_000)))
        assertEquals(0L, store().position(a, legacy = PlaybackSnapshot(episode, 150_000)))
        assertEquals(150_000L, store().position(episode))
    }

    @Test fun numericUiCollisionDoesNotRestoreAnotherTracksPosition() {
        assertEquals(0L, store().position(b.copy(id = a.id), legacy = PlaybackSnapshot(a, 25_000)))
    }

    @Test fun subSecondProgressIsNotDiscarded() {
        assertEquals(450L, store().position(a, legacy = PlaybackSnapshot(a, 450)))
    }

    @Test fun onlyOutgoingEpisodesKeepIndependentBookmarksAfterAnotherSelection() {
        store().save(episode, 23_750)
        store().save(a, 8_000)
        assertEquals(23_750L, store().position(episode, legacy = PlaybackSnapshot(a, 8_000)))
        assertEquals(0L, store().position(a, legacy = PlaybackSnapshot(b, 1_000)))
        assertNull(values[PlaybackProgressStore.key(a)])
    }

    @Test fun stableSpotifyIdentitySurvivesChangedMetadataAndPlaybackUrl() {
        assertEquals(34_000L, store().position(a.copy(title = "Renamed", url = "file:///downloads/a.mp3"),
            legacy = PlaybackSnapshot(a, 34_000)))
        store().save(episode, 72_000)
        assertEquals(72_000L, store().position(episode.copy(url = "https://cdn.test/new-signed-url.mp3")))
    }

    @Test fun internalRssIdentityRetainsFullGuidAndShow() {
        val internal = episode.copy(spotifyTrackId = "", url = "episode:show:guid:part:1")
        store().save(internal, 90_000)
        assertEquals(90_000L, store().position(internal))
        assertEquals(0L, store().position(internal.copy(url = "episode:show:guid:part:2")))
        assertEquals(0L, store().position(internal.copy(url = "episode:other:guid:part:1", podcastShowId = "other")))
    }

    @Test fun explicitSeekPositionIncludingZeroOverridesAutomaticResume() {
        store().save(a, 40_000)
        assertEquals(0L, store().position(a, requested = 0))
        assertEquals(2_000L, store().position(a, requested = 2_000))
        assertEquals(40_000L, store().position(a, requested = Long.MIN_VALUE, legacy = PlaybackSnapshot(a, 40_000)))
    }

    @Test fun seekingBackToZeroPersistsZero() {
        store().save(episode, 120_000)
        store().save(episode, 0)
        assertEquals(0L, store().position(episode, legacy = PlaybackSnapshot(episode, 120_000)))
    }

    @Test fun naturalCompletionResetsOnlyCompletedItem() {
        val other = episode.copy(spotifyTrackId = idB, url = "episode:$idB")
        store().save(episode, 150_000)
        store().save(other, 20_000)
        store().save(episode, 180_000, completed = true)
        assertEquals(0L, store().position(episode))
        assertEquals(20_000L, store().position(other))
    }

    @Test fun legacyLastItemIsMigratedOnlyForMatchingTypedIdentity() {
        val old = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(PlaybackSnapshot(episode, 93_000)))!!
        assertEquals(0L, store().position(a, legacy = old))
        assertEquals(93_000L, store().position(episode, legacy = old))
        assertEquals(93_000L, store().position(episode))
    }

    @Test fun storedEpisodeProgressWinsOverOlderLastItemSnapshot() {
        store().save(episode, 0)
        assertEquals(0L, store().position(episode, legacy = PlaybackSnapshot(episode, 90_000)))
    }

    @Test fun invalidOrContradictoryIdentityCannotBorrowProgress() {
        val current = PlaybackSnapshot(a, 20_000)
        assertEquals(0L, store().position(a.copy(url = "spotify:track:$idB"), legacy = current))
        assertEquals(0L, store().position(episode.copy(mediaType = MediaType.TRACK), legacy = current))
        assertEquals(0L, store().position(SongsModel().copy(title = a.title, id = a.id), legacy = current))
    }

    @Test fun negativeMeasurementsDoNotEraseSavedProgress() {
        store().save(episode, 10_000)
        store().save(episode, -1)
        assertEquals(10_000L, store().position(episode))
        assertEquals(0L, store().position(a, legacy = PlaybackSnapshot(a, -1)))
    }

    @Test fun staleCallbackCannotWriteProgressForNewOwner() {
        val ownership = PlaybackOwnership()
        val old = ownership.begin(a.url, a.mediaType, a)
        ownership.activate(old.token)
        store().save(a, 12_000)
        val fresh = ownership.begin(episode.url, episode.mediaType, episode, external = true, mediaId = episode.url)
        ownership.activate(fresh.token, episode.url)
        store().save(episode, 31_000)
        assertFalse(ownership.runIfCurrent(old.token) { store().save(a, 88_000) })
        assertEquals(0L, store().position(a, legacy = PlaybackSnapshot(episode, 31_000)))
        assertNull(values[PlaybackProgressStore.key(a)])
        assertEquals(31_000L, store().position(episode))
    }

    @Test fun positionsSurviveReopeningDurableStorageWithNoInMemoryState() {
        val file = temporary.newFile("progress.properties")
        fun open(): PlaybackProgressStore {
            fun readDisk() = Properties().apply { file.inputStream().use { load(it) } }
            return PlaybackProgressStore(
                read = { readDisk().getProperty(it)?.toLong() },
                write = { key, value ->
                    val data = readDisk().apply { setProperty(key, value.toString()) }
                    file.outputStream().use { data.store(it, null) }
                },
            )
        }
        open().save(a, 7_300)
        open().save(episode, 137_500)
        val reopened = open()
        assertEquals(7_300L, reopened.position(a, legacy = PlaybackSnapshotCodec.decode(
            PlaybackSnapshotCodec.encode(PlaybackSnapshot(a, 7_300)))))
        assertEquals(0L, reopened.position(a))
        assertEquals(137_500L, reopened.position(episode))
        assertEquals(0L, reopened.position(b))
    }

    @Test fun exactLegacyMusicQueryDoesNotUseTitleOrNumericId() {
        val legacy = a.copy(url = "Exact original playback query", spotifyTrackId = "")
        val current = PlaybackSnapshot(legacy, 5_000)
        assertEquals(5_000L, store().position(legacy.copy(title = "Different display", id = 99), legacy = current))
        assertEquals(0L, store().position(legacy.copy(url = "Different playback query"), legacy = current))
    }
}
