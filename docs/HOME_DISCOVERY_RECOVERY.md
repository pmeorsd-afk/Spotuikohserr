# Home startup and missing discovery shelves

2026-10-21 follow-up to HOME_RECOMMENDATIONS.md. The user installed the preceding
implementation and reported a blank Home with an off-center blue loader, followed
by a feed missing Popular Radio, Recent History and Popular Albums/Singles.

## Reproduced causes

1. The production public catalog client disabled redirects. Live requests with its
   headers returned HTTP 302 from `/intl-he` to `/`, and from localized track,
   artist and playlist paths to their identical nonlocalized paths. Those errors
   became empty catalog results. The earlier Python audit followed redirects
   automatically, whereas the application did not; parser fixtures did not expose
   that transport mismatch.
2. Initial `getHomeFeed` waited for all remote sources. Even the supposedly local
   recent-listening path could start a full network build. The feed mutex was held
   across network calls, preventing an initial local snapshot from being published.
3. Background discovery only updated the engine cache. The visible-feed retention
   policy also discarded discovery sections that had not been visible before, so a
   late first result could remain hidden until leaving and returning to Home.
4. The history ranker excluded the leading mix IDs entirely. A small valid pool
   could therefore have qualifying recommendations but an empty history shelf.
5. A partial full refresh could remove previously verified popular shelves; an
   empty incremental radio response explicitly removed its existing section.

## Changes

`PublicSpotifyCatalogHttp` now follows at most three redirects/four responses,
only over HTTPS on `open.spotify.com`, with the same Home or exact entity type and
22-character ID. A locale prefix may change or disappear. Cross-host, login,
downgrade, different entity, loop and query/credential redirects fail closed.
Cookies/auth remain absent; response-size/time/anonymous-state/type checks remain.
`PublicSpotifyHomeSource` delegates to this transport, provides a disk-only Home
read, and logs a compact catalog failure reason without response bodies/tokens.

`HomeFeedDelivery` publishes cached/local Home before waiting for the remote
refresh. `updateRecentListeningOnly` never invokes a network source. The full feed
builder and existing recommendation fetches do network work outside the feed mutex.
Local/count writes retain the actual remote refresh timestamp; invalidation keeps
the usable snapshot available. The brief initial disk-read indicator is centered
and green on Home only; other screens and populated Home geometry are unchanged.

General public shelves publish before personal playlist verification completes.
Missing discovery shelves can now appear during the same visit. Existing discovery
rows retain their items/order, with late new sections appended to avoid shifting
existing carousels. The next Home resume/explicit refresh restores canonical order.
The first real catalog after a local-only Recents snapshot uses canonical order.
Previously verified general radio/albums survive an empty/failed refresh. Legacy
unverified album/history migration and exact image grants are unchanged.

Recent-history ranking prefers variety without dropping all valid matches solely
because they overlap the top mixes. It still requires verified track/artist
evidence; empty/unqualified history does not get fabricated recommendations.
Personal cache `rankingVersion=2` triggers recomputation immediately for the old
overlap policy, while decoding the old verified cache remains available offline.

## Validation

Evidence: `app/build/reports/home-discovery-repair-20261021/`.

- `before-files.json` hashes 559 existing source/document files before this repair;
  selected original sources are retained under `before/`. The complete pre-feature
  project backup documented in HOME_RECOMMENDATIONS.md remains intact.
- Transport regression includes localized Home/entity redirects, identity-changing
  redirects, loops, absent Location, chain limits, HTTP errors and nonanonymous or
  missing state. A separate opt-in live test executes the production OkHttp/parser
  path, not Python: it returned 20 popular albums, 20 radios and exact valid
  track/artist/playlist entities. `transport-results.xml` preserves this first pass.
- Focused tests: 62 pass per flavor; the opt-in live diagnostic is skipped in that
  offline run. Both production and instrumentation Kotlin variants compile.
  Recovery tests cover immediate local delivery behind a pending network gate,
  failed fetch retention, late shelves without replacing visible rows, empty slots,
  verified public retention, legacy migration, cleared taste and history overlap.
- Final regression: User 637/637; Admin 633/637 with the same four established
  whitelist failures (see `full-tests.log` and `full-results.json`). No tests were
  skipped. The live diagnostic also passed in both flavors, using actual public
  playlist tracks to build taste and populate personal mixes, radio and history.
  `final-live-results.xml` preserves the production HTTP/ranking evidence.
- Scope hashes compared 559 existing source/document files; only the intended
  Home files, Home cache test and documentation changed. Playback, source engines,
  image permissions and backend files remained byte-identical to the repair baseline.
- No connected Android device was available. Instrumentation/normal/narrow preview
  sources compile, but screenshots, physical startup timing, gestures, audio and
  car acceptance were not observed on a device. Unit regression covers music,
  direct-audio podcasts and external controls. No APK packaging or installation.

No changes to playback/source engines, RSS, MediaSession callbacks, artist/track
matching, shared Spotify session, image approval scope, backend or Telegram.
