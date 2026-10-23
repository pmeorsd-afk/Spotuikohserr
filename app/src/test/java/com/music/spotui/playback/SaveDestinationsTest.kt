package com.music.spotui.playback

import com.metrolist.spotify.models.SpotifyTrack
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.podcast.savedPlaylistEpisode
import com.music.spotui.ui.library.*
import org.junit.Assert.*
import org.junit.Test

class SaveDestinationsTest {
    private val id = "38bS44xjbVVZ3No3ByF1dJ"
    private val episode = SongsModel(12,"Title","Show","Publisher","","episode:$id",id,mediaType=MediaType.PODCAST_EPISODE)
    private val track = episode.copy(url="spotify:track:$id",mediaType=MediaType.TRACK)

    @Test fun draftTracksOnlyExplicitEditsAndDoesNotOverwriteWithLateLoad() {
        val draft=SaveDestinationDraft()
        draft.load("default",true); draft.load("a",false); draft.load("b",true)
        draft.toggle("a"); draft.load("a",false)
        assertEquals(mapOf("a" to true),draft.changes())
        assertEquals(true,draft.selected("default"))
        draft.toggle("a"); assertTrue(draft.changes().isEmpty())
    }
    @Test fun failedDestinationRemainsPendingAndAcceptedDestinationDoesNotRepeatOnRetry() {
        val draft=SaveDestinationDraft()
        draft.load("local",false); draft.load("remote",false)
        draft.toggle("local"); draft.toggle("remote")
        draft.accept("local",true)
        assertEquals(mapOf("remote" to true),draft.changes())
    }
    @Test fun cancellingDraftCannotWriteOrChangeInitialSavedState() {
        val draft=SaveDestinationDraft(); draft.load("primary",true); draft.toggle("primary")
        val reopened=SaveDestinationDraft(); reopened.load("primary",true)
        assertEquals(true,reopened.selected("primary"))
        assertTrue(reopened.changes().isEmpty())
    }
    @Test fun unknownMembershipCannotBeToggledIntoFalseSuccess() {
        val draft=SaveDestinationDraft(); draft.toggle("not-loaded")
        assertNull(draft.selected("not-loaded")); assertTrue(draft.changes().isEmpty())
    }
    @Test fun shortUpwardDragExpandsAndDownwardDragFirstCollapsesThenDismisses() {
        val a=SaveSheetAnchors(1000f,232f)
        assertEquals(0f,a.settle(a.collapsed-30,a.collapsed,0f,24f,400f))
        assertEquals(a.collapsed,a.settle(30f,0f,0f,24f,400f))
        assertEquals(a.height,a.settle(a.collapsed+30,a.collapsed,0f,24f,400f))
    }
    @Test fun smallMovementReturnsToAnchorAndFlingUsesDirection() {
        val a=SaveSheetAnchors(480f,232f)
        assertEquals(a.collapsed,a.settle(a.collapsed-10,a.collapsed,0f,24f,400f))
        assertEquals(0f,a.settle(a.collapsed-5,a.collapsed,-600f,24f,400f))
        assertEquals(a.collapsed,a.settle(5f,0f,600f,24f,400f))
    }
    @Test fun anchorsRemainBoundedAcrossNormalSmallAndKeyboardViewports() {
        listOf(1067f,800f,480f,320f,120f).forEach { height ->
            val a=SaveSheetAnchors(height,minOf(232f,height*0.7f))
            assertTrue(a.collapsed in 0f..height)
            assertEquals(0f,a.clamp(-20f)); assertEquals(height,a.clamp(height+200))
        }
    }
    @Test fun interruptedSheetAnimationReturnsToAnAnchorRatherThanFloating() {
        val anchors = SaveSheetAnchors(1000f, 232f)
        assertEquals(0f, anchors.settle(190f, 200f, 0f, 24f, 400f))
        assertEquals(anchors.collapsed, anchors.settle(610f, 600f, 0f, 24f, 400f))
    }
    @Test fun sameNamePlaylistsAreRetainedAndDuplicateIdsDeduplicated() {
        val a=SavePlaylist("custom_a","Same",1,"",true)
        val b=SavePlaylist("remote_b","Same",2,"",false)
        assertEquals(listOf(a,b),mergeSavePlaylists(listOf(a),listOf(b,b)))
    }
    @Test fun remoteUrisKeepTrackAndEpisodeNamespacesSeparate() {
        assertEquals("spotify:episode:$id",savedItemSpotifyUri(episode))
        assertEquals("spotify:track:$id",savedItemSpotifyUri(track))
        assertNull(savedItemSpotifyUri(episode.copy(spotifyTrackId="",url="https://audio.example/rss.mp3")))
        assertNull(savedItemSpotifyUri(episode.copy(url="spotify:track:$id")))
    }
    @Test fun playlistEpisodeCanNeverBecomeMusicQuery() {
        val result=savedPlaylistEpisode(SpotifyTrack(id=id,name="Episode",uri="spotify:episode:$id",durationMs=120000))!!
        assertEquals(MediaType.PODCAST_EPISODE,result.mediaType)
        assertEquals("episode:$id",result.url)
        assertEquals(id,result.spotifyTrackId)
        assertEquals("",result.podcastShowId)
        assertNull(savedPlaylistEpisode(SpotifyTrack(id=id,uri="spotify:track:$id")))
        assertNull(savedPlaylistEpisode(SpotifyTrack(id="other",uri="spotify:episode:$id")))
    }
    @Test fun saveTargetUsesOwnedTypedItemAndRefusesStaleOrAmbiguousUiIdentity() {
        assertEquals(episode,savedMediaTarget(listOf(track,episode),12,"Title",episode))
        assertNull(savedMediaTarget(listOf(track,episode),12,"Title",null))
        assertNull(savedMediaTarget(listOf(track),12,"Stale title",episode))
        assertNull(savedMediaTarget(emptyList(),12,"Title",null))
    }
}
