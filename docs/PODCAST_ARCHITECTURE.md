# Podcast Architecture

## Status

2026-10-18 Home cover presentation: podcast feed cards use exact episode title/publisher over gradients extracted only from permitted artwork, with stable show-color fallbacks. This is a Home-only rendering change; direct-audio, item identity and all playback/save/navigation callbacks are unchanged. See PODCAST_HOME_COVERS.md for lifecycle, scope and device acceptance.

2026-10-16 saving follow-up: saved episodes now have their own full-model store and Your Episodes Library collection. Explicit legacy episode records are read through/tombstoned without guessing ambiguous data. Custom playlists preserve typed episode keys/metadata; Spotify playlist episode URIs are decoded before music mapping. Save destination drafts, session-scoped confirmed remote membership and exact Player save targets prevent episode/music identity leakage. Saved episode/mixed-playlist playback uses the existing resolved/direct-audio entry and commits presentation after preparation. SongPlayer, ShowViewModel and resolvers are unchanged. See PODCAST_SAVING.md for behavior, tests and pending device acceptance.

2026-10-15 follow-up: Podcast Home now composes cached bounded RSS catalogs into recommendation/new/following/resume sections. A full-screen multiselect show chooser applies explicit follow edits atomically. Both adapt existing repositories and typed playback; no additional source resolver or video path. See PODCAST_HOME_IMPLEMENTATION.md and CURRENT_WORK.md for implementation and device-validation limits. The foundation list below predates this follow-up.

Podcast playback/search/catalog support is already substantial and works inside SpotUI.

The goal of future work is to preserve the existing direct-audio path, not replace it.

## Core invariant

```text
PODCAST_EPISODE
    ↓
EpisodePlaybackResolver
    ↓
Podcast feed / RSS matching
    ↓
PlaybackSource.DirectAudio
    ↓
ExoPlayer
```

Never route a podcast episode through YouTube, InnerTube, VISIONOS, TrackVideoCache, or music fuzzy matching.

## Existing podcast infrastructure

The project contains concepts/components including:

- `EpisodePlaybackResolver`
- `PodcastFeedResolver`
- `PodcastRssParser`
- `PodcastRssEpisode`
- `PodcastEpisodeUiModel`
- conversion from Spotify Episode metadata to `SongsModel`
- direct audio playback
- local podcast/show context helpers
- show screens and episode cards

## RSS behavior

RSS parsing/resolution already works with data such as:

- GUID
- title
- description/summary
- publication date
- duration
- enclosure URL
- enclosure MIME type
- enclosure length where available
- episode image where available

Episode matching should remain safe and deterministic. Never choose the first feed item just to succeed.

## Podcast search / GraphQL

Spotify GraphQL `searchDesktop` has been used for podcast discovery.

The app has already been extended so search results can include:

- shows
- episodes

Podcast topics/categories are derived from Spotify GraphQL show topic metadata rather than unreliable/rate-limited REST endpoints in this environment.

## Podcast Hub

The app has a real Podcast Hub with:

- multi-query discovery
- bounded concurrency
- memory cache
- disk cache
- deduplication

Do not regress this flow while working on MediaSession/Waze.

## Show / episode UI

Episode catalog loading is based on RSS/feed resolution and supports direct episode playback.

Rich episode cards include artwork, title, description, date, duration and actions.

Show presentation stage 2 (2026-10-15) extracts the header/tabs/episode heading and adds a Show-only episode-card style used by the main and search lists. Existing Home cards and all episode action callbacks stay unchanged. The header uses actual topics rather than a fabricated rating; narrow controls wrap with full touch targets. See PODCAST_SHOW_PRESENTATION.md for measurements, compiled previews and the unverified device/rendering boundary.

Show menu stage 3 introduces a separate typed `PodcastShowMenuTarget` and `PodcastShowMenuSheet`; the Show overflow no longer converts the first episode to a music menu. Exact route IDs constrain loaded metadata, sharing and Spotify-code URLs. Follow/unfollow continues through the existing store. User-approved personal ratings live in separate `PodcastPersonalRatings` preferences, are clearly local-only, and never replace public metadata. Functional code graphics use the fixed verified Spotify Codes image route, while Show artwork retains the whitelist. Episode menu/saving and playback remain unchanged. See PODCAST_SHOW_MENU.md. Save-sheet/plus-animation stages 4–5 still await individual approval.

## Following

Podcast following exists at a foundation level:

- persistent followed-show snapshots
- reactive revision signal
- repository wrappers
- Show follow/unfollow state
- Home filters including Following
- empty Following state
- followed-show shortcuts

The 2026-10-15 Home follow-up adds cached RSS episode aggregation and atomic multiselect following; see PODCAST_HOME_IMPLEMENTATION.md.

Library stage 1 now observes the same durable snapshots through `observeFollowedShows` and `LibraryViewModel.followedPodcasts`. Typed `LibraryItem.Podcast` entries render in both list/grid, with a Podcasts filter, search and exact Show navigation. They are independent of remote music response/cache and use the show artwork whitelist. Show follow state derives from the same persisted revision. No migration or duplicate Library store is needed. See PODCAST_LIBRARY_FOLLOWING.md for validation and the later UI stages awaiting approval.

## Direct playback safety

For external playback:

```text
External identity
   ↓
SongsModel
   ↓
EpisodePlaybackResolver
   ↓
PlaybackSource.DirectAudio
   ↓
Playable MediaItem / Player commit
```

Only after a real playable source exists should UI/queue state move to the new episode.
