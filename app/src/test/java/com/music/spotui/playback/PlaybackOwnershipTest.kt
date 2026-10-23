package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the same commit gate used by SongPlayer, including results that ignore cancellation. */
class PlaybackOwnershipTest {
    private val ownership = PlaybackOwnership()
    private val episode = SongsModel(
        42, "Episode", "Show", "Publisher", "", "episode:show:guid",
        mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show",
    )
    private val mediaId = "episode/42"

    private fun music() = ownership.begin("spotify:track:track-id|Title Artist", MediaType.TRACK)

    private fun takeover() = ownership.begin(
        episode.url, episode.mediaType, episode, external = true, mediaId = mediaId,
    )

    @Test fun takeoverInvalidatesPendingRequestBeforeExternalItemIsAdopted() {
        val old = music()
        val external = takeover()
        assertTrue(external.generation > old.generation)
        assertNotEquals(old.token, external.token)
        assertFalse(ownership.isCurrent(old.token))
        assertNull(ownership.active)
        assertNull(ownership.positionOwner(external.token))
        assertFalse(ownership.runIfCurrent(old.token) { fail("Stale request committed") })
    }

    @Test fun oldResolutionCompletesAfterPodcastTakeover() = runBlocking {
        val old = music()
        val result = CompletableDeferred<Unit>()
        var playerItem = "old music"
        val request = launch(start = CoroutineStart.UNDISPATCHED) {
            result.await()
            ownership.runIfCurrent(old.token) {
                playerItem = "late resolved music"
                ownership.activate(old.token)
            }
        }
        val external = takeover()
        assertTrue(ownership.activate(external.token, mediaId))
        playerItem = mediaId
        result.complete(Unit)
        request.join()
        assertEquals(mediaId, playerItem)
        assertEquals(external, ownership.active)
    }

    @Test fun oldQualityUpgradeCannotReplaceAdoptedPodcast() = runBlocking {
        val old = music()
        ownership.activate(old.token)
        val qualityResult = CompletableDeferred<String>()
        var source = "music"
        val upgrade = launch(start = CoroutineStart.UNDISPATCHED) {
            val url = qualityResult.await()
            ownership.runIfCurrent(old.token) { source = url }
        }
        val external = takeover()
        ownership.activate(external.token, mediaId)
        source = "podcast direct audio"
        qualityResult.complete("old music lossless")
        upgrade.join()
        assertEquals("podcast direct audio", source)
        assertEquals(external, ownership.active)
    }

    @Test fun oldErrorRecoveryCannotRestoreMusicOrSkipQueue() = runBlocking {
        val old = music()
        ownership.activate(old.token)
        assertTrue(ownership.canRecoverMusic(old.token))
        val recoveryResult = CompletableDeferred<Unit>()
        var recoveries = 0
        var skips = 0
        val recovery = launch(start = CoroutineStart.UNDISPATCHED) {
            recoveryResult.await()
            ownership.runIfCurrent(old.token) { recoveries++ }
            ownership.runIfCurrent(old.token) { skips++ }
        }
        val external = takeover()
        ownership.activate(external.token, mediaId)
        recoveryResult.complete(Unit)
        recovery.join()
        assertEquals(0, recoveries)
        assertEquals(0, skips)
        assertFalse(ownership.canRecoverMusic(old.token))
        assertFalse(ownership.canRecoverMusic(external.token))
    }

    @Test fun oldCrossfadeCompletionCannotPromoteItsIncomingMusic() = runBlocking {
        val old = music()
        ownership.activate(old.token)
        val fadeComplete = CompletableDeferred<Unit>()
        val fade = launch(start = CoroutineStart.UNDISPATCHED) {
            fadeComplete.await()
            ownership.runIfCurrent(old.token) {
                ownership.begin("next music", MediaType.TRACK, token = "incoming-token")
                ownership.activate("incoming-token")
            }
        }
        val external = takeover()
        ownership.activate(external.token, mediaId)
        fadeComplete.complete(Unit)
        fade.join()
        assertEquals(external, ownership.current)
        assertEquals(external, ownership.active)
    }

    @Test fun positionTrackingBelongsOnlyToAdoptedEpisode() {
        val old = music()
        ownership.activate(old.token)
        assertEquals(old, ownership.positionOwner(old.token))
        val external = takeover()
        assertNull(ownership.positionOwner(old.token))
        assertNull(ownership.positionOwner(external.token))
        ownership.activate(external.token, mediaId)
        val owner = ownership.positionOwner(external.token)!!
        assertSame(episode, owner.song)
        assertEquals(MediaType.PODCAST_EPISODE, owner.mediaType)
        assertEquals(mediaId, owner.mediaId)
        assertEquals(external.generation, owner.generation)
        assertNull(ownership.positionOwner(old.token))
    }

    @Test fun latePositionTickDoesNotWriteOldPositionAfterTakeover() = runBlocking {
        val old = music()
        ownership.activate(old.token)
        val sampledPosition = CompletableDeferred<Long>()
        var persistedPosition = 0L
        val watch = launch(start = CoroutineStart.UNDISPATCHED) {
            val position = sampledPosition.await()
            ownership.runIfCurrent(old.token) { persistedPosition = position }
        }
        val external = takeover()
        ownership.activate(external.token, mediaId)
        ownership.runIfCurrent(external.token) { persistedPosition = 42_000L }
        sampledPosition.complete(190_000L)
        watch.join()
        assertEquals(42_000L, persistedPosition)
    }

    @Test fun lateEndedEventDoesNotDeactivatePodcast() {
        val old = music()
        ownership.activate(old.token)
        val external = takeover()
        ownership.activate(external.token, mediaId)
        ownership.deactivate(old.token)
        assertEquals(external, ownership.active)
    }

    @Test fun wrongMediaIdCannotAdoptExternalItem() {
        val external = takeover()
        assertFalse(ownership.activate(external.token, "episode/other"))
        assertNull(ownership.active)
    }

    @Test fun staleExternalItemCannotBeAdoptedEvenWithSameMediaId() {
        val oldExternal = takeover()
        val currentExternal = takeover()
        assertFalse(ownership.activate(oldExternal.token, mediaId))
        assertTrue(ownership.activate(currentExternal.token, mediaId))
    }

    @Test fun externalItemRequiresItsOriginalModel() {
        val external = ownership.begin(
            episode.url, episode.mediaType, external = true, mediaId = mediaId,
        )
        assertFalse(ownership.activate(external.token, mediaId))
    }

    @Test fun sameMusicQueryAfterTakeoverStillHasANewGeneration() {
        val old = music()
        takeover()
        val newer = music()
        assertEquals(old.request, newer.request)
        assertTrue(newer.generation > old.generation)
        assertFalse(ownership.runIfCurrent(old.token) { fail("Same-query stale commit") })
        assertTrue(ownership.activate(newer.token))
        assertTrue(ownership.canRecoverMusic(newer.token))
    }

    @Test fun currentMusicCanCommitActivateTrackPositionAndRecover() {
        val music = music()
        var committed = false
        assertTrue(ownership.runIfCurrent(music.token) { committed = true })
        assertTrue(committed)
        assertTrue(ownership.activate(music.token))
        assertEquals(music, ownership.positionOwner(music.token))
        assertTrue(ownership.canRecoverMusic(music.token))
    }

    @Test fun inAppPodcastCannotEnterMusicRecoveryEither() {
        val podcast = ownership.begin(episode.url, episode.mediaType, episode)
        ownership.activate(podcast.token)
        assertFalse(ownership.canRecoverMusic(podcast.token))
    }

    @Test fun restoredIdentityCanObtainFreshOwnershipWithoutOldTag() {
        val beforeDeath = takeover()
        val restarted = PlaybackOwnership()
        val restored = restarted.begin(
            episode.url, episode.mediaType, episode.copy(), external = true, mediaId = mediaId,
        )
        assertNotEquals(beforeDeath.token, restored.token)
        assertTrue(restarted.activate(restored.token, mediaId))
        assertEquals(episode, restarted.positionOwner(restored.token)?.song)
    }

    @Test fun stopInvalidatesEveryPendingCommitAndPositionOwner() {
        val external = takeover()
        ownership.activate(external.token, mediaId)
        ownership.begin("", MediaType.TRACK)
        assertFalse(ownership.runIfCurrent(external.token) { fail("Commit after stop") })
        assertNull(ownership.positionOwner(external.token))
        assertNull(ownership.active)
    }
}