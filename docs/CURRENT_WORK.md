# Current Work

Updated 2026-10-23.

## Home cover tap requests and empty Recently shelf (2026-10-23)

Single-tapping a Home content card now requests approval of its exact cover URL
in the background while preserving its existing navigation. This includes top
grid, catalog/recent/artist shelves, legacy Home fallback cards and podcast Home
covers/shortcuts. Profile, play/save controls and other screens do not initiate
this request. The long-press manual approval menu remains available. Built-in or
invalid covers produce no request; existing global/Home grants are respected.

Manual and automatic requests share an exact-URL in-flight gate, existing durable
10-minute pending suppression and a remote grant recheck before sending. Locale
variants remain distinct. Failed sync/send stays retryable on the next tap; this
does not introduce an offline delivery queue. Automatic requests are silent and
survive navigation away from Home. Grant scope, bot callback protocol and shared
image loaders are unchanged. Podcast Home artwork reacts to both global and
Home-only permission updates without expanding those permissions elsewhere.

Home hides the whole Recently shelf when it is empty or contains only a zero-count
Liked Songs card. Its source placeholder is retained so existing listening/like
revision flows reveal the shelf without restarting. Nonempty likes, actual
listening items or saved playlists keep it visible. The local history-update
path now reads local likes directly rather than waiting for a remote likes call.
Discovery shelves, card dimensions and exact-item navigation remain unchanged.

Validation: both Debug Kotlin variants compile. Full regression: User 721 passed,
0 failed, 2 skipped; Admin 717 passed, the same four established whitelist failures,
2 skipped. All 14 new tests pass per flavor (request concurrency/retry/identity and
Recent visibility/reactive updates). Results: app/build/reports/home-tap-requests/.
Final focused rerun after retaining global-grant reactivity in podcast Home:
50/50 passed in each flavor, including all new tests and the existing Home artwork,
pending requests, cross-device approval and podcast Home tests. Diff check passes.
SongPlayer, PlaybackService, MainActivity and app/build.gradle.kts match their
pre-task hashes. No APK packaging/install or live Telegram test messages. No ADB
device is connected; actual tap-to-bot, small/normal viewport, first-listen reveal
and audible music/direct-podcast acceptance remain pending.

## Welcome redesign and shared Waze setup (2026-10-23)

Replaced the unscrollable 2.8 announcement with a bounded RTL Spotify-green card:
Android Auto is highlighted, followed by concise Podcast/Home/Search announcements.
Text uses natural height and respects font scaling; the entire content and buttons
can scroll on short viewports. Buttons have minimum, not fixed, heights. A new seen
key shows this revised content once to existing users; subsequent releases use the
actual BuildConfig version instead of hardcoded 28. Login pending behavior remains.

Continue queues a durable Waze offer unless already active. Dismissal does not queue
it; accepting/skipping the offer clears it, including across process recreation.
Home and Settings use the same WazeSetupDialog, the existing local 640x380 demo,
muted looping playback, lifecycle pause/release and fit rendering capped at 260dp.
TextureView avoids a separate video surface over scrollable text. The prompt gives
a short permission explanation, opens accessibility settings and offers Later.

Settings now derives the switch from the current OS grant AND local preference on
every resume, instead of retaining stale remembered state. The grant check also
requires Android's global accessibility enabled flag. Explicit activation waits
for a confirmed grant on return; denial keeps the switch off and the prompt usable.
Disabling locally still works. No accessibility-service or playback engine changes.

Validation: both Debug Kotlin variants compile; full User 707 pass/0 fail/2 skips,
Admin 703 pass/the four established whitelist failures/2 skips. All four new real
preference-adapter tests pass per flavor (old-version migration, durable second
step, skip, login/future-version behavior). Live NewPipe passed both this run;
diff check passed. Evidence: app/build/reports/welcome-waze/. No connected device,
APK packaging/install or rendered small/normal/giant-font acceptance. Still test
scrolling to both actions, muted video/background return, denied/granted/revoked
accessibility and setup after skipping via Settings on a real phone.

## In-app APK updates — implemented locally, device acceptance pending

The update dialog now downloads a public HTTPS APK through Android DownloadManager
when version.json supplies apkUrl. It shows Spotify green progress/bytes, supports
cancel/retry/background continuation and restores the owned request after process
recreation. A scrollable, width-bounded dialog has small/normal/large-font Compose
Preview configurations. The actual viewports have not been rendered on a device.

The existing downloadUrl remains the legacy browser path when apkUrl is absent.
Admin uses adminApkUrl rather than the User package. Before installer handoff, the
file must be a complete APK with the exact expected newer version, package and
current signing certificates, compatible minSdk and optional SHA-256. It is
verified again after returning from unknown-source settings. APK files are shared
only through a narrow FileProvider; Android still asks the user to install.
Update metadata now uses platform TLS/hostname validation instead of the old
trust-all client, retaining the app's existing system/user CA configuration and
metadata mirrors. No playback network client was changed.

Validation: all 26 new update tests pass in User and Admin on final source, and
both Debug variants compile through unit-test tasks. Full regression before the
final TLS/destination adapter adjustments: User 702 pass/1 live NewPipe failure/
2 skips, then User full rerun 703 pass/0 failures/2 skips. Admin full run: 699 pass/
the four established whitelist failures/2 skips. An isolated Admin NewPipe retry
then failed with HTTP 403 (the preceding full Admin run passed it); this external
test is unstable, not a verified playback fix. Final updater tests do not access
DownloadManager or the real package installer. No connected ADB device, audible
music/direct-podcast, filtered-network, installation or rendered viewport test.

SongPlayer, PlaybackService, MainActivity and app/build.gradle.kts hashes are
unchanged. No APK packaging/install, release upload, live manifest mutation or
version bump. Local version.json remains 2.8/28 with empty apkUrl/apkSha256.
Publishing instructions and first-install limitations: IN_APP_UPDATES.md.
Evidence: app/build/reports/in-app-update/ (full/recheck/final-focused logs and
JSON/XML results, before-file copies, protected source hashes).

## Telegram approval identity repair and immediate callback receipt

Deployed Worker `ca76f6cc-a7db-4a9f-b9dc-1ef169e6d706`. The reported October 8
grant was stored with truncated `id: youtube`; an October 14 duplicate cleanup
through the redundant GAS write deleted it while approving another recording.
The full callback ID is now preserved, different track IDs are never merged by
title, and bot approvals no longer mutate the same file twice through GAS.
Webhook receipt is returned independently of persistence; one final Telegram
edit follows a successful write. Conflicts/retries preserve other approvals.

Restored only verified `youtube:tLTgCQovmpc`, matching both the historical grant
and exact public video metadata. Live whitelist version 1668 verified. 32/32
Worker tests, Wrangler dry-run and both Debug Kotlin compilations pass. Webhook
settings/secrets unchanged; no Telegram test messages, Android code, APK/install
or fresh device playback test. Real rapid-click acceptance remains with the user.
See TELEGRAM_APPROVAL_REPAIR.md and app/build/reports/telegram-approval-repair/.

## Music resumes only the current MiniPlayer item; episodes remain independent

User corrected the earlier per-item music rule: returning to a previous song must
start at zero, while Pause/Play of the current MiniPlayer item retains its exact
position, including after app recreation. PlaybackProgressStore now reads music
only from the matching last-item snapshot and writes per-item bookmarks only for
episodes. Old per-track entries are ignored without deleting episode data.
SongPlayer also disarms a cold-start restore query on a different request, so a
cancelled preparation cannot leave an earlier music restore point armed.

Added 11 tests through the real preference adapter and updated the 18 store tests
to the approved policy; all 29 pass in both flavors. Both Debug variants compile.
Full regression: User 677 pass/0 fail/2 skips; Admin 673 pass/the same 4 established
whitelist failures/2 skips. SongPlayer changes only the restore-query guard; six
other protected playback/session sources match their previous hashes. No connected
device or audible/external-controller acceptance, APK packaging or installation.
See PLAYBACK_POSITION_POLICY.md and app/build/reports/current-track-progress/.

## MiniPlayer saved circle on narrow viewports

The fixed 260dp artwork/title row left only 18dp for both controls at a 320dp
viewport, squeezing the 22dp saved circle and hiding Play. It now reserves the
existing 22dp + 36dp controls before limiting the info row to 260dp. Widths of
360dp and above retain their previous geometry; there is no device-model check.
Source geometry audited at 240/280/320/360/393/450dp, not rendered on a device.
Both Debug/instrumentation variants compile; User tests 666 pass/0 fail/2 skips,
Admin 662 pass/the same 4 whitelist failures/2 skips. Playback and shared saved
button sources are unchanged. No connected device, audio check, APK or install.
Evidence: app/build/reports/miniplayer-circle/.

## Radio preview unavailable — playlist-duration repair

User reported previews failing while normal playback works. Reproduced against
the production live Radio endpoint: 20 tracks all mapped to durationMs=0 because
fetchPlaylist's trackDuration.totalMilliseconds field was not read. Added that
duration shape; the same live read now returns 20/20 positive durations. Three
new deterministic parser tests plus a live diagnostic cover this regression and
preserve unknown-length rejection. No playback engine or preview lifecycle changes.

Seven focused Spotify tests pass. Full app regression: User 666/0/2 skipped,
Admin 662/4 established whitelist failures/2 skipped. Both Debug/instrumentation
variants compile. Protected playback source hashes match. No connected device,
audible acceptance, APK packaging or installation. See RADIO_PREVIEWS.md and
app/build/reports/radio-preview-fix/ for before/after evidence.

## Active Search reference alignment and live completion

Implemented rounded active input/reference colors and cover geometry, native
editable caret with spaces, exact recent X, animated bottom clear-history pill,
existing SavedInSheet for tracks/episodes, direct Library saves for albums and
playlists, and no + for artists/shows. The idle rectangle and both opening
transition specifications are verified unchanged against the before snapshot.

Fixed the suggestions endpoint's HTTP 400, verified real Hebrew completions
through the production client, protected against stale searches, and published
Spotify results before slower merged sources. Previously discarded Spotify
playlists now appear. Exact collection choices persist for guests and survive
remote refresh; Search/Library album navigation carries the exact ID.

Twelve new deterministic tests plus the live HTTP test pass in both flavors.
Full regression: User 666 pass/0 fail/2 skips; Admin 662 pass/4 established
whitelist failures/2 skips. Both Debug/instrumentation variants compile. Four
viewport previews and two Compose interaction tests compile only: no connected
device/render/audio acceptance and no APK packaging/install. See
SEARCH_EXPERIENCE.md and app/build/reports/search-experience/ for details.

## Radio preview list and isolated ten-second samples

Implemented the requested Radio summary press/gray feedback and separate preview
surface: gray RTL cards, press animation without layout changes, cover play/pause,
actual-audio circular progress and pinned radio title. Main collection scroll and
the existing bottom player are preserved. A separate screen-owned ExoPlayer clips
ten seconds around the track midpoint through the existing source resolver, without
changing main metadata, queue, listening history or podcast direct-audio routing.

The user confirmed automatic restoration of a previously playing main item on
preview pause/completion. This is guarded by main ownership and preview generations;
previously paused items stay paused. Switching, cancellation, stale callbacks, failure,
external transport, focus/noisy interruptions and foreground cleanup are handled.
Unknown duration/source failure never chooses a replacement track.

Final validation: all 18 new coordinator tests pass in both variants. User: 654 pass,
0 fail, 1 opt-in live-test skip (655 total). Admin: 650 pass, the same four established
whitelist failures, 1 skip (655 total). Both production Debug variants and both
instrumentation variants compile. Four viewport previews and two production UI
instrumentation cases compile; no connected device, actual rendering, gesture/audio
or external-controller acceptance. No APK packaged or installed.
Evidence: `app/build/reports/radio-preview-validation-final.log` and JUnit XML reports.
See RADIO_PREVIEWS.md for scope, behavior and remaining device checks.

## Home startup / missing shelves repair

User device feedback exposed a production-HTTP mismatch: Spotify returns 302 from
localized public pages, but the client rejected redirects. Exact same-entity HTTPS
redirects are now supported and verified through the production client against live
Home/track/artist/playlist pages. Public Home returned 20 radios and 20 albums.
Local Home publishes before remote loading; network no longer holds the feed mutex.
Late missing shelves publish in the same visit without replacing existing carousels,
verified general shelves survive failed refresh, and mix overlap cannot erase valid
history recommendations. Old ranking caches recompute while retaining offline data.

Focused: 62 passing tests per flavor plus the separately successful live transport
test; production/instrumentation variants compile. Final full User 637/637 and
Admin 633/637 with only the same four established whitelist failures. Live public
catalog and populated personal mixes/radio/history checks passed in both flavors.
Final regression evidence is in
`app/build/reports/home-discovery-repair-20261021/`. No device/render/audio acceptance
or APK/install. See HOME_DISCOVERY_RECOVERY.md; it supersedes the initial re-entry-only
publication and reject-all-redirects behavior documented below.

## Home discovery from local listening — implementation complete

The required complete restore point was verified before source edits:
`Desktop/ספוטיפי אדישן כל העדכונים/SpotUI_before_home_recommendations_2026-10-21_03-21-21`.
All 44,923 files (including .git/build outputs/APKs) matched source SHA-256.

Home now ranks existing mixes/radio using exact artist IDs resolved from counted
local music listens and verified playlist track samples. This works for guests;
cold history gets explicit general labels. Recent history retains recent seeds,
and public popular albums use the verified Spotify public shelf, not new releases
or a year-keyword search. Background discovery is published on Home re-entry or
refresh; active carousels stay stable. Public-source/cache limits and coverage are
documented in HOME_RECOMMENDATIONS.md; this is not Spotify's private recommender.

Full regression: User 619/619, Admin 615/619 with only the four known whitelist
failures. Final focused tests after the last history guards pass 46/46 per flavor, recorded in
`app/build/reports/home-discovery-20261021/`. Both debug/instrumentation Kotlin
variants compile. No device/audio/UI/car acceptance, APK packaging or installation.
Existing playback/image-permission/Worker files and all 14 APKs remain unchanged.

## Android Auto Home artwork / cross-device refresh / shelf stability

Implemented exact Home-image grants for car browse cards at both metadata and
provider boundaries, digest-versioned artwork URIs and permission-change page
notifications. Home refresh now observes remote approvals even without local
pending requests; sending rechecks shared decisions to avoid duplicate requests.
Two-line catalog captions reserve stable height while horizontally scrolling.
Source engines, shared player/Waze artwork and global permission scope are intact.

User confirmed the Peer Tasi `/en` and `/en-GB` images contain different people.
They remain distinct approval targets; both are currently approved in the server
records (18 and 20 October respectively). Public server sources agreed at audit.
No locale alias or automatic grant migration is appropriate for those images.

Final full regression: User 584/584, Admin 580/584 with exactly the four established
whitelist failures. All ten new tests pass per flavor; stage 1 Admin focused 82/82.
Both production gesture-test variants and normal/narrow/large-text previews compile;
no connected device, rendered preview or physical car/gesture verification. No APK,
installation, backend deployment or live messages. See HOME_AUTO_ARTWORK_AND_SHELVES.md.
Hash checks cover 524 existing files, including 14 unchanged APKs. Only the eight
intended existing source files changed; source engines/Worker/global whitelist
manager and unrelated UI remain byte-identical to the pre-task snapshot.


## Search review links and collection reference corrections — source complete

The user reported a 404 when opening the Pop artwork link from Telegram, then
asked to finish that repair and correct Radio/Playlist to the supplied references.
Radio must have **no search**, and Playlist search must match Liked Songs.

Search audit found 19/21 hardcoded category image URLs returned 404. Fifteen were
replaced with exact corresponding artwork from Spotify's public Hebrew browse
grid; two working URLs were preserved. All 17 resulting links were verified with
bounded GET as actual images at the unchanged URL. Four unavailable regional/new
categories (Upcoming Releases, Israeli, Mizrahi, Jewish/religious) have no verified
matching artwork in that source, so their broken URLs were removed and the menu
states that no image is available. Category tiles/navigation are retained. Do not
silently choose another category's artwork or invent image IDs to fill these gaps.

Before a Search request is sent, a bounded exact-URL check requires successful
HTTP, image MIME and a matching raster signature. Redirect/error/HTML/empty/offline
responses leave the request retryable and do not send or mark pending. Old Telegram
messages retain their old link; users need a new request from an updated app.
Worker/authentication/grants remain unchanged; no bot message or deployment ran.

Collection correction removes Radio search entirely, uses a top-anchored crop of
the same approved cover, brighter collection-specific palette (no player palette
changes), compact styled artist/track preview and adjusted controls/spacing. The
extra Show All button is replaced by tapping the track preview to expand. Playlist
uses Liked Songs' 36dp/13sp/4dp search controls with an explicit pull reveal; initial
short/loading content can no longer accidentally reveal search. Cover shrink/fade
and pinned title/Play remain. Palette requests cancel on disposal/revocation.

Focused Search tests 21/21 per flavor, six new validator cases. Final full User
574/574, Admin 570/574 with exactly the four established whitelist failures. Both
debug variants and nine collection/search preview configurations compile. No
device connected, rendered/gesture/audio acceptance, APK packaging or installation.
Evidence: app/build/reports/search-cover-link-fix/ and collection-reference-fix/.
See SEARCH_IMAGE_APPROVALS.md and LIBRARY_AND_COLLECTION_REFINEMENT.md.

## Collection refinement — stages 1–5 source complete

The user now requested continuing from the stopping point **and the following
stages**, authorizing stages 3–5 without another permission question. Report each
stage as it finishes. No APK packaging/install was requested.

Stage 2 connects Library to NewEpisodesScreen: full followed-show RSS catalogs,
two history-based Continue Listening items independent of follows, exact durable
completion decisions and six-second Undo, plus a dedicated episode menu. The
foreground refresh checks every minute, respects the existing five-minute RSS
XML cache, and retains cached content on errors. Two feeds load concurrently.
Bookmarks and live playback are not changed by completion or Undo. Downloads
enqueue unique-GUID RSS audio in Android Downloads, not the app's offline music
store. Spotify codes require an exact Spotify episode ID; RSS-only codes are
disabled and their Share action sends title/show text.

Both debug variants/six new previews compile. New tests 17/17 per flavor; full
User 562/562, Admin 558/562 with exactly the four known whitelist failures. No
connected device/AVD, rendering or audible test. No APK/install was performed;
two pre-existing release APKs changed concurrently and were left untouched.
Evidence: app/build/reports/new-episodes-stage2/.

Stage 3 sends selectAll only for bottom Home taps. Generic refreshes
from saving/library changes preserve the selected filter. Existing icon/pill
animations and navigation remain. Both debug variants compile; navigation tests
pass except the established Admin whitelist assertion.

Stage 4 replaces Playlist presentation with the reference square cover, green
Play, scroll-revealed search/sort, cover shrink/fade and pinned title/Play. Existing
playlist loading and music/direct-podcast playback callbacks are retained.

Stage 5 adds the wide Radio hero/fade, compact track preview with expansion, and
real playlist recommendations from up to three artist-specific catalog searches.
Ranking combines catalog relevance, local artist affinity, deterministic identity
deduplication and owner diversity. Exact Home-approved cover URLs also render in
collection headers and recommendations, with the same long-press request/retry
menu. No scoped grant registers a global image URL. Search, Library, song rows,
Player, Auto and Waze retain their own permission policies.

Final validation: both debug variants and seven collection viewport previews
compile. Full User 567/568 initially, with a transient external stream HTTP 403;
the isolated retry passed (all 568 tests passed across these runs). Admin 564/568,
exactly the four established whitelist failures. All 23 new stage-2/5 tests pass
per flavor. Worker tests 16/16 and Wrangler dry-run pass. The two-line Worker
caption update is live at 100%, version d74d92b4-80ae-445a-a755-2f27c31e315b,
published 2026-10-19 00:19:29 UTC. Metadata and public health/whitelist 200 verified;
secrets, webhook and approval storage/authentication are unchanged.

No APK packaging/install; no connected device/AVD for rendering, gestures or audible
music/podcast acceptance. Existing release artifacts changed concurrently and were
left intact. Evidence: app/build/reports/radio-refinement-stage5/ and the preceding
stage reports. See LIBRARY_AND_COLLECTION_REFINEMENT.md for implementation and
device acceptance. No further implementation stage is inferred.

## Library / New Episodes / collection redesign — stage 1 history

The user requested a new five-stage sequence and explicitly required a report and
their next instruction between stages. The full request, screenshot mapping and
later requirements are preserved in LIBRARY_AND_COLLECTION_REFINEMENT.md.

Stage 1 adds a derived, non-removable New Episodes Library tile in list/grid,
visible while at least one valid podcast is followed and removed at zero follows.
It uses the existing durable follow observer, independent of remote music state,
without creating another playlist/store. Search/Create now use matching 24 dp
outline icons in 48 dp touch areas; a 4 dp dark fade sits below the filter header.
Cover colors are sampled #64397E / #1ED760, with a dedicated filled bell vector.

The tile's dedicated screen/navigation was added in stage 2. Its subtitle remains
"פודקאסטים במעקב" rather than inventing a feed-update timestamp. Stages 2–5 are
now implemented as described above; no APK/install was requested.

Focused Library/follow-observer tests: 18/18 per flavor. Both debug variants and
four production-component preview configurations compile. The follow observer is
tested through 0 → 1 → 2 → 1 → 0 → 1 and persisted reopening. ADB has no connected
device; previews are compiled, not rendered. Evidence:
`app/build/reports/library-new-episodes-stage1/`. Stage 1 was reported before the
user authorized continuation.

Final full regression: User 545/545; Admin 541/545 with exactly the four prior
whitelist failures by class/test/message. All 90 non-target baseline hashes and
nine APK paths/hashes are unchanged. Stage 1 is source-complete; visual/device
acceptance remains pending a separately requested build.

## Reboot autoplay — source fix validated, device acceptance pending

The user reports reliable car playback, but music starts after reboot even with no
Bluetooth connection. Auditing pinned Media3 1.3.1 found that its boot-time recent
metadata query invokes the same `onPlaybackResumption` callback as Play; our music
branch started playback as a side effect. The callback now synchronously captures
the current transport-request controller and routes metadata-only queries before
accessing the actual resumption future. Saved exact identity/bookmark and permitted
cached artwork are returned without an audio source, player/ticket/queue mutation
or source lookup. Explicit Play uses the unchanged existing body and receiver.
See BLUETOOTH_RESUMPTION.md for the pinned modern/legacy dispatch evidence.

Both debug variants compile. All 22 resumption tests pass per flavor (seven new);
full User 542/542, Admin 538/542 with exactly the same four prior whitelist failures
by class/test/message. Music/direct-podcast/external-controller JVM regression
tests pass. The actual Play body matches the pre-task copy. No APK packaging or
installation; ADB has no connected device. The user's reboot has not been locally
reproduced, so device reboot and subsequent car Play checks remain after a
separately requested build. Evidence: `app/build/reports/reboot-resumption/`.

## Search category image approvals — stage 3 completed source and live bot

User authorized stage 3, then resumed after the temporary approval-review limit. Search browse/category tiles now open a dedicated image-approval sheet on long-press; normal navigation and grid geometry remain. Separate `search_images` grants, request preferences and Telegram callbacks restrict approvals to category covers and their menu. They do not enable Home, search results, Library, Player or car artwork. Rejection/retry, bounded foreground checks, manual refresh and durable-before-confirmation bot behavior are covered. See SEARCH_IMAGE_APPROVALS.md.

Full User 535/535, Admin 531/535 with exactly the four established failures; all 15 new tests pass in both flavors. Worker 16/16, dry-run and both debug variants/three viewport preview configurations compile. All 80 non-target protected files and nine APKs remain unchanged. No APK packaged/installed or device UI/audio check. Live Worker version `930625cb-2435-4e0d-a8bc-563365f667ce` is deployed at 100%; public health/whitelist return 200, unauthenticated Home/Search callbacks 403. Existing secret names and webhook configuration are preserved; no real approval or Telegram message was sent. Initial source/token inspection was rejected and replaced by standard Wrangler metadata/authentication without extraction. Evidence: `app/build/reports/search-image-approval/`. **All three requested stages have source implementations. Next is device acceptance after a separately requested app build.**

## Filter interaction stage 2 — completed source (previous stage)

The user authorized the next stage. Tapping Following while already selected now returns to Podcasts; the existing state-derived styling restores its gray #333333 fill and white text. Tapping it again enters Following normally. Both are podcast modes, so the segment stays expanded beside the green Podcasts pill. Only Following's click callback changed; animation, layout, stage-1 Podcasts toggle, playback and Search are unchanged.

Both debug variants and normal/narrow/large-text interactive previews compile. Relevant existing navigation and playback tests: User 44/44, Admin 43/44 with the established `HomeNavigationAndTrackingTest.testKosherWhitelist_blocksUnapprovedPodcastImages` failure. No new tests for this small UI callback change. All 86 non-target protected files, including nine APKs, are unchanged. No APK packaged/installed or device rendering/playback check. Evidence: `app/build/reports/podcast-filter-toggle-step2/`. Stage 2 was reported before the user authorized stage 3 above.

## Filter interaction stage 1 — completed source (previous stage)

User requested a three-stage sequence: (1) Podcasts toggles back to All with reverse Following retraction, (2) Following toggles back to Podcasts/gray, (3) Search category-cover long-press image approval. Only stage 1 is implemented. Podcasts now selects All immediately from either podcast mode; the existing reveal state is hoisted above feed/loading/fallback branches so the same 220ms animation survives header changes. Reading State only during measurement keeps animation frames local. Geometry, colors, RTL placement, Following click behavior, Search, bottom navigation and playback are unchanged. See HOME_UI_ALIGNMENT.md.

Full User 520/520, Admin 516/520 with exactly the four established failures; final User/Admin debug compilation and three interactive preview configurations pass. No new tests for this UI-only change, no physical rendering/motion verification, and no APK packaging/install performed. Two existing release APKs changed concurrently during checks; no protected source drift was found and those artifacts were left untouched. Evidence: `app/build/reports/podcast-filter-toggle-step1/`. Stage 1 was reported before the user authorized stage 2 above.

## Bottom navigation icon tap animation — completed source (2026-10-18)

After finishing rejection retry, added an icon-only pulse to Home/Search/Library: 1.0 → 0.88 over 80ms, then → 1.0 over 160ms, using FastOutSlowInEasing. Each accepted click triggers it, including tapping the active tab; rapid repeated taps cancel/restart the same per-item Animatable. Navigation and Home refresh still execute immediately through the original callback. Graphics-layer scaling leaves labels, layout, hit targets, colors, ripple policy, MiniPlayer and Podcast filter motion unchanged.

Both User/Admin debug variants and normal/small/large-text production-navigation previews compile. No unit tests added for this visual-only change; the immediately preceding full suite passed User 520/520 and Admin 516/520 (four known failures), including all nine retry tests per flavor. Source/hash checks preserve all other protected files, all nine APKs and the navigation callback body. Previews compile only; actual animation/touch has not been verified on device. No APK packaged/installed. Evidence: `app/build/reports/bottom-nav-press/`.

## Home image rejection retry — completed source (2026-10-18)

The user confirmed the live Home-image approval feature works. Fixed its reported retry issue: a synchronized rejection now releases the pending state and restores "התר תמונה זו", including requests stored by the older version. Resubmission captures the preceding exact-image decision before dispatch and waits for a new decision; repeated syncs/unrelated decisions cannot prematurely release it. Decision timestamps survive cache export/reload; approval resolves pending too. No image is granted by retrying. Foreground polling stops when pending requests are resolved.

User 520/520; Admin 516/520 with exactly the four existing failures. Nine new tests pass per flavor; both debug variants compile and existing playback/direct-podcast regressions pass. Menu/Home/filter geometry and Worker source unchanged. No live bot changes or APK build/install; nine APKs unchanged. Evidence: `app/build/reports/home-image-retry/`; details in HOME_IMAGE_APPROVALS.md. The subsequently requested Home/Search/Library icon animation is recorded above.

## Home cover image approvals — source complete and bot deployed

Home cover long-press opens a reference-style RTL sheet with functioning collection sharing, playlist Library save, exact opening/existing track options and “התר תמונה זו”. Requests include an exact image link and Approve/Reject buttons. Separate versioned `home_images` permissions affect Home only; they never register global allowed URLs or grant artists/tracks. Pending requests remain blocked until approval/sync. Worker checks webhook authentication and channel administrator status, writes durably before confirming, and supports rejection/revocation. Podcast filter animation and music/direct-podcast engines are unchanged. See HOME_IMAGE_APPROVALS.md.

User 511/511; Admin 507/511 with exactly the four established failures; all seven new tests pass per flavor. Worker 8/8 and Wrangler dry-run pass. Both debug variants/three viewport previews compile; no rendered preview/device, APK or install. Nine APKs unchanged. Evidence: app/build/reports/home-image-approval/.

The user explicitly approved publishing the live bot, configuring the two Cloudflare secrets and updating Telegram webhook authentication after the earlier automatic-review rejection. Fresh read-only checks confirmed unchanged live code, no existing secrets, bot administrator status and a healthy whitelist. On 2026-10-18 at 06:00 UTC, deployed Worker version `48caf043-f13c-47b7-b8f0-db53af2c78a6` at 100%. The existing live GitHub credential is preserved as `GITHUB_TOKEN`; `TELEGRAM_WEBHOOK_SECRET` matches the configured Telegram header. Existing webhook URL/subscriptions/connection limit are preserved, with no dropped updates.

Downloaded deployed source matches the bundle. Live health and whitelist return HTTP 200 (whitelist version 1061); an unauthenticated Home callback returns 403; Telegram reports zero pending updates and no webhook error. Worker tests rerun: 8/8. No synthetic approval, whitelist write or Telegram message was sent. Evidence: `live-preflight.json`, `activation.json`, `worker-deploy.log` in the existing report directory. Next: actual cover-request/approve/reject acceptance from an app build containing these changes. No APK was built or installed; APK build/install remains separately authorized.

## Completed source — Podcast / Following pill appearance; preserve animation

The user supplied fresh SpotUI/original screenshots and explicitly prohibited animation changes. Sampled original Following selected fill is #1ABC54 (26/188/84); Podcasts remains #1ED760. Only these labels use 13.5 sp (other filters retain 14 sp), with Following's inner padding reduced from 32 to 28 dp. Its covered inner edge is square to remove the visible notch, and the group has an outer capsule clip so the collapsed pill also retains clean corners. Height, line height, outer padding, filter behavior and selection callbacks remain.

Source comparisons confirm the complete animateFloatAsState target/220 ms/FastOutSlowInEasing block and the measurement/16 dp overlap/relative-placement block are identical to the pre-task snapshot. Narrower content changes the measured endpoints naturally; no new animation, timing, easing or movement formula is introduced. Normal/narrow/large-text debug previews use the production group.

Final User 504/504; Admin 500/504 with exactly the same four known failures by class/test/message. Both debug variants and three preview configurations compile; no new unit tests for this styling-only change. All 73 non-target protected hashes, including cover/playback files and nine APKs, are unchanged; HomeScreen is the intentional exception. No APK/install/device; previews are compiled, not rendered. Evidence: app/build/reports/podcast-filter-appearance/ (reference samples, animation comparison, exact diff, results/XML and final log). See HOME_UI_ALIGNMENT.md. Actual small/normal screen appearance and motion still need device acceptance.

## Completed source — Podcast Home text/gradient covers

The user approved the annotated text-cover design. Large episode covers in Home Podcasts now show the exact title and publisher/show over artwork-derived gradients, with automatic black/white text. Missing/forbidden artwork uses a stable show palette, without requesting or reading cached forbidden artwork. The green library placeholder is removed from the feed's large covers, small metadata swatches and Following thumbnails only. Other artwork surfaces use their existing components. Normal 852:360 geometry is preserved; large accessibility fonts can grow short covers to avoid overlap. Existing item navigation, save actions and playback callbacks are unchanged.

Color loading is limited to 64×64, uses bounded timeouts/background palette extraction, releases Glide targets and keeps a 64-entry color cache. URL/permission changes reset visible palette state and cancel the old request. No changes to music/source/MediaSession/whitelist engines. See PODCAST_HOME_COVERS.md.

Final User 504/504; Admin 500/504 with exactly the same four known whitelist failures by class/test/message. All four new contrast/layout tests pass per flavor; both debug variants and six production-composable preview configurations compile. Previews were not rendered; there is no connected ADB device. Source comparison confirms 70 protected files unchanged; the one intentional baseline change is PodcastHomeContent. All nine APK paths/hashes unchanged; no APK/install. Evidence: app/build/reports/podcast-cover-design/ (results, scope verification, exact task diff, XML and logs). The first full-run attempt was prevented by an automatic-approval usage-limit failure; the user resumed and the same approved Gradle route subsequently ran successfully to completion, with only the established Admin test failures. Next: visual/device acceptance on small and normal screens after a separately requested build, including live image permissions/offline scrolling and actual music/direct-podcast playback.

## Completed source stage 4 — Android Auto dashboard Save / Play / Next

The user authorized the next stage. Save now precedes Radio/Repeat during car presentation, filling the custom-action priority needed for the dashboard's free Previous slot; standard Play/Pause and Next remain. The CarConnection observer refreshes the shared legacy layout and restores standalone ordering on disconnect. Modern car controllers receive their initial/current order explicitly. Actual host placement still requires DHU acceptance.

The existing Liked Songs writer/sync is used directly, with an immediate check state, no chooser and idempotent repeated taps. Car saved-state checks now distinguish exact track identities even with identical title/numeric UI IDs. Unsupported items/podcasts cannot use music Save. No phone UI, playback engine, direct-audio resolver or shared likes-store edits.

User 500/500; Admin 496/500 with exactly the four existing whitelist failures by class/test/message. All eight new tests pass in each flavor; both debug variants compile via unit-test tasks. All 64 protected hashes and nine APK paths/hashes unchanged. No APK/install or connected device. Evidence: app/build/reports/auto-refinement-stage4/. See ANDROID_AUTO_REFINEMENT.md for legacy notification behavior and remaining acceptance. All four requested stages now have source work; A–Z suppression remains unresolved and no exact host-rendering claim is made. Next is physical/DHU validation after a separately requested build, not an automatic new implementation stage.

## Android Auto refinement stages 2–3 — source implemented; A–Z/host acceptance pending

The user authorized stage 2, then explicitly requested finishing it and continuing to stage 3 in the same turn. Search artists now expose a persisted exact-artist playback action, selecting randomly only from that artist's validated top tracks, and commit through the existing source-prepared path. Car-only accent is #2CF261; Top results grouping matches the reference. No supported A–Z suppression hint was found: its removal is NOT marked complete, and black icon contrast/actual green rendering need DHU acceptance.

Car Home/Music now leads with up to eight real recent tracks/episodes from the successful-playback history log, automatically notifies subscribers after changes, then shows actual Recommended stations playlists when the raw Spotify Home response supplies that shelf. Other sections/library shortcuts remain. Generic radio search is not relabeled as personalized stations. A bounded, service-owned cache preserves local/cached content during remote refresh. No music engine, podcast resolver, phone UI, history writer or Spotify client changes.

Final User 492/492; Admin 488/492 with exactly the four existing whitelist failures by class/test/message. All 20 new tests across stages 2–3 pass per flavor; both debug variants compile via unit-test tasks. Stage-3 checks confirm 53 of 54 baseline-protected hashes unchanged (the Auto-only accent was intentionally refined to the sampled reference color); all nine APKs unchanged; APK list unchanged and diff check passes. No APK/install or connected ADB device. Live station availability, exact host layout/color/A–Z, audible and background/controller acceptance remain pending. Evidence: app/build/reports/auto-refinement-stage2/ and auto-refinement-stage3/. See ANDROID_AUTO_REFINEMENT.md.

Stages 2–3 were reported before the user authorized dashboard stage 4, now recorded above.

## Android Auto refinement — stage 1 source complete; host acceptance pending

Implemented the new stage-1 car controller permission policy (hide Previous/public queue to fit Radio/Save/Play/Next/Repeat) using AndroidX CarConnection and Media3's notification-controller bridge. Disconnect restores standalone permissions. Radio now starts the highest-ranked valid new recommendation through existing source preparation before committing its queue; cancellation, stale fetches and late callbacks are guarded. No SongPlayer/RSS/matching or phone screen edits. Host-owned progress geometry remains unchanged and seeking is preserved; no pixel-match claim. See ANDROID_AUTO_REFINEMENT.md for all four requested stages and exact limitations.

Final validation: User 472/472; Admin 468/472 with exactly the four prior whitelist failures by class/test/message. All 10 new tests pass per flavor; both debug variants compile. All 42 protected file hashes (including nine APKs) and APK paths unchanged. No connected device or APK packaging/install; real car rendering/connection lifecycle and live background audio remain pending. Evidence: app/build/reports/auto-refinement-stage1/. Stage 1 was reported before the user subsequently authorized stages 2–3 above.

## Completed stages 4–5 — typed episode saving and expandable destination sheet

The user authorized finishing stage 4 and proceeding directly to stage 5. Shared plus/check rotation, immediate default save, separate Your Episodes storage/artwork/Library access, reference compact sheet sizing/brightness, draggable full expansion, all local/paginated editable Spotify playlists, new local playlists and Done/Cancel are implemented. Destination edits are staged; partial errors retain retryable selections. Exact episode identity survives legacy migration, custom/remote playlist conversion, Player/MiniPlayer saving and reopening. The existing direct-audio/music engines are unchanged. See PODCAST_SAVING.md for semantics, scope and remaining device checks.

Validation: User 462/462, Admin 458/462 with exactly the four known whitelist failures. All 27 new tests pass in both flavors; debug code and normal/small/narrow preview configurations compile. Six protected files and all nine APK paths/hashes are unchanged; diff check passes. ADB has no device, so previews are compiled only and actual animation/touch/playback/remote mutation acceptance remains pending. No APK was generated or installed. Evidence: app/build/reports/podcast-save-step4/. All five planned stages are implemented in source; the next step is device/visual acceptance when a build is separately requested.

## Completed stage 3 — Show menu and personal rating; stop for user decision

Approved Show overflow now opens a dedicated exact-Show sheet with four actions: Share, Follow/Unfollow, Rate show and Spotify code. Header Share also uses the exact Show link. Following reuses the existing Library store; the user chose persistent personal 1–5 ratings inside SpotUI only, with explicit local-only text. The code action loads the real fixed Spotify Codes endpoint, with loading/error/retry and no fake graphic fallback. Route-ID guards reject stale Show/episode metadata. Reference compact dimensions, RTL, scrollable small viewport content and animated transitions are implemented. See PODCAST_SHOW_MENU.md.

Both debug variants/previews compile. All 11 new tests pass per flavor. Full User 434/435 (existing live NewPipe HTTP 403; isolated retry 1/1 passes), Admin 431/435 with exactly the four stage-2 whitelist failures. Eight protected files, all episode callbacks and nine APK paths/hashes unchanged. No APK/install; no connected ADB device or rendered previews. Live visual, scanning, chooser and playback acceptance remains pending. Evidence: app/build/reports/podcast-show-menu-step3/. Next: stage 4, episode plus/check animation and save destination entry, only after the user's decision.

## Completed stage 2 — Show page presentation; stop for user decision

User explicitly approved the next stage after Library following. Refined Show header/cover proportions, typography, follow pill, outline icons, tabs, list-heading order, gradient extent and denser episode rows against the supplied reference. Removed the fabricated 4.8 rating in favor of real topic metadata. Both Show lists opt into a dedicated presentation; Home's existing card body and all episode callbacks remain unchanged. Narrow viewports wrap controls while preserving 48dp targets; debug previews cover regular, small, narrow and large-font layouts. Menu/save-sheet behavior remains for stages 3–5.

Final User 424/424; Admin 420/424 with exactly the four stage-1 whitelist failures. Both debug variants/previews compile via unit-test tasks; protected playback/ViewModel/sheet files and all nine APK paths/hashes unchanged. No APK/install. No ADB device during this stage, and previews were compiled rather than rendered: live viewport/touch/playback acceptance remains pending. See PODCAST_SHOW_PRESENTATION.md and app/build/reports/podcast-show-step2/. Next stage is the Show three-dot menu, only after the user's decision.

## Completed stage 1 — followed podcasts in Library; stop for user decision

User authorized only the first stage of the podcast UI plan. Library now subscribes to the existing durable followed-show store, displaying existing/new follows in list/grid with a Podcasts filter and search. Unfollow immediately removes the exact show; Show/Home/chooser share the persisted state. Local follows remain available independently of remote music loading/errors. Typed show entries navigate by exact ID and retain artwork permissions; music/artist rendering bodies and playback files are unchanged.

User 424/424; Admin 420/424 with the exact four established whitelist failures. All nine new tests pass in both flavors and both debug variants compile via unit-test tasks. Nine APK paths/hashes unchanged; no APK/install. A device appeared in the final ADB check, but the new source is not installed; rendered small/normal viewport and live playback acceptance remain pending. Evidence: app/build/reports/podcast-library-step1/. See PODCAST_LIBRARY_FOLLOWING.md for implementation and the complete staged plan. Next is stage 2 (Show page UI alignment), only after the user's decision; menu/save-sheet/animation work is not part of this stage.

## Completed implementation — Bluetooth cold Play resumption

User approved completing the missing media-button route. Added a filtered Media3 receiver, immediate loading foreground notification with Cancel, bounded source/dispatch deadlines, exact typed saved-item/position resumption and cancellation/ownership guards. Music resumption now returns its actual prepared item without resetting the stream; podcasts retain RSS/DirectAudio. Media3 1.3.1's automatic Play after failed futures is scoped so it cannot restart a newer paused item. SongPlayer itself is unchanged. See BLUETOOTH_RESUMPTION.md for the callback/foreground lifecycle and physical acceptance checklist.

Final User 415/415; Admin 411/415 with exactly the four established whitelist failures by class/test/message. All 15 new tests pass in both flavors and both debug variants compile through unit-test tasks. Nine APK paths/hashes unchanged; no APK packaging/install. Evidence: app/build/reports/bluetooth-resumption/. ADB has no connected device; actual car cold-start, foreground notification handoff and audible playback remain unverified on the new source.

## Bluetooth/background investigation — confirmed listener defect corrected

Connected F22 Pro (API 31) records the reported-period termination at 12:35:48 as EXCESSIVE CPU USAGE, not a Java crash. During the user's live background test, audio played while MediaSession stayed BUFFERING with an empty timeline and PlaybackService never entered foreground. Matching installed release bytecode confirms `Player.Listener by listener` dropped Java default callbacks. Replaced that delegation with explicit forwarding, preserving metadata/repeat projection. Two new regression tests fail before/pass after; final User 400/400, Admin 396/400 with exactly the four established whitelist failures. Both debug variants compile; nine APK hashes unchanged; no APK/install. The physical phone still runs the old build, so repaired foreground behavior needs post-install acceptance. Original CPU hotspot is not profiled. The missing Bluetooth cold-resumption route found during this audit was implemented in the subsequent follow-up above. See BLUETOOTH_BACKGROUND_AUDIT.md and app/build/reports/bluetooth-background-audit/.

## Completed implementation — Dynamic Podcast Home and multiselect following

User explicitly authorized the episode feed and chooser. Implemented real recommended/new/followed RSS episodes, exact saved resume items, reference-sized cover-only cards, guarded direct playback, cached bounded loading, stable session diversity, completion exclusion and partial-error retry. Added full-screen show selection with search/category browsing, preserved draft selections and atomic Done; existing follows/bookmarks and concurrent unrelated edits survive. Home also has the reference behind-pill reveal, per-filter scrolling, Back and header access during music loading/failure. Podcast catalog initialization is deferred until entering Podcasts. See PODCAST_HOME_IMPLEMENTATION.md for exact scope and acceptance limits.

Final validation after the download guard and lazy initialization: User 398/398; Admin 394/398 with exactly the four preceding whitelist failures by class/test/message. All 19 new catalog/ranking/persistence tests pass in both flavors, alongside existing music/direct-podcast/ownership regression suites. Both debug variants compile via unit-test tasks; diff check passes; all nine original APK paths/hashes are unchanged. Evidence: app/build/reports/podcast-home-implementation/validation-complete.log, results.json and apk-verification.json. No APK packaging/install. ADB has no connected device; rendered small/normal viewport, actual search/selection interactions, live discovery, direct podcast/music and background-controller acceptance remain device checks.

## Completed follow-up — Home filter sizing and existing podcast access

User explicitly requested matching the new original screenshot and opening the existing podcast content. Home pills now use 32 dp default height, 16 dp side padding, normal 14 sp text and the reference #333333 inactive fill; selected green was measured as #2CF261 in both screenshots and remains that exact color. Both Podcasts interception callbacks and the Home dialog invocation/state are removed. Existing Podcasts/Following content, Hub and Show routes are accessible. No new episode feed, chooser, recommendation system or full animation implementation is claimed. See HOME_UI_ALIGNMENT.md for measurements and compatibility details.

Validation: User 379/379; Admin 375/379 with exactly the four preceding whitelist failures by class/test/message. Both debug variants compile via unit-test tasks; live NewPipe checks pass in both. Existing music/ownership and direct-podcast regression suites pass. Diff check passes and all nine APK paths/hashes are unchanged; no APK packaged or installed. No ADB device is connected, so rendered small/normal viewport sizing, actual taps and live music/podcast acceptance remain unverified. Source changes in this follow-up are confined to HomeScreen.kt. Evidence: app/build/reports/home-filter-access/validation.log, results.json, reference-measurements.json and apk-verification.json.

## Completed implementation — Waze movable button and artwork

User chose a movable button with hard screen bounds instead of a fixed button, then approved the position, visibility and image tasks. Implemented physical right-side default placement, full-button safe-area clamping, relative position persistence separately per orientation, and immediate overlay suppression for observable keyboard/input/dialog/menu signals. The mini player and list now check the exact item's artwork permission directly, including owned background playback, without requiring a visit to SpotUI. See WAZE_OVERLAY.md for the compatibility boundary and device checklist.

Final full run: User 378/379; the only failure was the existing live NewPipe request receiving HTTP 403 instead of 206. That test passed on one isolated retry (1/1); this does not replace the recorded full-run result. Admin 375/379 with exactly the four known whitelist failures by class/test/message. All 21 new geometry, visibility and artwork tests pass in both flavors, including background playback with no loaded phone queue. Both debug variants compile through unit-test tasks; diff check passes. Evidence: app/build/reports/waze-overlay/validation-complete.log, results.json, network-recheck.log and apk-verification.json. No APK generated/installed; nine original artifacts are unchanged. No ADB device is connected, so live music/direct-podcast, background-controller and rendered small/normal viewport acceptance remain pending. Canvas-only Waze menus without accessibility signals remain a device-validation limitation, not guaranteed detection. Podcast Home remains audit-only.

## Completed implementation — Android Auto presentation and player controls

User confirmed the prior Auto integration works in DHU. Implemented the remaining Home pagination, Recently icon, purple Liked Songs cover and plain-color Browse covers. Added owned-item session artwork/artist metadata, direct idempotent Save (+ → check), existing-recommender Radio with stale-result guards, and three-state Repeat synchronized with the app queue. Save does not open a playlist chooser, as clarified by the user. Radio does not interrupt the current song while loading. See ANDROID_AUTO.md for behavior and limits.

Search history persists independently from listening history, is reachable in Library and appears as matching suggestions during nonempty searches. The empty native Auto search screen remains limited by Media3 1.3.1 rejecting empty queries before callbacks. Host layout/tint may move custom buttons into overflow; pixel-identical Spotify player placement is not claimed.

Final validation: User 358/358; Admin 354/358, with exactly the four established whitelist failures by class/test/message. All 15 new tests pass in both flavors, along with the existing music, direct-podcast, ownership and external-request regression suites. Both debug variants compile via unit-test tasks. The first run's live NewPipe HTTP 403 is retained in results-first.json; it passed in both flavors on the final run. No APK generated/installed; all nine existing APK paths/hashes are unchanged. Source-vector rendering was inspected; no ADB device is connected, so live playback/background-controller and rendered viewport/DHU acceptance remain pending. Evidence: app/build/reports/auto-polish/validation-complete.log, results.json, apk-verification.json and assets.png. Podcast Home remains at the audit-only state below.

## Prior audit — Podcast Home and following chooser

Audit only completed from the user's detailed brief and Spotify(1).mp4 reference. No application code, tests, build, APK or install in this phase. See PODCAST_HOME_AUDIT.md for source inventory, measured chip motion/card geometry, following chooser flow, existing data signals and the staged proposal. Evidence frames and measurement scripts are under app/build/reports/podcast-home-audit/.

At audit time, Podcasts was intercepted by two coming-soon Dialog callbacks. The 2026-10-14 access follow-up above removes those callbacks and exposes the existing filter/follow UI. Following storage and RSS catalogs exist, but episode recommendations, latest-followed aggregation and a multiselect chooser do not. The video shows a fixed Podcasts pill with Following revealed behind it; the large Episode container measures approximately 852x360 in the 900x1600 recording. The planned episode cards will use cover-only Crop in that outer container, never video.

The 2026-10-15 request explicitly authorizes the feed and chooser, superseding the earlier access-only approval boundary. The implementation and remaining device acceptance are described at the top of this document and in PODCAST_HOME_IMPLEMENTATION.md. The audit below is a historical design record, not a current instruction to wait before implementing these authorized features. Existing no-APK restriction and playback/identity/artwork constraints remain in force.

## Current follow-up — Android Auto online Home, Browse and search

Implemented the user's original/SpotUI DHU comparison: roots are now Home, Recently, Browse and Library with distinct icons. Home uses the phone's feed with titled grid groups; Browse uses existing category playlists and Podcast Hub; Library retains liked songs, playlists, followed artists, albums, podcasts and downloads. Online combined search includes artists, playlists, albums, songs, shows and episodes, with coalesced stable snapshots and exact local matches for offline fallback. Category cards currently use local icons; host geometry is not controlled by the phone UI.

Albums preserve real Spotify IDs through API/model/Home cache; typed collection tokens survive recreation. Artist selection uses an exact ID, and episodes retain RSS/direct audio. Only exposed pages are persisted; serialized publication prevents concurrent pruning, batch writes prevent unseen libraries evicting visible folders, and callback waits are bounded. The existing music engine, phone layouts, whitelist policy, playlists and progress stores are preserved. Home cache codec now retains podcast identity/duration instead of defaulting cached episodes to tracks.

Final validation: User 343/343; Admin 339/343 with exactly the four existing whitelist failures by class/test/message. All 45 new tests pass in both flavors. Both debug variants compile through unit-test tasks, diff check passes and all nine existing APK paths/hashes are unchanged. No APK packaged or installed. Evidence: app/build/reports/auto-online/validation-complete.log, results.json and apk-verification.json. Spotify/InnerTube summary entries are older module runs. New DHU rendering, live online catalog/authentication, music/direct podcast playback and background controller acceptance remain pending a separately requested build; see ANDROID_AUTO.md.

## Completed foundation — Android Auto media integration

Completed the existing media-service foundation with four car browse roots (recent, music, podcasts, downloads), followed-show/recent-episode browsing, continuation folders, exact saved-album IDs, bounded IO loading, persisted typed car tokens and read-only whitelist-aware local artwork. Added local-library search and exact-unique-title voice selection through the existing external playback coordinator. Phone-task dismissal preserves playing/buffering audio; Media3 1.3.1 requires an explicit policy. No phone UI, playback engine, RSS or telemetry changes. See ANDROID_AUTO.md for scope, limits and device acceptance.

Validation: User 298/298; Admin 294/298 with exactly the four existing whitelist failures. All 13 new tests pass. Both debug variants compile through unit-test tasks; no APK packaging. Evidence: app/build/reports/android-auto/. Live Android Auto/DHU discovery, artwork and callback delivery remain device acceptance; JVM tests do not establish those. Voice search covers local/previously browsed content, not global Spotify discovery.

## Completed follow-up — Player opacity, slider width and artwork swipe regressions

Device feedback found the underlying Home visible through Player, a narrower-than-reference progress track, and artwork swipes failing to switch tracks. The gradient's 60%-alpha stop had become visibly transparent after Player changed to a dialog. All gradient stops are now opaque; the middle color is blended over a fixed dark base. Normal progress width is restored to a 456dp container (matching artwork), while title/transport controls retain their existing 440dp maximum. Compact control dimensions remain unchanged.

Artwork navigation now listens to real DragInteraction events and consumes authorization at the pager's onPostFling, after snapping. The previous snapshotFlow of dragged/scrolling/settledPage could conflate a short Start/Stop and could react to the idle gap between finger-up and fling, prematurely restoring the current page. A per-gesture ticket prevents an old fling consuming a newer drag; cancellation/queue disposal revoke it, and PlaybackPagerGuard still rejects stale playback ownership, missing/ambiguous identity and programmatic movement. Nested downward dismissal yields while horizontal artwork scrolling owns the gesture. Both viewport branches use the existing animated HorizontalPager with 16dp page spacing and one precomposed neighbor.

No playback/source engine, artist-loading timing, RSS, whitelist or saved-state changes. No APK generation. Evidence and device acceptance: PLAYER_PRESENTATION.md and app/build/reports/player-regressions/.

Final validation: User 285/285; Admin 281/285 with the exact four existing whitelist failures by class/test/message. All nine new ArtworkSwipeControllerTest cases pass in both flavors, along with previous presentation/playback suites. Both debug variants compile through unit-test tasks, git diff --check passes, and all nine existing APK paths/hashes are unchanged. Evidence: validation-final.log, results.json and apk-verification.json in the directory above. Spotify/InnerTube summary entries are earlier module results, not new runs. Rendered/device acceptance remains pending; source review preserves narrow/compact dimensions and wider-screen control caps.

## Completed follow-up — Player reference spacing, dismissal and early artist loading

Implemented 2026-10-12 from original/SpotUI screenshots. Regular Player keeps 456dp artwork, moves it up 6dp, reduces the control block to 224dp and the play circle to 52dp, aligns controls within 440dp, removes the duplicate quality text, and refines sticky-header/artist-card proportions. Existing queue, shuffle, repeat, device and share actions remain. Compact viewport (<600dp) dimensions remain unchanged; no additional content beyond the existing artist card.

Player is a full-window Navigation dialog so pulling the opaque surface down reveals the actual previous screen. Nested scrolling hands downward user drags to dismissal only at the top; short pulls return, sufficient distance/velocity completes a 240ms close. Header drag is also available in compact mode. Inertial scroll cannot dismiss, horizontal movement is not consumed, and modal controls disable dismissal. Navigation from Player to another full screen follows standard dialog-destination semantics (the Player dialog is removed).

Artist overview loading now starts at Player composition, outside LazyColumn, through a lightweight PlayerArtistViewModel. It uses the existing repository API without initializing ArtistViewModel's general feeds. Same-artist recompositions do not repeat requests; artist changes cancel the previous job and reject stale generations. State is keyed by requested artist to prevent a previous bio flashing during changes. Podcast/missing-identity items do not request a music artist. Allowed header artwork is downloaded to Glide cache before scrolling; existing whitelist policy is preserved. ArtistBioCard only renders the supplied result. Network speed can still affect first display; loading no longer waits for scrolling.

Validation: User 276/276; Admin 272/276 with exactly the four existing whitelist failures by class/test/message. All 13 new tests pass in both flavors. Both debug variants compiled via unit-test tasks; diff check passes; all nine APK paths/hashes unchanged. Evidence: app/build/reports/player-ui/validation-final.log, results.json and apk-verification.json. Spotify/InnerTube entries in the summary are earlier results. Physical/visual acceptance remains pending; see PLAYER_PRESENTATION.md. No playback engine/source/resume changes and no APK packaging.

## Completed follow-up — one-time legacy history cleanup

User confirmed new history items work on-device, then authorized removing legacy non-navigable history on update and removing the top-left three-dot menu. First history access now performs an atomic, marked cleanup in ListeningHistory only, retaining valid exact track/episode entries. The marker prevents another cleanup on later reads/restarts/updates. Saved/custom playlists, liked items, listening tracker and resume/progress stores are untouched. The history header overflow menu and clear-all action are removed; individual long-press removal remains. No playback changes or APK generation. See LISTENING_HISTORY_UI.md.

Validation: User 263/263; Admin 259/263 with exactly the four existing whitelist failures by class/test/message. All eight new migration tests pass in both flavors, exercising the real preference adapter with in-memory SharedPreferences: mixed/legacy/fresh/corrupt input, valid identity retention, one-time writes, unrelated file/key preservation and marker persistence. Both debug variants compiled through unit-test tasks; git diff --check passed. All nine existing APK paths/hashes are unchanged. Evidence: app/build/reports/history-cleanup/validation.log, results.json and apk-verification.json. Module result summaries for Spotify/InnerTube are retained earlier runs, not reruns in this follow-up. The new migration and menu removal have not been installed on-device; visual/update acceptance remains pending a separately requested build.

## Completed follow-up — Recently Played history and saved check

Implemented 2026-10-12: replaced the clipped text-glyph saved check with a vector badge; added Home Show all to the existing History route; replaced the old history/stats presentation with dated RTL history, Music/Podcasts filters, expandable real listening groups, exact detail navigation and shared episode progress. Clear-all and individual removal remain available. Existing 500-entry storage is extended compatibly with full typed SongsModel snapshots. Legacy metadata-only entries remain visible under All without guessed filters or navigation. No playlist/source context is invented.

History now records from the owned primary Player's playing snapshot instead of CurrentSongState's tentative metadata update. The observer rejects pending, paused, secondary and stale generations without changing playback. Typed detail-registry keys prevent track/episode collisions. PodcastEpisodeUiModel and the single-episode ShowScreen conversion retain Spotify episode identity, preserving bookmark continuity after entering details. See LISTENING_HISTORY_UI.md for behavior, compatibility and device checks.

Final validation: User 255/255; Admin 251/255 with exactly the four established whitelist failures by class/test/message. All 22 new history/identity/recording tests pass in both flavors, together with existing playback/progress suites. Both debug variants compiled via the unit-test tasks; git diff --check passed. The combined command exits 1 solely for the accepted Admin baseline. No APK packaging ran; all 9 existing APK paths/hashes remain unchanged. Evidence: app/build/reports/listening-history/validation-final.log, results.json and apk-verification.json. Initial 253-test results are retained in results-first.json; Spotify/InnerTube summary entries are older module results, not reruns in this task.

ADB is now online at 900x1600 / 240 dpi, but the new UI has not been installed or visually tested. Source review considered 240 dp and 600 dp widths; debug compilation and JVM tests do not establish rendered viewport acceptance. No APK generated.

## Completed follow-up — Home reference alignment and podcast progress

Implemented 2026-10-12 from the user's original Spotify/SpotUI screenshots. Home standard covers and artist circles are now 200 dp; Recently Played uses 98 dp covers. Artwork stays square, with 4 dp corners, explicit 16 dp margins/gaps and viewport constraints. Home-only background, header/grid dimensions, section spacing and captions were adjusted. MiniPlayer, bottom navigation, PlayerScreen, artwork filtering, feed data and exact-item navigation are preserved.

Recent Podcast Episode cards now display an RTL-aware green progress bar. It reads the existing typed per-item resume bookmark through a preference listener. New PodcastProgressStore records duration and completion separately, so completion can show 100% while resume retains its existing zero-at-end behavior. SongPlayer changes only to supply actual duration at its existing guarded progress-save hook. Unknown-duration episodes retain their subtitle; no guessed progress or new Player/source pipeline exists. Detailed design, screenshot measurements and device checklist: HOME_UI_ALIGNMENT.md.

Final validation: User 233/233; Admin 229/233, with exactly the four existing whitelist failures by class/test/message. All 12 new progress tests pass in both flavors, alongside prior playback/history suites. Both debug variants compiled via unit-test tasks; no APK packaging ran. First-run User NewPipe live-network test returned HTTP 403 beyond the 1 MiB boundary; final full run passed it in both flavors. This transient result is retained in the report, not folded into the whitelist baseline. git diff --check passed. All 9 existing output APK paths/hashes are unchanged. Evidence: app/build/reports/home-ui/validation-final.log, results.json, results-first.json, reference-measurements.json and apk-verification.json. Spotify/InnerTube module results in the summary are retained from earlier work; those module tasks were not rerun for this UI change.

ADB lists emulator-5554 as offline. Rendered normal/small viewport comparison and live Android preference-listener/MediaSession acceptance remain pending an online device and a separately requested build. The code sizing and JVM tests do not establish pixel-perfect visual acceptance. No APK generated.

## Completed follow-up — approved playback performance/reliability fixes

The user approved the audit below for implementation. No APK is authorized. Implemented source-request coalescing with identity/matching inputs, quality, account, settings and network scope; bounded URL caches with expiry; one queued prefetch worker respecting the preload preference; first-playing stage timings; a generation-guarded startup deadline and bounded music recovery with visible failure and same-item retry. Retry actions also follow crossfade ownership. PlaylistViewModel now uses one metadata/content flow, with a shared first-page request, ordered pagination using raw consumed offsets and retry after failure.

Changes remain in SongPlayer and small playback helpers, playlist API/repository/ViewModel, the existing Spotify playlist parser and prefetch call sites in five screens. No layout, buffer-threshold, underlying VISIONOS/InnerTube client, matching algorithm, TrackVideoCache, RSS, EpisodePlaybackResolver or server telemetry changes. Podcasts still use their existing direct-audio path. Existing earlier P0/progress/button-sync changes are preserved.

Final validation (2026-10-12): User 221/221; Admin 217/221. All four Admin failures exactly match the accepted baseline by class, test and assertion message; no new failures. All 27 new app tests (21 performance/ownership/retry/cache cases and 6 playlist-loading cases) pass in both flavors, together with the existing podcast/Waze/progress/button-state suites. Spotify 22/22, including 3 new parser tests; InnerTube 6/6. The module tests were UP-TO-DATE in the final combined run; Spotify's new tests ran successfully earlier in this follow-up. Both debug variants compiled via unit-test tasks. No assemble/package APK task ran. All 10 existing output APK paths/hashes are unchanged. git diff --check passed, with no trailing whitespace in untracked source/docs. Evidence: app/build/reports/playback-performance-fix/validation-complete.log, results.json and apk-verification.json. Gradle exits 1 solely because of the known Admin baseline.

Rendition-safe disk-cache keys and smaller buffer thresholds remain deferred until device measurements. A VPN-related cause has not been established and this affected user's failure has not been reproduced. No claim of measured phone startup improvement or a confirmed VPN fix. Pause during automatic music recovery now revokes its ownership too, including external user-pause callbacks, so a late recovery cannot restart audio. These JVM tests cover helpers/parsers and ownership boundaries; live Android callback delivery remains a device check. See PLAYBACK_PERFORMANCE_AUDIT.md, implementation follow-up section, for current behavior and manual checks. Implementation is complete for this scope; no APK generated and no P1 work started.

## Completed investigation — playback latency / one silent device

Completed source audit and live host-network diagnostics on 2026-10-11; see PLAYBACK_PERFORMANCE_AUDIT.md for exact paths, evidence, pinned BitChord comparison and safe follow-up order. No production playback/UI code or permanent tests changed, and no APK was generated.

Findings: uncoalesced prefetch/play requests; duplicated playlist header/content requests; expiring stream URLs stored without TTL; URI-based disk cache; default 2500/5000 ms audio-buffer thresholds versus current BitChord's 500/2000; speed logs end before actual audio; potentially long sequential fallbacks and an exception path that logs without user feedback. No explicit VPN rejection found. Direct VISIONOS initially failed in host probes, but later production-resolver attempts succeeded with VISIONOS URLs and readable audio (HTTP 206). The affected user's VPN/device failure is not reproduced or proven fixed.

Final normal-config regression run: User 194/194; Admin 190/194 with exactly the same four whitelist failures; diff check passed. Opt-in diagnostic harness remains only in ignored app/build/reports/playback-performance-audit and was removed from the normal test configuration by completing a run without its init script. No device attached; no audible-startup, real playlist-rendering or VPN-on/off benchmark was possible. Device-specific information and measured optimization implementation remain follow-up work.

## Completed follow-up — external Play/Pause state

The user confirmed per-item progress works on-device, then reported that pausing from the lock screen/notification pauses audio but leaves the in-app Pause icon visible. No APK was authorized.

Fixed in SongPlayer: project the owned primary Player's onEvents snapshot into the shared CurrentSongState, including playWhenReady changes during buffering. Read the current state on bind and crossfade promotion as well. Old tokens, secondary players and untagged items cannot overwrite the current button state. Existing Presence callbacks, metadata/queue, progress storage, playback resolution and all layouts are unchanged.

Validation (2026-10-11): User 194/194; Admin 190/194 with exactly the same four whitelist failures by class/name/message. All 12 new PlaybackControlStateTest cases passed in both flavors, as did prior identity/ownership/progress tests. Both debug variants compiled through the unit-test tasks; the combined command exits 1 only for the Admin baseline. git diff --check passed. No APK was generated; hashes and file list of existing app/build/outputs APKs are unchanged. Evidence: app/build/reports/playback-button-sync/validation.log, results.json and apk-verification.json.

Changed for this follow-up: SongPlayer.kt; new PlaybackControlState.kt and PlaybackControlStateTest.kt; CURRENT_WORK.md, STATUS.md and MEDIASESSION_WAZE.md. Tests cover production projection/ownership logic, not real Android callback delivery or Compose rendering. When a new APK is separately requested, verify external pause/resume for a track and podcast in both full Player and MiniPlayer, including buffering. Stop here; no P1 work.

## Completed follow-up — per-item playback progress

The music portion of this historical policy is superseded by the 2026-10-22
current-item-only rule above. Independent podcast bookmarks remain in effect.

The user reports that Step 4 works well on their device, but selecting another track/episode and returning resets playback to the beginning. The user authorized fixing this without generating an APK.

Root cause: PlaybackStatePref stored only one last-item position, reset it on selection changes, and ordinary playSong starts did not consult per-item progress.

Implemented: durable PlaybackProgress preferences keyed by typed stable identity; migration of the old last-item position; capture of the actual tagged outgoing Player before ownership changes; automatic resume for normal playback, overlay selection and unset MediaSession start positions; save on pause/seek/stop/release/service teardown and every second while playing. Crossfade resumes incoming progress and resets the completed outgoing item. Explicit seek positions (including zero) take precedence; natural completion resets that item's bookmark only.

Validation completed 2026-10-11 against final source: User 182/182; Admin 178/182 with exactly the four unchanged whitelist failures (class/name/message comparison). All 18 new progress-store tests and existing playback tests passed in both flavors. Debug code compiled through testUserDebugUnitTest and testAdminDebugUnitTest; no assemble/package APK task ran. git diff --check passed. Existing User/Admin APK SHA-256 values are unchanged. Evidence: app/build/reports/playback-progress/validation-final.log and results.json.

Changed for this follow-up: PlaybackStatePref.kt, SongPlayer.kt, PlaybackService.kt, WazeOverlayView.kt; new PlaybackProgressStore.kt and PlaybackProgressStoreTest.kt. No UI layout, RSS, EpisodePlaybackResolver or music matching changes. The new tests exercise production storage/identity logic with memory/file-backed test storage; the Android SharedPreferences and live Player lifecycle still need device verification when a new APK is requested.

## P0 Waze / MediaSession — Steps 1–4

Steps 1–3 were approved. Step 4 is limited to hardening and integration; no P1 work is authorized.

Implemented:
1. Strict typed identity resolution and preserved local Spotify episode identity.
2. SongPlayer ownership generations and stale resolution/upgrade/crossfade/recovery guards.
3. Exact Waze overlay/MainActivity resume, full persisted identity and position.
4. Shared external request coordination for MediaSession set/resumption; explicit add/replace rejection; old transport blocked after failed resolution; PlayerScreen missing-ID and pager guards; queue projection and stale radio guards; headless integration tests.

The actual flow and remaining legacy music compatibility branch are documented in MEDIASESSION_WAZE.md. Media3 1.3.1 has the two-argument onPlaybackResumption; no isForPlayback overload.

## Validation

Step 4 local validation completed:
- User: 164/164 passed.
- Admin: 160/164 passed. Exactly the same four whitelist failures as Step 3, compared by class, test name and failure message; no added/changed failures.
- All 96 Steps 1–4 identity/ownership/resume/integration tests passed in both flavors, including 22 new integration tests.
- The live NewPipe test passed in both flavors.
- Spotify: 19/19; InnerTube: 6/6 (unchanged module test tasks were UP-TO-DATE).
- assembleUserDebug, assembleAdminDebug and aggregate assembleDebug succeeded.
- The combined Gradle command exits 1 because of the accepted Admin baseline, not an APK build failure.
- git diff --check passed.
- User APK signature verified (v2). No device connected.

Evidence: app/build/reports/step4/validation.log, results.json, apk.json.
Delivery: app/build/outputs/apk/SpotUI-Step4-User-debug.apk, version 2.8 (28), com.music.spotui, 23,397,895 bytes.
SHA-256: 1AB3B6A354866D1AB2FEBFE089ECD8DD4C3699170BB024C8099EC056A35D79C9.

These are headless production-logic integration tests with a simulated Player boundary, not Android/Waze end-to-end tests.

Step 3 baseline: User 142/142; Admin 138/142 with four whitelist assertion failures; Spotify 19/19; InnerTube 6/6. Compare every failure's class, test and message to TEST_BASELINE.md. A new failure blocks acceptance.

Physical Waze, real background/process recreation, audio identity and both Player viewports remain pending. P0_DEVICE_TESTS.md provides the checklist. Do not close P0 based only on JVM tests and compilation.

## Scope boundary

No changes to VISIONOS, InnerTube, YouTube matching, TrackVideoCache, RSS, EpisodePlaybackResolver, Podcast Hub/Following, telemetry, AddToLikedSongsViewModel or P1 browse nodes. PlayerScreen changes are safety logic only; layout and F22 adaptation are preserved.

Stop after Step 4 and deliver the User debug APK for device acceptance.
## Step 4 file changes

- New production helpers: playback/ExternalPlaybackRequests.kt and playback/PlaybackQueueIdentity.kt.
- Ownership/transport guards: di/SongPlayer.kt and playback/PlaybackOwnership.kt.
- Callback coordination, add/replace rejection, resume and queue safety: ui/notification/PlaybackService.kt.
- Shared accepted-item state commit: di/CurrentSongState.kt, MainActivity.kt and ui/overlay/WazeOverlayView.kt.
- Missing/ambiguous current ID, drag-only pager and stale radio guards: ui/screens/PlayerScreen.kt and ui/viewmodel/PlayerViewModel.kt. Radio work captures ownership after the new track starts so a normal skip does not invalidate its own fetch.
- New 22-test headless integration suite: app/src/test/java/com/music/spotui/playback/PlaybackIntegrationTest.kt.
- Updated MEDIASESSION_WAZE.md, CURRENT_WORK.md, STATUS.md and TEST_BASELINE.md; added P0_DEVICE_TESTS.md.
- Existing Step 1–3 edits remain in the working tree. No commit or push was made.
