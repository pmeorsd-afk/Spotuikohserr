package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.WazeResumeResolver.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WazeResumeResolverTest {
    private val trackId = "1234567890abcdefghijkl"
    private val episodeId = "abcdefghijkl1234567890"
    private val track = SongsModel(7, "Track", "Album", "Artist", "", "spotify:track:$trackId|Track Artist", trackId)
    private val episode = SongsModel(
        7, "Episode", "Show", "Publisher", "", "episode:$episodeId", episodeId,
        mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show-id",
    )
    private val podcast = PlaybackSnapshot(episode, 93_000)

    @Test fun trackResumeDispatchesOnlyToTrackPath() {
        val plan = WazeResumeResolver.resolve(Request(), PlaybackSnapshot(track, 12_000), null)
        var received: PlaybackSnapshot? = null
        assertTrue(WazeResumeResolver.dispatch(plan,
            { fail("Unexpected active resume") }, { received = it }, { fail("Track entered podcast route") }))
        assertEquals(track, received?.song)
        assertEquals(12_000L, received?.positionMs)
    }

    @Test fun podcastResumeNeverEntersMusicQueryPath() {
        val plan = WazeResumeResolver.resolve(Request(), podcast, null)
        var received: PlaybackSnapshot? = null
        WazeResumeResolver.dispatch(plan,
            { fail("No active player") },
            { fail("Podcast must never enter the track/query-building path") },
            { received = it })
        assertEquals(podcast, received)
        assertEquals(MediaType.PODCAST_EPISODE, received?.song?.mediaType)
        assertEquals(episodeId, received?.song?.spotifyTrackId)
        assertEquals("show-id", received?.song?.podcastShowId)
    }

    @Test fun partialWazePodcastMetadataUsesExactPersistedIdentityAndPosition() {
        val plan = WazeResumeResolver.resolve(Request(mediaType = MediaType.PODCAST_EPISODE), podcast, null)
        assertEquals(podcast, plan?.playback)
        assertFalse(plan!!.resumeActive)
    }

    @Test fun staleDisplayMetadataCannotOverridePersistedSnapshot() {
        val intent = podcast.copy(song = episode.copy(title = "Stale title", singer = "Old display"), positionMs = 1)
        val plan = WazeResumeResolver.resolve(Request(exact = intent), podcast, null)
        assertEquals(podcast, plan?.playback)
    }

    @Test fun ambiguousMetadataWithNoExactSnapshotFailsWithoutPlayback() {
        for (request in listOf(Request(), Request(mediaType = MediaType.TRACK), Request(mediaType = MediaType.PODCAST_EPISODE))) {
            val plan = WazeResumeResolver.resolve(request, null, null)
            assertFalse(WazeResumeResolver.dispatch(plan,
                { fail("Old player resumed") }, { fail("Music fallback") }, { fail("Guessed podcast") }))
        }
    }

    @Test fun incompleteRequestDoesNotResumeAnUnrelatedLoadedPlayer() {
        assertNull(WazeResumeResolver.resolve(Request(), null, PlaybackSnapshot(track, 100)))
    }

    @Test fun playerIdentityOverridesNumericUiIdForSameItemCheck() {
        // Both have the same numeric UI ID; the actual loaded item is old music.
        val plan = WazeResumeResolver.resolve(Request(), podcast, PlaybackSnapshot(track, 20_000))!!
        assertFalse(plan.resumeActive)
        assertEquals(podcast, plan.playback)
    }

    @Test fun matchingActiveIdentityUsesLivePositionAndDoesNotRestart() {
        val active = podcast.copy(positionMs = 110_000)
        val plan = WazeResumeResolver.resolve(Request(), podcast, active)
        var resumed: PlaybackSnapshot? = null
        WazeResumeResolver.dispatch(plan, { resumed = it }, { fail("Restarted track") }, { fail("Restarted episode") })
        assertEquals(active, resumed)
    }

    @Test fun currentUiStateAloneCannotEstablishPlayerOwnership() {
        val plan = WazeResumeResolver.resolve(Request(), podcast, null)!!
        assertFalse(plan.resumeActive)
    }

    @Test fun explicitConflictingTypeFailsSafe() {
        assertNull(WazeResumeResolver.resolve(Request(mediaType = MediaType.TRACK), podcast, null))
        assertNull(WazeResumeResolver.resolve(Request(mediaType = MediaType.PODCAST_EPISODE),
            PlaybackSnapshot(track, 0), null))
    }

    @Test fun explicitIntentAndPersistedIdentityConflictFailsSafe() {
        val other = podcast.copy(song = episode.copy(url = "episode:$trackId", spotifyTrackId = trackId))
        assertNull(WazeResumeResolver.resolve(Request(exact = other), podcast, null))
        assertNull(WazeResumeResolver.resolve(Request(exact = PlaybackSnapshot(track, 0)), podcast, null))
    }

    @Test fun malformedIntentCannotFallBackToLastPlayback() {
        assertNull(WazeResumeResolver.resolve(Request(malformed = true), podcast, podcast))
    }

    @Test fun fullIntentWorksWithoutPersistedState() {
        assertEquals(podcast, WazeResumeResolver.resolve(Request(exact = podcast), null, null)?.playback)
    }

    @Test fun podcastSnapshotRoundTripsIdentityAndPosition() {
        val json = PlaybackSnapshotCodec.encode(podcast)
        assertEquals(episodeId, JSONObject(json).getString("spotifyEpisodeId"))
        assertEquals(podcast, PlaybackSnapshotCodec.decode(json))
    }

    @Test fun oldPlaybackStatePodcastSnapshotRetainsGenericEpisodeId() {
        val json = JSONObject(PlaybackSnapshotCodec.encode(podcast)).apply {
            remove("spotifyEpisodeId"); remove("positionMs")
        }
        val decoded = PlaybackSnapshotCodec.decode(json.toString())!!
        assertEquals(episodeId, decoded.song.spotifyTrackId)
        assertEquals("show-id", decoded.song.podcastShowId)
        assertEquals(MediaType.PODCAST_EPISODE, decoded.song.mediaType)
        assertEquals(0L, decoded.positionMs)
    }

    @Test fun internalEpisodeKeepsOpaqueRssKeyAndDerivesOnlyItsEncodedShow() {
        val internal = episode.copy(
            url = "episode:show-id:https://feed.example/ep:42?q=a|b#part",
            spotifyTrackId = "", podcastShowId = "",
        )
        val normalized = WazeResumeResolver.normalize(internal)!!
        assertEquals(internal.url, normalized.url)
        assertEquals("", normalized.spotifyTrackId)
        assertEquals("show-id", normalized.podcastShowId)
    }

    @Test fun spotifyEpisodeAndInternalRssIdentityCanCoexistWithoutLoss() {
        val internal = episode.copy(url = "episode:show-id:guid")
        val restored = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(PlaybackSnapshot(internal, 321_000)))!!
        assertEquals(internal, WazeResumeResolver.normalize(restored.song))
        assertEquals(321_000L, restored.positionMs)
    }

    @Test fun conflictingEpisodeIdsOrShowIdsAreRejected() {
        assertNull(WazeResumeResolver.normalize(episode.copy(url = "episode:$trackId")))
        assertNull(WazeResumeResolver.normalize(episode.copy(url = "episode:other-show:guid")))
        val damaged = JSONObject(PlaybackSnapshotCodec.encode(podcast)).put("spotifyEpisodeId", trackId)
        assertNull(PlaybackSnapshotCodec.decode(damaged.toString()))
    }

    @Test fun trackCannotMasqueradeAsPodcastOrViceVersa() {
        assertNull(WazeResumeResolver.normalize(episode.copy(mediaType = MediaType.TRACK)))
        assertNull(WazeResumeResolver.normalize(track.copy(mediaType = MediaType.PODCAST_EPISODE)))
        assertNull(WazeResumeResolver.normalize(episode.copy(url = "spotify:track:$episodeId|Title")))
    }

    @Test fun hashedIdAndTitleAreNotAValidSpotifyTrackIdentity() {
        assertNull(WazeResumeResolver.normalize(track.copy(url = "spotify:track:7|Track Artist", spotifyTrackId = "")))
        assertNull(WazeResumeResolver.normalize(track.copy(url = "Track Artist", spotifyTrackId = "")))
        assertNull(WazeResumeResolver.normalize(episode.copy(url = "", spotifyTrackId = "")))
    }

    @Test fun conflictingTrackStableIdsAreRejected() {
        assertNull(WazeResumeResolver.normalize(track.copy(spotifyTrackId = episodeId)))
    }

    @Test fun localMusicRetainsConcretePlaybackUri() {
        val local = track.copy(url = "content://media/external/audio/media/42", spotifyTrackId = "")
        assertEquals(local, WazeResumeResolver.normalize(local))
    }

    @Test fun corruptedSnapshotDoesNotFallBackToAnotherExactItem() {
        val corrupted = podcast.copy(song = episode.copy(url = "unknown"))
        assertNull(WazeResumeResolver.resolve(Request(exact = podcast), corrupted, podcast))
        assertNull(PlaybackSnapshotCodec.decode("{broken"))
    }

    @Test fun oldUntypedEpisodeSnapshotCanBeRecoveredFromItsStableUrl() {
        val json = JSONObject(PlaybackSnapshotCodec.encode(podcast)).apply { remove("mediaType"); remove("spotifyEpisodeId") }
        assertEquals(episode, WazeResumeResolver.normalize(PlaybackSnapshotCodec.decode(json.toString())!!.song))
    }
    @Test fun sameEpisodeIdWithConflictingShowIdentityFailsSafe() {
        val otherShow = podcast.copy(song = episode.copy(podcastShowId = "different-show"))
        assertNull(WazeResumeResolver.resolve(Request(exact = otherShow), podcast, null))
    }

    @Test fun malformedSpotifyUrlIsNotTreatedAsDirectAudio() {
        assertNull(WazeResumeResolver.normalize(episode.copy(url = "https://open.spotify.com/track/bad-id")))
    }
}
