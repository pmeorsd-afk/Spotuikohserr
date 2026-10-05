package com.music.spotui.playback

import com.metrolist.spotify.Spotify
import com.metrolist.spotify.models.SpotifyPaging
import com.metrolist.spotify.models.SpotifySearchResult
import com.music.spotui.data.entity.MediaType
import com.music.spotui.di.SongPlayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PodcastSearchAndSimilarityTest {

    // ==========================================
    // 1. GRAPHQL PODCAST & EPISODE PARSING TESTS
    // ==========================================

    @Test
    fun testParseGqlSearchPodcast_extractsBenBenBaruchCorrectly() {
        val jsonPayload = """
            {
                "__typename": "PodcastResponseWrapper",
                "data": {
                    "__typename": "Podcast",
                    "name": "המוג׳ו של בן בן ברוך",
                    "publisher": {
                        "name": "בן בן ברוך"
                    },
                    "uri": "spotify:show:775CMAc4uTNcUUwgkFgvQJ",
                    "mediaType": "AUDIO",
                    "coverArt": {
                        "sources": [
                            {
                                "height": 64,
                                "url": "https://i.scdn.co/image/ab6765630000f68dc98cf592789f571c24999e2c",
                                "width": 64
                            },
                            {
                                "height": 300,
                                "url": "https://i.scdn.co/image/ab67656300005f1fc98cf592789f571c24999e2c",
                                "width": 300
                            },
                            {
                                "height": 640,
                                "url": "https://i.scdn.co/image/ab6765630000ba8ac98cf592789f571c24999e2c",
                                "width": 640
                            }
                        ]
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(jsonPayload).jsonObject
        val data = root["data"]!!.jsonObject

        val show = Spotify.parseGqlSearchPodcast(data)

        assertEquals("775CMAc4uTNcUUwgkFgvQJ", show.id)
        assertEquals("המוג׳ו של בן בן ברוך", show.name)
        assertEquals("בן בן ברוך", show.publisher)
        assertEquals("spotify:show:775CMAc4uTNcUUwgkFgvQJ", show.uri)
        assertEquals(3, show.images.size)
        assertEquals("https://i.scdn.co/image/ab6765630000f68dc98cf592789f571c24999e2c", show.images[0].url)
    }

    @Test
    fun testParseGqlSearchEpisode_extractsFieldsAndDurationCorrectly() {
        val jsonPayload = """
            {
                "__typename": "EpisodeResponseWrapper",
                "data": {
                    "__typename": "Episode",
                    "name": "פרק 100 - שיחה פתוחה עם שרון גל",
                    "description": "פרק חגיגי במיוחד",
                    "duration": {
                        "totalMilliseconds": 2900112
                    },
                    "uri": "spotify:episode:5AbCdEfGhIjKlMnOpQrStU",
                    "coverArt": {
                        "sources": [
                            {
                                "height": 300,
                                "url": "https://i.scdn.co/image/ab67656300005f1fa518c8f36d04f905e26c4427",
                                "width": 300
                            }
                        ]
                    },
                    "releaseDate": {
                        "isoString": "2026-10-04T12:00:00Z"
                    }
                }
            }
        """.trimIndent()

        val root = Json.parseToJsonElement(jsonPayload).jsonObject
        val data = root["data"]!!.jsonObject

        val episode = Spotify.parseGqlSearchEpisode(data)

        assertEquals("5AbCdEfGhIjKlMnOpQrStU", episode.id)
        assertEquals("פרק 100 - שיחה פתוחה עם שרון גל", episode.name)
        assertEquals("פרק חגיגי במיוחד", episode.description)
        assertEquals(2900112, episode.durationMs)
        assertEquals("2026-10-04T12:00:00Z", episode.releaseDate)
        assertEquals(1, episode.images.size)
        assertEquals("https://i.scdn.co/image/ab67656300005f1fa518c8f36d04f905e26c4427", episode.images[0].url)
    }

    @Test
    fun testSpotifySearchResult_holdsShowsAndEpisodes() {
        val show = Spotify.parseGqlSearchPodcast(
            Json.parseToJsonElement("""
                {
                    "name": "The Daily",
                    "publisher": { "name": "The New York Times" },
                    "uri": "spotify:show:daily123"
                }
            """.trimIndent()).jsonObject
        )

        val result = SpotifySearchResult(
            shows = SpotifyPaging(items = listOf(show), total = 1, limit = 20, offset = 0)
        )

        assertNotNull(result.shows)
        assertEquals(1, result.shows?.items?.size)
        assertEquals("daily123", result.shows?.items?.first()?.id)
        assertEquals("The Daily", result.shows?.items?.first()?.name)
    }

    // ==========================================
    // 2. BIGRAM SIMILARITY & CRASH PREVENTION
    // ==========================================

    @Test
    fun testBigramSimilarity_emptyVariantsDoesNotCrash_returnsZero() {
        // Punctuation and symbols stripped out must not throw NoSuchElementException
        val score1 = SongPlayer.bigramSimilarity("???", "!!!")
        assertEquals(0.0, score1, 0.001)

        val score2 = SongPlayer.bigramSimilarity("", "")
        assertEquals(0.0, score2, 0.001)

        val score3 = SongPlayer.bigramSimilarity("פרק 21 - איך להוריד שומן בטני? עם יאיר להב", "")
        assertEquals(0.0, score3, 0.001)

        val score4 = SongPlayer.bigramSimilarity("", "פרק 21 - איך להוריד שומן בטני? עם יאיר להב")
        assertEquals(0.0, score4, 0.001)
    }

    @Test
    fun testBigramSimilarity_identicalTitles_returnsOne() {
        val score = SongPlayer.bigramSimilarity("המוג׳ו של בן בן ברוך", "המוג׳ו של בן בן ברוך")
        assertEquals(1.0, score, 0.001)
    }

    @Test
    fun testBigramSimilarity_partialMatch_computesPositiveScore() {
        val score = SongPlayer.bigramSimilarity(
            "פרק 21 - איך להוריד שומן בטני? עם יאיר להב",
            "פרק 21 איך להוריד שומן בטני"
        )
        assertTrue("Partial match should have similarity > 0.5, was $score", score > 0.5)
    }

    // ==========================================
    // 3. PHASE C2: PODCAST HUB ROUTING & SHOW MAPPING
    // ==========================================

    @Test
    fun testPodcastHubRoute_isCorrect() {
        assertEquals("podcast_hub", com.music.spotui.ui.navigation.podcastHubRoute())
    }

    @Test
    fun testPodcastShowRoute_formatsCorrectly() {
        val route = com.music.spotui.ui.navigation.showRoute("775CMAc4uTNcUUwgkFgvQJ", "המוג׳ו של בן בן ברוך")
        assertTrue(route.startsWith("show/775CMAc4uTNcUUwgkFgvQJ"))
        assertTrue(route.contains("name="))
    }

    @Test
    fun testPodcastHubShows_deduplicationAndMapping() {
        val show1 = com.music.spotui.data.entity.PodcastModel(
            id = "show1",
            name = "פודקאסט 1",
            publisher = "מארח 1",
            coverUri = "https://img1.jpg"
        )
        val show1Duplicate = com.music.spotui.data.entity.PodcastModel(
            id = "show1",
            name = "פודקאסט 1",
            publisher = "מארח 1",
            coverUri = "https://img1.jpg"
        )
        val show2 = com.music.spotui.data.entity.PodcastModel(
            id = "show2",
            name = "פודקאסט 2",
            publisher = "מארח 2",
            coverUri = "https://img2.jpg"
        )

        val list = listOf(show1, show1Duplicate, show2).distinctBy { it.id }.filter { it.name.isNotBlank() }
        assertEquals(2, list.size)
        assertEquals("show1", list[0].id)
        assertEquals("show2", list[1].id)
    }

    @Test
    fun testLiveGqlSearchBenBenBaruch() {
        kotlinx.coroutines.runBlocking {
            val authRes = com.metrolist.spotify.SpotifyAuth.fetchAccessToken("anonymous", allowAnonymous = true)
            val token = authRes.getOrThrow()
            com.metrolist.spotify.Spotify.accessToken = token.accessToken
            val testQueries = listOf("המוג׳ו של בן בן ברוך", "בן בן ברוך", "המוג'ו", "פודקאסטים", "פודקאסט")
            for (q in testQueries) {
                val searchRes = com.metrolist.spotify.Spotify.search(q, limit = 10)
                assertTrue(searchRes.isSuccess)
                val result = searchRes.getOrThrow()
                println("QUERY '$q': shows=${result.shows?.items?.size ?: 0} episodes=${result.episodes?.items?.size ?: 0}")
                result.shows?.items?.take(3)?.forEach {
                    println("  SHOW: id=${it.id} name=${it.name} publisher=${it.publisher}")
                }
            }
        }
    }
}

