package com.music.spotui.ui.screens

import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.music.spotui.ui.collections.RadioPreviewScreen
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.AlbumsModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.preferences.getCachedLibraryEntries
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.components.HomeCoverMenuSheet
import com.music.spotui.ui.components.SongOptionsSheet
import com.music.spotui.ui.collections.CollectionContent
import com.music.spotui.ui.collections.CollectionPresentation
import com.music.spotui.ui.viewmodel.PlaylistViewModel
import com.music.spotui.util.KosherWhitelistManager
import com.music.spotui.util.HomeArtworkPermissions
import com.music.spotui.ui.collections.isRadioCollection
import com.music.spotui.ui.navigation.playlistRoute
import com.music.spotui.ui.viewmodel.CollectionRecommendationsViewModel

@Composable
fun PlaylistScreen(navController: NavController, playlistId: String, playlistName: String = "", homeCover: String = "") {
    val vm: PlaylistViewModel = hiltViewModel()
    val songsResponse by vm.songs.collectAsState()
    val playlistResponse by vm.playlist.collectAsState()
    val context = LocalContext.current
    HomeImageApprovalRefresh(LocalLifecycleOwner.current)
    LaunchedEffect(playlistId) { vm.loadPlaylist(playlistId) }
    val songs = (songsResponse as? Response.Success)?.data.orEmpty()
    val playlist = (playlistResponse as? Response.Success)?.data
        ?: AlbumsModel(id = playlistId.hashCode() and 0x7fffffff, artists = "", coverUri = "", name = playlistName, time = "")
    // Keep the established prefetch and playback callbacks; this change is presentation only.
    LaunchedEffect(songs) { if (songs.isNotEmpty()) SongPlayer.prefetchSongs(songs, context) }
    var menuSong by remember { mutableStateOf<SongsModel?>(null) }
    menuSong?.let { SongOptionsSheet(it, navController, context, onDismiss = { menuSong = null }) }
    var collectionMenu by remember { mutableStateOf(false) }
    var savedRevision by remember { mutableIntStateOf(0) }
    val cover = HomeArtworkPermissions.imageUrl(homeCover) ?: playlist.coverUri
    val item = HomeItem.Playlist(playlist.name.ifBlank { playlistName }, cover, playlist.artists, playlistId)
    val radio = isRadioCollection(item.name) || isRadioCollection(playlistName)
    var recommendationMenu by remember { mutableStateOf<HomeItem.Playlist?>(null) }
    var scrollReset by remember { mutableIntStateOf(0) }
    if (collectionMenu) HomeCoverMenuSheet(item, onOpen = { scrollReset++ }, onSongOptions = {},
        onDismiss = { collectionMenu = false; savedRevision++ })
    recommendationMenu?.let { recommended ->
        HomeCoverMenuSheet(recommended,
            onOpen = { navController.navigate(playlistRoute(recommended.id, recommended.name, recommended.imageUrl)) },
            onSongOptions = {}, onDismiss = { recommendationMenu = null })
    }
    val recommendations: CollectionRecommendationsViewModel = hiltViewModel()
    val recommendationState by recommendations.state.collectAsState()
    LaunchedEffect(radio, playlistId, songs) { if (radio) recommendations.load(playlistId, songs) }
    val whitelistVersion by KosherWhitelistManager.versionState
    val allowed = remember(whitelistVersion, playlist, songs) {
        KosherWhitelistManager.isPlaylistWhitelisted(playlistId, playlist.coverUri, playlist.artists) ||
            songs.any { it.coverUri == playlist.coverUri && KosherWhitelistManager.isSongWhitelisted(it) }
    }
    LaunchedEffect(allowed, playlist.coverUri) {
        if (allowed && playlist.coverUri.isNotBlank()) KosherWhitelistManager.allowImageUrl(playlist.coverUri)
    }
    val saved = remember(playlistId, savedRevision) { getCachedLibraryEntries(context).any { it.spotifyId == playlistId } }
    val playing by vm.currentSongPlayingState
    val currentId by vm.currentSongId
    // Only the legacy grant above may register a global URL. A Home approval stays scoped here.
    val presentation = CollectionPresentation(item.name, playlist.artists, playlist.time, cover,
        (allowed && cover == playlist.coverUri) || HomeArtworkPermissions.isAllowedInCollection(item), radio)
    var radioPreview by rememberSaveable(playlistId) { mutableStateOf(false) }
    Box {
    Box(if (radioPreview) Modifier.clearAndSetSemantics {} else Modifier) {
    CollectionContent(presentation, songs, playing, currentId, saved,
        loading = songsResponse is Response.Loading, error = (songsResponse as? Response.Error)?.error,
        onBack = { navController.navigateUp() }, onRetry = { vm.loadPlaylist(playlistId) },
        onSave = { collectionMenu = true }, onMore = { collectionMenu = true }, onSongMore = { menuSong = it },
        onDownload = {
            if (!SongPlayer.allDownloaded(songs, context)) {
                SongPlayer.downloadAll(songs.filterNot { it.isPodcast() }, context)
                Toast.makeText(context, "Downloading ${songs.count { !it.isPodcast() }} tracks…", Toast.LENGTH_SHORT).show()
            }
        },
        onShuffle = { displaySongs ->
            if (displaySongs.any { it.isPodcast() }) {
                val shuffled = displaySongs.shuffled()
                shuffled.firstOrNull()?.let { vm.playSavedSelection(it, shuffled, context) }
            } else {
                vm.startShuffled(displaySongs)?.let { first ->
                    SongPlayer.playSong(first.url, context)
                    vm.updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, playlist.name)
                }
            }
        },
        onPlay = { displaySongs ->
            when {
                playing -> vm.setPlaying(false)
                songs.any { it.id == vm.currentSongId.value } -> vm.setPlaying(true)
                else -> displaySongs.firstOrNull()?.let { first ->
                    if (first.isPodcast()) vm.playSavedSelection(first, displaySongs, context)
                    else {
                        vm.updateQueue(displaySongs)
                        SongPlayer.playSong(first.url, context)
                        vm.updateSongState(first.coverUri, first.title, first.singer, true, first.id, 0, playlist.name)
                    }
                }
            }
        },
        onSong = { song, index, displaySongs ->
            if (song.isPodcast()) vm.playSavedSelection(song, displaySongs, context)
            else {
                vm.updateQueue(displaySongs)
                vm.updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, playlist.name)
                SongPlayer.playSong(song.url, context)
            }
        }, scrollReset = scrollReset, recommendations = recommendationState.items,
        recommendationsLoading = recommendationState.loading, recommendationsFailed = recommendationState.failed,
        onRecommendationsRetry = { recommendations.load(playlistId, songs, force = true) },
        onRecommendation = { navController.navigate(playlistRoute(it.id, it.name, it.imageUrl)) },
        onRecommendationMore = { recommendationMenu = it }, onRadioPreview = { radioPreview = true })
    }
    if (radio && radioPreview) RadioPreviewScreen(presentation, songs,
        onBack = { radioPreview = false }, onMore = { menuSong = it })
    }
}
