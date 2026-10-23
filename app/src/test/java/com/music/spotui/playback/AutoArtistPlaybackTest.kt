package com.music.spotui.playback

import com.music.spotui.data.entity.ArtistOverviewModel
import com.music.spotui.data.entity.ArtistTrackUi
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AutoArtistPlaybackTest {
    private val artist = AutoBrowseCatalog.Entry(AutoBrowseCatalog.Kind.ARTIST,
        "0123456789abcdefghijkl", "Same artist name")
    private fun track(n: Int) = SongsModel(n, "Track $n", "Album", artist.title, "",
        "spotify:track:${n.toString().padStart(22, '0')}|Track $n Artist", n.toString().padStart(22, '0'))
    private fun overview(vararg songs: SongsModel) = ArtistOverviewModel(
        id = artist.id, name = artist.title, topTracks = songs.map { ArtistTrackUi(it) })

    @Test fun searchPlayActionSurvivesCatalogRecreationAndKeepsBrowseIdentitySeparate() {
        var json: String? = null
        val catalog = AutoBrowseCatalog({ json }, { json = it })
        catalog.register(artist)
        val action = AutoArtistPlayback.mediaId(artist)!!
        assertNotEquals(artist.mediaId, action)
        assertEquals(artist.mediaId, AutoArtistPlayback.folderId(action))
        val restored = AutoBrowseCatalog({ json }, {})
        assertEquals(artist, AutoArtistPlayback.resolve(action, restored::find))
        assertEquals(artist, restored.find(artist.mediaId))
    }

    @Test fun malformedUnknownAndWrongKindTokensDoNotBecomeArtistPlayback() {
        val action = AutoArtistPlayback.mediaId(artist)!!
        assertNull(AutoArtistPlayback.resolve(action) { null })
        assertNull(AutoArtistPlayback.resolve(action) { artist.copy(id = "different") })
        for (kind in AutoBrowseCatalog.Kind.values().filter { it != AutoBrowseCatalog.Kind.ARTIST }) {
            assertNull(AutoArtistPlayback.mediaId(artist.copy(kind = kind)))
            assertNull(AutoArtistPlayback.resolve(action) { artist.copy(kind = kind) })
        }
        for (invalid in listOf(artist.mediaId, "spotify:artist:${artist.id}", "auto-artist-play/",
            "auto-artist-play/../${artist.id}", "$action?track=1", "auto-artist-play/${"f".repeat(63)}")) {
            assertNull(AutoArtistPlayback.resolve(invalid) { error("must reject before catalog access") })
        }
    }

    @Test fun randomChoiceUsesOnlyTheExactRequestedArtistsTracks() = runBlocking {
        val tracks = (1..6).map(::track)
        val selected = mutableSetOf<Int>()
        repeat(30) { seed ->
            val plan = AutoArtistPlayback.select(artist, load = { id ->
                assertEquals(artist.id, id)
                overview(*tracks.toTypedArray())
            }, random = Random(seed))!!
            assertEquals(tracks.toSet(), plan.queue.toSet())
            assertEquals(plan.song, plan.queue[plan.index])
            assertEquals(0, plan.index)
            selected += plan.song.id
        }
        assertTrue("Selection must not always choose the first track", selected.size > 1)
    }

    @Test fun anotherArtistWithTheSameNameCannotSubstituteForTheSelectedArtist() = runBlocking {
        assertNull(AutoArtistPlayback.select(artist, { overview(track(1)).copy(id = "other-artist") }))
        assertNull(AutoArtistPlayback.select(artist, { null }))
        assertNull(AutoArtistPlayback.select(artist, { overview() }))
        assertNull(AutoArtistPlayback.select(artist.copy(kind = AutoBrowseCatalog.Kind.SHOW),
            { error("must not load a show through the artist path") }))
    }

    @Test fun episodesContradictoryIdsAndNumericCollisionsCannotEnterTheArtistQueue() = runBlocking {
        val episode = track(1).copy(mediaType = MediaType.PODCAST_EPISODE, url = "episode:show:guid")
        val plan = AutoArtistPlayback.select(artist, { overview(episode, track(2).copy(url = ""),
            track(3).copy(url = track(4).url), track(5).copy(id = 99), track(6).copy(id = 99),
            track(7), track(7).copy(title = "Duplicate metadata")) })!!
        assertEquals(listOf(track(7)), plan.queue)
        assertEquals(track(7), plan.song)
        assertNull(AutoArtistPlayback.select(artist, { overview(episode) }))
    }

    @Test fun newerExternalRequestCancelsAnArtistLookupBeforeItCanStartAudio() = runBlocking {
        val owner = PlaybackOwnership()
        val requests = ExternalPlaybackRequests({ owner.current.token }, { _, _ -> error("music must not use the podcast resolver") },
            { error("music must not use the podcast resolver") })
        owner.begin("artist request", MediaType.TRACK)
        val request = requests.begin()
        val reply = CompletableDeferred<ArtistOverviewModel>()
        var playbackStarted = false
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                requests.identity(request) { AutoArtistPlayback.select(artist, { reply.await() })?.song }
                playbackStarted = true
            }
        }
        owner.begin(track(2).url, MediaType.TRACK, track(2))
        requests.begin()
        reply.complete(overview(track(1)))
        assertTrue(old.await().exceptionOrNull() is CancellationException)
        assertFalse(playbackStarted)
        assertEquals(track(2), owner.current.song)
    }

    @Test fun lookupErrorsAndCancellationAreNotConvertedIntoRandomFallbacks() = runBlocking {
        val failed = runCatching { AutoArtistPlayback.select(artist, { throw java.io.IOException("offline") }) }
        assertTrue(failed.exceptionOrNull() is java.io.IOException)
        val cancelled = runCatching { AutoArtistPlayback.select(artist, { throw CancellationException("cancelled") }) }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }
}
