package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.local.TrackListenStat
import com.music.spotui.playback.ExternalMediaIdentityResolver.Identity
import com.music.spotui.playback.ExternalMediaIdentityResolver.Request
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExternalMediaIdentityResolverTest {
    private val a = "1234567890abcdefghijkl"
    private val b = "abcdefghijkl1234567890"

    private fun episode(id: String = a, title: String = "Shared title", show: String = "Show") =
        SongsModel(id.hashCode() and 0x7fffffff, title, show, "Publisher", "",
            "episode:$id", id, mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show-id")

    private fun internal(key: String) = episode().copy(
        id = key.hashCode() and 0x7fffffff, url = "episode:show-id:$key", spotifyTrackId = ""
    )

    private fun resolve(request: Request, vararg candidates: SongsModel): SongsModel? = runBlocking {
        ExternalMediaIdentityResolver.resolve(request, candidates.toList()) {
            fail("Unexpected remote lookup for $it")
            null
        }
    }

    @Test fun spotifyAliasesNormalizeToTheSameEpisode() {
        for (value in listOf("episode:$a", "episode/$a", "spotify:episode:$a",
            "https://open.spotify.com/episode/$a?si=abc#fragment")) {
            assertEquals(value, Identity.SpotifyEpisode(a), ExternalMediaIdentityResolver.parse(value))
        }
    }

    @Test fun internalRssKeyRemainsOpaque() {
        val guid = "https://feed.example/ep:42?q=1#part"
        assertEquals(Identity.InternalEpisode("show-id", guid),
            ExternalMediaIdentityResolver.parse("episode:show-id:$guid"))
    }

    @Test fun internalNumericEpisodeDoesNotBecomeSpotifyId() {
        assertEquals(Identity.EpisodeNumber(42), ExternalMediaIdentityResolver.parse("episode/42"))
        assertEquals(Identity.SongNumber(42), ExternalMediaIdentityResolver.parse("song/42"))
        assertTrue(ExternalMediaIdentityResolver.parse("episode:42") is Identity.Unknown)
    }

    @Test fun malformedAndSpoofedIdsAreRejected() {
        for (id in listOf("episode:", "episode:show:", "episode/:guid", "spotify:episode:",
            "spotify:episode:$a/extra", "https://example.com/open.spotify.com/episode/$a",
            "https://open.spotify.com.evil/episode/$a", "https://open.spotify.com/episode/$a/extra")) {
            assertTrue(id, ExternalMediaIdentityResolver.parse(id) is Identity.Unknown)
            assertNull(resolve(Request(listOf(id), MediaType.PODCAST_EPISODE, "Shared title", "Show"),
                episode()))
        }
    }

    @Test fun stableIdWinsOverContradictoryTitle() {
        val right = episode(b, title = "Actual title")
        assertEquals(right, resolve(Request(listOf("spotify:episode:$b"), title = "Shared title"),
            episode(a), right))
    }

    @Test fun missingStableIdCannotSelectSameTitleFromHistory() = runBlocking {
        var fetched: String? = null
        val result = ExternalMediaIdentityResolver.resolve(
            Request(listOf("spotify:episode:$b"), MediaType.PODCAST_EPISODE, "Shared title", "Show"),
            listOf(episode(a))
        ) { fetched = it; null }
        assertEquals(b, fetched)
        assertNull(result)
    }

    @Test fun unknownIdBlocksMetadataFallback() {
        assertNull(resolve(Request(listOf("opaque-controller-id"), MediaType.PODCAST_EPISODE,
            "Shared title", "Show"), episode()))
    }

    @Test fun twoEpisodesInOneShowAreNotInterchangeable() {
        val right = internal("guid:2")
        assertEquals(right, resolve(Request(listOf(right.url)), internal("guid:1"), right))
        assertNull(resolve(Request(listOf("episode:show-id:missing")), internal("guid:1"), right))
    }

    @Test fun spotifyShowIdIsNotUsedAsEpisodeId() = runBlocking {
        var fetched: String? = null
        val result = ExternalMediaIdentityResolver.resolve(
            Request(listOf("episode:$a:guid-missing")), listOf(internal("other"))
        ) { fetched = it; episode() }
        assertNull(result)
        assertNull(fetched)
    }

    @Test fun contradictoryStableIdentifiersFailClosed() {
        assertNull(resolve(Request(listOf("spotify:episode:$a", "spotify:episode:$b")),
            episode(a), episode(b)))
    }

    @Test fun equivalentStableIdentifiersAreAccepted() {
        val song = episode()
        assertEquals(song, resolve(Request(listOf("spotify:episode:$a",
            "https://open.spotify.com/episode/$a?si=1")), song))
    }

    @Test fun knownInternalAndSpotifyAliasesMustBothMatch() {
        val song = episode()
        assertEquals(song, resolve(Request(listOf("episode/${song.id}", "spotify:episode:$a")), song))
        assertNull(resolve(Request(listOf("episode/${song.id}", "spotify:episode:$b")), song))
    }

    @Test fun remoteLookupWorksWithoutHistoryOrCallerMetadata() = runBlocking {
        val song = episode()
        var calls = 0
        assertEquals(song, ExternalMediaIdentityResolver.resolve(
            Request(listOf("spotify:episode:$a")), emptyList()
        ) { assertEquals(a, it); calls++; song })
        assertEquals(1, calls)
    }

    @Test fun remoteResultWithDifferentIdentityIsRejected() = runBlocking {
        assertNull(ExternalMediaIdentityResolver.resolve(
            Request(listOf("spotify:episode:$a")), emptyList()
        ) { episode(b) })
    }

    @Test fun metadataFallbackRequiresExactUniqueEpisode() {
        val song = episode()
        val request = Request(mediaType = MediaType.PODCAST_EPISODE,
            title = song.title, album = song.album, artist = song.singer)
        assertEquals(song, resolve(request, song, episode(b, show = "Other show")))
        assertNull(resolve(request, song, episode(b)))
    }

    @Test fun duplicateSnapshotsOfOneIdentityAreNotAmbiguous() {
        val song = episode()
        val request = Request(mediaType = MediaType.PODCAST_EPISODE,
            title = song.title, album = song.album)
        assertEquals(song, resolve(request, song, song.copy(coverUri = "older-art")))
    }

    @Test fun titleOnlyAndPartialMetadataCannotMatch() {
        val song = episode()
        assertNull(resolve(Request(mediaType = MediaType.PODCAST_EPISODE, title = song.title), song))
        assertNull(resolve(Request(mediaType = MediaType.PODCAST_EPISODE,
            title = "Shared", album = song.album), song))
        assertNull(resolve(Request(mediaType = MediaType.PODCAST_EPISODE,
            title = song.title, album = song.album, artist = "Wrong publisher"), song))
    }

    @Test fun untypedMetadataDoesNotInferPodcast() {
        assertNull(resolve(Request(title = "Shared title", album = "Show"), episode()))
    }

    @Test fun explicitTrackMetadataCannotBecomePodcast() {
        assertNull(resolve(Request(mediaType = MediaType.TRACK,
            title = "Shared title", album = "Show"), episode()))
    }

    @Test fun spotifyTrackIdCannotBecomePodcastEvenWhenTitleMatches() {
        assertNull(resolve(Request(listOf("spotify:track:$a"),
            title = "Shared title", album = "Show"), episode()))
        assertNull(resolve(Request(listOf("https://open.spotify.com/track/$a"),
            MediaType.PODCAST_EPISODE, "Shared title", "Show"), episode()))
    }

    @Test fun podcastIdWithTrackTypeFailsWithoutNetwork() {
        assertNull(resolve(Request(listOf("spotify:episode:$a"), MediaType.TRACK), episode()))
    }

    @Test fun knownMusicIdentityAndBrowseAliasRemainSupported() {
        val track = episode().copy(mediaType = MediaType.TRACK, url = "spotify:track:$a|title artist")
        assertEquals(track, resolve(Request(listOf("song/${track.id}")), track))
        assertEquals(track, resolve(Request(listOf("spotify:track:$a")), track))
        assertEquals(track, resolve(Request(listOf("https://open.spotify.com/track/$a?si=1")), track))
    }

    private fun stat(url: String, spotifyId: String = "", showId: String = "") = TrackListenStat(
        trackId = url, title = "Title", artist = "Publisher", coverUri = "",
        playCount = 3, completedCount = 1, lastPlayedAt = 123L, album = "Show",
        mediaType = MediaType.PODCAST_EPISODE, podcastShowId = showId,
        originalUrl = url, durationMs = 123000, spotifyEpisodeId = spotifyId,
    )

    @Test fun historyJsonRoundTripPreservesSpotifyEpisodeIdentity() {
        val saved = stat("episode:show-id:guid:1", a, "show-id")
        val restored = TrackListenStat.fromJson(JSONObject(saved.toJson().toString()))!!
        assertEquals(saved, restored)
        val song = restored.toSongModel()
        assertEquals(a, song.spotifyTrackId)
        assertEquals("show-id", song.podcastShowId)
        assertEquals(saved.originalUrl, song.url)
        assertEquals(song, resolve(Request(listOf("spotify:episode:$a")), song))
    }

    @Test fun legacySpotifyEpisodeRecoversIdentityWithoutInventingShowId() {
        val json = stat("episode:$a").toJson().apply { remove("spotifyEpisodeId"); remove("mediaType") }
        val song = TrackListenStat.fromJson(json)!!.toSongModel()
        assertEquals(MediaType.PODCAST_EPISODE, song.mediaType)
        assertEquals(a, song.spotifyTrackId)
        assertEquals("", song.podcastShowId)
    }

    @Test fun legacyInternalEpisodeKeepsShowAndFullGuid() {
        val saved = stat("episode:show-id:https://host/guid:1?q=2")
        val json = saved.toJson().apply { remove("spotifyEpisodeId"); remove("mediaType"); remove("originalUrl") }
        val song = TrackListenStat.fromJson(json)!!.toSongModel()
        assertEquals("show-id", song.podcastShowId)
        assertEquals(saved.trackId, song.url)
        assertEquals("", song.spotifyTrackId)
    }

    @Test fun historyDoesNotInferSpotifyIdFromInternalShowOrGuid() {
        assertEquals("", stat("episode:$a:$b").toSongModel().spotifyTrackId)
        assertNull(ExternalMediaIdentityResolver.spotifyEpisodeId(internal(a)))
    }

    @Test fun legacyMusicHistoryRetainsTrackIdentity() {
        val saved = stat("spotify:track:$a").copy(
            trackId = a, mediaType = MediaType.TRACK, originalUrl = "spotify:track:$a|Title Publisher"
        )
        val restored = TrackListenStat.fromJson(saved.toJson())!!.toSongModel()
        assertEquals(MediaType.TRACK, restored.mediaType)
        assertEquals(a, restored.spotifyTrackId)
        assertEquals(saved.originalUrl, restored.url)
    }

    @Test fun corruptSnapshotCannotOverrideItsUrlIdentity() = runBlocking {
        val corrupt = episode(b).copy(spotifyTrackId = a)
        assertNull(ExternalMediaIdentityResolver.spotifyEpisodeId(corrupt))
        assertNull(ExternalMediaIdentityResolver.resolve(
            Request(listOf("spotify:episode:$a")), listOf(corrupt)
        ) { null })
    }

    @Test fun bareSpotifyIdNeedsAnExplicitMediaType() {
        assertEquals(episode(), resolve(Request(listOf(a), MediaType.PODCAST_EPISODE), episode()))
        assertNull(resolve(Request(listOf(a)), episode()))
        assertNull(resolve(Request(listOf(a), MediaType.TRACK), episode()))
    }

    @Test fun typedTrackMetadataOnlySelectsTrack() {
        val track = episode().copy(mediaType = MediaType.TRACK, url = "spotify:track:$a|title artist")
        assertEquals(track, resolve(Request(mediaType = MediaType.TRACK,
            title = "Shared title", album = "Show"), episode(), track))
    }

    @Test fun metadataCannotDisambiguateCollidingInternalIds() {
        val podcast = internal("guid:1").copy(id = 42)
        val track = episode().copy(id = 42, mediaType = MediaType.TRACK, url = "spotify:track:$a")
        assertNull(resolve(Request(listOf("song/42"), MediaType.PODCAST_EPISODE,
            podcast.title, podcast.album), podcast, track))
    }

    @Test fun metadataCannotCollapseDifferentEpisodesWithoutUrls() {
        val first = episode().copy(id = 1, url = "", spotifyTrackId = "")
        val second = first.copy(id = 2)
        assertNull(resolve(Request(mediaType = MediaType.PODCAST_EPISODE,
            title = first.title, album = first.album), first, second))
    }
}
