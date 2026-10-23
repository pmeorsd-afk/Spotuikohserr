package com.music.spotui.data.home

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Base64

class PublicSpotifyCatalogHttpTest {
    private fun page(json: String = """{"session":{"isAnonymous":true}}""") =
        "<script id='initialState'>${Base64.getEncoder().encodeToString(json.toByteArray())}</script>"
    private fun response(request: Request, code: Int = 200, location: String? = null, body: String = page()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .header("Content-Type", "text/html; charset=utf-8")
            .apply { if (location != null) header("Location", location) }
            .body(body.toResponseBody("text/html".toMediaType())).build()
    private fun http(reply: (Request) -> Response) = PublicSpotifyCatalogHttp(
        OkHttpClient.Builder().addInterceptor { reply(it.request()) }.build())

    @Test fun localizedHomeRedirectLoadsTheRealPopularShelves() = runBlocking {
        val requests = mutableListOf<Request>()
        val fixture = javaClass.getResourceAsStream("/home/public-home.json")!!.bufferedReader().use { it.readText() }
        val source = http {
            requests += it
            if (it.url.encodedPath == "/intl-he") response(it, 302, "/") else response(it, body = page(fixture))
        }
        val data = source.read("/intl-he").getJSONObject("home").getJSONObject("data")
        val catalog = PublicSpotifyHomeParser.home(data)
        assertEquals(listOf("/intl-he", "/"), requests.map { it.url.encodedPath })
        assertEquals(2, catalog.radio.size)
        assertEquals(2, catalog.albums.size)
        assertTrue(requests.all { it.header("Cookie") == null && it.header("Authorization") == null })
    }

    @Test fun localeRedirectsPreserveTrackArtistAndPlaylistIdentity() = runBlocking {
        for ((kind, id) in listOf("track" to "4Nx17F3hhny6jw0CMOUQ65", "artist" to "1IAEef07H0fd9aA8aUHUlL",
            "playlist" to "37i9dQZF1E4wmWec21cCcJ")) {
            val paths = mutableListOf<String>()
            val source = http {
                paths += it.url.encodedPath
                if (paths.size == 1) response(it, 302, "/$kind/$id") else response(it)
            }
            assertTrue(source.read("/intl-he/$kind/$id").getJSONObject("session").getBoolean("isAnonymous"))
            assertEquals(listOf("/intl-he/$kind/$id", "/$kind/$id"), paths)
        }
    }

    @Test fun crossHostLoginAndChangedEntitiesAreNeverRequested() = runBlocking {
        val start = "/intl-he/track/4Nx17F3hhny6jw0CMOUQ65"
        for (target in listOf("https://example.org/track/4Nx17F3hhny6jw0CMOUQ65", "/login",
            "http://open.spotify.com/track/4Nx17F3hhny6jw0CMOUQ65", "/",
            "/playlist/4Nx17F3hhny6jw0CMOUQ65", "/track/37i9dQZF1E4wmWec21cCcJ")) {
            var calls = 0
            val source = http { calls++; response(it, 302, target) }
            assertTrue(target, runCatching { source.read(start) }.isFailure)
            assertEquals(target, 1, calls)
        }
    }

    @Test fun redirectLoopsAndMissingLocationFailWithoutRepeatedRequests() = runBlocking {
        for (location in listOf("/intl-he", null)) {
            var calls = 0
            val source = http { calls++; response(it, 302, location) }
            assertTrue(runCatching { source.read("/intl-he") }.isFailure)
            assertEquals(1, calls)
        }
    }

    @Test fun evenSameEntityRedirectChainsAreBounded() = runBlocking {
        val locales = listOf("en", "fr", "de", "it", "es")
        var calls = 0
        val source = http { response(it, 302, "/intl-${locales[calls++]}") }
        assertTrue(runCatching { source.read("/intl-he") }.isFailure)
        assertEquals(4, calls)
    }

    @Test fun serverFailureAndNonAnonymousPagesAreNotSuccessfulCatalogs() = runBlocking {
        assertTrue(runCatching { http { response(it, 503) }.read("/intl-he") }.isFailure)
        val signedIn = page("""{"session":{"isAnonymous":false}}""")
        assertTrue(runCatching { http { response(it, body = signedIn) }.read("/intl-he") }.isFailure)
        assertTrue(runCatching { http { response(it, body = "<html>Consent</html>") }.read("/intl-he") }.isFailure)
    }

    /** Explicit diagnostic only: exercises the production transport, unlike urllib's automatic redirects. */
    @Test fun liveGuestCatalogThroughProductionTransport() = runBlocking {
        assumeTrue(System.getenv("SPOTUI_PUBLIC_CATALOG_LIVE") == "1")
        val source = PublicSpotifyCatalogHttp()
        val state = source.read("/intl-he")
        val home = PublicSpotifyHomeParser.home(state.getJSONObject("home").getJSONObject("data"))
        assertTrue(home.albums.isNotEmpty())
        assertTrue(home.radio.isNotEmpty())
        val trackId = "4Nx17F3hhny6jw0CMOUQ65"
        val artistId = "1IAEef07H0fd9aA8aUHUlL"
        val playlistId = "37i9dQZF1E4wmWec21cCcJ"
        fun entity(state: JSONObject, kind: String, id: String) = state.getJSONObject("entities")
            .getJSONObject("items").getJSONObject("spotify:$kind:$id")
        val track = source.read("/intl-he/track/$trackId")
        assertNotNull(PublicSpotifyHomeParser.track(entity(track, "track", trackId), trackId))
        val artist = source.read("/intl-he/artist/$artistId")
        assertTrue(PublicSpotifyHomeParser.artistPlaylists(entity(artist, "artist", artistId), artistId).isNotEmpty())
        val playlist = source.read("/intl-he/playlist/$playlistId")
        val radio = PublicSpotifyHomeParser.playlist(entity(playlist, "playlist", playlistId), playlistId)!!
        val mixId = "37i9dQZF1DZ06evO0XEWbA"
        val mixState = source.read("/intl-he/playlist/$mixId")
        val mix = PublicSpotifyHomeParser.playlist(entity(mixState, "playlist", mixId), mixId)!!
        val listened = mix.tracks.filter { song -> song.artists.any { it.id == artistId } }.take(2)
        assertEquals(2, listened.size)
        val now = System.currentTimeMillis()
        val history = listened.map { com.music.spotui.data.local.TrackListenStat(it.id, "", "", "", 2, 0, now) }
        val taste = HomeRecommendationPolicy.taste(history, listened.associateBy { it.id }, now)
        val personal = HomeRecommendationPolicy.compose(taste, listOf(mix, radio))
        assertTrue(personal.history.isNotEmpty())
        assertTrue(personal.mixes.isNotEmpty())
        assertTrue(personal.radio.isNotEmpty())
        println("Verified production HTTP: albums=${home.albums.size}, radio=${home.radio.size}, exact track/artist/playlist OK; personal mixes/radio/history all populated from verified public tracks")
    }
}
