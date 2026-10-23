package com.music.spotui.playback

import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.preferences.HistoryEntry
import com.music.spotui.data.preferences.ListeningHistoryLog
import com.music.spotui.ui.history.*
import com.music.spotui.ui.navigation.ItemDetailRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ListeningHistoryTest {
    private val utc = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-12T10:00:00Z").toEpochMilli()
    private val id = "1234567890123456789012"
    private val track = SongsModel(7, "Same title", "Album", "Artist", "cover", "spotify:track:$id", id, durationMs = 90_000)
    private val episode = track.copy(mediaType = MediaType.PODCAST_EPISODE,
        url = "episode:show:guid", podcastShowId = "show", durationMs = 120_000)
    private fun entry(song: SongsModel = track, ts: Long = now) = HistoryEntry.fromSong(song, ts)
    private fun append(log: List<HistoryEntry>, item: HistoryEntry) = ListeningHistoryLog.append(log, item, utc)

    @Test fun persistedTrackAndPodcastPreserveEveryModelField() {
        val original = listOf(entry(track.copy(explicit = true)), entry(episode, now - 10))
        val decoded = ListeningHistoryLog.decode(ListeningHistoryLog.encode(original))
        assertEquals(original, decoded)
        assertEquals(id, decoded[1].exactSong()!!.spotifyTrackId)
        assertEquals("show", decoded[1].exactSong()!!.podcastShowId)
    }

    @Test fun persistedEpisodeHasExplicitEpisodeAlias() {
        val raw = ListeningHistoryLog.encode(listOf(entry(episode)))
        assertEquals(id, JSONArray(raw).getJSONObject(0).getJSONObject("media").getString("spotifyEpisodeId"))
    }

    @Test fun oldMetadataIsKeptWithoutInventingTypeOrNavigation() {
        val old = ListeningHistoryLog.decode("""[{"ts":123,"songId":7,"title":"Episode","singer":"Host"}]""").single()
        assertEquals("Episode", old.title)
        assertNull(old.exactSong())
        assertNull(historyDetail(old))
        assertEquals(1, historyDays(listOf(old), HistoryFilter.ALL, utc).size)
        assertTrue(historyDays(listOf(old), HistoryFilter.MUSIC, utc).isEmpty())
        assertTrue(historyDays(listOf(old), HistoryFilter.PODCASTS, utc).isEmpty())
    }

    @Test fun malformedTypedPayloadDoesNotTurnEpisodeIntoTrack() {
        val raw = ListeningHistoryLog.encode(listOf(entry(episode)))
            .replace("\"spotifyEpisodeId\":\"$id\"", "\"spotifyEpisodeId\":\"other\"")
        assertNull(historyDetail(ListeningHistoryLog.decode(raw).single()))
    }

    @Test fun conflictingTypeIsNotClickableOrRecorded() {
        val invalid = entry(track.copy(url = "spotify:episode:$id"))
        assertNull(historyDetail(invalid))
        assertTrue(append(emptyList(), invalid).isEmpty())
    }

    @Test fun malformedWholeLogFailsSafely() {
        assertTrue(ListeningHistoryLog.decode("broken").isEmpty())
        assertTrue(ListeningHistoryLog.decode(null).isEmpty())
    }

    @Test fun metadataOnlyAttemptIsNotAdded() {
        assertTrue(append(emptyList(), HistoryEntry(now, 7, "Title", "Artist")).isEmpty())
        assertTrue(append(emptyList(), entry(track.copy(title = "", url = "legacy query", spotifyTrackId = ""))).isEmpty())
    }

    @Test fun pauseResumeAndQualityMetadataChangesDoNotDuplicateSameDay() {
        val log = listOf(entry())
        assertSame(log, append(log, entry(track.copy(id = 99, coverUri = "new"), now + 100)))
    }

    @Test fun sameNumericIdAndTitleButDifferentStableIdentityAreSeparateListens() {
        val other = track.copy(url = "spotify:track:abcdefghijklmnopqrstuv", spotifyTrackId = "abcdefghijklmnopqrstuv")
        assertEquals(2, append(listOf(entry()), entry(other)).size)
    }

    @Test fun trackAndEpisodeWithSameSpotifyTextIdNeverMerge() {
        assertEquals(2, append(listOf(entry()), entry(episode)).size)
    }

    @Test fun aThenBThenARetainsChronologicalEvents() {
        var log = append(emptyList(), entry())
        log = append(log, entry(episode, now + 100))
        log = append(log, entry(track, now + 200))
        assertEquals(listOf(track, episode, track), log.map { it.song })
    }

    @Test fun sameItemOnNewDayIsRecorded() {
        assertEquals(2, append(listOf(entry()), entry(ts = now + 86_400_000)).size)
    }

    @Test fun logRetainsOnlyNewest500Events() {
        val existing = (1..500).map { entry(ts = now - it) }
        val result = append(existing, entry(episode))
        assertEquals(500, result.size)
        assertEquals(episode, result.first().song)
        assertEquals(now - 499, result.last().ts)
    }

    @Test fun groupingUsesLocalCalendarDateAcrossMidnight() {
        val before = Instant.parse("2026-10-11T20:59:59Z").toEpochMilli()
        val after = Instant.parse("2026-10-11T21:00:01Z").toEpochMilli()
        val days = historyDays(listOf(entry(ts = before), entry(episode, after)), HistoryFilter.ALL, ZoneId.of("Asia/Jerusalem"))
        assertEquals(listOf(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 11)), days.map { it.date })
    }

    @Test fun filtersPreserveExactTypesAndDoNotMergeSeparatedGroups() {
        val rows = listOf(entry(ts = now + 3), entry(ts = now + 2), entry(episode, now + 1), entry(ts = now))
        val music = historyDays(rows, HistoryFilter.MUSIC, utc).single()
        assertEquals(listOf(2, 1), music.groups.map { it.entries.size })
        assertTrue(music.groups.flatMap { it.entries }.all { it.exactSong()!!.mediaType == MediaType.TRACK })
        assertEquals(listOf(episode), historyDays(rows, HistoryFilter.PODCASTS, utc).single().groups.flatMap { it.entries }.map { it.song })
    }

    @Test fun expandingAndCollapsingGroupOnlyChangesVisibleRows() {
        val entries = listOf(entry(ts = now), entry(ts = now - 1))
        val group = historyDays(entries, HistoryFilter.ALL, utc).single().groups.single()
        assertTrue(group.visibleEntries(false).isEmpty())
        assertEquals(entries, group.visibleEntries(true))
        assertTrue(group.visibleEntries(false).isEmpty())
        assertEquals(entries, group.entries)
        assertEquals(entries.take(1), HistoryGroup(entries.take(1)).visibleEntries(false))
    }

    @Test fun detailsUseExactModelsAndRegistrySeparatesTrackFromEpisode() {
        val songTarget = historyDetail(entry()) as HistoryDetail.Track
        val episodeTarget = historyDetail(entry(episode)) as HistoryDetail.Episode
        assertEquals(track, songTarget.song)
        assertEquals(episode, episodeTarget.song)
        val trackKey = ItemDetailRegistry.register(songTarget.song)
        val episodeKey = ItemDetailRegistry.register(episodeTarget.song)
        assertNotEquals(trackKey, episodeKey)
        assertEquals(track, ItemDetailRegistry.get(trackKey))
        assertEquals(episode, ItemDetailRegistry.get(episodeKey))
    }

    @Test fun recreatedHistoryReadsSamePodcastBookmarkAndCompletion() {
        val values = mutableMapOf<String, Long>()
        val bookmark = PlaybackProgressStore(values::get) { key, value -> values[key] = value }
        val progress = PodcastProgressStore(values::get) { values.putAll(it) }
        bookmark.save(episode, 30_000)
        progress.record(episode, 120_000, false)
        val restored = ListeningHistoryLog.decode(ListeningHistoryLog.encode(listOf(entry(episode)))).single().exactSong()!!
        assertEquals(0.25f, progress.progress(restored)!!.fraction, 0f)
        bookmark.save(episode, 120_000, completed = true)
        progress.record(episode, 120_000, true)
        assertEquals(1f, progress.progress(restored)!!.fraction, 0f)
    }

    @Test fun internalEpisodeDerivesShowOnlyFromStableSourceIdentity() {
        val target = historyDetail(entry(episode.copy(podcastShowId = ""))) as HistoryDetail.Episode
        assertEquals("show", target.song.podcastShowId)
        assertEquals(id, target.song.spotifyTrackId)
    }

    @Test fun episodeDetailConversionKeepsSameBookmarkIdentity() {
        val detail = PodcastEpisodeUiModel(id = episode.url, numericId = episode.id, title = episode.title,
            playUrl = episode.url, showId = episode.podcastShowId, showName = episode.album,
            publisher = episode.singer, coverUri = episode.coverUri, durationMs = episode.durationMs.toLong(),
            spotifyEpisodeId = episode.spotifyTrackId)
        assertEquals(episode, detail.toSongModel())
        assertEquals(PlaybackProgressStore.key(episode), PlaybackProgressStore.key(detail.toSongModel()))
    }

    @Test fun unresolvedOrPausedOrSecondaryPlayerNeverRecords() {
        val ownership = PlaybackOwnership()
        val ticket = ownership.begin(track.url, track.mediaType, track)
        val recorder = PlaybackHistoryRecorder()
        val recorded = mutableListOf<SongsModel>()
        recorder.onPlayingSnapshot(ownership, ticket.token, true, true) { recorded += it }
        ownership.activate(ticket.token)
        recorder.onPlayingSnapshot(ownership, ticket.token, true, false) { recorded += it }
        recorder.onPlayingSnapshot(ownership, ticket.token, false, true) { recorded += it }
        assertTrue(recorded.isEmpty())
        recorder.onPlayingSnapshot(ownership, ticket.token, true, true) { recorded += it }
        recorder.onPlayingSnapshot(ownership, ticket.token, true, false) { recorded += it }
        recorder.onPlayingSnapshot(ownership, ticket.token, true, true) { recorded += it }
        assertEquals(listOf(track), recorded)
    }

    @Test fun externalTakeoverPreventsOldRequestFromEnteringHistory() {
        val ownership = PlaybackOwnership()
        val recorder = PlaybackHistoryRecorder()
        val recorded = mutableListOf<SongsModel>()
        val old = ownership.begin(track.url, track.mediaType, track)
        ownership.activate(old.token)
        val external = ownership.begin(episode.url, episode.mediaType, episode, external = true, mediaId = "episode")
        ownership.activate(external.token, "episode")
        recorder.onPlayingSnapshot(ownership, old.token, true, true) { recorded += it }
        recorder.onPlayingSnapshot(ownership, external.token, true, true) { recorded += it }
        ownership.revoke(external.token)
        recorder.onPlayingSnapshot(ownership, external.token, true, true) { recorded += it }
        assertEquals(listOf(episode), recorded)
    }
}
