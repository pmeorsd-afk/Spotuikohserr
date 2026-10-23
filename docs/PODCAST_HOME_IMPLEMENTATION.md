# Podcast Home and following chooser — 2026-10-15

2026-10-18 presentation follow-up: Home's large episode artwork is now a text/gradient cover, and Home/Following thumbnails use matching swatches without the library placeholder. Only permitted artwork supplies colors; missing/forbidden images use stable show palettes. Normal 852:360 geometry is retained, with accessibility text overflow handled by growing short covers. Feed data, navigation and playback remain as described below. See PODCAST_HOME_COVERS.md for current presentation and validation; the cropped-image description below records the original implementation.

The user explicitly authorized implementing both the dynamic episode feed and multiselect following screen after the header/access follow-up. This supersedes the audit's earlier wait-for-phase-approval state for these features. No APK packaging or installation is included.

## Implemented behavior

- Podcasts displays real RSS episodes: recommendations, dated new episodes (from followed shows when available), and a resume section only for exact saved incomplete episodes. Empty sections are omitted. Following displays the loaded followed-show episodes sorted by publication date; no invented dates or episodes.
- The large cover container follows the supplied video's outer 852:360 ratio, with 16 dp horizontal margins and cropped permitted episode artwork. It has no video player or preview. At 600/360/240 dp viewports the container is 568/328/208 dp wide and approximately 240/139/88 dp tall. Metadata and actions grow below the image rather than clipping into a fixed-height card.
- Cards open the exact registered episode detail; show links use the exact show ID. Play/Pause checks actual owned identity, uses the existing typed resume entry point and commits UI/queue only in its guarded prepared callback. Progress is read from the existing exact bookmark. Save, sharing and the existing options sheet are available. The new Following cards intentionally hide Download: the existing downloader resolves music sources and is not reused for episodes.
- The Podcasts pill stays in place while Following is revealed behind it over 220 ms, using relative layout for RTL/LTR. Header access is retained during music feed loading/failure. All/Music/Podcasts/Following have separate scroll positions; Back from Following returns to Podcasts, then to the preceding All/Music filter.
- Following's empty-state button, the shortcut + and the feed's selection action open a full-screen chooser. Existing follows appear immediately and are preselected. Search is debounced 350 ms; cancellation plus request generation prevents old results replacing newer queries. Categories use the existing topic/category catalog with explicit pagination. Selection survives query/category changes; Done applies only explicit net edits in one preference write/revision. Back without Done discards the draft. Unrelated follow changes made while the screen is open are preserved, as are existing follow timestamps/bookmarks.

## Data and resource bounds

`PodcastHomeRepository` adapts the existing AppRepository catalog and Hub discovery; no new backend, RSS parser or media resolver. Podcast services are created on entry to the podcast surface, not when rendering the music Home.

Each entry/refresh/page requests at most four show catalogs, with at most two RSS catalog calls in flight and eight retained latest episodes per show. Additional followed shows remain reachable through an explicit load-more action; the initial subset is not represented as the whole library. In-flight calls are shared, including across a cancelled reader. Catalog calls time out after 25 seconds once admitted; Hub discovery has a 20-second timeout. A failed refresh retains the old catalog and exposes a partial-error/retry state. These bounds limit show requests, not XML feed byte length; the existing parser still reads the full feed.

The metadata cache stores at most 24 shows, eight episodes per show, with a one-hour TTL. Identity, publication date, duration, artwork and description are stored; the internal `episode:show:guid` locator remains intact. No resolved stream URL is persisted as a playback shortcut. Restored snapshots validate exact show/episode identity and reject malformed/future-version data. Legacy Hub cache entries remain readable, with description/topics now retained in new writes.

Recommendation scoring uses followed IDs, actual podcast listening counts, selected show search history, known topics and publication recency. Verified completed bookmarks are excluded from recommendations; incomplete bookmarks provide resume items, including older episodes outside the latest catalog page. It does not treat the listening tracker's 75% completion count as a finished episode. A session seed adds bounded variety; composition does not rerank or issue requests. Explicit refresh/new ViewModel session changes the seed. Up to two recommendations per show and unique IDs across Home sections keep the feed varied without duplicated cards. There is no fabricated recommendation fallback, fuzzy episode mapping or new exposure-tracking service.

## Files and verification

- `data/podcast/`: catalog validation/loading, cache codec, scoring and repository adapter.
- `PodcastHomeViewModel`, `PodcastChooserViewModel`: lifecycle/request/draft state and guarded playback adapter.
- `PodcastHomeContent`, `PodcastChooserScreen`, `HomeScreen`, navigation: rendering and entry points.
- `PodcastFollowPref`, `PodcastHubCachePref`, `PlaybackStatePref`: batch following, preserved topics and a read-only progress accessor. Existing music/RSS/source engines, whitelist rules, Waze and telemetry remain unchanged.
- `EpisodeCardItem`: optional download-action visibility; existing callers retain their prior behavior.

19 new JVM tests cover coalescing, concurrency, cache expiry failure, reader cancellation, catalog size/identity, JSON persistence, stable/diverse ranking, exact completed/resume handling, older saved episodes, no duplicate sections and real batch-follow preference writes/revisions. Existing music/direct-podcast/ownership suites are also run in both flavors. Final counts and evidence are recorded in CURRENT_WORK.md and app/build/reports/podcast-home-implementation/.

No online ADB device is available. Unit tests and debug compilation do not validate rendered animation/font metrics, live authenticated discovery or physical playback. Device acceptance after a separately requested build:

1. Compare normal/small viewports to the reference: stable primary pill, behind-pill Following reveal, cover ratio, scrolling and bottom controls.
2. Open Podcasts while Music is loading/failing; navigate between filters, detail and chooser, and verify Back and scroll restoration.
3. Rapidly change search queries/categories; toggle several shows, return between searches, Done, reopen, and cancel a second draft. Verify no duplicate/lost follows.
4. Verify cold/warm cache, offline/partial RSS failure, Retry and loading additional shows beyond the initial four. Unknown dates must not be advertised as new.
5. Play/pause/resume an exact episode, switch quickly to another and to music, and confirm artwork/audio/queue agree. Finish an episode and verify recommendation exclusion on re-entry/refresh. Check permitted and blocked artwork without changing whitelist policy.
