# Radio track previews — 2026-10-22

## Device failure follow-up: missing playlist duration

The user reported that multiple previews showed unavailable while ordinary playback
of the same songs worked. Reproduced through the production anonymous Spotify
playlist client: the sampled Radio returned 20 tracks, all mapped to durationMs=0.
`fetchPlaylist` supplies `trackDuration.totalMilliseconds`, but parseGqlTrackDurationMs
only read `duration.totalMilliseconds` and the flat forms. The preview coordinator
correctly rejects an unknown duration before resolving audio, so this mapping bug
made ordinary Radio songs ineligible for their ten-second midpoint clip.

The parser now also reads the positive `trackDuration.totalMilliseconds` value,
preserving all existing duration formats and exact track identity. The same live
Radio check now reports 20/20 positive durations and zero unknown lengths. The
regression fixture reproduces 213500 -> 0 before the fix and 213500 after it.
Unknown/invalid lengths still remain unknown; no guessed lengths or substitute
tracks are introduced. SongPlayer, RadioPreviewSession, RadioPreviewPlayer and
EpisodePlaybackResolver SHA-256 hashes remain identical to the before snapshot.

All seven focused Spotify playlist tests pass, including the opt-in live read.
App User: 666 pass/0 fail/2 skips; Admin: 662 pass/the same four whitelist failures/
2 skips. Both Debug and instrumentation variants compile. Evidence is in
`app/build/reports/radio-preview-fix/` (before/after XML, logs and protected hashes).
No device is connected, so audible acceptance on the reporting phone remains
pending. No APK was packaged or installed.

## Requested behavior

The existing Radio hero, controls and recommendations remain in place. Tapping its
artist/track summary now opens a separate preview list rather than expanding normal
full-track rows. Pressing the summary scales its drawing to 97% and temporarily
turns all spans gray. Preview cards use the same press/release feedback, without
remeasuring or moving neighboring cards. Static references establish the visual
states; the 80 ms down/up timings are an approximation, not measured video timing.

The new surface has a cover-derived gradient, centered title and explanatory text,
RTL gray cards, right-side artwork/play controls, left-side options, and a pinned
title when scrolling. Normal, narrow and large-font layouts use the same surface.
The main collection remains composed behind it to preserve its exact scroll state;
its accessibility subtree is hidden while previews are open. Back returns to that
position. The app's existing bottom player and navigation remain outside this surface.

## Audio contract

The user explicitly confirmed that a playing main song/podcast pauses at preview
start and automatically resumes when the preview is paused or ends. A previously
paused main item stays paused. Main item, position, queue, notification metadata and
playback identity are never replaced with the preview track.

- A screen-owned ExoPlayer plays a ten-second clip centered on the known track
  duration. Tracks shorter than ten seconds use their available length; unknown
  duration or source failure produces an unavailable/retry message, never a guessed
  substitute. Podcast rows are excluded and rejected by the preview source boundary.
- Sources use the existing exact-query music resolution and data-source configuration
  with `forPlayback=false`. There are no changes to matching, provider selection,
  VISIONOS, InnerTube, TrackVideoCache, or the RSS/direct-podcast path.
- Media3 [clipping configuration](https://developer.android.com/reference/androidx/media3/common/MediaItem.ClippingConfiguration)
  bounds the audio itself. The progress ring reads actual clipped player position;
  loading time is not counted as listened audio. Pause saves exact player position;
  another tap continues the remainder of that preview. Completed previews restart.
- This player has no MediaSession, main metadata listener, queue writer, crossfade,
  history recorder, or automatic next track. Artwork uses the existing song permissions.
- Switching previews retains the main playback resume lease without briefly resuming
  it between samples. Generations reject late resolver results and stale callbacks.
  The main playback token must still match before any automatic resume.
- Back/normal screen disposal restores an interrupted main item. Main transport
  commands revoke that restoration. Audio-focus loss, headphone disconnection and
  leaving the foreground stop the preview without restarting audio. The noisy-audio
  receiver also covers loading, before the temporary player exists.
- Source resolution and stalled output have bounded waits (25 and 20 seconds).
  Failure releases preview resources and restores an eligible main item.

The small optional `RadioPreviewInterruption` hook runs before phone transport and
external Play/Pause/Stop commands. This includes Pause on an already paused main
player, for which ExoPlayer might emit no state-change callback. With no preview
screen open, the hook has no listener and changes no transport behavior. The existing
Media3 forwarding methods and ownership checks still perform the actual commands.

## Validation and device boundary

`RadioPreviewSessionTest` exercises the production coordinator using fake audio ports:
clip boundaries, partial resume, paused-main preservation, switching, late completion,
loading cancellation, source failure, focus interruption, external ownership/transport,
screen close, podcast rejection and exact pause position. It does not simulate sound,
network decoding, the actual Android focus manager or foreground-service lifecycle.

`RadioPreviewLayoutTest` exercises the production surface at 360 dp and 240 dp with
1.3 font scale: stable geometry after pressing, exact row selection, nested options
and pinned heading. Four additional Compose previews cover 600/360/240 dp and large
text. Instrumentation/previews are compiled; no device was connected and they have
not been rendered or run. No APK was packaged or installed.

Final build/test evidence: `app/build/reports/radio-preview-validation-final.log`
and the User/Admin JUnit XML reports. See CURRENT_WORK.md for the final result counts.

Device acceptance still required: audible middle ten-second clip and circular timing;
main music and RSS podcast pause/resume; inactive main remaining paused; quick switching;
back and background transitions; Bluetooth/notification Play/Pause/Next; call/focus loss;
headphone removal during loading/playback; source failures; and visual/press comparison
against the provided screenshots at normal and narrow sizes.
