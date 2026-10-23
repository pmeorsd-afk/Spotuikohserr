package com.music.spotui.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.podcast.*
import com.music.spotui.ui.components.PodcastEpisodeMenuContent
import com.music.spotui.ui.theme.SpotuiTheme
import com.music.spotui.ui.viewmodel.NewEpisodesState

private fun previewEpisode(id: String) = PodcastEpisodeUiModel(id = "episode:show:$id", numericId = id.hashCode(),
    title = "פרק 74 — העולם בעוד 20 שנה: איך הטכנולוגיה תשנה את העבודה והחיים שלנו",
    showId = "show", showName = "הפודקאסט שלנו", publisher = "יוצר התוכנית", playUrl = "episode:show:$id",
    pubDate = "2026-10-18", durationMs = 5_000_000, description = "פרק חדש על השינויים בעולם, עם אורחים, רעיונות וסיפורים.")

@Preview(name = "New Episodes — reference", widthDp = 600, heightDp = 1067)
@Preview(name = "New Episodes — phone", widthDp = 360, heightDp = 720)
@Preview(name = "New Episodes — narrow", widthDp = 240, heightDp = 600)
@Preview(name = "New Episodes — large text", widthDp = 240, heightDp = 600, fontScale = 1.3f)
@Composable
private fun NewEpisodesPreview() {
    var removed by remember { mutableStateOf<PodcastEpisodeUiModel?>(null) }
    val episodes = remember { (1..5).map { previewEpisode(it.toString()) } }
    val feed = NewEpisodesFeed(episodes.take(2).filter { it != removed }.map { ContinuingEpisode(it, EpisodeListening(50_000, it.durationMs)) },
        episodes.drop(2).filter { it != removed })
    val undo = removed?.let { EpisodeCompletionUndo(it.id, "preview", Long.MAX_VALUE) }
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        NewEpisodesContent(NewEpisodesState(feed, loading = false, followedCount = 1, undo = undo),
            onBack = {}, onRefresh = {}, onUndo = { removed = null }, onComplete = { removed = it },
            onMore = {}, onDetail = {}, onPlay = {}, onSave = {}, onDownload = {},
            saved = { false }, isPlaying = { false }, downloading = { false })
    }
}

@Preview(name = "Episode menu — phone", widthDp = 360, heightDp = 640)
@Preview(name = "Episode menu — narrow large text", widthDp = 240, heightDp = 600, fontScale = 1.3f)
@Composable
private fun EpisodeMenuPreview() {
    SpotuiTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(Modifier.fillMaxSize().background(Color(0xFF1F1F1F)).verticalScroll(rememberScrollState())) {
                PodcastEpisodeMenuContent(previewEpisode("1"), false, onShare = {}, onToggleSaved = {},
                    onPlaylist = {}, onDownload = {}, onComplete = {}, onShow = {}, onEpisode = {}, onCode = {})
            }
        }
    }
}
