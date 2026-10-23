package com.metrolist.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class SpotifyPlaylistPageTest {
    private fun pageWithDuration(fields: String) = Spotify.parsePlaylistPage("radio-id",
        Json.parseToJsonElement("""{"data":{"playlistV2":{"name":"Radio","content":{"totalCount":1,"items":[
          {"uid":"entry-a","itemV2":{"_uri":"spotify:track:0123456789012345678901","data":{
            "name":"Exact radio track",$fields
          }}}
        ]}}}}""").jsonObject, 100, 0).tracks.items.single().track!!

    @Test fun playlistTrackDurationReachesTheModelForMidpointPreview() {
        val track = pageWithDuration(""""trackDuration":{"totalMilliseconds":213500}""")
        assertEquals("0123456789012345678901", track.id)
        assertEquals(213_500, track.durationMs)
    }

    @Test fun durationShapesRemainCompatibleAndMillisecondsAreNotMultiplied() {
        for (field in listOf(""""duration":{"totalMilliseconds":213500}""",
            """"durationMs":213500""", """"duration_ms":213500""")) {
            assertEquals(field, 213_500, pageWithDuration(field).durationMs)
        }
        assertEquals(213_000, pageWithDuration(""""duration":213""").durationMs)
    }

    @Test fun missingDurationRemainsUnknownAndInvalidShapeCanUseValidAlternate() {
        assertEquals(0, pageWithDuration(""""trackDuration":null""").durationMs)
        assertEquals(213_500, pageWithDuration(""""trackDuration":{"totalMilliseconds":0},"durationMs":213500""").durationMs)
        assertEquals(0, pageWithDuration(""""trackDuration":{"totalMilliseconds":-1}""").durationMs)
    }

    /** Read-only diagnostic against the same production endpoint used by Radio playlists. */
    @Test fun liveRadioPlaylistDurations() = kotlinx.coroutines.runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("SPOTUI_RADIO_DURATION_LIVE") == "1")
        val previous = Spotify.accessToken
        try {
            val token = SpotifyAuth.fetchAccessToken("anonymous", allowAnonymous = true).getOrThrow()
            Spotify.accessToken = token.accessToken
            val page = Spotify.playlistPage("37i9dQZF1E4wmWec21cCcJ", limit = 20).getOrThrow()
            val tracks = page.tracks.items.mapNotNull { it.track }
            println("Live Radio durations: tracks=${tracks.size}, positive=${tracks.count { it.durationMs > 0 }}, zero=${tracks.count { it.durationMs == 0 }}")
            assertTrue(tracks.isNotEmpty())
            assertTrue(tracks.all { it.durationMs > 0 })
        } finally { Spotify.accessToken = previous }
    }

    @Test fun oneResponsePreservesHeaderTrackIdentityAndPlaylistUid() {
        val json = Json.parseToJsonElement("""{"data":{"playlistV2":{
          "name":"My list","description":"Description",
          "ownerV2":{"data":{"uri":"spotify:user:owner","name":"Owner"}},
          "content":{"totalCount":3,"items":[
            {"uid":"entry-a","itemV2":{"_uri":"spotify:track:0123456789012345678901","data":{"name":"Track A"}}},
            {},
            {"uid":"entry-b","itemV2":{"_uri":"spotify:track:1123456789012345678901","data":{"name":"Track B"}}}
          ]}
        }}}""").jsonObject
        val page = Spotify.parsePlaylistPage("exact-playlist", json, 100, 0)
        assertEquals("exact-playlist", page.playlist.id)
        assertEquals("My list", page.playlist.name)
        assertEquals("Owner", page.playlist.owner?.displayName)
        assertEquals(3, page.consumedItems)
        assertEquals(3, page.tracks.total)
        assertEquals(listOf("entry-a", "entry-b"), page.tracks.items.map { it.uid })
        assertEquals(listOf("0123456789012345678901", "1123456789012345678901"), page.tracks.items.map { it.track?.id })
    }

    @Test fun emptyPagePreservesPagingAndHeader() {
        val json = Json.parseToJsonElement("""{"data":{"playlistV2":{"name":"Empty","content":{"totalCount":0,"items":[]}}}}""").jsonObject
        val page = Spotify.parsePlaylistPage("id", json, 100, 0)
        assertEquals("Empty", page.playlist.name)
        assertEquals(0, page.consumedItems)
        assertTrue(page.tracks.items.isEmpty())
        assertEquals(100, page.tracks.limit)
    }

    @Test fun malformedResponseFailsInsteadOfInventingEmptyPlaylist() {
        val json = Json.parseToJsonElement("""{"data":{"playlistV2":{"name":"Missing content"}}}""").jsonObject
        assertTrue(runCatching { Spotify.parsePlaylistPage("id", json, 100, 0) }.isFailure)
    }
}
