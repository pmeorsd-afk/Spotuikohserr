# Android Auto media integration

2026-10-16 stage 4: Save is now first in the car custom layout, allowing the host to use it beside standard Play/Pause and Next in the dashboard; Radio/Repeat remain available in the full player. CarConnection refreshes/restores the shared legacy order, and modern car controllers receive their layout on connection/update. Saving directly uses existing Liked Songs persistence/sync, with exact identity and idempotent repeated taps. User 500/500, Admin 496/500 (four known failures), eight new tests pass per flavor; all 64 protected hashes and nine APKs unchanged. No APK/install/device; rendered layout and background/controller acceptance remain pending. No phone Compose, music or direct-podcast engine changes. See ANDROID_AUTO_REFINEMENT.md.

2026-10-16 stages 2–3 supersede the earlier Home/search presentation below. Search artists are dedicated playable actions tied to exact persisted artist IDs (other artist folders remain browsable). Home/Music starts with real successful-playback history, not the phone's mixed recent/saved shelf, and places actual server-provided Recommended stations next. Existing remaining sections stay on Home. Browser notifications update live history/feed changes; bounded shared loading preserves cached/local content. Auto-only #2CF261 theme added. A–Z removal remains unresolved; colors/contrast and live station availability require DHU. Final User 492/492, Admin 488/492 (four known failures), all 20 new stage-2/3 tests pass, no APK/install/device. Stage 4 was subsequently authorized and is recorded above; see ANDROID_AUTO_REFINEMENT.md.

2026-10-16 stage-1 refinement supersedes the previous radio/presentation behavior below: Radio starts a new recommendation after source preparation; car-only controller permissions hide Previous/public queue to fit five actions. Standalone permissions return on disconnect. Seeking remains enabled and host progress geometry is unchanged. User 472/472; Admin 468/472 (four unchanged baseline failures), no APK/install or connected device. See ANDROID_AUTO_REFINEMENT.md for implementation details and the subsequent stages 2–4.

Updated 2026-10-14 after the user's original/SpotUI DHU comparison. The user confirmed the prior online-content integration works in DHU. The latest presentation/player follow-up has been compiled and unit-tested, but has not been packaged, installed or exercised in DHU.

## Architecture

The existing Media3 1.3.1 MediaLibraryService, automotive manifest descriptor and shared SongPlayer ExoPlayer are retained. Android Auto renders its own media interface; phone Compose layouts are unchanged. This is projected Android Auto media support, not a separate Android Automotive OS application.

Four browsable root entries now match the requested structure: Home, Recently played, Browse and Library, with distinct local icons. Home projects the same HomeFeedEngine used by the phone, with contiguous section titles and grid hints; the existing repository home feed is the fallback. Recently retains exact listening tracks/episodes rather than inventing playlist context. Browse offers Music, Podcasts and six genre/topic entries through the existing category-playlist search and Podcast Hub. Library retains liked songs, playlists, followed artists, albums, followed podcasts/recent episodes and downloads, and now includes search history. Collection covers use grid hints; track lists and search use list hints. Category cards use fixed, plain-color local covers. Host geometry and circle rendering remain controlled by Android Auto.

Root lookup is immediate. Repository flows run on IO with a 12-second deadline, preserving the latest successful response or returning an error for retry. Browse callbacks have a 15-second overall deadline including lock wait. Projection and exposed-page persistence are serialized to prevent concurrent pruning from invalidating another response. Online search loading occurs outside that lock.

Auto does not request Media3 pagination. Home exposes its complete current feed; other lists expose 50 entries plus an explicit continuation folder. Media3 page arguments are also validated for other browsers. Saved albums use Spotify album IDs. Legacy name/artist folder IDs resolve only against an exact unique saved album. onGetItem resolves browsable folders too, as required by Media3's default onSubscribe.

## Identity and playback

AutoMediaCatalog hashes typed stable identity plus browse parent into an opaque token. Tracks/episodes sharing an integer UI ID remain distinct; the same track in different playlists keeps independent queue contexts. Metadata-only or contradictory snapshots are excluded. Only exposed pages are persisted. The separate AutoMediaCatalog preference retains at most 1,000 recently issued snapshots; unknown/evicted tokens fail safely and require rebrowsing. Search exposes at most 1,000 playable results so even a full-page request can be persisted. Playlist, likes, history and progress stores are not cleared or changed.

AutoBrowseCatalog separately persists up to 500 exact typed album/artist/playlist/show identities. It writes only exposed pages, in a batch, so a large library cannot evict its first visible page before selection. Album model/API/Home cache conversions now retain the real Spotify album ID; a numeric UI hash or title never substitutes for it. Missing legacy album IDs are omitted in the car until feed refresh supplies an ID. Artist selection uses the exact overview ID and top tracks, never the name-search first result. Shows retain the existing repository/RSS episode path. Home cache tracks now use the shared typed snapshot codec, preserving episode/show IDs and duration; ambiguous old entries are omitted, not reclassified.

Car selection enters the existing guarded external request flow. Tokens resolve from app-issued snapshots, never controller metadata/URLs. Conflicting types are rejected. Music retains SongPlayer.resumeResolvedPlayback and its empty-result compatibility branch. Podcasts retain DirectAudio resolution, Media3 set/prepare/play, ownership adoption and exact queue/state commit. onAddMediaItems still rejects arbitrary additions/replacements. onPlaybackResumption retains typed last-item identity and saved position.

Music selections retain the browsed music queue while the service is alive; mixed history/search entries do not add podcast episodes to music autoplay. The entire queue is not persisted: after process recreation, cached selection restores the exact singleton until rebrowsed. External podcasts retain the existing singleton policy; another episode is selected explicitly, with no new podcast autoplay.

Phone-task dismissal saves position and preserves playing/buffering audio. Paused, empty or ended services call stopSelf (an external binding can still keep them alive). Installed Media3 1.3.1 has no default onTaskRemoved override, so this policy is explicit. Force-stop is different from dismissing the activity.

## Search and artwork

onSearch/onGetSearchResult now use the existing combined Spotify GraphQL search for artists, playlists, albums, tracks, shows and episodes, merged with exact local liked/downloaded/recent/browsed matches. Online search no longer depends on listening history. The car skips the phone search's extra REST podcast fallback so a music search cannot wait on two unrelated podcast requests; GraphQL podcast results remain available. Phone search retains its default behavior.

AutoSearchCache coalesces identical trimmed queries and holds 12 completed snapshots for 30 seconds, shared by search/count/result callbacks. Failed lookups are retryable; callback cancellation does not cancel another waiter. Network failures retain matching known local results when available; otherwise callbacks return IO error rather than a misleading empty success. Voice playback accepts searchQuery only without an explicit ID/URI and requires an exact unique title from the same combined results. Partial matches may be browsed but cannot automatically select a first/fuzzy result. No autoplay occurs on connection.

Correct media types and local artwork URIs are supplied for tracks and collections. AutoArtworkProvider accepts only app-issued catalog tokens, rechecks the existing song/album/artist/playlist/show whitelist rules, fetches HTTPS art with Glide (512px, eight-second timeout), and exposes a read-only PNG pipe. Callers cannot supply arbitrary remote URLs or filesystem paths. Forbidden artwork uses the bundled placeholder; fixed folders use local resource icons. Phone artwork policy and playback metadata production are unchanged.

## Validation and limitations

The original 13 AutoMediaCatalog tests remain. This follow-up adds 45 tests: 15 typed collection persistence/batch/large-library tests, 11 exact model projection tests, 9 concurrent search-cache tests and 10 Home cache identity tests. Production helpers are tested; Android binder/Player delivery is simulated rather than instrumented.

User 343/343; Admin 339/343, with exactly the four existing whitelist failures by class/test/message. All 45 new tests pass in both flavors. Both debug flavors compile through unit-test tasks; diff check passes. No APK packaging; all nine existing APK paths/hashes are unchanged. Evidence: app/build/reports/auto-online/validation-complete.log, results.json and apk-verification.json. Spotify/InnerTube entries in the aggregate report are earlier module results, not new runs.

## Device acceptance before release

After a separately requested build, install over existing data. Test parked with a real Auto head unit or Google's DHU:

1. Connect with the phone app closed: Home/Recently/Browse/Library, distinct icons, no autoplay, safe empty/offline states. Home should show titled grid sections from the account's feed, not just two recent tracks.
2. Browse liked songs, playlists, exact albums, downloads and a list exceeding 50 items; verify continuation and selection.
3. Choose a middle playlist track. Play/pause, seek and next/previous must agree across car, actual audio, MiniPlayer and full Player.
4. Select a podcast after paused music, from recent episodes and a followed show. Same episode and saved position; rapidly request A then B and ensure late A cannot replace B.
5. Exercise Auto, steering-wheel and notification controls. Dismiss the phone activity while playing/buffering: car playback continues.
6. Recreate the process normally, then resume: same typed item and position. Cached browse tokens resolve exactly or safely fail if evicted. Do not equate Android Force stop with ordinary recreation.
7. Search an artist/track never listened to (for example the user's Hebrew query), open an artist, album and playlist result, and select a middle track. Search a new podcast and open its show/episode through direct audio. Repeat after service recreation, offline and with concurrent search/navigation. Voice-search unique exact, partial, duplicate and unknown titles; only a unique exact title may autoplay.
8. Verify permitted/blocked User/Admin artwork, offline images and timeout; image failure must not block audio.
9. Disconnect/reconnect USB/wireless, lock phone, change audio focus and recheck phone music, direct podcasts and Waze. Playlists, likes and bookmarks remain intact.

For sideloaded media test builds, Android Auto's own developer setting **Unknown sources** may need enabling. Android's general APK installation setting is separate. No app-side distribution bypass or Google Play approval is claimed.

## Player and presentation follow-up — 2026-10-14

The user tested the previous integration in DHU and confirmed browsing/playback work, then supplied the remaining presentation/control issues. This follow-up changes the car surface and session presentation; phone Compose layouts, source resolution, RSS, InnerTube, TrackVideoCache and telemetry are unchanged.

- Home (and its Music entry) returns the complete current feed without a continuation folder. Large library/collection folders retain their existing paging. Previously issued Home continuation IDs still open the remainder without introducing another continuation.
- Recently now uses an outlined clock/dot vector. Liked Songs uses the phone's purple #450AF5–#8E8EE5 gradient and small white heart. Browse categories are square, fixed-color PNG covers without people, exposed by an allowlisted local artwork provider and ordinary grid style.
- The player exposes Radio, Save, and Repeat custom commands. Save is idempotent: it adds the owned music track to Liked Songs, mirrors through the existing SpotifySync path, and changes to a check. It never opens a chooser or removes the like on a second tap. A pending different phone selection cannot receive the previous song's like state.
- Repeat cycles OFF → ALL → ONE → OFF using CurrentSongState. AutoSessionPlayer also handles a controller's standard repeat command and publishes repeat events. The underlying ExoPlayer still holds one stream and never receives REPEAT_ALL, which would loop that one song incorrectly. Existing queue-end/repeat behavior is retained.
- Radio requests the existing Spotify-backed recommendation pipeline using the owned track's exact Spotify ID. The current track continues while recommendations load; only a valid result replaces its upcoming queue. Token/queue guards discard late results after navigation or queue edits. Podcasts, malformed entries, duplicate Spotify IDs and ambiguous numeric queue-ID collisions are excluded. Failure leaves the current queue intact. The existing end-of-queue continuation extends the station when further recommendations are available; no new audio resolver, guaranteed-infinite catalog, station-save UI or independent learning/BPM algorithm is introduced.
- Session title, artist, album and permitted artwork come only from SongPlayer's actually owned snapshot. A separate eight-entry artwork catalog prevents player artwork from evicting advertised browse/search tokens. Crossfade/player swaps refresh presentation. Existing whitelist decisions still apply. Web playback uses its existing adapter; the new owned-ExoPlayer actions are unavailable there.
- Search history persists up to 20 real queries separately from listening history. Typing prefixes coalesce. Library → Search history reopens the exact query; nonempty search also includes matching saved queries. History suggestions are captured in the same cache snapshot as online results so result counts stay stable. Media3 1.3.1's legacy bridge rejects empty search queries before the app callback; therefore the initial empty Android Auto search screen does **not** show this history. No unsupported bridge workaround is claimed.

Android Auto owns final button placement, overflow, tint, spacing and corner treatment. Custom actions do not guarantee the original Spotify five-button arrangement or green rendering on every host. The local source-vector preview is `app/build/reports/auto-polish/assets.png`; it verifies vector paths/colors only, not DHU rendering.

Validation evidence is under `app/build/reports/auto-polish/`. New tests cover Home paging, persisted/matching search history, radio identity filtering and actual AutoSessionPlayer metadata/repeat/listener behavior. Final results are recorded in CURRENT_WORK.md. No APK is packaged or installed; original artifacts are hash-compared. ADB has no connected device, so live music/direct-podcast playback, background-controller delivery and rendered car/phone viewport acceptance remain pending.

For the next authorized device build, check: long Home scrolling without More; purple likes and colored Browse covers; Save from the car followed by checking the phone library; radio while changing tracks and queues; all three repeat states including playlist end; metadata during rapid skips/crossfade and background resume; exact query reopening after service recreation; podcast direct playback without music-radio actions; unchanged narrow/normal phone UI.

## Official references

- [Hierarchy/root limits and pagination](https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy)
- [Local artwork URIs](https://developer.android.com/training/cars/media/create-media-browser/media-artwork)
- [Grid/list and section-title hints](https://developer.android.com/training/cars/media/create-media-browser/content-styles)
- [Browsable search](https://developer.android.com/training/cars/media/create-media-browser/browsable-search)
- [Playback controls](https://developer.android.com/training/cars/media/enable-playback)
- [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu)
- [Real-car testing and unknown sources](https://developer.android.com/training/cars/testing)
