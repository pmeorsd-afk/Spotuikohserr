# Home discovery from local listening

Follow-up: see HOME_DISCOVERY_RECOVERY.md for the device-reported locale redirect,
startup, late-publication and history-overlap fixes. It supersedes this document's
reject-all-redirects and re-entry-only publication behavior.

Implemented 2026-10-21. The user authorized a full project snapshot before this
change, then implementation of evidence-based Home discovery without requiring
a Spotify account. No APK packaging or installation was requested.

## Verified restore point

`C:\Users\Orel lugasi\Desktop\ספוטיפי אדישן כל העדכונים\SpotUI_before_home_recommendations_2026-10-21_03-21-21`

The `Spotuikohserr` subdirectory contains the complete project, hidden files,
`.git`, build outputs and existing APKs. The adjacent `sha256-manifest.json` and
`snapshot-info.json` record 44,923 files / 2,036,591,906 bytes. Every copied file
matched its source SHA-256; no transient files were skipped. Verification finished
at 2026-10-21 03:35 local time, before source edits. The older 20 October snapshot
was interrupted and is not the verified restore point.

## Public catalog audit

Cookie-free GET requests to `https://open.spotify.com/intl-he` and exact
track/artist/playlist pages returned HTTP 200 on 21 October. Their base64
`initialState` declared `session.isAnonymous=true`. The Home payload contained:

| Shelf | Exact section URI | Observed items |
| --- | --- | --- |
| Popular albums and singles | `spotify:section:0JQ5DAnM3wGh0gz1MXnu3B` | 20 |
| Popular radio | `spotify:section:0JQ5DAnM3wGh0gz1MXnu4h` | 20 |
| Featured Charts | `spotify:section:0JQ5DAzQHECxDlYNI6xD1g` | 4 |

Popular albums preserve the public shelf's typed album IDs and order, independent
of local taste. This is Spotify's public/regional shelf, not a claimed worldwide
numeric chart. The old Home `queryWhatsNewFeed` / search `2024` approximation was
removed. Other screens' album APIs remain unchanged.

Exact entity checks used track `4Nx17F3hhny6jw0CMOUQ65`, artist
`1IAEef07H0fd9aA8aUHUlL`, and playlist `37i9dQZF1E4wmWec21cCcJ`. The artist page
contained four real `featuringV2` playlist links. The playlist page returned 30
tracks out of a total of 50. Sanitized fixtures under `app/src/test/resources/home`
retain selected actual catalog fields only; they contain no sessions or tokens.

These public page schemas are not a stable documented API. Parsing requires the
anonymous flag, expected entity type, exact URI, and agreeing ID when supplied.
Missing/changed schemas fail closed and use a bounded previously verified cache.
No artist/playlist/track identity is inferred from a title or image URL.

## Ranking and labels

- Input: up to 100 recent records already maintained by `LocalListeningTracker`.
  Existing counters are unchanged: counted listens and completions, not mere
  playback starts. Podcast media types, episode URLs/IDs/show IDs and non-Spotify
  track IDs cannot become music seeds.
- Readiness: at least two distinct qualified tracks and three counted listens
  across them. A single repeatedly played track does not personalize every shelf.
- Up to 12 exact track pages resolve credited artist IDs. Selection reserves the
  five latest qualified songs alongside seven strongest songs, filling overlaps
  from the remaining strongest songs. Weight follows the existing local formula:
  `(plays + 1.5 * completions) * 2^(-ageDays / 14)`.
- Candidate pool: up to four explicitly linked playlists for each of the four
  strongest resolved artists, plus the public radio shelf; at most 36 distinct
  existing playlist IDs. There are no synthetic playlist IDs or fuzzy searches.
- A playlist needs at least two distinct returned tracks credited to artists in
  the listening profile. Ranking uses weighted artist matches divided by the
  number of returned tracks. Ties use stable IDs; at most two results per dominant
  artist and eight per shelf provide bounded diversity.
- `המיקסים המובילים שלכם` and `רדיו בשבילכם` contain verified personal matches.
  When evidence is insufficient, general discovery is labeled `פלייליסטים לגלות`
  and `רדיו פופולרי`. The general discovery refresh uses Featured Charts, with
  existing catalog playlist suggestions available as a cold/source-error fallback.
- The recent-history shelf uses the latest five qualified resolved songs and
  excludes the first three top-mix IDs. It is omitted if no suitable candidate
  remains. Radio names distinguish radio presentation only, never artist identity.

This selects existing catalog playlists; it does not generate Spotify Daily Mixes
or reproduce Spotify's private recommender. Content verification uses the public
track sample actually returned, not an assumed complete playlist. Coverage can
therefore be incomplete; unrelated results are not substituted to fill a shelf.
Taste remains local to this app/device and does not imply cross-device history sync.

## Refresh, caching, and visible stability

`PublicSpotifyHomeSource` has an isolated OkHttp client without cookies or auth.
Only fixed public Spotify paths with validated IDs are requested. Requests have
12-second call / 6-second connection limits, reject redirects and non-HTML, and
limit pages to 4 MiB. Entity metadata is cached for six hours; verified stale data
may be used on failure for at most seven days. Failed requests back off five
minutes. The public cache is pruned when it exceeds 160 entries.

`HomeDiscoveryRepository` performs at most three parallel reads, with a 90-second
background budget. Personal results refresh after six hours for unchanged taste,
or after a minimum 30-minute interval when taste changes. Failures back off five
minutes and preserve compatible verified results for up to seven days. The saved
seed IDs are only those actually resolved for that recommendation. Cleared or
unrelated history cannot restore a personal cache without sufficient seed overlap.

`HomeFeedEngine` performs expensive discovery outside its feed mutex. A history
change during a request is rechecked before application. The v2 feed migration
removes legacy unverified popular albums/history and relabels legacy mixes/radio.
Liked-song counts patch visible and prepared feeds separately so an older visible
feed cannot overwrite new recommendations awaiting display.

`HomeViewModel` refreshes discovery in one background job on initial loading,
listening revisions, explicit refresh and Home resume. Listening events can update
Recents immediately; mix/radio/history/album carousels keep their visible snapshot.
Prepared discovery appears on the next Home entry/resume or refresh. A first fetch
can finish after entry, so the next visit may be needed to see its result.

## Validation and scope

Evidence: `app/build/reports/home-discovery-20261021/`.

- Stage 1: 35/35 focused Home tests and both production Kotlin variants compiled.
- Full regression: User 619/619; Admin 615/619, with the same four established
  whitelist failures listed in `final-results.json` / `final-tests.log`.
- Final focused check: 46/46 per flavor after adding legacy-episode guards, recent-seed reservation
  and resolved-only cache seeds: see `final-focused.log` and
  `final-focused-results.json` (BUILD SUCCESSFUL). Includes both production and instrumentation Kotlin
  variants; test fixtures cover two different guest histories, deceptive names,
  exact typed identity, stable order, cache/reset/offline limits and public source
  parsing. These policy/source/cache tests are independent of Spotify login.
- No device was connected. Music/direct-podcast/external playback regressions ran
  as unit tests; actual audio, normal/narrow viewport rendering, and car acceptance
  remain unverified. Instrumentation sources compile but were not executed.
- No APK was generated or installed. All 14 existing APK hashes remain unchanged.
- Only existing `HomeFeedEngine.kt` and `HomeViewModel.kt` changed for this task,
  plus new Home policy/source/cache/repository/presentation tests and documentation.
  SongPlayer, MediaSession callbacks, RSS/direct audio, InnerTube/VISIONOS/matching,
  whitelist grants, artwork URL scope, Worker and Telegram sending are untouched.

Known Admin baseline failures (not introduced here):
`HomeNavigationAndTrackingTest.testKosherWhitelist_blocksUnapprovedPodcastImages`,
and `RecentItemsAndSafeAlbumTest.testKosherWhitelist_playlistWhitelisting`,
`testKosherWhitelist_blocksUnapprovedEpisodesAndShows`,
`testKosherWhitelist_podcastEpisodeWhitelisting`.
