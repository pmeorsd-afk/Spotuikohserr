package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class PodcastRssAndMatchingTest {

    // ==========================================
    // 1. RSS PARSER TESTS
    // ==========================================

    @Test
    fun testParser_itemWithValidEnclosure() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Test Podcast</title>
                <item>
                  <title>Episode 1: The Beginning</title>
                  <guid>ep-001</guid>
                  <pubDate>Mon, 01 Jan 2024 12:00:00 GMT</pubDate>
                  <enclosure url="https://cdn.podcasts.com/ep1.mp3" type="audio/mpeg" length="15000000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(1, episodes.size)
        val ep = episodes[0]
        assertEquals("ep-001", ep.guid)
        assertEquals("Episode 1: The Beginning", ep.title)
        assertEquals("https://cdn.podcasts.com/ep1.mp3", ep.enclosureUrl)
        assertEquals("audio/mpeg", ep.enclosureType)
        assertEquals(15000000L, ep.enclosureLength)
    }

    @Test
    fun testParser_multipleEpisodes() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Multi Show</title>
                <item>
                  <title>Ep 1</title>
                  <enclosure url="https://cdn.com/1.mp3" type="audio/mpeg" length="1000" />
                </item>
                <item>
                  <title>Ep 2</title>
                  <enclosure url="https://cdn.com/2.mp3" type="audio/mpeg" length="2000" />
                </item>
                <item>
                  <title>Ep 3</title>
                  <enclosure url="https://cdn.com/3.mp3" type="audio/mpeg" length="3000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(3, episodes.size)
        assertEquals("Ep 1", episodes[0].title)
        assertEquals("Ep 2", episodes[1].title)
        assertEquals("Ep 3", episodes[2].title)
    }

    @Test
    fun testParser_missingEnclosure() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Text Only</title>
                <item>
                  <title>Announcement without audio</title>
                  <guid>no-audio</guid>
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(1, episodes.size)
        assertNull(episodes[0].enclosureUrl)
        assertNull(episodes[0].enclosureType)
    }

    @Test
    fun testParser_missingMimeType() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Missing Type</title>
                <item>
                  <title>Audio No Mime</title>
                  <enclosure url="https://cdn.com/audio.m4a" length="5000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(1, episodes.size)
        assertEquals("https://cdn.com/audio.m4a", episodes[0].enclosureUrl)
        assertNull(episodes[0].enclosureType)
    }

    @Test
    fun testParser_channelMetadataAndFeed() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>המוג׳ו של בן בן ברוך</title>
                <itunes:author>בן בן ברוך</itunes:author>
                <description>פודקאסט ראיונות שבועי</description>
                <itunes:image href="https://i1.sndcdn.com/avatars-000351419093.jpg" />
                <item>
                  <title>פרק 377: אורח מיוחד</title>
                  <guid>tag:soundcloud,2010:tracks/12345678</guid>
                  <itunes:duration>01:15:30</itunes:duration>
                  <enclosure url="https://feeds.soundcloud.com/stream/12345678.mp3" type="audio/mpeg" length="80000000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val feed = PodcastRssParser.parseFeed(xml)
        assertEquals("המוג׳ו של בן בן ברוך", feed.title)
        assertEquals("בן בן ברוך", feed.author)
        assertEquals("פודקאסט ראיונות שבועי", feed.description)
        assertEquals("https://i1.sndcdn.com/avatars-000351419093.jpg", feed.imageUrl)
        assertEquals(1, feed.episodes.size)
        assertEquals("פרק 377: אורח מיוחד", feed.episodes[0].title)
        assertEquals(4530000L, feed.episodes[0].durationMs)
        assertEquals("https://feeds.soundcloud.com/stream/12345678.mp3", feed.episodes[0].enclosureUrl)
    }

    @Test
    fun testParser_durationPresent() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
              <channel>
                <title>Duration Show</title>
                <item>
                  <title>Duration in HH:MM:SS</title>
                  <itunes:duration>01:15:30</itunes:duration>
                  <enclosure url="https://cdn.com/ep.mp3" type="audio/mpeg" length="1000" />
                </item>
                <item>
                  <title>Duration in Seconds</title>
                  <itunes:duration>3600</itunes:duration>
                  <enclosure url="https://cdn.com/ep2.mp3" type="audio/mpeg" length="1000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(2, episodes.size)
        // 1h 15m 30s = 4530s = 4,530,000ms
        assertEquals(4530000L, episodes[0].durationMs)
        // 3600s = 3,600,000ms
        assertEquals(3600000L, episodes[1].durationMs)
    }

    @Test
    fun testParser_durationMissing() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>No Duration</title>
                <item>
                  <title>Ep Without Duration</title>
                  <enclosure url="https://cdn.com/ep.mp3" type="audio/mpeg" length="1000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(1, episodes.size)
        assertNull(episodes[0].durationMs)
    }

    @Test
    fun testParser_podcastingNamespaceAndCdata() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
              <channel>
                <title>Modern Podcasting</title>
                <item>
                  <title><![CDATA[פרק 42: שיחה על &quot;בינה מלאכותית&quot; &amp; העתיד]]></title>
                  <itunes:duration>42:15</itunes:duration>
                  <enclosure url="https://cdn.com/ai.mp3" type="audio/mpeg" length="8000000" />
                </item>
              </channel>
            </rss>
        """.trimIndent()

        val episodes = PodcastRssParser.parse(xml)
        assertEquals(1, episodes.size)
        // 42m 15s = 2535s = 2,535,000ms
        assertEquals(2535000L, episodes[0].durationMs)
        assertEquals("פרק 42: שיחה על \"בינה מלאכותית\" & העתיד", episodes[0].title)
    }

    // ==========================================
    // 2. EPISODE MATCHING TESTS
    // ==========================================

    @Test
    fun testMatching_identicalTitleAndIdenticalDuration() {
        val target = SongsModel(
            id = 1,
            title = "פרק 55: ראיון עם אבא אמיץ לילד מכור בהחלמה",
            album = "קופה ראשית",
            singer = "כאן",
            coverUri = "",
            url = "episode:spot123",
            spotifyTrackId = "spot123",
            durationMs = 3600000, // 60 mins
            mediaType = MediaType.PODCAST_EPISODE
        )

        val candidates = listOf(
            PodcastRssEpisode(
                guid = "rss-999",
                title = "פרק 55: ראיון עם אבא אמיץ לילד מכור בהחלמה",
                pubDate = "2024-01-01",
                durationMs = 3600000L,
                enclosureUrl = "https://kann.org/ep55.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 50000000L
            )
        )

        val matched = PodcastEpisodeMatcher.match(target, candidates)
        assertNotNull("Expected match for identical title and duration", matched)
        assertEquals("https://kann.org/ep55.mp3", matched!!.enclosureUrl)
    }

    @Test
    fun testMatching_slightlyDifferentTitleAndCloseDuration() {
        val target = SongsModel(
            id = 1,
            title = "פרק 55: ראיון עם אבא אמיץ לילד מכור בהחלמה",
            album = "קולות",
            singer = "קולות",
            coverUri = "",
            url = "episode:spot123",
            spotifyTrackId = "spot123",
            durationMs = 3610000, // 60 mins 10 sec
            mediaType = MediaType.PODCAST_EPISODE
        )

        val candidates = listOf(
            PodcastRssEpisode(
                guid = "rss-diff-guid",
                title = "55 - ראיון עם אבא אמיץ לילד מכור בהחלמה | פודקאסט קולות",
                pubDate = "2024-01-01",
                durationMs = 3600000L, // 60 mins (diff is only 10 sec)
                enclosureUrl = "https://pod.com/55.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 50000000L
            )
        )

        val matched = PodcastEpisodeMatcher.match(target, candidates)
        assertNotNull("Expected match for slight title diff and close duration", matched)
        assertEquals("https://pod.com/55.mp3", matched!!.enclosureUrl)
    }

    @Test
    fun testMatching_identicalTitleWithVastlyDifferentDuration_doesNotMatchBlindly() {
        val target = SongsModel(
            id = 1,
            title = "פרק בונוס מיוחד",
            album = "פודקאסט",
            singer = "פודקאסט",
            coverUri = "",
            url = "episode:spot123",
            spotifyTrackId = "spot123",
            durationMs = 300000, // 5 minutes trailer/bonus
            mediaType = MediaType.PODCAST_EPISODE
        )

        val candidates = listOf(
            PodcastRssEpisode(
                guid = "ep-full",
                title = "פרק בונוס מיוחד",
                pubDate = "2024-01-01",
                durationMs = 7200000L, // 2 hours! Diff > 115 minutes!
                enclosureUrl = "https://pod.com/full.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 50000000L
            )
        )

        val score = PodcastEpisodeMatcher.scoreCandidate(target, candidates[0])
        // Score should be heavily penalized by the huge duration mismatch (>10min penalty)
        assertTrue("Score should be penalized due to massive duration mismatch, got $score", score < 40.0)
        val matched = PodcastEpisodeMatcher.match(target, candidates)
        assertNull("Should not match blindly when duration is vastly different", matched)
    }

    @Test
    fun testMatching_multipleSimilarEpisodes_selectsCorrectCandidate() {
        val target = SongsModel(
            id = 1,
            title = "פרק 55: חידושים במדעי המוח",
            album = "מדע",
            singer = "מדע",
            coverUri = "",
            url = "episode:spot55",
            spotifyTrackId = "spot55",
            durationMs = 1800000, // 30 min
            mediaType = MediaType.PODCAST_EPISODE
        )

        val candidate54 = PodcastRssEpisode(
            guid = "ep-54",
            title = "פרק 54: חידושים במדעי המוח חלק א",
            pubDate = "2024-01-01",
            durationMs = 1800000L,
            enclosureUrl = "https://cdn.com/ep54.mp3",
            enclosureType = "audio/mpeg",
            enclosureLength = 20000000L
        )

        val candidate55 = PodcastRssEpisode(
            guid = "ep-55",
            title = "פרק 55: חידושים במדעי המוח חלק ב",
            pubDate = "2024-01-08",
            durationMs = 1805000L,
            enclosureUrl = "https://cdn.com/ep55.mp3",
            enclosureType = "audio/mpeg",
            enclosureLength = 20000000L
        )

        val matched = PodcastEpisodeMatcher.match(target, listOf(candidate54, candidate55))
        assertNotNull(matched)
        assertEquals("Expected candidate 55 to be selected over 54", "https://cdn.com/ep55.mp3", matched!!.enclosureUrl)
    }

    @Test
    fun testMatching_noCandidatePassesThreshold_returnsNull() {
        val target = SongsModel(
            id = 1,
            title = "היסטוריה של רומא העתיקה",
            album = "היסטוריה",
            singer = "היסטוריה",
            coverUri = "",
            url = "episode:spot-rome",
            spotifyTrackId = "spot-rome",
            durationMs = 2400000,
            mediaType = MediaType.PODCAST_EPISODE
        )

        val candidates = listOf(
            PodcastRssEpisode(
                guid = "cooking-1",
                title = "מתכונים לארוחת ערב מהירה",
                pubDate = "2024-01-01",
                durationMs = 600000L,
                enclosureUrl = "https://cdn.com/food.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 10000000L
            )
        )

        val matched = PodcastEpisodeMatcher.match(target, candidates)
        assertNull("Should return null when no candidate passes threshold", matched)
    }

    @Test
    fun testMatching_spotifyIdDifferentFromRssGuid_stillMatches() {
        val target = SongsModel(
            id = 1,
            title = "How to Build a Habit",
            album = "Productivity Show",
            singer = "Host",
            coverUri = "",
            url = "episode:4A8zK8sJzL0192ksp",
            spotifyTrackId = "4A8zK8sJzL0192ksp", // Spotify unique ID
            durationMs = 2700000, // 45 min
            mediaType = MediaType.PODCAST_EPISODE
        )

        val candidates = listOf(
            PodcastRssEpisode(
                guid = "https://feeds.megaphone.fm/prod-show/ep-102", // Arbitrary RSS GUID
                title = "How to Build a Habit",
                pubDate = "Wed, 14 Feb 2024 08:00:00 GMT",
                durationMs = 2702000L, // 45m 2s
                enclosureUrl = "https://traffic.megaphone.fm/habit.mp3",
                enclosureType = "audio/mpeg",
                enclosureLength = 40000000L
            )
        )

        val matched = PodcastEpisodeMatcher.match(target, candidates)
        assertNotNull("Should match despite Spotify ID differing from RSS GUID", matched)
        assertEquals("https://traffic.megaphone.fm/habit.mp3", matched!!.enclosureUrl)
    }

    // ==========================================
    // 3. FEED CACHE TESTS
    // ==========================================

    @Test
    fun testFeedCache_keyNormalization() {
        val norm1 = PodcastFeedCache.normalizeKey("קופה ראשית-פודקאסט")
        val norm2 = PodcastFeedCache.normalizeKey("  קופה ראשית - פודקאסט  ")
        assertEquals(norm1, norm2)

        val normEng1 = PodcastFeedCache.normalizeKey("Huberman Lab")
        val normEng2 = PodcastFeedCache.normalizeKey("huberman  lab!")
        assertEquals(normEng1, normEng2)
    }

    // ==========================================
    // 4. LIVE END-TO-END FLOW VERIFICATION
    // ==========================================

    @Test
    fun testRealFlow_liveShowResolution() {
        kotlinx.coroutines.runBlocking {
            val resolver = DefaultEpisodePlaybackResolver()
            // Create a realistic episode model for Huberman Lab
            val episode = SongsModel(
                id = 12345,
                title = "How to Optimize Your Brain & Focus",
                album = "Huberman Lab",
                singer = "Andrew Huberman",
                coverUri = "",
                url = "episode:huberman_live_test",
                spotifyTrackId = "huberman_live_test",
                durationMs = 7200000,
                mediaType = MediaType.PODCAST_EPISODE
            )

            // 1. Test iTunes feed discovery
            val feedResolver = DefaultPodcastFeedResolver()
            // In unit tests context is mocked or null, but we can call discoverViaItunes directly or test parseItunesFeedUrl
            val itunesJson = """
                {
                    "resultCount": 1,
                    "results": [
                        {
                            "collectionName": "Huberman Lab",
                            "artistName": "Scicomm Media",
                            "feedUrl": "https://feeds.megaphone.fm/hubermanlab"
                        }
                    ]
                }
            """.trimIndent()
            val discoveredFeedUrl = feedResolver.parseItunesFeedUrl(itunesJson, "Huberman Lab")
            assertEquals("https://feeds.megaphone.fm/hubermanlab", discoveredFeedUrl)

            // 2. Test live HTTP validation against a real public podcast audio URL
            val isReachable = resolver.validateAudioEnclosure("https://traffic.libsyn.com/secure/atpfm/atp500.mp3")
            println("Live Audio Enclosure Validation Result: $isReachable")
            assertTrue("Expected public podcast audio URL to be reachable via HEAD/Range GET", isReachable)
        }
    }
}

