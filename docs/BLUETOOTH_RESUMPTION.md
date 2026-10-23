# Bluetooth media-button resumption

## Reboot autoplay correction — 2026-10-18

The user confirmed car Bluetooth playback starts reliably and remains playing, but
reported unwanted playback after a phone reboot **without any Bluetooth connection**.
No device is connected to ADB, so that reboot has not been reproduced locally.

The pinned Media3 **1.3.1** source exposes a concrete unsafe path in our callback:
`MediaLibrarySessionImpl.getRecentMediaItemAtDeviceBootTime` calls the same
two-argument `onPlaybackResumption` used by Play. That query needs metadata for
System UI's resumption card, but our callback also started music through
`SongPlayer.resumeResolvedPlayback`. A podcast query also unnecessarily resolved
RSS/reserved ownership even though the host only needed metadata.

`PlaybackResumptionRequest` now separates these paths **before** consulting or
creating the real resumption future. The service captures
`mediaSession.controllerForCurrentRequest != null` synchronously on callback
entry. In 1.3.1, both modern queued Play and legacy/media-button Play dispatch
inside `callWithControllerForCurrentRequestSet`; the boot library query does not.
The callback's `controller` parameter is not a discriminator: both paths supply
one, and Media3 can substitute the notification controller during a boot query.
Re-audit this boundary before upgrading Media3; do not defer this getter to a
coroutine or replace it with a package-name/Bluetooth/uptime heuristic.

The metadata branch validates the saved typed snapshot with `WazeResumeResolver`
and returns its title, artist, album, exact media ID, type and bookmark. It has no
audio URI, ownership tag, source lookup, player preparation, queue/UI commit or
transport-future mutation. Artwork reuses an already registered exact cover with
the existing whitelist/provider checks, or the existing placeholder. Missing or
contradictory history fails only the metadata future. The actual Play body,
receiver, source resolution, cancellation guards and SongPlayer are unchanged.

Pinned source references:
[boot metadata query](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaLibrarySessionImpl.java),
[current-request API](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaSession.java),
[modern command dispatch](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/ConnectedControllersManager.java),
[legacy Play dispatch](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionLegacyStub.java).

Focused validation: both debug variants compile; all 22 resumption tests pass per
flavor (15 existing plus seven new). New JVM cases exercise production request
routing/metadata: saved music, exact Spotify/RSS/direct podcast identities, invalid
and corrupt history, bookmark normalization, explicit Play after boot, and a query
while a car Play future is pending. They do not simulate Android boot or a real
Bluetooth connection. Evidence: `app/build/reports/reboot-resumption/`.

Full regression results: User 542/542; Admin 538/542 with exactly the four prior
whitelist failures (class, method and assertion message match the preceding run).
The existing music/direct-podcast/external-controller suites pass. A source
comparison confirms the actual Play resumption body is unchanged.
All 88 non-target baseline hashes, including SongPlayer and nine existing APKs,
are unchanged. Source changes are confined to the callback routing and new helper;
the other additions are its tests and three documentation updates.

Device acceptance after a separately requested build/install: reboot with Bluetooth
disconnected and a saved track, then with a saved episode; neither should start
audio. After each, explicitly press Play and check exact identity/bookmark, then
connect the car and verify its Play request still starts music. Repeat with the
app backgrounded and check Pause/Cancel during pending resolution. No APK has been
packaged or installed by this correction.

## Original implementation — 2026-10-15

Implemented 2026-10-15 after the user explicitly approved completing the missing
cold Play route. Source validation is complete; a new APK has not been packaged
or installed. Physical car/phone acceptance remains pending.

## Behavior

`ResumeMediaButtonReceiver` is registered for MEDIA_BUTTON. It accepts a fresh
PLAY or PLAY_PAUSE key-down, then delegates service discovery/background-start
handling to the existing Media3 1.3.1 receiver. Key-up, held-key repeats, Pause,
Stop and unrelated keys cannot cold-start playback. Bluetooth connection alone
does not trigger playback; the car/system must send a media Play request and
route it to SpotUI. No Bluetooth-connect or boot receiver was added.

The service promptly enters foreground with a small loading notification and
Cancel action before dispatching the media event through Media3. The normal
Media3 notification replaces it using the same explicit notification ID. Idle
controller updates cannot remove the loading notification while the source is
being resolved. Service start IDs are updated even for coalesced deliveries so
failure cleanup stops the latest start request, not an obsolete start ID.

The source-resolution deadline is 30 seconds. A separate 35-second startup
watchdog covers dispatch/notification handoff that never completes. It is not
extended by repeated receiver Play events. Resolution failure, missing/invalid
history, Cancel, Pause/Stop and service destruction revoke only the attempt's
own playback token. If SongPlayer has already revoked that token after a
failure, the startup watchdog performs any remaining notification cleanup.
Prepared playback uses the existing engine's normal buffering/error lifecycle.

## Exact identity and callback contract

The two-argument `onPlaybackResumption` callback validates the full typed saved
snapshot with `WazeResumeResolver` and restores its saved position. There is no
replacement track, title search, first-item fallback or tentative UI identity.

- Podcasts reserve an external ownership ticket before awaiting the existing
  episode resolver. The callback requires DirectAudio, returns a genuine
  playable MediaItem, and Media3 sets/prepares/plays it. RSS and the podcast
  resolver are unchanged. The new optional `onReserved` hook exposes the ticket
  for cancellation while RSS/source resolution is still pending.
- Music calls the existing `SongPlayer.resumeResolvedPlayback` path, waits for
  its guarded preparation callback, then returns that actual MediaItem and
  position. A one-use, instance-and-token checked marker prevents Media3 from
  applying the already-prepared stream a second time. Metadata/queue commit
  occurs only after the owned actual item exists. This changes music
  **resumption** from the old empty compatibility result to a real result; the
  existing **onSetMediaItems** music compatibility branch is unchanged.

Media3 1.3.1 calls `handlePlayButtonAction` even when a resumption future fails.
Future completion/cancellation is dispatched on the player application thread
under a scoped transport guard, preventing that automatic prepare/seek/play
from restarting a newer paused item. The guard is released afterwards so a
later explicit user Play is unaffected. A newer ownership token is never
stopped by an older timeout. Queued receiver events carry a generation so
cancellation before their dispatch cannot restart the cancelled attempt.

Versioned references used for the audit:
[receiver](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaButtonReceiver.java),
[resumption dispatch](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionImpl.java),
[service](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaSessionService.java),
[notification lifecycle](https://github.com/androidx/media/blob/1.3.1/libraries/session/src/main/java/androidx/media3/session/MediaNotificationManager.java).

## Validation

Both debug variants compile through their unit-test tasks. Final User 415/415;
Admin 411/415 with exactly the four established whitelist failures compared by
class, method and assertion message. All 15 new resumption tests pass in both
flavors, along with the prior MediaSession, ownership, music and direct-podcast
regression suites. These are JVM tests of production helpers and simulated
Media3 completion boundaries, not Android service or audible car tests.

New coverage includes input filtering, asynchronous/synchronous preparation,
offline deadline, pause cancellation, late callbacks, superseding ownership,
one-use actual-item handoff, Media3 failure/cancel Play suppression, direct
podcast identity/position, cancellation ownership before RSS completes, and
missing/invalid saved snapshots.

Evidence: `app/build/reports/bluetooth-resumption/validation-step1.log`,
`validation-step2.log`, `validation-complete.log`, `results.json` and
`apk-verification.json`. All nine existing APK paths/hashes are unchanged.
SongPlayer's hash equals the pre-task copy. No APK packaging/install, UI,
matching/cache, RSS, telemetry or model-specific changes.

## Device acceptance after a separately requested build/install

ADB currently has no connected device. Verify on the phone and car:

1. Start a track, pause at a known position, background SpotUI and allow/perform
   normal process termination. Send car Play; verify exact track/position,
   advancing platform media state, a populated timeline and foreground service.
   Android Settings Force stop is a different stopped-package case.
2. Repeat with a saved podcast; verify actual RSS/direct episode audio and
   position, without opening the phone UI first.
3. Check cold Play with no history, invalid history, offline/unavailable source,
   and delayed resolution. Verify prompt foreground startup, bounded cleanup,
   no unrelated audio and no late restart after Cancel/Pause.
4. Send repeated Play/key-up events; then select another item and pause it while
   the old resolver is pending. Verify no restart or overwrite by the old result.
5. Check warm car/notification Play/Pause/Stop, task dismissal during startup
   and normal music/podcast playback. Confirm loading-to-media notification
   handoff and that paused playback is not kept in foreground indefinitely.

The earlier excessive-CPU incident and dropped listener callbacks are described
in `BLUETOOTH_BACKGROUND_AUDIT.md`. This work completes the separate resumption
route; it does not claim a physical retest or profiling of that CPU hotspot.
