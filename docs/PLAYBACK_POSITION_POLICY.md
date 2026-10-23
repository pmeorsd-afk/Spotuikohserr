# Playback position policy

User-approved behavior, 2026-10-22:

- Music retains the position of the current MiniPlayer item only. Pause/Play and
  cold restoration of that same item retain the exact position.
- Selecting another item replaces that single music resume point. Returning to
  an earlier song starts at zero, including Next/Previous and crossfade paths.
- Podcast episodes keep independent, typed bookmarks across other selections
  and process recreation. Podcast completion and display progress are unchanged.
- An explicit requested position (including zero) still takes precedence for
  seek and external resumption. Negative/unset requests use the policy above.

## Implementation boundary

`PlaybackProgressStore.position` is the shared decision point for phone, Waze,
Android Auto and crossfade. Music uses only a matching last-item snapshot; it
does not consult old per-track bookmarks. `save` writes independent bookmarks
only for podcast episodes. Old music entries are inert and are not bulk-deleted.

`PlaybackStatePref` continues updating the last-item position only when the tagged
outgoing Player identity matches the saved item. A delayed save for music A after
selection B cannot restore A's former bookmark or change B's position. Podcast A
still records its own outgoing position after the MiniPlayer changes to B.

Source resolution, matching, player ownership, transport callbacks and UI layouts
are outside this change. Existing current-player and external-session resumption
keep their original explicit-position handling. No model-specific logic is used.
The armed cold-start restore query is disarmed immediately when a different song
is requested, including when that new preparation is cancelled before readiness.
The current restored item's own retry/paused preparation keeps its restore point.

## Validation

All 11 new `PlaybackPositionPolicyTest` cases and all 18 updated
`PlaybackProgressStoreTest` cases pass in User and Admin. The preference tests
exercise real preference functions with separate in-memory files, covering
A-to-B-to-A, current-item recreation, older version bookmarks, exact identity,
outgoing saves, seek/completion, independent episodes and legacy episode migration.
They do not claim to run ExoPlayer or device audio.

Both Debug variants compile. Full app regression is User 677 pass/0 fail/2 skips,
Admin 673 pass/4 established whitelist failures/2 skips (679 cases per flavor).
The four failing class/method pairs exactly match the pre-change baseline.
Existing music/podcast routing, ownership, resumption, Auto and Waze unit suites
remain in that run. Six protected source hashes are unchanged; SongPlayer's
before/after comparison confirms only the three-line restore-query guard.

No device was connected, so actual audio, app recreation and Bluetooth/Auto
acceptance remain pending. No APK was packaged or installed. Evidence is under
`app/build/reports/current-track-progress/`: `targeted.xml`, `validation.log`,
`test-totals.json`, `baseline-comparison.json`, source diffs and protected hashes.
The initial targeted build took about 21 minutes under heavy local disk I/O; the
final full regression completed in 1 minute 36 seconds after caches warmed.
