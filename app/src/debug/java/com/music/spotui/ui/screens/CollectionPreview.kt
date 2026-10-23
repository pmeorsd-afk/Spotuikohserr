package com.music.spotui.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.data.entity.HomeItem
import com.music.spotui.ui.collections.CollectionContent
import com.music.spotui.ui.collections.CollectionPresentation
import com.music.spotui.ui.collections.CollectionSearchRow
import com.music.spotui.ui.theme.SpotuiTheme

@Preview(name = "Playlist — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "Playlist — phone", widthDp = 360, heightDp = 720)
@Preview(name = "Playlist — narrow", widthDp = 240, heightDp = 600)
@Preview(name = "Playlist — large text", widthDp = 240, heightDp = 600, fontScale = 1.3f)
@Composable
private fun CollectionPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CollectionContent(CollectionPresentation("Beach Party", "Spotify", "The ocean, the sun, your friends, the music.", "", false),
            (1..20).map { SongsModel(id = it, title = "שיר מספר $it", album = "", singer = "האמן", coverUri = "", url = "song:$it", durationMs = 180_000) },
            playing = false, currentId = -1, saved = false, loading = false, error = null,
            onBack = {}, onRetry = {}, onSave = {}, onMore = {}, onSongMore = {}, onDownload = {},
            onShuffle = {}, onPlay = {}, onSong = { _, _, _ -> })
    }
}

@Preview(name = "Radio — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "Radio — phone", widthDp = 360, heightDp = 720)
@Preview(name = "Radio — narrow large text", widthDp = 240, heightDp = 600, fontScale = 1.3f)
@Composable
private fun RadioPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CollectionContent(CollectionPresentation("רדיו לפי אושר כהן", "Spotify", "עם אמנים נוספים שאתם אוהבים", "", false, radio = true),
            (1..20).map { SongsModel(it, "שיר מספר $it", "", "האמן", "", "source:$it", durationMs = 180_000) },
            playing = false, currentId = -1, saved = false, loading = false, error = null,
            onBack = {}, onRetry = {}, onSave = {}, onMore = {}, onSongMore = {}, onDownload = {},
            onShuffle = {}, onPlay = {}, onSong = { _, _, _ -> },
            recommendations = (1..6).map { HomeItem.Playlist("הלהיטים הגדולים — $it", "", "Playlist • Spotify", it.toString().padStart(22, '0')) })
    }
}

@Preview(name = "Playlist search — Liked Songs geometry", widthDp = 600, heightDp = 48)
@Preview(name = "Playlist search — narrow large text", widthDp = 240, heightDp = 48, fontScale = 1.3f)
@Composable
private fun PlaylistSearchPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(color = Color(0xFF816141)) { CollectionSearchRow("", {}, true, {}) }
        }
    }
}

@Preview(name = "Radio samples — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "Radio samples — phone", widthDp = 360, heightDp = 720)
@Preview(name = "Radio samples — narrow", widthDp = 240, heightDp = 600)
@Preview(name = "Radio samples — narrow large text", widthDp = 240, heightDp = 600, fontScale = 1.3f)
@Composable
private fun RadioSamplesPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        val songs = (1..20).map { SongsModel(it, "אצלנו זה ככה — שיר מספר $it", "", "יסמין מועלם, בן צור", "", "source:$it", durationMs = 180_000) }
        com.music.spotui.ui.collections.RadioPreviewContent("רדיו לפי אודיה", Color(0xFF94E8B3), songs,
            com.music.spotui.playback.RadioPreviewState("|source:2", com.music.spotui.playback.PreviewPhase.PLAYING, 4_000), {}, {}, {})
    }
}
