package com.music.spotui.playback

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.music.spotui.data.entity.MediaType
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

internal class SavedMediaTestStorage {
    val files = mutableMapOf<String, MutableMap<String, Any>>()
    fun context() = object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val values = files.getOrPut(name) { mutableMapOf() }
            fun editor(): SharedPreferences.Editor {
                val pending = mutableMapOf<String, Any?>()
                return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    when(method.name) {
                        "putString", "putBoolean", "putLong", "putInt" -> { pending[args!![0] as String] = args[1]; proxy }
                        "remove" -> { pending[args!![0] as String] = null; proxy }
                        "apply", "commit" -> { pending.forEach { (k,v) -> if(v == null) values.remove(k) else values[k] = v }; if(method.name == "commit") true else null }
                        else -> error(method.name)
                    }
                } as SharedPreferences.Editor
            }
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _,method,args ->
                when(method.name) {
                    "getAll" -> values.toMap()
                    "getString", "getBoolean", "getLong", "getInt" -> values[args!![0]] ?: args[1]
                    "contains" -> values.containsKey(args!![0])
                    "edit" -> editor()
                    else -> error(method.name)
                }
            } as SharedPreferences
        }
    }
}

class SavedEpisodeTest {
    private val store = SavedMediaTestStorage()
    private val episode = SongsModel(12, "Same title", "Show", "Publisher", "", "https://audio.example/episode.mp3",
        "38bS44xjbVVZ3No3ByF1dJ", durationMs = 120000, mediaType = MediaType.PODCAST_EPISODE, podcastShowId = "show-id")
    private val track = episode.copy(url = "spotify:track:38bS44xjbVVZ3No3ByF1dJ", mediaType = MediaType.TRACK, podcastShowId = "")

    @Test fun episodePersistsWithFullIdentityAndStaysOutOfLikedSongs() {
        assertTrue(saveEpisode(store.context(), episode))
        assertEquals(listOf(episode), getSavedEpisodes(store.context()))
        assertTrue(isSongLiked(store.context(), episode))
        assertTrue(getLocallyLikedSongs(store.context()).isEmpty())
        assertEquals(0, getLikedSongsCount(store.context()))
    }
    @Test fun musicAndEpisodeWithSameNumericAndSpotifyIdsStayIndependent() {
        addLikedSong(store.context(), track)
        addLikedSong(store.context(), episode)
        assertEquals(listOf(track), getLocallyLikedSongs(store.context()))
        assertEquals(listOf(episode), getSavedEpisodes(store.context()))
        removeLikedSong(store.context(), episode)
        assertEquals(listOf(track), getLocallyLikedSongs(store.context()))
        assertFalse(isSongLiked(store.context(), episode))
    }
    @Test fun exactSourcesWithSameNamesAreDistinctWithoutSpotifyIds() {
        val a = episode.copy(spotifyTrackId = "")
        val b = a.copy(url = "https://audio.example/other.mp3")
        saveEpisode(store.context(), a); saveEpisode(store.context(), b)
        assertEquals(setOf(a,b), getSavedEpisodes(store.context()).toSet())
        removeSavedEpisode(store.context(), a)
        assertEquals(listOf(b), getSavedEpisodes(store.context()))
    }
    @Test fun readThroughLegacyEpisodeMigrationIsNonDestructiveAndRemovalStaysRemoved() {
        val old = episode.copy(url = "episode:38bS44xjbVVZ3No3ByF1dJ", podcastShowId = "")
        val raw = JSONObject(PlaybackSnapshotCodec.encode(PlaybackSnapshot(old,0))).apply {
            remove("mediaType"); remove("spotifyEpisodeId"); remove("podcastShowId"); put("likedAt", 100L)
        }.toString()
        store.files["LikedSongs_Data"] = mutableMapOf("spotify:${old.spotifyTrackId}" to raw)
        assertEquals(listOf(old), getSavedEpisodes(store.context()))
        assertTrue(getLocallyLikedSongs(store.context()).isEmpty())
        removeSavedEpisode(store.context(), old)
        assertTrue(getSavedEpisodes(store.context()).isEmpty())
        assertEquals(raw, store.files["LikedSongs_Data"]?.values?.single())
        assertTrue(saveEpisode(store.context(), old))
        assertEquals(listOf(old), getSavedEpisodes(store.context()))
    }
    @Test fun malformedOrMissingIdentityCannotShowSuccessfulSave() {
        assertFalse(saveEpisode(store.context(), episode.copy(url = "", spotifyTrackId = "")))
        assertFalse(saveEpisode(store.context(), track))
        assertTrue(getSavedEpisodes(store.context()).isEmpty())
    }
    @Test fun customPlaylistKeepsMediaTypeAcrossReopeningAndIndependentRemoval() {
        val playlist = CustomPlaylistStore.createPlaylist(store.context(), "Mixed", track)
        assertTrue(CustomPlaylistStore.toggleSong(store.context(), playlist.id, episode))
        val reopened = CustomPlaylistStore.getPlaylists(store.context()).single()
        assertEquals(setOf(track,episode), reopened.cachedTracks.toSet())
        assertEquals(2,reopened.songKeys.size)
        assertTrue(CustomPlaylistStore.isSongInPlaylist(store.context(), playlist.id, episode))
        assertFalse(CustomPlaylistStore.toggleSong(store.context(), playlist.id, episode))
        assertEquals(listOf(track),CustomPlaylistStore.getPlaylists(store.context()).single().cachedTracks)
    }
    @Test fun checkedItemInPlaylistDoesNotGetAddedToDefaultAgainOnOpeningDestinations() {
        CustomPlaylistStore.createPlaylist(store.context(), "Episodes", episode)
        assertTrue(saveToDefaultIfNeeded(store.context(), episode))
        assertTrue(getSavedEpisodes(store.context()).isEmpty())
        assertTrue(isSavedInAnyDestination(store.context(), episode))
    }
    @Test fun oldCustomEpisodeKeysUpgradeOnlyWhenExplicitMetadataProvesType() {
        val old = episode.copy(url = "episode:38bS44xjbVVZ3No3ByF1dJ", podcastShowId = "")
        val pl = CustomPlaylistStore.createPlaylist(store.context(), "Old", old)
        val prefs = store.files.getValue("SpotUI_CustomPlaylists")
        val raw = prefs.getValue("playlists") as String
        prefs["playlists"] = raw.replace("episode:spotify:${old.spotifyTrackId}", old.spotifyTrackId)
            .replace("\"mediaType\":\"PODCAST_EPISODE\",", "")
        assertTrue(CustomPlaylistStore.isSongInPlaylist(store.context(), pl.id, old))
        assertEquals(listOf(old),CustomPlaylistStore.getPlaylists(store.context()).single().cachedTracks)
    }
    @Test fun legacyEpisodeNeverMarksSameIdMusicAsLikedAndSurvivesMusicRemoval() {
        val raw = PlaybackSnapshotCodec.encode(PlaybackSnapshot(episode, 0))
        store.files["LikedSongs_Data"] = mutableMapOf("spotify:${episode.spotifyTrackId}" to raw)
        store.files["LikedSongs"] = mutableMapOf(episode.id.toString() to episode.id.toString(), episode.spotifyTrackId to episode.spotifyTrackId)
        assertFalse(isSongLiked(store.context(), track))
        assertFalse(getLikedSongIds(store.context()).contains(episode.id))
        removeLikedSong(store.context(), track)
        removeLikedSongId(store.context(), track.id.toString())
        assertEquals(listOf(episode), getSavedEpisodes(store.context()))
        assertEquals(raw, store.files.getValue("LikedSongs_Data").values.single())
    }
    @Test fun savingMusicOverALegacyKeyPreservesExactEpisodeAndItsRemovalTombstone() {
        store.files["LikedSongs_Data"] = mutableMapOf("spotify:${episode.spotifyTrackId}" to PlaybackSnapshotCodec.encode(PlaybackSnapshot(episode, 0)))
        addLikedSong(store.context(), track)
        assertEquals(listOf(episode), getSavedEpisodes(store.context()))
        assertEquals(listOf(track), getLocallyLikedSongs(store.context()))
        removeSavedEpisode(store.context(), episode)
        assertTrue(getSavedEpisodes(store.context()).isEmpty())
        assertTrue(isSongLiked(store.context(), track))
    }
    @Test fun bulkMusicImportDoesNotLeaveLegacyEpisodeInPlaceOfMusic() {
        store.files["LikedSongs_Data"] = mutableMapOf("spotify:${episode.spotifyTrackId}" to PlaybackSnapshotCodec.encode(PlaybackSnapshot(episode, 0)))
        removeSavedEpisode(store.context(), episode)
        saveLocalLikedSongs(store.context(), listOf(track))
        assertEquals(listOf(track), getLocallyLikedSongs(store.context()))
        assertTrue(getSavedEpisodes(store.context()).isEmpty())
    }
    @Test fun confirmedRemoteOnlySavePersistsWithTypedIdentityAndSessionIsolation() {
        val remote = RemoteSavedMembershipStore(store.context(), "account-a")
        remote.record("playlist", episode, true)
        assertTrue(RemoteSavedMembershipStore(store.context(), "account-a").contains(episode))
        assertFalse(remote.contains(track))
        assertFalse(RemoteSavedMembershipStore(store.context(), "account-b").contains(episode))
        remote.record("other", episode, true)
        remote.record("playlist", episode, false)
        assertTrue(remote.contains(episode))
        remote.retainPlaylists(setOf("playlist"))
        assertFalse(remote.contains(episode))
    }
    @Test fun twoNewSameNamePlaylistsKeepSeparateIds() {
        val a = CustomPlaylistStore.createPlaylist(store.context(), "Same", episode)
        val b = CustomPlaylistStore.createPlaylist(store.context(), "Same", track)
        assertNotEquals(a.id, b.id)
        assertEquals(2, CustomPlaylistStore.getPlaylists(store.context()).size)
    }
}
