# Podcast Show menu — stage 3

Implemented in source on 2026-10-15 after the user's stage-3 approval. The user explicitly chose personal ratings stored inside SpotUI only. Stop after reporting this stage; stages 4–5 need separate user approval.

## Behavior and identity

The Show header overflow now opens `PodcastShowMenuSheet`, independent of episodes being loaded or available. `PodcastShowMenuTarget.fromRoute` accepts metadata only when its ID exactly matches the route. A missing/stale Show supplies no artwork or publisher; the exact route ID/name remain usable. Blank/unknown route IDs do not open a menu. No episode, first-list item, fuzzy name match or music model supplies the Show identity.

The sheet has exactly four actions:

1. **שיתוף** opens Android's sharing chooser with the exact Show HTTPS link and name. The header Share button uses the same helper. This opens the chooser without sending anything automatically.
2. **הפסקת המעקב** (or **מעקב** when not followed) calls the existing ShowViewModel follow/unfollow wrappers. The existing durable `PodcastFollowPref` revision updates Library/Home/chooser. Unfollow removes the exact Show from Library; playlists, saved episodes and playback are untouched.
3. **דירוג התוכנית** opens a personal 1–5 star editor. `PodcastPersonalRatings` SharedPreferences stores an integer keyed by exact Show ID, independently of follows and music stores. Saving runs off main and reports success only after `commit()` succeeds; invalid input and storage failure do not report success. Reopening preselects the stored rating. Back/dismiss before Save does not write. The UI explicitly says the rating stays in SpotUI and is not sent to Spotify; no public average/count is fabricated.
4. **הציגו את קוד Spotify** loads the real code graphic for `spotify:show:<id>`, retaining its full rectangular aspect ratio and contrast. Loading, retry and failure states are explicit; there is no fake code fallback. The bar-shaped menu glyph is only an icon. Code requests are cleared when leaving the page. Show cover artwork still goes through the existing exact-Show whitelist.

Share/code URLs require a raw 22-character base62 Spotify ID. Local/non-Spotify IDs retain follow/rating capability while those two Spotify-specific rows are disabled. Typed IDs/URLs, delimiters and arbitrary URLs cannot enter the fixed code endpoint.

## Reference presentation

Reference: `codex-clipboard-5a83fb2e-a8f7-4ff6-a07f-6875a853c341.png` (900×1600). Inferred density 1.5 matches stage 2. The reference sheet begins around y=1118, approximately 321dp tall.

- Background #1F1F1F, black 60% scrim, 16dp top corners.
- Handle 36×4dp, 10dp top/18dp bottom spacing.
- Exact Show header: 48dp square artwork, 12dp gap, 16dp side/bottom padding, 16sp normal title and 13sp muted publisher.
- Four minimum-56dp rows, 24dp icons, 12dp icon/text gap, 16sp normal labels; green follow/unfollow icon.
- RTL order, multiline labels, full-width row targets and vertically scrollable content for constrained viewports. Five 48dp star targets wrap rather than shrinking.
- Material bottom-sheet open/drag/dismiss animation; action dismissal awaits `hide()`. Internal rating/code pages crossfade with size transitions. No forced half-screen height for this compact menu.

Debug previews use production menu/star content at reference, small, narrow and large-font sizes. These previews compile but were not rendered; exact pixels and motion still need device acceptance.

## Spotify Codes capability verification

The [official Spotify Codes site](https://www.spotifycodes.com/) documents code generation/scanning. Its public `js/app.min.23e05782.js` constructs the endpoint `https://scannables.scdn.co/uri/plain/<type>/<background>/<foreground>/<size>/<uri>`. A live unauthenticated HTTPS probe on 2026-10-15 for the [official API example Show ID](https://developer.spotify.com/documentation/web-api/reference/get-a-show) returned HTTP 200, image/png, 7988 bytes. The app uses PNG, black background, white foreground, size 640, and the exact Show URI. This is the website's current public endpoint, not an authenticated ratings API or a guaranteed permanent app API. Runtime errors remain visible and retryable. No Spotify credentials/private data are sent. Actual in-app loading/scanning remains unverified on a device.

## Validation and limits

- Both User/Admin debug variants and previews compile via their unit-test tasks. No APK packaging/install; all nine existing APK paths/hashes unchanged.
- Full run: User 434/435, with the existing live NewPipe HTTP 403 test failure; that test passed on one isolated retry (1/1). The retry does not replace the recorded full-run result.
- Admin 431/435: exactly the same four whitelist failures as stage 2, matched by class/method/message.
- All 11 new tests pass in both flavors: exact Show identity, stale/unloaded metadata, unavailable IDs, link construction guards, persistent local rating editing/isolation and failed/corrupt storage behavior.
- Existing deterministic music/direct-podcast/external playback regressions pass. The eight protected files (playback, ViewModel, stage-2 header/row, following store and episode sheets) are byte-identical to the stage-3 start. All six episode callbacks in both Show lists are unchanged. Diff check passes.
- ADB has no connected device. Live normal/small viewport layout, touch/animation, share chooser, code scan and audible playback on the new source remain unverified. No claim of physical acceptance.
- Evidence: `app/build/reports/podcast-show-menu-step3/validation-final.log`, `network-recheck.log`, preserved `full-run-User`/`full-run-Admin`, `results.json`, `scope-verification.json`, `reference-and-viewport.json`, `code-endpoint-probe.json`, `verify.py`.

## Next stage — wait for user

Stage 4: episode/song plus-to-green-check animation and destination sheet entry, with Your Episodes/bookmark artwork for episodes and Liked Songs/purple heart for songs. Stage 5: save-sheet expansion/snapping and complete destination list with Done/Cancel. Neither stage was implemented here; `SavedInSheet`, `SongOptionsSheet`, episode saving and episode menu callbacks are unchanged.
