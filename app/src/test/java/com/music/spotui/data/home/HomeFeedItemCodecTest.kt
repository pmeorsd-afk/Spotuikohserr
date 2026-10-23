package com.music.spotui.data.home

import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackProgressStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeFeedItemCodecTest {
    private val spotifyId = "1234567890abcdefghijkl"
    private val song = SongsModel(
        17, "Title", "Album", "Artist", "https://art.test/image",
        "spotify:track:$spotifyId|Title Artist", spotifyId, true, 180000,
    )

    private fun roundTrip(item: HomeItem): HomeItem? =
        HomeFeedItemCodec.encode(item)?.toString()?.let { HomeFeedItemCodec.decode(JSONObject(it)) }

    @Test fun cachedAlbumsKeepTheirExactIdentityEvenWithTheSameTitle() {
        val first = HomeItem.Album("Same title", "", "Artist", "Artist", spotifyId)
        val second = first.copy(spotifyId = "abcdefghijkl1234567890")
        assertEquals(first, roundTrip(first))
        assertEquals(second, roundTrip(second))
        assertNotEquals((roundTrip(first) as HomeItem.Album).spotifyId,
            (roundTrip(second) as HomeItem.Album).spotifyId)
    }

    @Test fun legacyAlbumWithoutIdentityDoesNotInventAnIdFromItsName() {
        val stored = JSONObject().put("itemType", "album").put("name", "Same title")
            .put("artists", "Artist")
        val album = HomeFeedItemCodec.decode(stored) as HomeItem.Album
        assertEquals("", album.spotifyId)
        assertEquals("Same title", album.name)
    }

    @Test fun trackSnapshotPreservesIdentityDurationAndPlaybackKey() {
        val restored = (roundTrip(HomeItem.Track(song)) as HomeItem.Track).song
        assertEquals(song, restored)
        assertEquals(PlaybackProgressStore.key(song), PlaybackProgressStore.key(restored))
    }

    @Test fun spotifyPodcastKeepsTypeShowAndEpisodeResumeIdentity() {
        val episode = song.copy(url = "episode:$spotifyId", mediaType = MediaType.PODCAST_EPISODE,
            podcastShowId = "show", durationMs = 3600000)
        val restored = (roundTrip(HomeItem.Track(episode)) as HomeItem.Track).song
        assertEquals(episode, restored)
        assertEquals(MediaType.PODCAST_EPISODE, restored.mediaType)
        assertEquals(PlaybackProgressStore.key(episode), PlaybackProgressStore.key(restored))
    }

    @Test fun internalPodcastRetainsTheWholeGuidAndShow() {
        val episode = song.copy(url = "episode:show:https://feed.test/ep:2?a=b#part", spotifyTrackId = "",
            mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show")
        assertEquals(episode, (roundTrip(HomeItem.Track(episode)) as HomeItem.Track).song)
    }

    @Test fun legacyExactMusicSnapshotRemainsReadable() {
        val stored = HomeFeedItemCodec.encode(HomeItem.Track(song))!!
        stored.getJSONObject("song").remove("mediaType")
        stored.getJSONObject("song").remove("podcastShowId")
        assertEquals(song, (HomeFeedItemCodec.decode(stored) as HomeItem.Track).song)
    }

    @Test fun legacyInternalEpisodeRemainsPodcastFromItsExplicitInternalKey() {
        val episode = song.copy(url = "episode:show:guid:123", spotifyTrackId = "",
            mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show")
        val stored = HomeFeedItemCodec.encode(HomeItem.Track(episode))!!
        stored.getJSONObject("song").apply {
            remove("mediaType"); remove("podcastShowId"); remove("spotifyEpisodeId")
        }
        assertEquals(episode, (HomeFeedItemCodec.decode(stored) as HomeItem.Track).song)
    }

    @Test fun ambiguousLegacyHttpEntryIsOmittedInsteadOfBecomingMusic() {
        val stored = HomeFeedItemCodec.encode(HomeItem.Track(song))!!
        stored.getJSONObject("song").apply {
            remove("mediaType"); remove("podcastShowId")
            put("url", "https://audio.test/episode.mp3")
            put("spotifyTrackId", "")
        }
        assertNull(HomeFeedItemCodec.decode(stored))
    }

    @Test fun contradictoryPodcastTypeOrIdentityCannotBeCachedAsTrack() {
        val malformed = song.copy(url = "episode:$spotifyId")
        assertNull(HomeFeedItemCodec.encode(HomeItem.Track(malformed)))
        val stored = HomeFeedItemCodec.encode(HomeItem.Track(song))!!
        stored.getJSONObject("song").put("url", "episode:$spotifyId")
        assertNull(HomeFeedItemCodec.decode(stored))
        stored.getJSONObject("song").put("mediaType", "unknown")
        assertNull(HomeFeedItemCodec.decode(stored))
    }

    @Test fun playlistArtistAndLikedItemsKeepExistingCacheShape() {
        listOf(
            HomeItem.Playlist("Playlist", "image", "Owner", "playlistId"),
            HomeItem.Artist("Artist", "image", "artistId"),
            HomeItem.LikedSongs(23),
        ).forEach { assertEquals(it, roundTrip(it)) }
    }
}
