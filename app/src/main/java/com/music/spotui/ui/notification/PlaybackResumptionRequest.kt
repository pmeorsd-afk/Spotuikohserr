package com.music.spotui.ui.notification

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.spotui.data.entity.SongsModel
import com.music.spotui.playback.PlaybackSnapshot
import com.music.spotui.playback.WazeResumeResolver

/**
 * Media3 1.3.1 uses onPlaybackResumption for both Play and System UI's recent-item query at boot.
 * Capture controllerForCurrentRequest synchronously in that callback: only the Play dispatch has
 * a transport controller in scope. The callback's controller argument alone cannot distinguish them.
 */
internal object PlaybackResumptionRequest {
    fun dispatch(
        hasTransportRequest: Boolean,
        loadSaved: () -> PlaybackSnapshot?,
        artwork: (SongsModel) -> Uri?,
        resumePlayback: () -> ListenableFuture<MediaSession.MediaItemsWithStartPosition>,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        // Keep metadata queries outside the real Play future, its ownership and cancellation guards.
        if (hasTransportRequest) return resumePlayback()
        return try {
            val saved = WazeResumeResolver.resolve(WazeResumeResolver.Request(), loadSaved(), null)
                ?.playback ?: throw IllegalStateException("No exact playback identity to resume")
            val song = saved.song
            val item = MediaItem.Builder()
                .setMediaId(song.url)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(song.title).setArtist(song.singer).setAlbumTitle(song.album)
                    .setMediaType(if (song.isPodcast()) MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE
                        else MediaMetadata.MEDIA_TYPE_MUSIC)
                    .setArtworkUri(artwork(song))
                    .setIsPlayable(true).setIsBrowsable(false)
                    .build())
                // No source URI, ownership tag, player preparation or current-queue update.
                .build()
            Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, saved.positionMs))
        } catch (e: Exception) {
            Futures.immediateFailedFuture(e)
        }
    }
}
