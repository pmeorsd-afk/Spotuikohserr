# Home cover image approvals

Updated 2026-10-19. **Source implemented and tested; live bot deployed after explicit user approval. No APK packaged or installed.**

## Android Auto browse expansion and cross-device refresh (2026-10-20)

The user explicitly expanded exact approved Home covers to Android Auto browse
cards. Service metadata and content-provider reads now opt in, with digest-versioned
URIs and permission-change notifications. Shared Now Playing, phone Library/Search/
Player and Waze remain excluded. Home now checks while RESUMED even without a local
request; a send first synchronizes and skips an already approved image. Different
locale URLs are not merged: supplied Peer Tasi examples show different composites,
and each has its own approved record. No Worker/deployment change is required.
This supersedes the older Android Auto exclusion below only for browse cards.
See HOME_AUTO_ARTWORK_AND_SHELVES.md for findings, tests and acceptance limits.


## Explicit collection expansion (2026-10-19)

The user now authorizes the same exact Home cover in its Playlist/Radio header
and collection recommendations. These surfaces opt into `isAllowedInCollection`
without calling `allowImageUrl`. Home navigation carries its validated exact
cover URL so a generated Home mix cover is retained inside the collection.
Recommendation cards use actual playlist IDs and reuse the existing long-press
menu and bounded foreground approval synchronization. A changed URL needs a new
grant; rejection/revocation removes this scoped permission. Existing global/Admin
permissions remain independent. Library, Search, track rows, Player, Waze and
Android Auto do not opt into Home grants. The shared menu now says "התמונה מותרת".

Requests explain the expanded cover scope. Only two Worker text lines changed
from the preceding deployed source, verified against its local source map/hash;
protocol, authentication, storage, secrets and webhook are unchanged. Worker
tests 16/16 and dry-run passed; published version
`d74d92b4-80ae-445a-a755-2f27c31e315b` at 100% on 2026-10-19 00:19:29 UTC.
Deployment metadata and public health/whitelist HTTP 200 verified. No live source
or credentials were extracted and no Telegram message or approval was sent.
Evidence: `app/build/reports/radio-refinement-stage5/`.

The original Home-only implementation/history below is superseded only for these
explicit collection surfaces; all other scope isolation continues unchanged.

## Re-request after rejection (2026-10-18)

The user confirmed live Home-image approvals work, then reported that an accidentally rejected cover still showed "request sent". The client previously retained its ten-minute pending flag without reading rejection decisions. Pending records now capture the exact image's existing decision before dispatch; a synchronized newer rejection releases the request immediately, restoring "התר תמונה זו". A repeated request records the previous decision and waits for a new one, including repeated rejections. Existing pending records without decision metadata are released by a known rejection. Rejection never grants the image.

Home decisions retain the Worker's `updated_at` value through cache export/reload. An approval also ends pending; unrelated images and identical repeated syncs do not end a new request. Capturing before dispatch covers a fast moderator decision arriving before the send callback. Foreground polling stops when no requests remain pending. No Worker deployment/configuration or menu geometry/style changes are required.

Validation: User 520/520; Admin 516/520, exactly the four established failures; all nine new retry/persistence/identity tests pass in both flavors. Both debug variants compile via unit-test tasks, including existing music/direct-podcast regressions. Home/menu/filter layouts, Worker code and all nine APK paths/hashes remain unchanged. No APK/build installation or device validation of this follow-up. Evidence: `app/build/reports/home-image-retry/`.

## User behavior

Long-press a cover in Home All/Music (top grid, shelves, artist cards/header, and legacy album/artist fallback cards) to open a dark RTL bottom sheet. Ordinary taps retain their existing navigation/playback behavior. The sheet uses a 48dp thumbnail, 16dp top corners, 36×4dp handle, 56dp minimum action rows and scrollable content for short/large-text viewports. It offers exact collection sharing, playlist Add to Library, opening the selected item, existing track options, and **התר תמונה זו**. Unsupported Spotify Premium/Jam actions are not presented as functioning features. Podcast text/gradient feed covers and the Podcast/Following filter animation are unchanged.

Only actual HTTPS artwork can be requested; missing/local/invalid URLs cannot be approved. The existing Telegram transport sends plain text plus a direct image-review link, Approve for Home and Reject buttons. Names are not parsed as Markdown. Sending does not grant permission. Successful requests suppress immediate duplicates for ten minutes; failed sends remain retryable. A pending sheet exposes a manual refresh; Home checks up to eight times at 15-second intervals after a request or foreground return. Each check awaits completion before the next; no polling loop remains active in the background. Actual appearance follows whitelist synchronization, not a claimed instant push.

## Scope and persistence

`HomeArtworkPermissions` holds a separate reactive map. `home_images` entries contain `id` (first 24 bytes of SHA-256 of the exact URL, hex), `url`, `scope: home`, and explicit `status`. Query strings/path case are retained. Only exact approved URLs qualify; changing a playlist's cover requires another approval. The same approved URL may appear on multiple Home cards, but its item/artist/playlist is not granted permission.

Only Home rendering and its contextual menu opt into this map. A Home grant never calls `allowImageUrl`, never modifies artist/track permission entries, and never enables the same URL in Search, Library, Player, Waze or Android Auto. Existing global permissions continue independently. Playlist Add to Library uses exact identity and the existing Spotify mutation; its cached artwork is checked against the original global policy rather than the Home grant.

The map is read/written with the existing whitelist sync/cache. Missing/blocked/invalid/duplicate entries fail closed, and missing `home_images` clears scoped grants. The manager's stale-version guard precedes replacement. Export now preserves `currentVersion` rather than hardcoding version 2, so an exported cache containing new grants remains acceptable to version-aware consumers. Schema version 2 remains additive/backward-compatible. No source engine, playback ownership, shared Glide wrapper or podcast resolver changes.

## Bot protocol

`home_image_approval.mjs` validates the exact image link/digest and configured channel. Callback prefixes `appr:home:`, `deny:home:`, `rem:home:` fit Telegram's 64-byte limit. New Home callbacks require a server-only `TELEGRAM_WEBHOOK_SECRET` (32+ characters) matching Telegram's header and a successful `getChatMember` check returning channel administrator/creator. The bot must itself be a channel administrator. These checks follow the [Telegram Bot API](https://core.telegram.org/bots/api#setwebhook); administrator lookup is documented under [getChatMember](https://core.telegram.org/bots/api#getchatmember).

The Worker updates only `home_images` through its existing GitHub SHA/retry writer. It preserves other collections, increments the existing version, and updates memory only after successful durable storage. It then confirms the decision in Telegram. Approval retains a Revoke button; rejection/revocation stores `blocked`. Failed persistence leaves the request/buttons available for retry and never announces approval. Home actions do not enter the legacy artist/track/GAS path. Existing other callbacks/telemetry are unchanged.

## Validation and activation boundary

- User: **511/511**. Admin: **507/511**, exactly the four established whitelist failures by class/test/message. Seven new permission/identity/payload tests pass per flavor.
- Worker: **8/8** offline tests using the real router and mocked network. Approval, rejection/revocation, auth, altered URL, non-admin and failed durable write covered. The unused Cloudflare DurableObject runtime is stubbed only for Node.
- Both debug variants and three production-content previews compile. Wrangler dry-run bundles successfully. No APK/install; nine original APKs unchanged. No connected ADB device, so actual small/normal rendering, long-press/scroll and audible playback are not claimed as device-tested. Existing music/direct-podcast regression suites pass.
- Playback/cover/filter protected snapshots remain unchanged except the intended Home/whitelist/transport targets. The complete Podcast filter block, including animation, remains source-identical.

Evidence: `app/build/reports/home-image-approval/` (results/XML, before snapshots, scoped diff, Worker tests/build, live backup/comparison). Baseline comparison found live Worker code equivalent to the pre-task source except its GitHub credential. That credential must be preserved as the `GITHUB_TOKEN` Worker secret during deployment; never replace it with the stale local literal. Private ignored backups contain existing credentials and must not be published.

**Live activation completed.** The earlier automatic-review rejection was resolved by explicit user authorization to publish the live bot, configure the required Cloudflare secrets and update Telegram webhook authentication. Fresh checks confirmed the live source still matched the saved baseline, no existing secrets were present, the webhook target was unchanged and the bot was a channel administrator. Cloudflare authentication was refreshed through the existing Wrangler login; the public endpoint requires a normal application User-Agent rather than Python's default agent.

Configured `GITHUB_TOKEN` using the preserved live value and a newly generated `TELEGRAM_WEBHOOK_SECRET`; secret values were passed to Wrangler via stdin and were not printed. Telegram's existing webhook URL, allowed updates and maximum connections were preserved, with `drop_pending_updates: false`. Published Worker version `48caf043-f13c-47b7-b8f0-db53af2c78a6` at 100% on 2026-10-18 06:00:20 UTC. The post-secret/pre-code version `743c3f86-140e-45e7-a7c6-562f722df597` retains the prior code with the configured secrets if code rollback is necessary; the original pre-task version remains `6e7cd238-3212-4fa1-bbc3-9d9db685cedc`.

Live checks completed at 06:00:28 UTC: downloaded deployed source matches the uploaded bundle; health and whitelist HTTP 200; whitelist version 1061; unauthenticated Home callback HTTP 403; Telegram webhook unchanged with zero pending updates and no reported error. Both configured secret names are present. Worker tests rerun: 8/8. No real/synthetic image approval, whitelist mutation or Telegram message was sent during deployment. Evidence: `live-preflight.json`, `activation.json`, `worker-deploy.log` and private before/after source snapshots under the ignored report directory.

Next is actual approval acceptance using a user-selected Home cover from a build containing the Android changes, after the user separately requests an APK build/install. Server deployment does not update an already installed Android application. Small/normal viewport and real app-to-Telegram round-trip acceptance remain pending; no APK was built or installed during activation.
