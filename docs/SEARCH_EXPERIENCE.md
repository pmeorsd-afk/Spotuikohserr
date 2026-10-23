# Active Search experience — 2026-10-22

The user supplied Spotify reference screenshots and explicitly asked to preserve
the opening animation of the idle search rectangle. They clarified that album +
saves directly to Library; only individual podcast episodes open the existing
save-destination sheet. Podcast shows and artists have no save button here.

## Presentation and interaction

`ui/search/SearchChrome.kt` contains the active header and common rows. Samples
from the 900 × 1600 reference images: body #121212, header #1F1F1F, input #363636,
secondary text #B3B3B3. At the reference density the input is 32 dp high inside a
48 dp toolbar; covers are 48 dp, ordinary rows 64 dp, titles 16 sp, subtitles
13 sp. Artists are circular; other covers have small rounded corners. X and +
have separate 48 dp targets. Existing artwork approval checks remain in force.

The editor uses `TextFieldValue` and the native Compose caret/selection/composition.
Raw input, including trailing spaces, is never trimmed. Only network requests are
normalized. The in-field X clears everything. The suggestion arrow fills the
field, puts the selection at the end and keeps editing; clicking suggestion text
submits. Live results do not require Enter. Input survives activity recreation.

Recents retain exact per-item removal. The clear-history pill is at the end of
the list and shrinks/rebounds before clearing; its animation is draw-only.
Tracks and episodes reuse `SavedInSheet`, including existing/new playlists. The
green saved state uses the same any-destination membership as the MiniPlayer.

The before-source snapshot verifies `SearchIdleBar`, `SearchBarTransition` and
`SearchBodyTransition` are unchanged. The body background was intentionally changed.
Evidence: `app/build/reports/search-experience/preservation.json`.

## Live data

The previous suggestions URL at suggestqueries-clients6.youtube.com returned 400.
The production client now uses suggestqueries.google.com/complete/search with
client=firefox, ds=yt, hl=he and gl=IL. The real production client was tested in
both flavors and returned Hebrew suggestions for אייל. This is Google/YouTube
autocomplete, not Spotify's private completion/ranking service. Suggestions are
real response strings, query-echo checked, deduplicated and capped at five.

Search remains debounced (150 ms results, 180 ms suggestions). Generation checks
and cancellation prevent earlier responses from replacing a later query or a
cleared field. Spotify results publish without waiting for YouTube completion;
existing merged sources then finish. Spotify playlist results, previously dropped
by SearchViewModel, now reach live and submitted results. Loading, empty and
all-source failure states are distinct; failures have a retry action.

## Collection identity and Library

Album and playlist + save the collection itself, not its individual songs.
`SavedCollectionPref` persists explicit choices separately from remote caches,
using collection type + exact provider ID. A delayed/empty refresh cannot erase
a guest save or resurrect a locally removed item. System shortcuts stay first.
The Library observes local changes, and album detail reads the same saved state.
Existing SpotifySync mirrors supported Spotify choices when possible; local
success does not promise that a remote account mutation succeeded.

New album recents retain an explicit collectionId, and Search/Library navigation
passes it through to the existing exact album loader. Name-only legacy recents
and YouTube album results without a supported exact album ID do not get a save
action that would guess a Spotify album. Exact YouTube playlists can save locally.
No automatic cross-provider name match or locale/image approval alias is added.

Main playback/source resolution, podcast RSS/direct audio, Radio previews,
InnerTube matching and Android Auto playback are outside this change. Existing
Search playback callbacks and recent-song restoration remain unchanged.

## Validation and device acceptance

Twelve new deterministic tests cover request normalization, stale responses,
malformed/error suggestions, production HTTP parsing, durable exact collection
choices, removal, typed identity and system shortcuts. A separate opt-in live
autocomplete test also passed in both flavors; XML evidence is `live-user.xml`
and `live-admin.xml` in `app/build/reports/search-experience/`.

The full regression run contains 668 tests per flavor: User 666 pass, 0 fail,
2 opt-in skips; Admin 662 pass, the same four established whitelist failures,
2 skips. Both Debug source variants and both instrumentation variants compile.
See `final-regression.log` and the JUnit reports for the final result.

Four normal/narrow/large-font previews and two production Compose tests cover
editing, native Hebrew cursor movement after spaces, suggestion insertion,
independent row buttons and clear-history animation. They compile, but were not
rendered/executed: ADB reports no connected devices. Physical keyboard/caret,
pixel comparison, gestures and real music/podcast playback still need device
acceptance. No APK was packaged or installed for this task.
