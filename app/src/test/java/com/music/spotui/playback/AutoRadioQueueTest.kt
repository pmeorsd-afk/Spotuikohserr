package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import org.junit.Assert.*
import org.junit.Test

class AutoRadioQueueTest {
    private fun track(n: Int) = SongsModel(n, "Same title", "Album", "Artist", "",
        "spotify:track:${n.toString().padStart(22, '0')}|Same title Artist", n.toString().padStart(22, '0'))

    @Test fun radioKeepsExactVersionsButRejectsAlreadyQueuedAndDuplicateIds() {
        val anchor = track(1)
        val previous = track(2)
        val fresh = track(3)
        assertEquals(listOf(fresh, track(4)), AutoRadioQueue.candidates(anchor, listOf(anchor, previous),
            listOf(anchor, previous, fresh, fresh.copy(title = "Renamed"), track(4))))
    }

    @Test fun episodeAndUnresolvedCandidatesNeverEnterMusicRadio() {
        val anchor = track(1)
        val episode = track(2).copy(mediaType = MediaType.PODCAST_EPISODE, url = "episode:show:guid")
        assertEquals(listOf(track(5)), AutoRadioQueue.candidates(anchor, listOf(anchor),
            listOf(episode, track(3).copy(spotifyTrackId = ""), track(4).copy(url = ""), track(5))))
        assertTrue(AutoRadioQueue.candidates(episode, emptyList(), listOf(anchor)).isEmpty())
        assertTrue(AutoRadioQueue.candidates(anchor.copy(spotifyTrackId = ""), emptyList(), listOf(track(5))).isEmpty())
    }

    @Test fun emptyRecommendationsLeaveNoSyntheticFallbackAndBatchesAreBounded() {
        assertTrue(AutoRadioQueue.candidates(track(1), emptyList(), emptyList()).isEmpty())
        assertEquals((2..51).map(::track), AutoRadioQueue.candidates(track(1), emptyList(), (1..100).map(::track)))
    }

    @Test fun numericCollisionsCannotCollapseTheStationOrMakeSkipsAmbiguous() {
        val anchor = track(1)
        val fresh = AutoRadioQueue.candidates(anchor, listOf(anchor),
            listOf(track(2).copy(id = anchor.id), track(3), track(4).copy(id = 3), track(5)))
        assertEquals(listOf(track(3), track(5)), fresh)
        assertEquals(3, PlaybackQueueIdentity.select(anchor, listOf(anchor) + fresh).queue.size)
    }
}
