# Bluetooth resumption and background process termination

Investigated 2026-10-15 on the user's connected F22 Pro, Android 12 / API 31.
Installed package: `com.music.spotui`, version 2.8 (28), updated at 09:36.
The installed APK SHA-256 matches the existing local user release APK:
`a984915f180ae4b9a696ed3c45dcd295a00acbed53b0edf4383d56cf60e83259`.
No APK was generated or installed during this investigation.

## Device findings

The user clarified that audio stopped while another app was visible, and SpotUI
loaded again on reopening. The incident was roughly 15–20 minutes before the
diagnosis. Android's latest recorded termination is consistent with that report:

- Process 28603 started at 12:32:06; audio started at 12:32:33.
- At **12:35:48.912**, Android killed it with reason 9, `EXCESSIVE RESOURCE USAGE`,
  subreason 7, `EXCESSIVE CPU USAGE`.
- Description: `excessive cpu 75510 during 300060 dur=188302 limit=25`.
- Exit importance was 400; ActivityManager logged adjustment 700. This was not
  a process protected as an active foreground media service.
- Audio focus owner death followed at 12:35:49.005. The next app process started
  at 12:43:31. There is no recorded Java crash or ANR for that incident.
- The measured interval is several minutes; the evidence does not establish a
  five-second foreground-service-start timeout.

The user then played a track and switched apps. This short test did not terminate
the process, but exposed the state mismatch directly: AudioTrack was `started`,
while the platform MediaSession remained at state 6 (BUFFERING), position 0,
queue size 0. PlaybackService still had `startForegroundCount=0`, with no
`isForeground=true`. The process remained at adjustment 700 / process state 15.
Later thread/audio snapshots were collected after the user paused, so they are
not a profile of the original CPU spike.

These observations prove the termination mechanism and a service-state defect.
They do not identify the original CPU hotspot, prove a Bluetooth-specific crash,
or establish a hardware defect. Background app-ops were allowed. No battery,
Bluetooth, account, or device settings were changed. The release package is not
debuggable, so `run-as` cannot read its private crash file; the system exit record
and lifecycle/audio evidence were available without that file.

## Confirmed code defect and narrow correction

`AutoSessionPlayer.addListener` used `object : Player.Listener by listener`.
The compiled class in the matching release build contained only the three
explicit overrides: metadata, repeat and onEvents. Kotlin delegation did not
forward the Java default methods: timeline, playback state, playWhenReady,
isPlaying, errors and the other callbacks inherited their no-op defaults.

Media3 1.3.1 caches its controller state in these individual callbacks. The
notification manager uses that controller's timeline and playback state to
decide whether to publish a notification and promote the service. Losing those
callbacks explains the live mismatch and missing foreground protection.
[Versioned MediaSession implementation](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionImpl.java),
[versioned notification manager](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaNotificationManager.java).

Added `ForwardingPlayerListener.java`, explicitly forwarding all 37 callbacks,
and used it underneath the existing metadata/repeat projection. Original
listener registration/removal and ForwardingPlayer wrapping are preserved.
Music stream selection, podcast DirectAudio, ownership, UI and device detection
were not changed. This corrects the proven integration defect; it does not claim
to eliminate every possible system termination or fix an unprofiled CPU hotspot.

## Bluetooth auto-resume: audit finding, now implemented separately

The following records the pre-change finding. The subsequently approved receiver
and bounded startup/resumption implementation are documented in
`BLUETOOTH_RESUMPTION.md`; physical acceptance is still pending a new build.

Both manifests and the live session lack a media button receiver:
`mediaButtonReceiver=null`, `Last MediaButtonReceiver: null`.
This prevents the normal receiver-based cold restart when the media service has
gone away. A Bluetooth connection and a PLAY request are distinct events; no
capture of the car's original PLAY command is available.
[Android resumption documentation](https://developer.android.com/media/media3/session/background-playback#resumption)
requires the receiver plus the resumption callback.

The callback exists, but music currently returns an empty compatibility result
while SongPlayer resolves/starts asynchronously. Merely adding the receiver is
not a complete cold-start fix: the 1.3.1 receiver starts a foreground service,
and source resolution or failure must not leave that start without a timely
notification/cleanup. See the [1.3.1 receiver](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaButtonReceiver.java).

No receiver was added by this audit itself. Its follow-up implements a bounded
foreground startup/failure lifecycle, exact saved-item resumption, and tests for
slow/offline/no-snapshot startup. Automatic play on any Bluetooth connection is
not part of that behavior; it handles an actual media Play request.
Do not introduce a model-specific F22 branch or broad song fallback.

## Validation and remaining acceptance

Two new regression tests fail before the correction and pass after it. One
checks BUFFERING → playWhenReady → READY → isPlaying callback ordering before
onEvents. The other exercises every unmodified Java default callback through
the real Media3 ForwardingPlayer, including its deprecated loading alias.

Final suite: User **400/400**, Admin **396/400**, with exactly the four established
whitelist failures by class/test/message. Both debug variants compile through
unit-test tasks. Existing music/direct-podcast/ownership tests pass. No APK
packaging or install; all nine original APK paths/hashes unchanged. Diff check
passes. Evidence: `app/build/reports/bluetooth-background-audit/`.

The device still runs the old APK. After a separately requested build/install,
verify both music and direct podcasts while backgrounded: platform PLAYING and
position advance, populated timeline, `isForeground=true`, lock-screen/BT
pause-resume, and task dismissal during playback. Then repeat the car incident
long enough to cover the system CPU monitoring interval. Cold service restart
over Bluetooth now has a separate implementation and device acceptance checklist
in `BLUETOOTH_RESUMPTION.md`.
