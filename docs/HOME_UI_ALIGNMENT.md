# Home reference alignment and podcast progress — 2026-10-12

## Filter interaction stages — 2026-10-18

The user requested three stages and explicitly required stopping after stage 1:

1. **Completed source:** tapping Podcasts while in Podcasts or Following immediately selects All and retracts Following with the existing reverse animation. From All/Music, Podcasts still opens normally.
2. **Completed source after user authorization:** tapping Following while Following is selected returns to Podcasts and restores Following's gray unselected fill.
3. **Completed source and live bot after user authorization:** long-press Search category/genre covers to open an image-approval request menu. Separate Search-only grants preserve Home/other surfaces; see SEARCH_IMAGE_APPROVALS.md for tests, deployment and pending device acceptance.

Stage 1 changes only the Podcasts click target and the lifetime of its existing reveal animation. A shared `State<Float>` lives above Home's feed/loading/fallback branches, so replacing a header does not reset the closing animation. That state is read only during the group's measurement, avoiding whole-Home recomposition on animation frames. The original 220ms/FastOutSlowInEasing, 16dp overlap, RTL-relative placement, clipping, colors and sizes are unchanged. Following's click callback was unchanged in stage 1. Debug previews accept clicks for interactive review in normal/narrow/large-text configurations.

Validation: full User 520/520; Admin 516/520 with the same four baseline failures. After the final render-state optimization, both debug variants/previews compile successfully. No new unit tests for the click/animation change; source comparison verifies identical animation parameters/measurement and unchanged Following callback. Protected source files (including Search, playback, bottom navigation and image approvals) are unchanged outside the target. No device rendering/motion acceptance or APK packaging/install was performed by this task. Two existing release APKs changed during validation at 11:18:29 local; our logged Gradle tasks are Debug compilation/unit tests only. Those concurrent artifact changes were recorded and left untouched. Evidence: `app/build/reports/podcast-filter-toggle-step1/`.

Stage 2 changes only Following's callback: selected Following → Podcasts, otherwise → Following. Existing styling restores #333333/white on deselection; Podcasts remains green, and the segment remains expanded because both states are podcast modes. Stage 1's Podcasts-to-All toggle and all animation/layout code are unchanged.

Stage-2 validation: both debug variants and the three interactive preview configurations compile. Existing Home navigation, podcast navigation/hub and playback integration tests pass User 44/44 and Admin 43/44; the sole Admin failure is the established `testKosherWhitelist_blocksUnapprovedPodcastImages`. No new tests added for this callback-only change. All 86 non-target protected files (including nine APKs) are unchanged. No APK packaging/install or rendered/device validation. Evidence: `app/build/reports/podcast-filter-toggle-step2/`. Stage 2 was reported before stage 3 was subsequently authorized and completed.

## Podcast / Following appearance follow-up — 2026-10-18

The user explicitly approved the existing animation and requested appearance corrections only. The supplied original shows Following in a darker selected green, #1ABC54 (26/188/84), with Podcasts still #1ED760. SpotUI used the same bright green for both. Both images retain a 48 px pill height; SpotUI's text ink is slightly larger (Podcasts 95 px wide vs 92 px in the original; Following 60 vs 58). The original pair is also about 12 px narrower overall.

Podcast/Following labels now use 13.5 sp, while All/Music retain 14 sp; all keep the existing 20 sp line height and 32 dp minimum pill height. Following's covered inner padding is 28 dp instead of 32 dp, keeping its outer 16 dp padding. The inner edge behind Podcasts is square, eliminating the notch between two separately rounded shapes; the combined capsule clips its outer corners, including when collapsed. Unselected Following stays #333333/white; selected Following is #1ABC54/black. Podcasts keeps its bright green in both podcast modes.

The full `animateFloatAsState` block (target, 220 ms, FastOutSlowInEasing) and full layout measurement/16 dp overlap/relative-placement block compare identically with the before snapshot. Filter callbacks, visibility and semantics are unchanged. No animation is added or replaced; final widths follow the refined text/padding. The debug preview uses the production group in collapsed, Podcasts and Following states at normal/narrow/large-text settings.

Validation: User 504/504, Admin 500/504 with exactly the four established whitelist failures; both debug variants and three previews compile. All 73 non-target protected hashes and nine APK paths/hashes unchanged; HomeScreen is the intentional target. No new unit tests for this appearance-only change. No APK/install or connected device; previews are compiled rather than rendered. Reference pixels, exact diff, animation verification and test evidence are in `app/build/reports/podcast-filter-appearance/`. This is not a claim of a rendered pixel-exact match; verify small/normal screens, RTL/LTR and actual animation after a separately requested build.

## Header filters and podcast access follow-up — 2026-10-14

The user supplied a new pair of 900 x 1600 Home screenshots and explicitly requested matching the three filter pills and removing the Podcasts coming-soon dialog to inspect the existing content. Pixel measurements are retained in app/build/reports/home-filter-access/reference-measurements.json. Original pill height is 48 px versus SpotUI's 54 px. Original Podcast/Music widths are 144/105 px versus 142/98 px; All is approximately 88 px including antialiasing. Both images already use exactly #1ED760 for selected fill; unselected fill differs (#333333 original, #2A2A2A SpotUI).

Home-only pills now use a 20 sp text line with 6 dp vertical padding (32 dp height at default font scale), 16 dp horizontal padding, normal 14 sp text in both selection states, #333333 unselected fill and the measured #1ED760 selected fill. Normal weight also avoids a width change on selection. Minimum height permits growth for larger text settings; the existing horizontally scrolling row remains available on small viewports. Podcasts keeps its rounded shape and green fill in both podcast modes; Following uses the same text sizing.

Both Home filter callbacks now call the existing HomeViewModel.setFilter directly. The Home-specific dialog state and invocation are removed. The existing Podcasts/Following content and its Hub/Show navigation become reachable; this is not implementation of the planned dynamic episode feed, new multiselect chooser or full reference animation. The unused dialog component definition remains, with no runtime caller. Existing playback, RSS, permissions and data fetching are unchanged. Full results are in CURRENT_WORK.md. No device is connected, so actual rendered widths/font fallback, small/normal viewport taps and live playback still require device acceptance; no APK is packaged or installed.

## Reference and scope

The user supplied eight original Spotify screenshots and three SpotUI screenshots, all 900 x 1600. The change concerns the personalized Home feed, retaining content, exact-item navigation, artwork whitelist, MiniPlayer, bottom navigation and PlayerScreen behavior.

Pixel sampling of the supplied images confirms:

| Element | Original screenshot | Previous SpotUI screenshot | New Compose sizing |
| --- | --- | --- | --- |
| Standard cover | 300 px square | 204 px wide, 222 px high | 200 dp square |
| Recent cover | about 146 px square | same large card as other rows | 98 dp square |
| Horizontal gap | 24 px | 24 px including internal padding | 16 dp explicit row gap |
| Outer margin | 24 px | artwork starts around 27 px from edge | 16 dp |
| Cover corner | subtle rounding | more pronounced rounding | 4 dp |
| Feed background | #121212 | #0B0B0F | #121212, Home only |

The 1.5x screenshot density is inferred from existing 16 dp/24 px margins and 56 dp/84 px grid artwork, not queried from a connected device. Source measurements: app/build/reports/home-ui/reference-measurements.json. Standard and circular artist covers use the same large size; Recently Played uses compact covers, including Liked Songs. The old width-then-padding combination constrained artwork width while leaving its height unchanged. Removing inner padding and using aspectRatio(1f) fixes that distortion. Header/avatar/grid sizes, caption size and section spacing were adjusted locally as well.

BoxWithConstraints caps card width to the available viewport minus the 32 dp horizontal margins. At 600, 360 and 240 dp widths, large cards remain 200 dp and recent cards 98 dp; at 200 dp width the large card caps at 168 dp. Rows scroll horizontally; no fixed card text height or device-model check was added. These are code-level layout checks, not screenshots of the rendered final app. Further pixel matching, text-scale and small-viewport acceptance require an online emulator/device.

## Podcast progress behavior

- Only typed Podcast Episode cards in Recently Played receive a progress bar. Tracks, playlist cards and shows do not acquire episode progress.
- The bar reads the same typed stable key and saved position used by playback resume. It never associates progress using a numeric UI ID, title or show alone.
- PlaybackStatePref stores two additional presentation values beside the bookmark: known Player duration and natural-completion state. SongPlayer's existing guarded save hook supplies its actual duration; source selection, playback ownership, seek and resume behavior are unchanged.
- A SharedPreferences listener refreshes only the matching episode card; it is removed when collection ends. No per-card polling, extra Player listener or network request is added. Existing one-second position saves, pause, seek, outgoing-item and external MediaSession saves update the bar.
- Natural completion shows a full bar while the existing resume bookmark remains zero. Starting the episode again clears completion and follows its new position. This presentation state survives normal app/process recreation through the existing SharedPreferences file.
- Previously stored positions work with catalog duration before new presentation metadata exists. A duration learned from the Player is retained when later updates have unknown duration. With no known duration, the card keeps its subtitle instead of inventing a percentage.
- Progress is clamped to 0–100%, draws from the layout's start edge (right in Hebrew RTL), and exposes Compose progress semantics. The bar is a display, not a second seek control.

## Validation and acceptance

PodcastProgressStoreTest adds 12 cases: old bookmark compatibility; advancing/paused/backward-seek values; exact-episode isolation; completion versus resume zero; replay; learned/unknown duration across store recreation; missing duration; track exclusion; conflicting identity rejection; Spotify identity across metadata/URL changes; clamping; and listener-key filtering. Existing playback/history tests remain required.

The first app-suite run had all 12 new tests passing in both flavors. Admin had its four exact whitelist baseline failures; User additionally had a live NewPipe HTTP 403 (expected HTTP 206 beyond the 1 MiB boundary). The final full rerun passed NewPipe in both flavors: User 233/233, Admin 229/233 with exactly the four unchanged whitelist failures by class/test/message. Both debug variants compiled; git diff --check passed; all 9 existing output APK paths and hashes are unchanged. Evidence: CURRENT_WORK.md and app/build/reports/home-ui/results.json, results-first.json and validation-final.log.

ADB listed emulator-5554 as offline. No APK was built or installed; JVM tests and debug compilation cannot validate actual Compose layout or Android preference-listener delivery. Device checks after a separately requested build:

1. Compare standard/Recent/artist rows against the references at the same density. Verify square covers, 4 dp corners, captions, Hebrew/English text and scrolling on normal and small viewports.
2. Play episode A for 10–20 seconds, return Home and confirm its bar advances. Pause externally, then seek backward/forward and check the same card.
3. Switch to episode B and to a music track; A retains its own progress and B has its own value. Music has no episode bar.
4. Close/reopen the app: the same episode retains its progress. Finish an episode: full bar; replay it: the bar starts over and resume still starts at zero.
5. Check unknown-length episodes, Waze pause/resume and exact-item card navigation. Artwork filtering remains enabled.
