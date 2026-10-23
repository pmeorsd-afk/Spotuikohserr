# Search category image approvals

## Broken review-link repair (2026-10-19)

The user's Pop request carried the exact URL stored in SearchScreen; it already
returned 404 before reaching Telegram. An audit found 19/21 old category URLs
were missing. Replaced 15 with their matching images from the public Hebrew
[Spotify browse grid](https://open.spotify.com/intl-he/search), preserving the
two working URLs and all category titles/colors/navigation. All 17 resulting URLs
returned an image with the same URL under a bounded GET. Upcoming Releases,
Israeli, Mizrahi and Jewish/religious were not present in that verified catalog;
their invalid URLs are now empty, keeping the category/placeholder and the honest
"אין תמונה זמינה לבקשת היתר" state. No guessed or unrelated image substitution.

`SearchArtworkLinkValidator` checks the exact HTTPS link before the existing send:
200/206, matching image MIME/signature, no redirect, max 32 body bytes, 5-second
connect/read and 10-second total timeout. Failed availability does not send a
message, set pending or alter permissions. It displays a retryable error. Decision
baseline capture still occurs immediately before dispatch; an actual successful
Telegram response remains the only reason to mark pending. This check cannot
guarantee that a remote image will never disappear later.

Existing messages cannot repair their embedded old URL; request again after an
app build containing the change. New URLs need their own exact approval; old
approvals are not transferred. The Worker and Search grant protocol remain intact.
No live deployment, Telegram message or approval mutation was performed.

Focused tests 21/21 per flavor, including six new HTTP/content/identity/failure
cases. Full regression User 574/574, Admin 570/574 with the same four established
whitelist failures. Both debug variants compile. No APK/install or device test.
Evidence: `app/build/reports/search-cover-link-fix/` and `collection-reference-fix/`.

2026-10-18. Stage 3 source completed; bot support deployed. No APK packaged/installed.

## Behavior and scope

Long-press a Search browse/category tile to open a dark RTL bottom sheet with the exact category title and cover plus **התר תמונה זו**. Ordinary clicks retain their existing category/Podcast Hub navigation. Grid dimensions, colors, typography and rotated cover placement are unchanged. The sheet uses the Home menu's 16dp upper corners, 36×4dp handle, 48dp thumbnail, 56dp minimum action rows and scrollable content for short/large-text viewports.

The Telegram request identifies the category and exact HTTPS image URL and offers Search-category approval/rejection plus an image-review link. Sending a request does not grant permission. The menu displays pending/approved states and allows manual synchronization. Search performs at most eight sequential checks at 15-second intervals while resumed and pending; it stops after a decision or leaving the foreground. Rejection permits another request, including repeated rejection; failed sends remain retryable. A new request captures the prior decision before dispatch so fast moderator decisions are not lost.

`SearchArtworkPermissions` consumes a separate `search_images` collection with `scope: search`, exact URL digest, status and timestamp. Only Search category tiles and their menu opt into these grants. Search results, Home, Library, Player, Waze and Android Auto retain their existing policies. Home grants never enable Search categories. The pure URL/digest helpers are shared with Home, but the reactive permission map, pending preferences and callback namespace are separate. The same exact approved URL can appear on multiple category tiles; another URL or changed cover needs its own approval.

The existing whitelist cache/sync imports and exports `search_images`. Missing/blocked/invalid/duplicate records fail closed; decision timestamps survive cache round-trips. No global URL registration, artist/track grant, playback change or APK update occurs from an image approval. Existing global permissions and Admin rendering policy remain independent.

## Bot

New `appr:search:`, `deny:search:` and `rem:search:` callbacks use the same existing authenticated webhook and channel-administrator check as Home. The exact reviewed URL must match the callback digest and an actual message button. The shared router saves durably to GitHub before announcing a decision. Search updates only `search_images`, preserving Home/artist/track entries; Home callbacks remain compatible. Approval exposes a revoke action. Failure to save leaves the original request actionable.

Live version `930625cb-2435-4e0d-a8bc-563365f667ce` was published at 100% using the official Wrangler CLI. Preflight verified the preceding known version `48caf043-f13c-47b7-b8f0-db53af2c78a6` and the existing `GITHUB_TOKEN`/`TELEGRAM_WEBHOOK_SECRET` names. No secrets or Telegram webhook settings were modified. Publication used the tested local source; postflight deployment metadata and local uploaded/dry-run bundle comparisons are recorded in the report.

The initial proposal to download live source and extract its Telegram credential was rejected by automatic review and was not executed. The workflow instead used standard Wrangler authentication and deployment metadata without extracting credentials. A subsequent temporary automatic-review usage limit was resolved after the user resumed the task. No real/synthetic approvals or Telegram messages were sent. Public postflight checks returned health 200, whitelist 200 (version 1201), and unauthenticated Home/Search callbacks 403.

## Validation and remaining acceptance

- Full User **535/535**, Admin **531/535**, with exactly the four established whitelist failures. All **15 new tests per flavor** pass: scope isolation, exact URL identity, cache persistence, request protocol, denial/retry timing and pending-state behavior. Existing music/direct-podcast regression tests pass.
- Worker **16/16**: existing Home tests plus Search approval, rejection/revocation, altered link, authentication, administrator check, durable-write failure and preservation of other collections. Wrangler dry-run succeeds.
- Both debug variants and normal/small/large-text production-menu previews compile. No rendered/device verification, APK packaging or installation was performed. All nine pre-existing APKs and 80 non-target protected files are unchanged. Only SearchScreen, whitelist import/export, Telegram transport entry point and Worker router changed among the protected existing files; new Search-specific sources/tests/previews were added.

Evidence: `app/build/reports/search-image-approval/` (source snapshots/diffs, test results/logs, protected-file checks, bundle build, deployment metadata and public verification). Reports/bundles may contain existing credentials and must remain private/ignored.

Device acceptance after a separately requested build: normal and small viewports; long-press versus scroll/tap; pending/manual refresh; approve then return from Telegram; reject/re-request; same URL remains blocked on unrelated surfaces; music and direct podcast playback. All three requested filter/Search stages have source implementations; no next implementation stage is inferred.
