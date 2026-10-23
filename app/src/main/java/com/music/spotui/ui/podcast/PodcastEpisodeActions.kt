package com.music.spotui.ui.podcast

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.music.spotui.data.entity.PodcastEpisodeUiModel
import com.music.spotui.data.podcast.hasExactIdentity
import com.music.spotui.playback.DefaultPodcastFeedResolver
import com.music.spotui.playback.ExternalMediaIdentityResolver
import com.music.spotui.playback.PodcastRssEpisode
import com.music.spotui.playback.PodcastRssParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.UUID

internal fun exactSpotifyEpisodeId(ep: PodcastEpisodeUiModel): String? {
    val identity = ExternalMediaIdentityResolver.parse(ep.playUrl) as? ExternalMediaIdentityResolver.Identity.SpotifyEpisode ?: return null
    return identity.id.takeIf { ep.hasExactIdentity() && it == ep.spotifyEpisodeId && it.matches(Regex("[A-Za-z0-9]{22}")) }
}

/** A show URL or look-alike title must never become an episode code or a download. */
internal fun exactDownloadEnclosure(ep: PodcastEpisodeUiModel, candidates: List<PodcastRssEpisode>): PodcastRssEpisode? {
    if (!ep.hasExactIdentity()) return null
    val identity = ExternalMediaIdentityResolver.parse(ep.playUrl) as? ExternalMediaIdentityResolver.Identity.InternalEpisode ?: return null
    val exact = candidates.singleOrNull { !it.guid.isNullOrBlank() && it.guid == identity.episodeKey } ?: return null
    val uri = runCatching { URI(exact.enclosureUrl.orEmpty()) }.getOrNull() ?: return null
    if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
    val mime = exact.enclosureType.orEmpty().substringBefore(';').trim()
    if (mime.isNotBlank() && !mime.startsWith("audio/") && mime != "application/ogg") return null
    return exact
}

/** Downloads the exact RSS audio using Android's downloader, never the music matching pipeline.
 * This exports an audio file; it does not falsely register a completed in-app offline item. */
internal suspend fun enqueueEpisodeDownload(context: Context, ep: PodcastEpisodeUiModel): Boolean = withContext(Dispatchers.IO) {
    try {
        val resolver = DefaultPodcastFeedResolver()
        val feed = resolver.resolveFeedUrl(ep.toSongModel(), context) ?: return@withContext false
        val xml = resolver.fetchFeedXml(feed) ?: return@withContext false
        val audio = exactDownloadEnclosure(ep, PodcastRssParser.parse(xml)) ?: return@withContext false
        val mime = audio.enclosureType?.takeIf { it.isNotBlank() } ?: "audio/mpeg"
        val extension = when (mime.substringBefore(';')) {
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/aac" -> "aac"
            "audio/mpeg", "audio/mp3" -> "mp3"
            else -> android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime.substringBefore(';')) ?: "audio"
        }
        val filename = ep.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(80) + "-${UUID.randomUUID()}.$extension"
        val request = DownloadManager.Request(Uri.parse(audio.enclosureUrl)).setTitle(ep.title)
            .setDescription(ep.showName).setMimeType(mime)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        if (android.os.Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        else request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, filename)
        ((context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager)?.enqueue(request) ?: -1L) > 0
    } catch (cancel: CancellationException) { throw cancel }
    catch (_: Exception) { false }
}
