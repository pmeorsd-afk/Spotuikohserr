package com.music.spotui.data.home

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class PublicSpotifyHomeParserTest {
    private val trackId = "4Nx17F3hhny6jw0CMOUQ65"
    private val playlistId = "37i9dQZF1E4wmWec21cCcJ"
    private val artistId = "1IAEef07H0fd9aA8aUHUlL"
    private fun fixture(name: String) = JSONObject(javaClass.getResourceAsStream("/home/$name.json")!!.bufferedReader().use { it.readText() })

    @Test fun actualGuestHomeSuppliesThePopularSectionsWithExactIds() {
        val parsed = PublicSpotifyHomeParser.home(fixture("public-home").getJSONObject("home").getJSONObject("data"))
        assertEquals(2, parsed.albums.size)
        assertEquals("6qBbLMrB32FOw0uitKl2RR", parsed.albums[0].spotifyId)
        assertEquals(playlistId, parsed.radio[0].id)
        assertEquals(2, parsed.discovery.size)
    }

    @Test fun publicPageRequiresAnonymousStateAndIgnoresUnrelatedScripts() {
        val json = fixture("public-home")
        fun html() = "<script>ignore</script><script type='text/plain' id='initialState'>" +
            Base64.getEncoder().encodeToString(json.toString().toByteArray()) + "</script>"
        assertNotNull(PublicSpotifyHomeParser.page(html()))
        json.getJSONObject("session").put("isAnonymous", false)
        assertNull(PublicSpotifyHomeParser.page(html()))
    }

    @Test fun missingOrMalformedPublicStateIsNotInvented() {
        assertNull(PublicSpotifyHomeParser.page("<html>login or network error</html>"))
        assertNull(PublicSpotifyHomeParser.page("<script id='initialState'>not base64</script>"))
        assertTrue(PublicSpotifyHomeParser.home(JSONObject()).albums.isEmpty())
    }

    @Test fun similarlyNamedNewReleaseSectionIsNotTreatedAsPopular() {
        val data = fixture("public-home").getJSONObject("home").getJSONObject("data")
        val sections = data.getJSONArray("sections")
        for (i in 0 until sections.length()) {
            if (sections.getJSONObject(i).getString("title") == "Popular albums and singles") {
                sections.getJSONObject(i).put("uri", "spotify:section:unverified")
            }
        }
        assertTrue(PublicSpotifyHomeParser.home(data).albums.isEmpty())
    }

    @Test fun duplicateConflictingSectionIdentityFailsClosed() {
        val data = fixture("public-home").getJSONObject("home").getJSONObject("data")
        val sections = data.getJSONArray("sections")
        val album = (0 until sections.length()).map { sections.getJSONObject(it) }
            .single { it.getString("title") == "Popular albums and singles" }
        sections.put(album)
        assertTrue(PublicSpotifyHomeParser.home(data).albums.isEmpty())
    }

    @Test fun exactTrackArtistsAreReadFromActualPublicPayload() {
        val entity = fixture("public-track").getJSONObject("spotify:track:$trackId")
        val parsed = PublicSpotifyHomeParser.track(entity, trackId)!!
        assertEquals(trackId, parsed.id)
        assertEquals("4ZXLucEFePscKcgUlD0Sf9", parsed.artists.single().id)
        assertNull(PublicSpotifyHomeParser.track(entity, playlistId))
        entity.put("__typename", "Episode")
        assertNull(PublicSpotifyHomeParser.track(entity, trackId))
    }

    @Test fun contradictoryTrackIdIsRejected() {
        val entity = fixture("public-track").getJSONObject("spotify:track:$trackId").put("id", playlistId)
        assertNull(PublicSpotifyHomeParser.track(entity, trackId))
    }

    @Test fun actualArtistLinksStayTypedAndUseCatalogIdentity() {
        val entity = fixture("public-artist").getJSONObject("spotify:artist:$artistId")
        val parsed = PublicSpotifyHomeParser.artistPlaylists(entity, artistId)
        assertEquals("37i9dQZF1DZ06evO0XEWbA", parsed.first().id)
        assertTrue(PublicSpotifyHomeParser.artistPlaylists(entity, trackId).isEmpty())
    }

    @Test fun actualRadioContentContainsExactTracksAndArtists() {
        val entity = fixture("public-playlist").getJSONObject("spotify:playlist:$playlistId")
        val parsed = PublicSpotifyHomeParser.playlist(entity, playlistId)!!
        assertTrue(parsed.radio)
        assertEquals(4, parsed.tracks.size)
        assertTrue(parsed.tracks.first().artists.any { it.id == artistId })
        assertEquals(playlistId, parsed.item.id)
        assertNull(PublicSpotifyHomeParser.playlist(entity, trackId))
    }

    @Test fun episodesInPlaylistCannotBecomeMusicEvidence() {
        val entity = fixture("public-playlist").getJSONObject("spotify:playlist:$playlistId")
        val items = entity.getJSONObject("content").getJSONArray("items")
        for (i in 0 until items.length()) items.getJSONObject(i).getJSONObject("itemV2").getJSONObject("data").put("__typename", "Episode")
        assertNull(PublicSpotifyHomeParser.playlist(entity, playlistId))
    }
}
