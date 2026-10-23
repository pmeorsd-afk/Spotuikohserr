# Podcast Library following and staged UI work

Updated 2026-10-16. Stages 1–3 were individually authorized. The user then approved stage 4 and explicitly asked to finish it and proceed to stage 5. All five stages are implemented in source; device/visual acceptance remains pending. No APK packaging/install was requested for these stages.

## Stage 1 — implemented

Following a podcast show adds the exact show to Library. Unfollowing removes that show from Library while preserving other shows, playlists, albums, saved episodes and playback state. Previously followed shows appear immediately without following again.

The existing `PodcastFollowPref` remains the single durable source of truth. `observeFollowedShows` maps its revision signal to snapshots; `LibraryViewModel` exposes the stream with an initial disk snapshot. Library does not copy shows into the remote music-library cache or convert them into albums/playlists. Show's follow button derives its state from the same persisted revision instead of an independent optimistic Boolean. Home and the multiselect chooser already write to this store.

`LibraryContent` projects typed saved entries, podcast shows and artists into one list with namespaced keys. Existing leading Liked/Downloads shortcuts stay first, followed shows retain their stored order, and music keeps its relative order. The existing sort control is not expanded into a new sorting feature. Both Library list and grid render shows; the Podcasts chip and Library search filter them. Tapping a show uses its exact show ID and name. Local followed shows remain available when the remote music response is loading or fails.

Artwork uses the existing exact-show whitelist policy and placeholder. No publisher is guessed from the minimal saved snapshot. Existing music/artist row and grid bodies and their dimensions are retained; podcast cells reuse the responsive square grid or 64 dp list-cover dimensions. No model-specific viewport checks were introduced.

## Validation

- Both User and Admin debug variants compile through their unit-test tasks; no APK packaging/install.
- User: 424/424 tests pass. Admin: 420/424; the four failures exactly match the preceding Bluetooth resumption baseline by class, test and message (existing whitelist expectations in the Admin flavor).
- Nine new tests pass in both flavors: six typed Library projection/navigation/filter tests and three persistence/observer integration tests. The two feature suites total 13 passing tests per flavor, including four earlier following tests.
- Coverage includes existing follows on subscription/reopening, live Show follow/unfollow, multiselect chooser edits, repeated follows without duplicates, blank/duplicate IDs, identical names with distinct IDs, Hebrew/English search, independent podcast filtering, retained music order and a stale remote snapshot that cannot restore an unfollowed show.
- Existing music, direct-podcast and external-controller regression suites pass in both flavors. No actual audible/device acceptance is claimed.
- Source-body comparison confirms existing music/artist rendering bodies are unchanged apart from indentation. `SongPlayer.kt`, `PlaybackService.kt` and all nine existing APK paths/hashes are unchanged. `git diff --check` passes.
- Evidence: `app/build/reports/podcast-library-step1/validation-complete.log`, `results.json`, `scope-verification.json`, `verify.py`.
- ADB was initially empty; a device appeared in the final connection check. The new source has not been packaged/installed, so rendered small/normal viewport checks, taps/navigation and live music/podcast checks of this change remain pending a separately requested device build. No new APK was generated or installed.

## Stage 2 — implemented

Show-page proportions, typography, gradient, supported controls and episode rows were refined against the references, including narrow viewport wrapping. User 424/424, Admin 420/424 with the same four failures; no APK/install or rendered acceptance. See PODCAST_SHOW_PRESENTATION.md for the comparison and validation boundary.

## Stage 3 — implemented

Dedicated Show three-dot sheet has the four reference options, Show cover/name/publisher and compact animated presentation. Share uses the exact Show URL; follow/unfollow updates the existing Library store. The user chose personal local ratings; a persistent 1–5 editor explicitly distinguishes them from Spotify ratings. The Spotify code action uses the verified real code-image endpoint with loading/error/retry. No episode/music options or identity substitution. All 11 new tests pass in both flavors; both debug variants compile. Full User 434/435 (live network test passed on isolated retry), Admin 431/435 with the same four failures. No APK/install or rendered acceptance. See PODCAST_SHOW_MENU.md.

## Stages 4–5 — implemented

4. Episode/song saving now uses an animated green check and opens the destination sheet. Episodes use Your Episodes with bookmark artwork and their own typed store/Library entry; songs use Liked Songs with the purple heart. Exact identity survives storage/reopening/playlist selection. Icons, colors, row dimensions and initial compact height/brightness follow the supplied references.
5. The sheet supports short-drag/fling expansion and smooth snapping, all local and paginated editable Spotify playlists, staged new local playlists, selection states, top Done/Cancel and bottom Done. Cancel discards uncommitted changes while preserving the initial plus save; failed destinations remain retryable. Tests cover persistence, legacy migration, exact identity, session-scoped remote state and viewport geometry. Small/normal/narrow production-component previews compile.

User 462/462, Admin 458/462 with the same four baseline failures; all 27 new tests pass per flavor. Both debug variants compile, six protected source hashes and all nine APK paths/hashes are unchanged. No connected device, rendered/gesture verification or live account mutations. No APK/install. See PODCAST_SAVING.md for complete behavior, evidence and the pending device acceptance checklist.
