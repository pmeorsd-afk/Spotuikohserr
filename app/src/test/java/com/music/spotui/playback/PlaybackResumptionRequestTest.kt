package com.music.spotui.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.ui.notification.PlaybackResumptionRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException

class PlaybackResumptionRequestTest {
    private val id = "1234567890abcdefghijkl"
    private val track = SongsModel(1, "Track", "Album", "Artist", "", "spotify:track:$id", id)
    private val episode = track.copy(title = "Episode", album = "Show", singer = "Publisher",
        url = "episode:$id", mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show")

    private fun metadata(saved: PlaybackSnapshot?) = PlaybackResumptionRequest.dispatch(
        hasTransportRequest = false,
        loadSaved = { saved },
        artwork = { null },
        resumePlayback = { error("A recent-item query must not start playback or resolve audio") },
    )

    @Test fun bootReturnsSavedTrackMetadataWithoutAnAudioSourceOrPlayerTicket() {
        val saved = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(PlaybackSnapshot(track, 51_000)))
        val future = metadata(saved)
        assertTrue(future.isDone)
        val result = future.get()
        val item = result.mediaItems.single()
        assertEquals(track.url, item.mediaId)
        assertEquals(track.title, item.mediaMetadata.title)
        assertEquals(track.singer, item.mediaMetadata.artist)
        assertEquals(track.album, item.mediaMetadata.albumTitle)
        assertEquals(MediaMetadata.MEDIA_TYPE_MUSIC, item.mediaMetadata.mediaType)
        assertEquals(true, item.mediaMetadata.isPlayable)
        assertEquals(false, item.mediaMetadata.isBrowsable)
        assertEquals(0, result.startIndex)
        assertEquals(51_000L, result.startPositionMs)
        assertNull(item.localConfiguration) // Neither a stream URI nor an ownership tag.
        assertNull(item.requestMetadata.mediaUri)
    }

    @Test fun podcastMetadataPreservesExactEpisodeIdentityWithoutResolvingRss() {
        for (song in listOf(episode,
            episode.copy(url = "https://audio.example.test/exact.mp3"),
            episode.copy(url = "episode:show:rss:key:42", spotifyTrackId = ""))) {
            val result = metadata(PlaybackSnapshot(song, 78_000)).get()
            val item = result.mediaItems.single()
            assertEquals(song.url, item.mediaId)
            assertEquals(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE, item.mediaMetadata.mediaType)
            assertEquals("Episode", item.mediaMetadata.title)
            assertEquals("Publisher", item.mediaMetadata.artist)
            assertEquals(78_000L, result.startPositionMs)
            assertNull(item.localConfiguration)
        }
    }

    @Test fun absentOrContradictoryHistoryFailsWithoutChoosingAnotherItem() {
        val invalid = listOf(null,
            PlaybackSnapshot(track.copy(url = ""), 0),
            PlaybackSnapshot(track.copy(spotifyTrackId = "abcdefghijkl1234567890"), 0),
            PlaybackSnapshot(episode.copy(url = track.url), 0),
            PlaybackSnapshot(episode.copy(url = "episode:different-show:rss", spotifyTrackId = ""), 0))
        for (saved in invalid) {
            val future = PlaybackResumptionRequest.dispatch(false, { saved },
                artwork = { error("Invalid history must not expose artwork") },
                resumePlayback = { error("Invalid boot history must not start playback") })
            assertTrue(runCatching { future.get() }.exceptionOrNull() is ExecutionException)
        }
    }

    @Test fun corruptSavedStateFailsItsOwnFutureWithoutCallingPlay() {
        val failure = IllegalStateException("Cannot read saved state")
        val future = PlaybackResumptionRequest.dispatch(false,
            loadSaved = { throw failure }, artwork = { null },
            resumePlayback = { error("A failed metadata read must not fall back to Play") })
        assertSame(failure, runCatching { future.get() }.exceptionOrNull()?.cause)
    }

    @Test fun metadataBookmarkUsesTheSameNonnegativeNormalizationAsPlayback() {
        assertEquals(0L, metadata(PlaybackSnapshot(track, -1)).get().startPositionMs)
    }

    @Test fun explicitPlayAfterBootUsesTheExistingTransportFutureAndNotCachedMetadata() {
        var resumes = 0
        var metadataReads = 0
        val transport = SettableFuture.create<MediaItemsWithStartPosition>()
        fun request(transportInScope: Boolean) = PlaybackResumptionRequest.dispatch(transportInScope,
            loadSaved = { metadataReads++; PlaybackSnapshot(track, 51_000) },
            artwork = { null }, resumePlayback = { resumes++; transport })

        repeat(3) { assertEquals(track.url, request(false).get().mediaItems.single().mediaId) }
        assertEquals(0, resumes)
        val play = request(true)
        assertSame(transport, play)
        assertFalse(play.isDone)
        assertEquals(1, resumes)
        assertEquals(3, metadataReads)
        val prepared = MediaItemsWithStartPosition(listOf(MediaItem.Builder().setMediaId(track.url).build()), 0, 51_000)
        transport.set(prepared)
        assertSame(prepared, play.get())
    }

    @Test fun metadataQueryDuringCarResumeCannotJoinCancelOrConsumeThePreparedPlay() {
        val owner = PlaybackOwnership()
        val ticket = owner.begin(track.url, MediaType.TRACK, track)
        val item = MediaItem.Builder().setMediaId(track.url).build()
        val marker = PreparedResumption<MediaItem>().apply { set(item, ticket.token) }
        val transport = SettableFuture.create<MediaItemsWithStartPosition>()
        var resumes = 0
        fun request(transportInScope: Boolean): ListenableFuture<MediaItemsWithStartPosition> =
            PlaybackResumptionRequest.dispatch(transportInScope,
                loadSaved = { PlaybackSnapshot(episode, 78_000) }, artwork = { null },
                resumePlayback = { resumes++; transport })

        val carPlay = request(true)
        val recent = request(false)
        assertNotSame(carPlay, recent)
        assertEquals(episode.url, recent.get().mediaItems.single().mediaId)
        recent.cancel(true)
        assertFalse(carPlay.isDone)
        assertFalse(carPlay.isCancelled)
        assertSame(ticket, owner.current)
        assertTrue(marker.consume(listOf(item), item, ticket.token))
        assertEquals(1, resumes)
        val prepared = MediaItemsWithStartPosition(listOf(item), 0, 51_000)
        transport.set(prepared)
        assertSame(prepared, carPlay.get())
    }
}
