# Player presentation follow-up — 2026-10-12

## Device-feedback corrections

The first build's device screenshots showed Home through Player. The existing 60%-alpha gradient stop was no longer backed by an opaque screen once Player became a dialog. Gradient colors now all have alpha 1: the middle color is composited over #121212, retaining darkening without transparency. The underlying page is visible only outside the moving surface during dismissal.

The normal controls container had been reduced to 440dp, compounding Material Slider's own endpoint inset. Only its progress area is now expanded to the same 456dp maximum as artwork; the title and transport row keep a 440dp maximum and remain constrained on narrower screens. Compact slider/controls dimensions are unchanged.

The old artwork observer combined a conflated dragged flag and isScrollInProgress/settledPage snapshots. Compose 1.6.8 releases its drag scroll mutation before emitting DragInteraction.Stop and starting a separate snap/fling mutation. This exposed an intermediate idle state to the observer; it could consume the authorization too soon and scroll back. It also could miss a brief Start/Stop. The replacement observes interaction events directly, captures a gesture ticket at onPreFling and commits at onPostFling, after the native pager snap ends. A cancelled or stale drag cannot start playback, and completion from a previous gesture cannot consume the next gesture's ticket. Queue disposal cancels authorization. The existing PlaybackPagerGuard remains authoritative for playback ownership and exact queue identity. No first-item fallback was introduced.

Both normal and compact pagers retain native snap animation, add 16dp space between pages and precompose one neighbor. Parent dismissal does not take diagonal movement while the horizontal gesture is active. The prior pure policy tests did not cover Compose drag-to-fling event ordering; the new controller tests cover the authorization lifecycle, while actual touch/rendering acceptance still requires a device build.

Evidence for this correction is in `app/build/reports/player-regressions/`; no APK packaging or installation is performed. Nine new tests cover both swipe directions, short returns, cancellation, programmatic movement, stale ownership/gestures, ambiguous identity and horizontal-vs-vertical ownership.

Correction validation: User 285/285; Admin 281/285 with exactly the four baseline whitelist failures (class/test/message comparison). All nine new tests pass in both flavors. Both debug variants compiled through unit tests; git diff --check passed; all nine existing APK paths/hashes unchanged. Final command exits 1 only for the accepted Admin baseline. Spotify/InnerTube entries in results.json are retained previous module results. Physical touch/rendering acceptance is still pending.

## Scope and source

User supplied original Spotify and SpotUI Player screenshots at 900x1600, requested closer normal-viewport proportions, pull-down dismissal and loading artist information immediately on opening Player. Stop at the existing artist-information section. No APK generation requested/allowed in this task.

The old ArtistBioCard owned its loading effect inside a lazy item; it could not request metadata until the card entered composition. Player now starts a dedicated artist loader at screen entry and passes its response to both layout branches. Image download starts on metadata success if allowed by the existing image policy. The existing artist repository endpoint is unchanged; first-load latency still depends on network availability. No new artist matching or fallback is introduced.

## Behavior and boundaries

- Normal artwork remains 456dp maximum with 8dp corners. Controls move closer together, the play circle becomes 52dp, and the information card image/header follow the supplied reference more closely.
- Compact mode remains viewport-based at <600dp, with existing artwork/control dimensions and natural scrolling.
- A full-window Navigation dialog preserves the real underlying page during dismissal. Existing bottom bars remain beneath the opaque player surface. Opening another full-screen destination from Player removes the modal Player destination, as standard dialog navigation does.
- At the top, a downward drag moves Player. Below the top, content scroll takes precedence. Threshold: min(96dp, max(48dp, 12% height)), or >=12dp and downward velocity >=1000dp/s. A strong upward release returns to the open position. Position thresholds use density-independent values.
- Short pulls animate back in 220ms; accepted dismissal animates down in 240ms. Arrow/back use the same close action. Modal sheets/lyrics disable Player dismissal. Horizontal pager and seek behavior remain owned by their existing controls.
- Requests are scoped to Player's ViewModel, deduplicated for the same artist and generation-guarded. Changing artist immediately removes old results; leaving Player cancels its scope. Podcasts do not trigger music-artist loading.
- No SongPlayer, queue identity, MediaSession, saved positions, RSS, source resolution, matching, whitelist implementation or history storage changes in this follow-up.

## Initial implementation validation (before device-feedback corrections)

User: 276/276 passing. Admin: 272/276 passing, with exactly the four pre-existing whitelist failures by class/test/message (baseline comparison in results.json). All 13 new PlayerPresentationTest cases pass in both flavors. Both debug variants compile, and git diff --check passes. Nine existing APK paths/hashes are unchanged. The combined unit-test command exits 1 solely because of the accepted Admin baseline. Spotify/InnerTube summary entries are retained earlier module results, not reruns in this task.

Evidence is saved under `app/build/reports/player-ui/`. Debug compilation is performed via unit-test tasks, without assemble/package tasks or APK creation. New JVM tests cover gesture thresholds/scroll ownership and artist loading, deduplication, cancellation, stale results, podcast/missing-artist clearing and request failures. They do not substitute for rendered UI or touch testing.

## Device acceptance on the next requested build

1. On a normal viewport, compare title, artwork, slider, play button, connections row and artist card against the supplied screenshots. On a short viewport, ensure the pinned header and all controls remain reachable without overlap.
2. Open a song and wait at the top; scroll to artist information. Data and permitted artwork should already be loading/cached. Repeat with slow/offline network: no crash or previous artist card.
3. Switch rapidly from artist A to B, then to a podcast; A must not appear under B, and no music bio should appear under a podcast. Reopen Player to verify loading lifecycle.
4. Pull down slightly and release: smooth return. Pull farther or flick down: smooth close with previous page visible, audio continuing. Scroll down within lyrics/artist card: content scrolls. Return to the top and then dismiss.
5. From an actual multi-item queue, swipe artwork left/right both slowly and quickly: it must slide/snap smoothly and select one target once. A short pull returns without changing audio. Repeat at both queue ends, cancel a gesture, and change the item externally during a swipe. Check diagonal movement does not close Player. A singleton queue has no adjacent item to swipe to. Repeat on normal and compact viewports.
6. Check seeking, repeated opens/closes, system back, top arrow, lyrics/options/saved sheets, and navigation from Player to artist/show/queue and back. Confirm Home is not visible through the stationary Player and that the progress rail matches the supplied reference.
7. Check normal track and direct-audio podcast playback, lock-screen pause/resume and saved positions. This task does not change their implementation.

Visual/physical acceptance remains pending; no new package was installed on an emulator or phone during this task.
