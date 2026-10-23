package com.music.spotui.ui.notification;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.common.util.UnstableApi;
import java.util.List;

/**
 * Explicitly forwards every Media3 listener callback, including Java default methods.
 * Kotlin interface delegation inherits their no-op defaults, so it must not be used here:
 * MediaSession caches playback/timeline state from these callbacks before onEvents arrives.
 */
@UnstableApi
@SuppressWarnings("deprecation")
abstract class ForwardingPlayerListener implements Player.Listener {
    private final Player.Listener listener;

    protected ForwardingPlayerListener(Player.Listener listener) {
        this.listener = listener;
    }

    @Override public void onEvents(Player player, Player.Events events) {
        listener.onEvents(player, events);
    }
    @Override public void onTimelineChanged(Timeline timeline, int reason) {
        listener.onTimelineChanged(timeline, reason);
    }
    @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        listener.onMediaItemTransition(item, reason);
    }
    @Override public void onTracksChanged(Tracks tracks) { listener.onTracksChanged(tracks); }
    @Override public void onMediaMetadataChanged(MediaMetadata metadata) {
        listener.onMediaMetadataChanged(metadata);
    }
    @Override public void onPlaylistMetadataChanged(MediaMetadata metadata) {
        listener.onPlaylistMetadataChanged(metadata);
    }
    @Override public void onIsLoadingChanged(boolean loading) { listener.onIsLoadingChanged(loading); }
    @Override public void onLoadingChanged(boolean loading) { listener.onLoadingChanged(loading); }
    @Override public void onAvailableCommandsChanged(Player.Commands commands) {
        listener.onAvailableCommandsChanged(commands);
    }
    @Override public void onTrackSelectionParametersChanged(TrackSelectionParameters parameters) {
        listener.onTrackSelectionParametersChanged(parameters);
    }
    @Override public void onPlayerStateChanged(boolean playWhenReady, int state) {
        listener.onPlayerStateChanged(playWhenReady, state);
    }
    @Override public void onPlaybackStateChanged(int state) { listener.onPlaybackStateChanged(state); }
    @Override public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
        listener.onPlayWhenReadyChanged(playWhenReady, reason);
    }
    @Override public void onPlaybackSuppressionReasonChanged(int reason) {
        listener.onPlaybackSuppressionReasonChanged(reason);
    }
    @Override public void onIsPlayingChanged(boolean playing) { listener.onIsPlayingChanged(playing); }
    @Override public void onRepeatModeChanged(int mode) { listener.onRepeatModeChanged(mode); }
    @Override public void onShuffleModeEnabledChanged(boolean enabled) {
        listener.onShuffleModeEnabledChanged(enabled);
    }
    @Override public void onPlayerError(PlaybackException error) { listener.onPlayerError(error); }
    @Override public void onPlayerErrorChanged(@Nullable PlaybackException error) {
        listener.onPlayerErrorChanged(error);
    }
    @Override public void onPositionDiscontinuity(int reason) { listener.onPositionDiscontinuity(reason); }
    @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition,
            Player.PositionInfo newPosition, int reason) {
        listener.onPositionDiscontinuity(oldPosition, newPosition, reason);
    }
    @Override public void onPlaybackParametersChanged(PlaybackParameters parameters) {
        listener.onPlaybackParametersChanged(parameters);
    }
    @Override public void onSeekBackIncrementChanged(long increment) { listener.onSeekBackIncrementChanged(increment); }
    @Override public void onSeekForwardIncrementChanged(long increment) { listener.onSeekForwardIncrementChanged(increment); }
    @Override public void onMaxSeekToPreviousPositionChanged(long position) { listener.onMaxSeekToPreviousPositionChanged(position); }
    @Override public void onAudioSessionIdChanged(int id) { listener.onAudioSessionIdChanged(id); }
    @Override public void onAudioAttributesChanged(AudioAttributes attributes) { listener.onAudioAttributesChanged(attributes); }
    @Override public void onVolumeChanged(float volume) { listener.onVolumeChanged(volume); }
    @Override public void onSkipSilenceEnabledChanged(boolean enabled) { listener.onSkipSilenceEnabledChanged(enabled); }
    @Override public void onDeviceInfoChanged(DeviceInfo info) { listener.onDeviceInfoChanged(info); }
    @Override public void onDeviceVolumeChanged(int volume, boolean muted) { listener.onDeviceVolumeChanged(volume, muted); }
    @Override public void onVideoSizeChanged(VideoSize size) { listener.onVideoSizeChanged(size); }
    @Override public void onSurfaceSizeChanged(int width, int height) { listener.onSurfaceSizeChanged(width, height); }
    @Override public void onRenderedFirstFrame() { listener.onRenderedFirstFrame(); }
    @Override public void onCues(List<Cue> cues) { listener.onCues(cues); }
    @Override public void onCues(CueGroup cues) { listener.onCues(cues); }
    @Override public void onMetadata(Metadata metadata) { listener.onMetadata(metadata); }
}
