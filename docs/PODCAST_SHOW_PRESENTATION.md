# Podcast Show presentation — stage 2

Updated 2026-10-15. User approved stage 2 only after completion of Library following. Stop after reporting this stage; the Show menu and save-sheet stages still require the user's next decision.

## Reference comparison and changes

Compared the supplied original `codex-clipboard-0cf7c0bb-f5e1-437f-a952-b688e277e95e.png` with SpotUI `codex-clipboard-3fde4d06-1087-4a31-aa65-bdc654736e30.png`. Both are 900 x 1600 pixels. The existing 96dp cover occupies 144 pixels, supporting an inferred 1.5 px/dp scale. Original show cover is approximately 168px square, episode covers 72px square and play circles 48px in diameter. These imply 112dp, 48dp and 32dp respectively. Measurements are reference analysis, not rendered acceptance of the changed code.

| Element | Previous Show page | Stage 2 |
| --- | --- | --- |
| Show cover | 96dp, 8dp corners | Up to 112dp, 4dp corners; scales down with available width |
| Header inset | 4dp top | 16dp top, 16dp horizontal |
| Title / publisher | 20sp bold / 13sp gray | 24sp bold, 28sp line height / 13sp bold white |
| Show metadata | Hardcoded 4.8 plus one topic | Actual available topics; podcast label when topics are absent |
| Follow pill | Green border/text/fill when followed | Gray outline, white label in both states, reference-sized padding |
| Header icons | Filled bell/share | Outline bell and existing outlined share asset, 48dp touch targets |
| Tabs | Inset text, fixed short 3dp underline | Edge-aligned text, 2dp underline matching tab width, selected semantics |
| Episode heading | List icon at physical left | Sliders icon first at physical right in RTL, weighted wrapping title |
| Episode cover / title | 56dp / 15sp bold, top aligned | 48dp / 16sp normal, vertically centered |
| Episode metadata | 12sp gray | 13sp white, real existing date/duration only |
| Episode actions | 36dp play circle, tightly sized click regions | 32dp play circle within a 48dp target; secondary targets also 48dp |
| Description and row spacing | Large gaps and duplicated inter-row padding | Two-line description retained, 4dp metadata/action gaps, 8dp next-row inset |
| Gradient extent | 460dp | 320dp with opaque blended midpoint; existing cover color extraction retained |
| List bottom space | 140dp content inset plus 130dp final spacer | Existing 140dp inset only |

The metadata model has no actual rating/count, so none is invented. Existing Episodes/About tabs and Share/Bell actions remain functional exactly as before; a recommendations tab, preview tile or settings action without an existing implementation is not added just to resemble the reference. Header overflow, episode menu, notifications behavior and save destinations are not reimplemented in this stage.

## Implementation boundary

- `PodcastShowHeader`, `PodcastShowTabs` and `PodcastEpisodeListHeading` are presentation composables; ShowScreen supplies its existing actions/state.
- `EpisodeCardItem` gains an opt-in `showPageStyle` path to `PodcastShowEpisodeCard`. Only ShowScreen's main and in-show search lists opt in. The existing Home episode-card body remains byte-for-byte unchanged.
- The same episode models, IDs and playback/save/download/share/more callbacks are retained in both lists. No player, resolver, ViewModel, download backend, music matching or MediaSession change.
- Existing artwork permissions and placeholders remain. The new episode component observes whitelist revisions; covers remain audio-only artwork.
- Library following from stage 1 remains connected. Following styling changes do not change its persistence or membership behavior.

## Small and normal viewports

At width 600/360/320/240dp the header cover is 112/82/72/72dp. The corresponding remaining title columns are 440/230/200/120dp. Header/title/metadata sections grow naturally; no device-model check or screen scaling is used. The action FlowRow can wrap on narrow screens and large fonts. Episode rows retain 48dp touch targets; when less than 240dp remains inside the action margins (with all four secondary actions), Play moves to a second row rather than being squeezed against another target.

Debug-only previews use the actual production components at 600 x 1067, 320 x 480, 240 x 480 and 320 x 480 with font scale 1.3. They use local placeholder artwork without Hilt/account/playback. The page remains scrollable and reserves its existing bottom-player space.

## Validation and limits

Both debug flavors compile through unit-test tasks, including the previews and vector resources. Final results and unchanged-file checks are recorded in `app/build/reports/podcast-show-step2/`: `validation-final.log`, `results.json`, `scope-verification.json`, `reference-and-viewport.json` and `verify.py`.

Final User: 424/424 pass. Admin: 420/424 with exactly the same four pre-existing whitelist failures by class/test/message as stage 1. Existing music, direct-podcast, following and external-controller regression suites pass. No implementation-mirroring unit tests were added for these presentation edits. All six episode callback bodies in both lists and the Home card body compare unchanged; the five protected playback/ViewModel/sheet files and all nine APK paths/hashes are unchanged. `git diff --check` passes.

The source/geometry checks and compiled previews do not constitute screenshot rendering or physical acceptance. No ADB device was connected during this stage; no APK was packaged or installed. Rendered regular/narrow/large-font previews, touch/scroll/RTL acceptance and audible music/direct-podcast checks still need a device build. Do not claim pixel-identical output from compilation alone.

Next stage: Show's three-dot bottom sheet, matching the reference options, after explicit user approval. Episode save/check animation and expandable destination sheet remain stages 4–5.
