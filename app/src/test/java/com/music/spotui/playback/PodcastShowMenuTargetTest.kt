package com.music.spotui.playback

import com.music.spotui.data.entity.PodcastModel
import com.music.spotui.ui.podcast.PodcastShowMenuTarget
import org.junit.Assert.*
import org.junit.Test

class PodcastShowMenuTargetTest {
    private val id = "38bS44xjbVVZ3No3ByF1dJ"
    private fun show(id: String = this.id) = PodcastModel(id, "תוכנית", "Publisher", "cover", "Description")

    @Test fun shareAndCodeAlwaysAddressExactShowTypeAndId() {
        val target = PodcastShowMenuTarget.fromRoute(id, "route name", show())!!
        assertEquals(show(), target.show)
        assertEquals("https://open.spotify.com/show/$id", target.shareUrl)
        assertEquals("https://scannables.scdn.co/uri/plain/png/000000/white/640/spotify:show:$id", target.codeUrl)
    }

    @Test fun staleLoadedShowNeverSuppliesIdentityNamePublisherOrCover() {
        val target = PodcastShowMenuTarget.fromRoute(id, "Current route", show("other"))!!
        assertEquals(PodcastModel(id, "Current route", "", ""), target.show)
        assertEquals("https://open.spotify.com/show/$id", target.shareUrl)
    }

    @Test fun menuWorksWithoutLoadedShowOrAnyEpisodes() {
        val target = PodcastShowMenuTarget.fromRoute(id, "Current route", null)!!
        assertEquals(id, target.show.id)
        assertEquals("Current route", target.show.name)
        assertEquals("", target.show.coverUri)
        assertNotNull(target.codeUrl)
        assertEquals("פודקאסט", PodcastShowMenuTarget.fromRoute(id, "", null)!!.show.name)
    }

    @Test fun missingRouteIdentityCannotBeReplacedByLoadedShow() {
        listOf("", " ", "unknown").forEach { invalid ->
            assertNull(PodcastShowMenuTarget.fromRoute(invalid, "Name", show()))
        }
    }

    @Test fun untrustedOrNonSpotifyIdsCannotBuildRemoteLinks() {
        listOf("local-rss-id", "spotify:episode:$id", "spotify:show:$id", "https://open.spotify.com/show/$id",
            "$id?x=y", "$id/other", " $id", "${id.dropLast(1)}_", "אבגדהוזחטיכלמנסעפצקרשת").forEach { invalid ->
            val target = PodcastShowMenuTarget.fromRoute(invalid, "Name", null)!!
            assertEquals(invalid, target.show.id)
            assertNull(target.shareUrl)
            assertNull(target.codeUrl)
        }
    }

    @Test fun identicalNamesStillResolveIndependentlyByExactId() {
        val secondId = "0sNOF9WDwhWunNAHPD3Baj"
        val a = PodcastShowMenuTarget.fromRoute(id, "Same", null)!!
        val b = PodcastShowMenuTarget.fromRoute(secondId, "Same", null)!!
        assertEquals(a.show.name, b.show.name)
        assertNotEquals(a.shareUrl, b.shareUrl)
        assertNotEquals(a.codeUrl, b.codeUrl)
    }
}
