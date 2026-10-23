# Library, new episodes, Home and collection presentation

Requested 2026-10-18. The user explicitly wants five separate stages and a report
after each, then their instruction before proceeding. Stage 2 was subsequently
authorized. On 2026-10-19 the user asked to continue from the stopping point and
the following stages, authorizing stages 3–5; report after each stage.
No APK packaging/install was requested.

## User screenshot correction (2026-10-19)

After reviewing the installed appearance, the user explicitly clarified: Radio
has **no search**, and Playlist search must match the existing Liked Songs control.
This supersedes the shared search behavior described in the original stages below.

Removed the Radio search item/gesture entirely. Playlist now begins at its header,
not at list index 1 behind an offscreen search item: that approach exposed search
when initial loading/short content forced the list back to zero. Search now uses
the same 56dp pull threshold/spring reveal pattern as Liked Songs, with 36dp-high
controls, 4dp corners, 13sp text, 18dp search icon, 8dp gap and 14dp sort padding.
LikedSongsScreen itself is unchanged. The new field has the same clear/cursor
behavior; search does not open simply because content has not loaded yet.

Radio artwork keeps the exact approved Home URL but displays the top of its square
image within the wide hero; the original printed cover title below the portraits
is cropped out instead of appearing underneath the UI title. A collection-only
Glide palette retains the original brightness (Radio samples its flat top band;
Playlist uses a real palette swatch) and cancels/discards old work on disposal or
permission changes. The global music-player Palette and Glide wrapper are unchanged.
The old double-darkening was removed. Header controls/metadata spacing, actual
first-track thumbnail and owner icon were refined; no fabricated saves/durations
or personalization labels were added. Actual artist names and track titles in
the Radio preview use distinct text colors and bidi isolation. Tapping this compact
preview expands the list; the extra standalone Show All button was removed.

Square cover shrink/fade and pinned title/Play remain, with indices adjusted for
the new structure. Both debug variants and nine preview configurations compile
(including the exact search row at normal/narrow large-text sizes). Full User
574/574, Admin 570/574: the same four known whitelist failures. No connected device,
rendered-preview/gesture/audio verification, APK packaging or installation.
Evidence: `app/build/reports/collection-reference-fix/`; the preceding Search
review-link fix is documented in SEARCH_IMAGE_APPROVALS.md.

**2026-10-19 outcome:** all five stages are implemented in source. Stages 2–5
were reported individually. Actual visual/gesture/audio acceptance is pending a
separately requested app build and available device; no APK was packaged/installed.

## Requested sequence

1. Library: match Search/Create icon sizes and the dark shadow below the header
   chips. Add the purple New Episodes collection with its filled green bell.
   It exists when at least one podcast is followed, disappears at zero follows,
   and cannot be removed manually.
2. New Episodes screen: reproduce the supplied screen and episode overflow menu.
   Aggregate real new episodes from followed shows, newest first, and update when
   new episodes are available. Continue Listening includes at most **two** partially
   played episodes, whether their shows are followed or not. Marking an episode
   complete hides it; show the white confirmation with green Undo for **six seconds**.
   Undo restores that same episode. Reference text: "מסומן שסיימתם לקרוא את הפרק."
   with "ביטול הפעולה". Menu reference: Share, Your Episodes, Add to playlist,
   Download, Mark finished, Go to podcast, Go to episode, Spotify code. Audit each
   existing capability and preserve exact episode/direct-audio semantics.
3. Bottom Home tab: tapping Home from the Podcasts filter must select All.
4. Playlist presentation: use the Beach Party references for the cover/header,
   search revealed by scrolling, cover shrink/fade, pinned playlist title and
   green Play at the left. User wrote "podcast" once in this paragraph, but its
   heading, screenshots and behavior specify **playlist**; this interpretation
   was stated in the initial summary. Do not change Show presentation implicitly.
5. Radio presentation: use the full-width artwork/fade and pinned header reference,
   plus relevant "ייתכן שתתעניינו בזה גם" recommendations. The user explicitly
   expands previously Home-only approved artwork to the same exact cover inside
   its playlist/radio screen. Add long-press image approval to recommendation
   covers. Implement this permission expansion only in this later authorized
   stage, preserving exact URL/item identity and unrelated artwork restrictions.

## Stage 1 implementation

`LibraryContent` derives a separate `LibraryItem.NewEpisodes` from the same durable
follow snapshots already observed by `LibraryViewModel`. It appears once in All
and Podcasts, supports Hebrew/English collection-name search, and is independent
of remote music loading/failure, saved-episode count and individual show-name
matches. Blank IDs do not create the collection. Existing music relative order,
leading system shortcuts and saved episodes remain. The derived collection is
never converted to `LibraryEntry` or written into a playlist/library cache.

List and grid render the same filled bell cover. Neither binds the playlist
long-press/delete menu. Last unfollow removes it through the existing revision
observer; reopening reads current follows rather than restoring a stale shortcut.
The dedicated screen/click navigation were added in stage 2. The stage-1 tile
did not expose a no-op button or navigate to another collection. Its subtitle
remains "פודקאסטים במעקב" rather than inventing an "updated today" timestamp.

`LibraryTopBar` uses 24 dp outline Search/Create artwork in separate 48 dp touch
areas. Search still toggles the existing input/clears it on close, Create still
opens the existing dialog, and the profile action is retained. The original 52 dp
default header height is preserved; the title can ellipsize on narrow/large-text
viewports without covering the actions. `LibraryHeaderSurface` draws a 4 dp black
fade below the filter row, without adding a spacer or changing list/grid geometry.

Reference measurements at the supplied 900×1600 resolution:

- Search visible bounds: x92–123, y56–86; plus x20–51, y56–87.
- Cover background: **#64397E**; bell: **#1ED760**.
- Bell occupies approximately 37% of cover width and 41% of cover height; the
  dedicated filled vector preserves its separate clapper and straight base.
- Shadow center samples below the header: #0A0A0A → #0D0D0D → #101010 → #121212.

References: user image 1 is SpotUI; images 2–3 are original Library, 4–6 New Episodes,
7–10 Playlist, 11–13 Radio, 14–15 current SpotUI Playlist/Radio. Image filenames
and measured values are recorded under `app/build/reports/library-new-episodes-stage1/`.

## Validation and limits

Production-composable previews cover 600 dp reference width, 360 dp normal,
240 dp narrow and 240 dp with 1.3× text; each includes list/grid collection
presentation and interactive Search/Create actions. Preview compilation does not
constitute rendered/device verification. ADB has no connected device. No UI
renderer/device or actual audible playback check is claimed.

Tests exercise the automatic collection's presence/search/type isolation and the
existing persisted follow observer through 0 → 1 → 2 → 1 → 0 → 1 follows, including
reopening and multiselect edits. Existing playlists with the same display name
remain separate and removable; they cannot recreate the automatic collection.

Final results: focused 18/18 per flavor; full User 545/545, Admin 541/545 with
exactly the same four preceding whitelist failures by class/test/message. Three
new collection tests and the expanded persistence cases pass. Both debug variants
and the four previews compile. All 90 non-target baseline files, including nine
existing APKs and the reboot-resumption fix, remain unchanged. No APK was packaged
or installed. Evidence: focused/full validation logs, XML, `results.json`,
`scope-verification.json` and `reference-measurements.json` in the stage report.

The music/direct-podcast/MediaSession sources, Home behavior, artwork permissions,
playlist/radio screens and Telegram Worker are outside stage 1 and unchanged.

Stage 1 was reported before the user authorized stage 2.

## Stage 2: New Episodes

Library navigates to `NewEpisodesScreen`. `NewEpisodesRepository` reads full
followed-show catalogs through the existing direct RSS path, with separate bounded
disk caching (4 MB per show; larger feeds remain complete in memory), two concurrent
feed requests, 30-second per-feed timeouts and cached results on failure. It
observes durable follows, history and progress. Visible refreshes run every minute
while respecting the existing resolver's five-minute XML cache; this is foreground
polling, not instant push. Unfollowing prunes the corresponding catalog.

`NewEpisodesProjection` orders exact typed episodes by publication date and groups
Today/Yesterday/This Week/older dates. Continue Listening derives from actual
partially listened history independently of followed shows, deduplicates against
the main list and contains at most two episodes. Completion uses a separate durable
exact-item decision, not a bookmark/seek or a live-player mutation. A white notice
with green Undo lasts six seconds; its token/deadline prevent stale Undo from
overwriting a later decision. Storage failure does not announce completion.

The overflow sheet implements the supplied actions using existing saved episodes,
custom playlists and exact Show/Episode navigation. Downloads require a unique
exact RSS GUID and audio enclosure; Android Downloads receives the file (it does
not become an app offline music download). Spotify code requires the exact typed
Spotify episode ID; RSS-only entries cannot fabricate one. Their Share action
uses title/show text. No podcast goes through music matching or video playback.

17 new tests per flavor cover full-cache round trips, feed/history projection,
publication dates, exact completion/Undo races/failure/deadline and download/code
identity. Both debug variants and six screen/menu previews compile. Full stage-2
result: User 562/562, Admin 558/562 (the four known whitelist assertions).
Evidence: `app/build/reports/new-episodes-stage2/`.

## Stage 3: Home resets to All

Only a bottom Home tap sends `HomeRefreshSignal.Request(selectAll = true)`.
`HomeViewModel` selects All before the existing refresh, so Podcasts/Following
close with their existing animation. Generic refresh callers keep the selected
filter. The bottom icon press animation and navigation graph behavior remain.
Both debug variants compile; Home navigation tests User 14/14, Admin 13/14 with
the established whitelist failure. Evidence: `app/build/reports/home-tab-reset-stage3/`.

## Stage 4: Playlist

`PlaylistScreen` delegates presentation to `CollectionContent`, retaining the
existing exact playlist loading, prefetch and music/direct-podcast playback
callbacks. Missing metadata no longer borrows an unrelated first track's cover.
The square hero shrinks/fades as the list moves; scrolling back reveals search
and sorting. A green Play control and the title pin above scrolled rows. Header
color derives only from permitted artwork, fading into the dark list background.
Actions wrap on narrow widths with full touch targets. Track overflow opens the
existing track menu; header actions reuse the collection menu. Durations/counts
use real loaded tracks; no invented follower/save metrics are shown.

Playlist loader tests pass in both variants. Production-content previews cover
600 dp reference, 360 dp phone, 240 dp narrow and 240 dp at 1.3x text. Both debug
variants compile. Evidence: `app/build/reports/collection-refinement-stage4/`.

## Stage 5: Radio, recommendations and cover scope

Radio-named collections keep the exact playlist identity and receive a wide hero,
large overlaid title, scroll fade/pinned bar and compact track preview. Expanding
or searching displays the real track rows; Play still uses the actual collection.
The name classifier uses separate Radio/רדיו words, not a substring such as
Radiohead; it changes presentation only and does not resolve a different entity.

Recommendations query the existing playlist catalog for up to three represented
music artists. They use real IDs, exclude the current playlist, deduplicate exact
identities and rank by textual catalog relevance, local listening affinity and
source rank, with at most three per owner/six total. Cancellation, bounded timeout
and retry retain partial real results without a random/fabricated fallback.
Cards use three columns at the reference width and two on narrower viewports;
long press opens the same exact-cover approval menu.

The user explicitly expanded Home-approved images to collection headers and
recommendations. `home_images` remains exact-URL scoped; only these renderers opt
in. Home routes carry the validated cover URL into the matching playlist; updated
URLs need their own grant. No Home grant enters the global URL whitelist. Song
rows, Search, Library, Player, Waze and Auto keep their original policies. Approval,
rejection, retry and revocation use the existing authenticated protocol/storage.

Six new JVM tests per flavor cover ranking/identity/diversity and scoped collection
permission/revocation. Both debug variants and three Radio viewport previews
compile. Final full run: User 567/568 with one transient external HTTP 403 in
`ExampleUnitTest.testNewPipeExtractorStreams`; its isolated retry passed, so all
568 passed across these runs. Admin 564/568 with exactly the same four whitelist
failures as stage 2. All 23 new stage-2/5 cases pass in both flavors. The final
menu-label-only edit was then recompiled in both debug variants.

Worker tests 16/16 and Wrangler dry-run pass. The only Worker delta is two caption
lines; the previous source-map content matches the recorded deployed-source hash.
Version `d74d92b4-80ae-445a-a755-2f27c31e315b` was published at 100% on
2026-10-19 00:19:29 UTC; deployment metadata and public health/whitelist 200 checked.
No secrets/webhook changes, live-source extraction, approval mutation or Telegram
messages. Evidence: `app/build/reports/radio-refinement-stage5/`.

## Final scope and device acceptance

Source snapshots confirm stable music/direct-podcast/resumption/Media3 engines and
unrelated filters/Search artwork policies were not changed by stages 2–5. Two
pre-existing release APKs changed concurrently and were left untouched. No APK
packaging or installation was run. ADB still has no device and no AVD is available;
compiled previews are not rendered or physical acceptance.

On a separately requested app build: verify Library 0→1→0 follows; feed refresh,
two history items and six-second Undo; episode menu exact audio; Home-to-All and
unchanged animations; Playlist search/scroll/collapse; Radio fade/recommendations;
long-press approval/rejection/retry/revocation and unrelated-surface blocking.
Use normal and narrow/large-text viewports, then actual music and direct podcast
playback. No further implementation stage is currently requested.
