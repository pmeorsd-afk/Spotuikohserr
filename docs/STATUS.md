# Project Status Snapshot

Always verify the repository before assuming this snapshot exactly matches current code.

## Home tap approvals and new-user Recently shelf (2026-10-23)

Home cover taps automatically request exact-image approval alongside the existing
navigation, using the manual menu's scope and pending state. Approved, pending and
in-flight covers do not cause repeated sends. Other screens do not initiate this
request. Home hides Recently until there is listening history or saved content;
the source placeholder and listening/like revision updates remain reactive.

Both Debug variants compile. Full User 721 pass/0 failures/2 skips; Admin 717 pass/
the four established whitelist failures/2 skips. All 14 new tests pass per flavor.
Final focused Home/artwork/podcast rerun passes 50/50 per flavor on final source.
No playback engine changes, APK/install, live bot test or connected device.
Small/normal viewport and end-to-end acceptance remain pending. See CURRENT_WORK.md
and app/build/reports/home-tap-requests/.

## Welcome and Waze setup updated (2026-10-23)

Scrollable welcome highlights Android Auto plus Podcast/Home/Search updates and
handles enlarged text without fixed content/button heights. Continue offers the
same muted-video Waze permission flow available later in Settings. The switch now
refreshes from actual accessibility permission and the user's local choice on
resume; permission launch alone never enables it. No playback/service changes.
Both Debug variants compile; User 707 pass/2 skips, Admin 703 pass/4 established
whitelist failures/2 skips. Four new onboarding persistence tests pass per flavor.
No APK or connected device: rendered viewports, giant fonts and real settings-return
acceptance remain pending. See CURRENT_WORK.md and reports/welcome-waze evidence.

## In-app updater implemented locally (2026-10-23)

The existing version.json now supports an optional direct apkUrl and SHA-256.
DownloadManager handles background downloads; the responsive, scrollable update
dialog shows green progress and cancel/retry actions. Exact APK package/version/
signature checks precede Android's permission/install screens. User/Admin APKs
are kept separate, and manifests without apkUrl retain the browser link.
Metadata TLS validation is enabled using the configured system/user CAs.

26/26 updater tests pass in each flavor on final source; both Debug variants
compile. The full User rerun passed 703 tests with 2 skips; the full Admin run had
699 passes, 4 established whitelist failures and 2 skips. Live NewPipe was unstable
across full/focused runs; details in CURRENT_WORK.md. No APK was packaged, installed
or published, no live JSON or version number changed, and no device was connected.
Actual viewport, filtered-network, DownloadManager/installer and audio acceptance
remain pending. See IN_APP_UPDATES.md for the release workflow and limitations.

## Telegram approvals repaired and deployed (2026-10-23)

Full YouTube track IDs now survive callbacks, and distinct recordings no longer
merge by title. Removed the duplicate GAS whitelist mutation; the actual missing
October 8 grant was traced to an October 14 collision/cleanup. Restored only that
verified track under `youtube:tLTgCQovmpc` (live whitelist 1668).

Worker `ca76f6cc-a7db-4a9f-b9dc-1ef169e6d706` is active at 100%. Callback receipt
does not wait for saving/UI edits; final success follows durable storage. 32/32
Node tests, dry-run, both Debug Kotlin compile tasks pass; live health, restored
row, authorization rejection and unchanged webhook verified. No Android edits,
APK/install or actual Telegram rapid-click/device audio acceptance. See
TELEGRAM_APPROVAL_REPAIR.md for evidence and legacy GAS-cache limits.

## Current-song resume policy (2026-10-22)

Music now resumes only the exact current MiniPlayer item; selecting another item
and returning to a previous song starts at zero. Current-song pause/recreation
remains exact. Podcast episodes retain independent bookmarks and progress.
Old per-track bookmarks are ignored, and another selection disarms the cold-start
restore query immediately. Source resolution and UI layouts are unchanged.

All 29 position-policy/store tests pass per flavor. Both Debug variants compile;
User 677 pass/0 fail/2 skips, Admin 673 pass/the same 4 established failures/2 skips.
No device audio/external-controller verification or APK/install. See
PLAYBACK_POSITION_POLICY.md and app/build/reports/current-track-progress/.

## MiniPlayer narrow-width control sizing (2026-10-22)

The artwork/title area now yields space to the existing saved and Play controls
on narrow viewports, preventing a squashed green circle and hidden Play. Existing
geometry is preserved at widths >=360dp. Debug/instrumentation compilation passes;
User 666 pass/0 fail/2 skips, Admin 662 pass/4 established failures/2 skips.
Source layout audit only: no device visual/audio verification or APK/install.
See CURRENT_WORK.md and app/build/reports/miniplayer-circle/.

## Radio previews unavailable: duration mapping repaired (2026-10-22)

The actual playlist endpoint uses trackDuration.totalMilliseconds, which was
missing from our duration parser. Live reproduction returned 20/20 zero-length
track models; after the targeted correction it returns 20/20 valid durations,
allowing the preview coordinator to calculate its midpoint clip. All seven focused
playlist tests pass. App regression remains User 666 pass/0 fail/2 skips and Admin
662 pass/4 known failures/2 skips; both Debug/instrumentation variants compile.
Preview/main playback engines are unchanged. Actual phone audio remains unverified;
no APK packaged/installed. See RADIO_PREVIEWS.md and radio-preview-fix reports.

## Search editing / history / live completion (2026-10-22)

Active Search now follows the supplied reference geometry/colors, with native
caret/selection, rounded clearable field, animated bottom history clearing and
separate per-row remove/save actions. Existing opening transitions are unchanged.
Tracks/episodes reuse SavedInSheet; albums/playlists save directly to Library with
durable exact identity. Shows/artists have no +. Real autocomplete works through
the verified Google/YouTube endpoint; stale responses cannot replace later input.
Spotify playlists are retained and live Spotify results publish earlier.

Full tests: User 666 pass/0 fail/2 skips; Admin 662 pass/4 known failures/2 skips.
All 12 new deterministic cases and the separate live HTTP case pass per flavor.
Both Debug/instrumentation variants compile. Device visual/input/audio acceptance
remains pending; no APK packaged/installed. See SEARCH_EXPERIENCE.md.

## Radio track preview screen (2026-10-22)

The Radio summary now opens an isolated preview list with press feedback, gray RTL
cards, pinned title, pause and circular progress. A screen-owned player clips ten
seconds from the middle while preserving the main MiniPlayer item/queue. Previously
playing main audio resumes on preview pause/completion; external commands and focus/
foreground loss revoke automatic restoration. Music source selection/matching and
the RSS/direct-podcast path are unchanged. See RADIO_PREVIEWS.md.

18/18 new tests pass per flavor; full User 654 pass/0 fail/1 skip, Admin 650 pass/4
known whitelist failures/1 skip. Both Debug and instrumentation variants compile.
Viewport previews/UI tests compile only: actual visual/audio/device acceptance is
pending. No APK packaged or installed.

## Missing Home shelves / startup follow-up (2026-10-21)

Fixed rejected Spotify locale redirects, network-blocked startup, hidden first
background results and overaggressive history/mix deduplication. The production
HTTP client now verifies same-entity redirects; live public Home returned 20 radio
playlists and 20 albums. Local content renders first, missing shelves arrive during
the same visit, and failed public refreshes retain verified shelves. Existing rows
remain stable until Home resume/explicit refresh. See HOME_DISCOVERY_RECOVERY.md
for tests, evidence and superseded behavior. Full User 637/637, Admin 633/637 (the
four established failures); live production HTTP/ranking passes in both flavors.
No APK/install or device acceptance.

## Local Home discovery (2026-10-21)

Complete project restore point verified before editing: 44,923 files, all SHA-256
matched. Guest-compatible mixes/radio now use counted local music history, exact
artist IDs and actual playlist track samples. Cold history has general labels;
popular albums remain the public Spotify shelf. Discovery is prepared in the
background and shown on Home re-entry/refresh to keep active shelves stable.
Public-page parsing is bounded and cached; it is not a stable public API or a
reproduction of Spotify's private personalization. See HOME_RECOMMENDATIONS.md.

Full User 619/619, Admin 615/619 (the same four established whitelist failures),
followed by 46/46 focused tests per flavor after final history guards. Both debug/instrumentation
Kotlin variants compile. No device acceptance, APK or install. Playback, exact
image approvals and backend are unchanged.

## Car artwork and Home shelf stability (2026-10-20)

Exact Home-image approvals now apply to Android Auto browse cards, with provider
checks, versioned image URIs and permission-change refresh notifications. Visible
Home screens refresh approvals across devices, and requests check shared decisions
before sending. Mix caption slots reserve two lines so horizontal scrolling does
not change shelf height. Distinct locale-specific composites remain separately
approved (the user supplied different Peer Tasi images). Full User 584/584 and
Admin 580/584 (the four established failures); all ten new tests pass per flavor.
Both debug/instrumentation variants and three previews compile; no device/gesture/car
acceptance or APK/install. See HOME_AUTO_ARTWORK_AND_SHELVES.md.


## Search artwork links / Radio and Playlist correction (2026-10-19)

Fixed 15 broken category URLs using matching official browse artwork, preserving
two working URLs; all 17 were verified as actual images. Four categories lacking
a verified matching image retain placeholders instead of sending a 404 link.
Search sends now validate exact URL/status/MIME/signature before Telegram and
leave failed requests retryable. Old messages require a new request from an
updated app. No Worker, secrets, whitelist migration or live message changes.

Radio search removed; top-cropped hero/gradient, typography, controls and compact
track preview refined. Playlist search matches Liked Songs and reveals only on
an intentional pull, including during initial loading. Both variants/previews
compile; User 574/574, Admin 570/574 (the four established failures), focused Search
21/21 per flavor. No APK/install, connected device or rendered validation. See
CURRENT_WORK.md for limits and evidence.

## Library and collection refinement (2026-10-19) — stages 1–5 source complete

Dedicated followed-podcast feed, two history-based Continue Listening items,
durable exact completion/six-second Undo and episode menu. Bottom Home selects
All while preserving the press/filter animations. Playlist has scroll-revealed
search/sort, shrinking/fading cover and pinned title/green Play. Radio has a wide
fading hero, expandable track preview and real catalog recommendations ranked
by artists and listening affinity. Exact Home cover grants extend to collection
headers/recommendations, including long-press approval, without global grants.

Both debug variants and all new preview configurations compile. User full run
567/568 plus successful isolated retry of a transient external stream HTTP 403;
Admin 564/568, exactly the four known whitelist failures. All 23 new tests per
flavor and 16 Worker tests pass. Two-line bot caption update published at 100%:
d74d92b4-80ae-445a-a755-2f27c31e315b. Health/whitelist 200, secrets/webhook unchanged.
No connected device/AVD, rendered verification, APK packaging or installation.
Existing release artifacts changed concurrently and were left intact. See
CURRENT_WORK.md and LIBRARY_AND_COLLECTION_REFINEMENT.md for evidence and limits.

## Library refinement (2026-10-18) — stage 1 history

Added the automatic New Episodes tile using existing podcast follow state; one
or more follows show it, zero removes it, and it has no playlist deletion action.
List/grid share the sampled purple/green filled-bell cover. Header Search/Create
icons now share the reference size and full touch targets; the filter header has
a dark fading shadow. Focused tests 18/18 per flavor; both debug variants/four
preview configurations compile. No APK/install, rendered previews or connected
device. The dedicated New Episodes screen/navigation was subsequently added in
stage 2 after user authorization. Full five-stage request and completed work:
LIBRARY_AND_COLLECTION_REFINEMENT.md.

Full validation: User 545/545; Admin 541/545, the same four established whitelist
failures. All 90 non-target baseline files and nine existing APK hashes are
unchanged, including the preceding reboot-resumption fix.

## Reboot autoplay (2026-10-18) — source corrected, physical check pending

Media3 1.3.1's boot recent-item query no longer enters the side-effecting Play
resumption path. It returns validated saved metadata/bookmark only; actual car,
notification and System UI Play retain the existing transport implementation.
Both debug variants compile; 22 resumption tests pass per flavor, including seven
new routing/metadata cases. Full User 542/542, Admin 538/542 with the same four known
whitelist failures. No APK/install or connected ADB device. The user confirmed the
earlier car behavior works; the new reboot fix still needs device acceptance.
See BLUETOOTH_RESUMPTION.md and `app/build/reports/reboot-resumption/`.

## Search category image approvals — stage 3 (2026-10-18), source and live bot complete

Search category-cover long-press opens an image-approval menu. Exact URL grants are isolated in `search_images`, with separate pending/retry state; Search results and all other surfaces remain outside that scope. Home approval behavior and existing navigation/layout/playback remain. Full User 535/535; Admin 531/535 (four established failures); all 15 new tests per flavor and 16 Worker tests pass. Both debug variants/three preview configurations compile. No APK/install or device validation; nine APKs and 80 non-target protected files unchanged. Published Worker `930625cb-2435-4e0d-a8bc-563365f667ce` using normal Wrangler authentication; existing secrets/webhook preserved. Public checks: health/whitelist 200, unauthenticated Home/Search callbacks 403. See SEARCH_IMAGE_APPROVALS.md. All three requested stages are implemented in source; physical acceptance requires a separately requested app build.

## Filter interaction stage 2 (2026-10-18) — completed source (previous stage)

Following now toggles back to Podcasts when tapped again, restoring its gray unselected fill while remaining expanded. The existing animation and stage-1 Podcasts-to-All behavior are unchanged. Both debug variants/three viewport preview configurations compile. Relevant navigation/playback tests: User 44/44; Admin 43/44 with one established whitelist failure. All 86 non-target protected files, including nine APKs, are unchanged. No APK packaging/install or physical UI/playback check. Stage 2 was reported before stage 3 was authorized. See HOME_UI_ALIGNMENT.md and `app/build/reports/podcast-filter-toggle-step2/`.

## Filter interaction stage 1 (2026-10-18) — completed source (previous stage)

Tapping Podcasts from Podcasts/Following now selects All immediately and retracts Following using the original animation. Shared reveal state survives Home's feed/loading/fallback header changes; only local measurement reads animated progress. The 220ms timing, RTL layout, colors and Following callback were unchanged in stage 1. Stages 2–3 were deferred until further user instruction. Full User 520/520, Admin 516/520 (four known failures); final debug variants/previews compile. No physical UI check or APK packaging/install performed by this task. Two concurrent release-APK changes were observed and left untouched; protected source checks pass. See HOME_UI_ALIGNMENT.md and `app/build/reports/podcast-filter-toggle-step1/`.

## Bottom navigation icon tap animation (2026-10-18) — source complete

Home/Search/Library icons now shrink to 88% and return on each accepted click (80ms down, 160ms back), including the already selected item. Only icon drawing scales; existing navigation/Home refresh, layout, labels and touch targets are unchanged. Both debug variants and three viewport previews compile; no new unit tests for this visual-only change. Preceding full regression suite remains User 520/520, Admin 516/520 (four established failures). Protected files and nine APKs unchanged; no APK/install or physical motion verification. Evidence: `app/build/reports/bottom-nav-press/`.

## Home image rejection retry (2026-10-18) — source fixed

User confirmed live image approvals work. Newer rejection decisions now release the matching pending request, restoring "התר תמונה זו"; retries remain pending until a new decision, and legacy pending entries can recover. Cached decision timestamps and pre-dispatch snapshots cover reloads and fast decisions. User 520/520; Admin 516/520 (same four known failures); nine new tests pass per flavor. Both debug variants compile. No Worker or visual-layout changes, APK packaging/install, or new device validation. See HOME_IMAGE_APPROVALS.md and `app/build/reports/home-image-retry/`.

## Home-only image approvals (2026-10-18) — tested source and live bot deployed

Home cover menus and exact-URL Home-only permissions are implemented with Telegram approval/rejection/revocation handling. No global artist/track image grant is created. User 511/511; Admin 507/511 (same four known failures); seven new tests per flavor and eight Worker tests pass. Debug variants/previews compile and Worker dry-run passes. No APK/install or connected device; nine APKs and protected playback/filter sources unchanged.

After explicit user authorization resolving the earlier automatic-review rejection, configured the GitHub/webhook secrets, preserved the live GitHub credential and existing Telegram webhook settings, and deployed version `48caf043-f13c-47b7-b8f0-db53af2c78a6` at 100% on 2026-10-18 06:00 UTC. Deployed source matches the bundle; health/whitelist return 200, unauthenticated Home callbacks return 403, and Telegram reports no webhook error or pending updates. No updates were dropped, no whitelist entry was changed and no Telegram message was sent. Worker tests rerun: 8/8. Real app-to-Telegram approval acceptance remains pending a separately requested app build/install. See HOME_IMAGE_APPROVALS.md and CURRENT_WORK.md for evidence.

## Latest implementation — Podcast filter appearance (2026-10-18)

Following selected fill now matches the sampled original #1ABC54; Podcasts retains #1ED760. Local text/padding adjustments and continuous joined-capsule painting refine the shape. Animation target/timing/easing and the complete overlap/measurement/placement block are source-identical to the baseline, as explicitly required by the user. Other Home filter typography and behavior retain their defaults.

User 504/504; Admin 500/504 with exactly four established failures. Both debug variants/three preview configurations compile, but previews are not rendered. All 73 non-target protected files and nine APK paths/hashes unchanged. No APK/install or connected device. See HOME_UI_ALIGNMENT.md and app/build/reports/podcast-filter-appearance/ for reference samples, animation verification and pending visual acceptance.

## Latest implementation — Podcast Home text covers (2026-10-18)

Home Podcasts episode covers now display their exact title and publisher/show on a permitted-artwork gradient, with readable black/white text and stable show-colored fallbacks. Feed/Following swatches also omit the green library placeholder. Existing other artwork, identity, navigation, save and playback behavior is retained. Width/font-based layout preserves ordinary reference proportions and grows only for large accessibility text. Bounded, cancellable palette loading is isolated from the shared music palette and whitelist logic.

User 504/504, Admin 500/504 with exactly four known failures; all four new tests pass in both flavors. Both debug variants/six viewport preview configurations compile; previews are not rendered and no device is connected. All 70 protected non-target files and nine APKs unchanged; no APK/install. Live appearance, permission/scroll lifecycle and actual playback acceptance remain pending. See PODCAST_HOME_COVERS.md and app/build/reports/podcast-cover-design/.

## Latest implementation — Android Auto dashboard stage 4 (2026-10-16)

Car custom actions prioritize Save for the dashboard slot alongside standard Play/Pause and Next; full-player Radio/Repeat stay available. The shared legacy/notification layout refreshes on car connection and restores standalone order on disconnect. Save directly uses existing Liked Songs persistence/sync, with an exact-identity check and no removal on repeated taps. No phone UI or playback/source engine changes.

User 500/500; Admin 496/500 with exactly the four known failures, all eight new tests passing in both flavors. Both debug variants compile; all 64 protected hashes and nine APK paths/hashes unchanged. No APK/install/device. Host layout, live controller/background playback and earlier A–Z/color/station acceptance remain pending; A–Z removal is unresolved. See ANDROID_AUTO_REFINEMENT.md and app/build/reports/auto-refinement-stage4/.

## Latest implementation — Android Auto refinement stages 2–3 (2026-10-16)

Explicit artist search selection plays a shuffled exact-artist track queue through existing preparation/ownership; durable artist action IDs survive service recreation. A separate Auto theme supplies #2CF261 without phone theme edits. Top results grouping is added; supported A–Z removal was not found and remains unresolved. Green/black host rendering remains unverified.

Car Home/Music now prepends real durable listening history, reacts to successful playback/history changes, and places actual Recommended stations playlists next when supplied by the raw Spotify Home feed. No fake recent entries or generic-radio relabeling. Existing sections/library shortcuts remain, and cached/local content works during bounded remote refresh. Server/account shelf availability and a match to the private Android Auto feed cannot be guaranteed. Phone UI, music/RSS engine, history writer and API clients unchanged.

Final User 492/492, Admin 488/492 with exactly four known failures; all 20 new tests across stages 2–3 pass per flavor. Both debug variants compile, 53 baseline-protected files unchanged, with only the Auto accent intentionally refined; nine APK paths/hashes unchanged. No APK/install/device. Dashboard stage 4 was subsequently authorized and is recorded above. See ANDROID_AUTO_REFINEMENT.md and app/build/reports/auto-refinement-stage2/ / auto-refinement-stage3/.

## Latest implementation — Android Auto refinement stage 1 (2026-10-16)

Car presentation now withholds Previous/public timeline permissions so the existing Radio/Save/Play/Next/Repeat controls can fit without queue/overflow. AndroidX CarConnection updates Media3 1.3.1's shared legacy/notification permissions only while projecting/native car mode, restoring standalone controls on disconnect. Radio starts a new validated recommendation after exact source preparation, with stale/cancel/timeout protection. Phone UI, music engine and direct podcast resolver are unchanged. Progress-bar geometry is host-controlled; seeking remains enabled, so this is not a pixel-identical progress-bar implementation.

User 472/472; Admin 468/472, exactly the four existing whitelist failures. Ten new tests pass in each flavor; both debug variants compile. All 42 protected source/artifact hashes and nine APK paths unchanged; no APK/install or connected device. DHU rendering and live controller acceptance remain pending. Stage 1 was reported before stages 2–3 were authorized. See ANDROID_AUTO_REFINEMENT.md and app/build/reports/auto-refinement-stage1/.

## Latest implementation — podcast saving stages 4–5 (2026-10-16)

Both stages were authorized together by the user's continuation. Episode plus/check animation and immediate destination-sheet entry now retain full typed identity. Your Episodes uses a separate durable store and Library collection; music retains Liked Songs. The reference compact sheet expands with drag/fling, shows all local/paginated editable Spotify destinations, stages new playlists and edits, and provides top Cancel/Done plus bottom Done. Membership failures are retryable and remote-only saved state persists per session. Legacy typed episodes cannot contaminate music saves. Saved podcast/mixed-playlist entries use existing direct playback after resolution; music engines and stage-3 menu are unchanged.

User 462/462; Admin 458/462 with exactly four known baseline failures. All 27 new tests pass; both debug variants/previews compile. Six protected source hashes and nine APK paths/hashes unchanged; diff check passes. No connected device, rendered previews, live remote mutations or audible acceptance. No APK/install. All five stages are implemented in source; device acceptance remains. See PODCAST_SAVING.md and app/build/reports/podcast-save-step4/.

## Latest implementation — podcast Show menu stage 3 (2026-10-15)

Dedicated four-action Show menu replaces the accidental first-episode/music menu. Share resolves the exact Show link; Follow/Unfollow uses the existing Library store; the user-approved personal rating persists in SpotUI only; Spotify code loads the real code endpoint with explicit loading/error/retry. Compact reference layout, RTL, scrollable narrow layout and smooth sheet/page transitions implemented. Both debug variants/previews compile; 11 new tests pass in each. Full User 434/435 (live NewPipe HTTP 403, isolated retry passes), Admin 431/435 with the same four known failures. Protected playback/sheets/episode callbacks and nine APKs unchanged. No APK/install or rendered/device acceptance. Stop before stage 4 pending the user's decision. See PODCAST_SHOW_MENU.md.

## Latest implementation — podcast Show presentation stage 2 (2026-10-15)

Approved Show UI comparison implemented: reference cover/row sizing, text hierarchy, gradient, follow pill and outline controls, tabs and RTL heading placement. Removed the hardcoded rating; actual metadata remains. Narrow controls wrap; debug preview configurations compile. User 424/424, Admin 420/424 with the same four baseline failures. Existing Home row bodies, episode callbacks, playback and sheets remain unchanged. No APK/install or rendered/device acceptance. Stop before stage 3 (Show menu) pending the user's next decision. See PODCAST_SHOW_PRESENTATION.md.

## Latest implementation — podcast Library stage 1 (2026-10-15)

Following/unfollowing now adds/removes the exact show in Library through the existing shared persistent store. Existing follows, list/grid, Podcasts filter, search and offline local display are connected. No playback changes. User 424/424; Admin 420/424 with exactly four established failures; nine new tests pass in both flavors and both debug variants compile. No APK/install; nine artifacts unchanged. Device/viewport acceptance remains pending. Stop here: stage 2 (Show page UI) and subsequent menu/save-sheet stages await individual user approval. See PODCAST_LIBRARY_FOLLOWING.md.

## Latest implementation — Bluetooth cold Play resumption (2026-10-15)

The approved receiver and safe foreground startup/failure lifecycle are implemented. Fresh media Play restores the exact saved track/episode and position, with bounded resolution, cancellation, stale-owner protection and a real music resumption result. Bluetooth connection alone does not trigger Play. User 415/415; Admin 411/415 with exactly four known failures; all 15 new tests pass and both debug variants compile. SongPlayer and nine APK hashes unchanged; no APK/install. Actual phone/car acceptance remains pending; ADB has no connected device. See BLUETOOTH_RESUMPTION.md.

## Latest investigation — background termination (2026-10-15)

Physical F22 Pro records an excessive-CPU system kill. Live audio/session mismatch traced to AutoSessionPlayer dropping Java default listener callbacks; explicit forwarding now fixes the code and regression tests. User 400/400; Admin 396/400 with four established failures. No APK/install: repaired background behavior still needs device acceptance. The separate Bluetooth receiver/lifecycle follow-up is implemented above; the original CPU hotspot is not profiled. See BLUETOOTH_BACKGROUND_AUDIT.md.

## Completed / substantially completed

### Dynamic Podcast Home and following chooser (2026-10-15)

Implemented the explicitly authorized first version: real RSS episode recommendations/new releases/following and exact saved resume items, metadata cache, bounded/coalesced requests and partial-error states. Cover-only cards use the reference container ratio; play uses the existing typed guarded direct-audio entry point. The multiselect show chooser supports search, category pages, preselection, draft persistence between queries and atomic Done. Header reveal/Back/scroll/loading access are connected; music Home does not initialize the podcast cache. User 398/398; Admin 394/398 with exactly four known failures. All 19 new tests pass and both debug variants compile. No APK; nine existing artifacts unchanged. No live/device visual acceptance. See PODCAST_HOME_IMPLEMENTATION.md and CURRENT_WORK.md.

### Home filter sizing and existing podcast access (2026-10-14)

Home filter pills adjusted to the user's new original screenshot measurements: 32 dp default height, normal text, 16 dp horizontal padding and #333333 inactive fill. Selected #2CF261 matches both supplied images. Home's Podcasts coming-soon interception is removed, exposing existing Podcasts/Following content and Hub/Show navigation. The planned dynamic episode feed and chooser remain future work. User 379/379; Admin 375/379 with exactly four known whitelist failures. Both debug variants compile; nine APK artifacts unchanged. No APK/install or rendered device acceptance. See HOME_UI_ALIGNMENT.md and CURRENT_WORK.md.

### Waze button bounds and independent artwork (2026-10-14)

Implemented the approved movable-button approach: physical right-side default, safe-area drag bounds and saved relative positions per orientation. Identifiable input/menu/modal windows suppress the overlay without stopping audio. Mini-player/list artwork now checks exact model permissions without phone-screen cache warmup, and handles verified background ownership. All 21 new tests pass in both flavors; both debug variants compile. Full run: User 378/379 (live NewPipe HTTP 403, passed on isolated retry); Admin 375/379 with exactly four known whitelist failures. No APK/install; nine original artifacts unchanged. No live Waze acceptance: accessibility-silent menus and actual viewport/background playback require device evidence. See WAZE_OVERLAY.md and CURRENT_WORK.md.

### Android Auto presentation and controls (2026-10-14)

After the user's successful DHU test, Home no longer adds a continuation folder; Recently, Liked Songs and Browse use the requested icon/cover treatment. Player adds direct save-to-liked, existing song-radio recommendations and OFF/ALL/ONE repeat, with owned-item metadata/artwork and controller event updates. Query history is available in Library and matching search suggestions; the empty native search screen is still blocked by Media3 1.3.1's empty-query contract. User 358/358; Admin 354/358 with exactly four known failures; all 15 new tests pass and both debug variants compile. No APK/install, nine original artifacts unchanged, and no live acceptance of this follow-up. See ANDROID_AUTO.md and CURRENT_WORK.md.

### Android Auto online content follow-up (2026-10-13)

Home/Recently/Browse/Library roots now project the phone feed, online category/podcast content and saved library. Combined online search returns exact artists, playlists, albums, tracks, shows and episodes, with bounded coalescing and local offline matches. Typed persisted collections, real album IDs, grouped grid hints, distinct icons and whitelist-aware collection art replace the first local-only browse surface. User 343/343; Admin 339/343 with exactly four known failures; 45 new tests pass. No APK generated; nine existing artifacts unchanged. New DHU/live playback acceptance remains pending. See ANDROID_AUTO.md and CURRENT_WORK.md.

### Android Auto media integration (2026-10-12)

Extended the pre-existing MediaLibraryService/automotive descriptor with car browse menus, podcasts, persisted exact selection tokens, library search/strict voice selection and local whitelist-aware artwork. Controls use the same ownership/progress/external playback pipeline. Ongoing playback survives phone-activity dismissal. User 298/298; Admin 294/298 with four exact baseline failures. No APK generated and no live car acceptance claimed. See ANDROID_AUTO.md and CURRENT_WORK.md.

### Player visual/gesture regression follow-up (2026-10-12)

After device feedback, Player gradient colors are fully opaque, the normal progress track uses the artwork's 456dp container, and other controls retain their widths. Artwork navigation now commits after the pager fling/snap completes using real drag events and per-gesture tickets, with existing playback identity/token guards preserved. Downward dismissal yields to horizontal swipes. Compact dimensions and early artist loading remain. No APK generated; physical gesture/visual acceptance remains pending. See CURRENT_WORK.md and PLAYER_PRESENTATION.md.

### Player presentation and early artist information (2026-10-12)

Regular Player dimensions refined against supplied screenshots; compact dimensions preserved. Added nested-scroll pull-down dismissal over the actual previous screen. Artist metadata and permitted artwork start loading when Player opens, with cancellation/generation guards and no artist request for podcasts. Playback engine unchanged. No APK generated; rendered viewport/gesture acceptance requires the next separately requested device build. See PLAYER_PRESENTATION.md and CURRENT_WORK.md.

### Legacy history cleanup follow-up (2026-10-12)

Device feedback confirmed typed history navigation/filtering works. User requested cleaning unusable old entries automatically and removing the History header menu. A one-time marker in ListeningHistory now removes entries lacking exact identity, retaining valid typed listens. Playlists, liked items and progress/resume stores are not accessed by cleanup. Header three-dot menu removed. User 263/263; Admin 259/263 with exactly four known whitelist failures; all eight new migration tests pass in both flavors. Debug compilation and diff check pass; no APK generated. Device update/visual acceptance pending. See CURRENT_WORK.md.

### Recently Played history and saved check (2026-10-12)

Home's saved badge uses a vector check; Show all opens the existing History route with real local dates, Music/Podcasts filters, expandable listening groups, exact detail navigation and synchronized episode progress. Typed identities are persisted; legacy metadata-only entries remain visible without guessed navigation. Logging moved from tentative metadata updates to the owned Player's actual playing snapshot. Exact episode detail conversion retains Spotify identity. Final User 255/255; Admin 251/255 with the four exact whitelist baseline failures. All 22 new tests pass in both flavors; both debug variants compile; diff check passes. No APK generated or changed. See LISTENING_HISTORY_UI.md and CURRENT_WORK.md for viewport/device acceptance limits.

### Podcast playback foundation
- `MediaType.TRACK`
- `MediaType.PODCAST_EPISODE`
- `PlaybackSource` separation
- direct podcast routing
- RSS discovery/parsing
- enclosure validation
- feed caching
- episode matching
- DirectAudio playback
- podcast blocking from music-only YouTube/search/prefetch paths

### Podcast search
Spotify GraphQL search extended to shows and episodes.

### Podcast Hub
Real Podcast Hub with multi-query discovery, bounded concurrency, memory/disk caching and deduplication.

### Show / episode catalog
RSS/feed-based catalog and direct episode playback.

### Podcast navigation
Podcast actions route to podcast surfaces rather than artist surfaces.

### Podcast categories/topics
GraphQL topic metadata used for categories.

### Rich episode UI
Artwork, title, description, date, duration and actions.

### Following foundation
- persistent followed shows
- reactive revision
- repository wrappers
- Show follow/unfollow
- Home Following filter
- empty state
- followed-show shortcuts

### Small-display Player fix
Compact adaptation for short viewports such as Qin F22 Pro. Normal-phone UI should remain unchanged.

## Design / pending work

### Home reference alignment and podcast progress (2026-10-12)

Implemented Home cover sizing (large versus Recently Played), square artwork, subtle corners, spacing/background/header refinements and viewport bounds from the user's reference screenshots. Recent episode cards now show progress from the exact typed persisted bookmark, with separate completion/duration presentation metadata. Existing playback/resume behavior and artwork whitelist are preserved. User 233/233; Admin 229/233 with exactly four known whitelist failures. All 12 new tests pass in both flavors; debug sources compile; diff check passes. An initial live NewPipe 403 passed on the final full rerun and remains documented. No APK generated or changed. The emulator is offline, so rendered viewport and real playback/seek/restart acceptance remain pending. See HOME_UI_ALIGNMENT.md and CURRENT_WORK.md.

### Telemetry v2
Architecture researched; verify implementation status in repo.

### Waze / MediaSession podcast desync
Steps 1–3 approved; Step 4 hardening and local validation completed (2026-10-10): User 164/164; Admin 160/164 with exactly the four pre-existing whitelist failures; both debug APKs built. All 22 new integration tests passed in both flavors. Strict identity, ownership, exact Waze resume, safe MediaSession set/resumption and PlayerScreen guards are present. See MEDIASESSION_WAZE.md for the actual flow and CURRENT_WORK.md for validation. P0 remains open until real-device Waze/audio/viewport acceptance in P0_DEVICE_TESTS.md. P1 browsing has not started.

### Per-item progress follow-up (2026-10-11)

User reported positive Step 4 device results and requested durable resume for previously selected tracks/episodes. Implemented separate typed per-item position storage, outgoing-owner capture, automatic resume and completion/seek handling. Final User tests: 182/182; Admin: 178/182 with exactly four known whitelist failures; all 18 new tests passed in both flavors. Debug code compiled via unit-test tasks. No APK generated; existing APKs are unchanged. See CURRENT_WORK.md for scope and verification limits.

### External Play/Pause button synchronization (2026-10-11)

User confirmed per-item progress works on-device. A new lock-screen/notification pause issue was traced to missing ExoPlayer-to-CurrentSongState transport synchronization. SongPlayer now synchronizes shared controls from the owned primary Player, including pause/resume during buffering and state binding/player promotion. No layout or source-resolution changes. User: 194/194; Admin: 190/194 with exactly the four known whitelist failures; all 12 new regression tests pass in both. Debug code compiled via unit-test tasks, git diff --check passed, and no APK was generated or changed. Physical verification of this latest fix remains pending a separately requested build.

### Approved playback performance/reliability follow-up (2026-10-12)

Implemented shared in-flight YouTube source resolution, scoped expiring URL caches, bounded prefetch, startup timings/deadlines and visible music failures with token-bound same-item retry. Automatic recovery is bounded and respects pause; crossfade promotion updates retry ownership. Playlist metadata and songs share the first request, with ordered raw-offset pagination and retry after failure. Buffer thresholds, matching algorithms, underlying stream clients, podcast RSS/direct resolution and UI layouts are unchanged.

Final User: 221/221; Admin: 217/221, with exactly the four accepted whitelist failures and no new ones. All 27 new app tests pass in both flavors. Spotify: 22/22 (3 new); InnerTube: 6/6. Both debug variants compiled through unit-test tasks; diff check passed. No APK was generated; all 10 existing output APK hashes are unchanged. See CURRENT_WORK.md and PLAYBACK_PERFORMANCE_AUDIT.md for evidence, exact behavior and manual checks. Physical startup speed and the affected VPN user's failure remain unverified; no claim of a confirmed VPN fix.
