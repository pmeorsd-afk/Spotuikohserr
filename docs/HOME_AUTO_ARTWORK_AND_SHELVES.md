# Home artwork in Android Auto and stable shelves

Requested and implemented in source on 2026-10-20. No APK packaging/install requested.

## Findings

Android Auto's folder and playable browse cards previously consulted only the
global/entity whitelist. They never consulted the separate `home_images` map,
so an approved Home mix/radio could still show the car's placeholder. The content
provider repeated that restriction, so changing only the advertised metadata
would not have fixed actual image delivery. The service also lacked a Home-image
permission change signal to invalidate an already returned browse page.

Read-only checks of the public Worker and GitHub endpoints returned the same
version (1391 at the initial audit), 98 Home decisions, 92 approved, no duplicate
URLs. No evidence of server divergence was found. Home polling only ran for
requests submitted on that device; an already open second phone would not receive
a decision made elsewhere. Opening a request could therefore use stale local state.

The user identified two Peer Tasi Radio URLs differing in `/en` vs `/en-GB` and
provided both images. The person on the right is different: these are distinct
composites for the same artist, not interchangeable review targets. Server records
show `/en` approved at 2026-10-18T06:49:39.239Z (09:49 Israel) and `/en-GB` at
2026-10-20T18:40:02.372Z (21:40 Israel). Both are currently approved independently.
No locale stripping, fuzzy title/artist matching or approval copying is introduced.
Image URLs alone do not establish equality of the underlying track lists.

The supplied phone images show the mix covers keeping their square size while
Radio underneath shifts. `LazyRow` measures the visible cards; publisher subtitles
used `maxLines = 2` but no reserved minimum. When a two-line caption entered or left
the visible window, shelf height changed and displaced everything below it.

## Changes

- `AutoArtworkPolicy` accepts an exact Home-image grant for car browse folders and
  playable cards. Both service metadata and provider read checks use it. It does
  not register global URLs or authorize an artist/song/entity. Shared Now Playing,
  phone Library/Search/Player and Waze keep their preceding policy.
- Browse artwork content URIs include the exact cover digest to avoid reusing the
  car host's bitmap when a folder changes its cover. The provider validates this
  digest against the stored app-issued entry; arbitrary URLs, extra parameters and
  changed-image tokens are rejected. Existing unversioned URIs remain compatible.
- Home decisions publish a `StateFlow` revision, including cache load/revocation;
  identical snapshots do not emit. The service calls `notifyChildrenChanged` for
  previously returned parents (bounded to 256), independently of Compose rendering.
  Browse initiates a throttled sync; while connected and Home has been requested,
  it rechecks once per minute. Existing playable tokens/source/queue are unchanged.
- Home/collection approval refresh runs only while RESUMED, once per minute even
  without a local pending request. Pending requests retain up to eight fast checks.
  Before dispatching a Home approval message, shared decisions are refreshed: an
  already approved URL is displayed as allowed without sending/recording pending;
  a failed refresh is retryable, and a synchronized rejection remains requestable.
- Home catalog subtitle slots now reserve two lines with `minLines`, preserving
  current font metrics, cover dimensions, spacing, click/long-click and navigation.
  The reservation scales with accessibility font size; it is not a fixed pixel
  shelf height. Recent-item captions retain their existing single-line setting.

The Media3 1.3.1 callback bytecode was inspected: default subscription delegates
to `onGetItem`. Current [MediaLibrarySession documentation](https://developer.android.com/reference/androidx/media3/session/MediaLibraryService.MediaLibrarySession)
confirms children-change notifications target subscribed browsers. Real host
refresh/caching still requires physical Android Auto acceptance.

## Validation boundary

Ten new JVM tests cover cold car/catalog recreation, all folder types, exact
locale-image isolation, browse-vs-player scope, revocation/digest changes, headless
revision delivery, cross-device snapshot import and request gating/failure/retry.
Stage 1 passed User 584/584 (full) and Admin 82/82 (focused).

Final full regression passed User 584/584; Admin 580/584 with exactly the four
previous whitelist failures (class/test/message compared to the preceding baseline).
All ten new tests pass per flavor. Existing music/direct-podcast/MediaSession
regression suites pass. Compilation of both debug and instrumentation variants
succeeded; the overall test command exits nonzero because of those four Admin tests.

Three Compose instrumentation tests scroll production Home shelves through short
and two-line captions, then assert unchanged shelf bounds and Radio anchor position
at normal/narrow/large-text sizes. Matching debug previews and both instrumentation
variants compile. No connected ADB device is available, so these gesture tests and
previews have not been executed/rendered. Source/API checks are not a car test.

No server deployment, Telegram message, permission mutation, APK packaging or
installation took place. Evidence: `app/build/reports/home-auto-artwork-20261020/`.
524 existing files were hash checked: exactly the eight intended source files
changed. All 14 APKs, Worker, global whitelist manager, music/podcast source engines,
and unrelated phone components match their pre-task hashes.

Device acceptance: show each exact approved Home cover in car Home; approve/revoke
while browsing and observe refresh; retry after a cold process launch; use a second
phone already on Home; check both Peer Tasi variants remain independent; drag the
mix shelf in both directions with large text and confirm Radio does not shift.
