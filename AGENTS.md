# SpotUI / SpotUI Kosher — Codex Instructions

You are working on an Android music + podcast application built with Kotlin, Jetpack Compose, AndroidX Media3 / ExoPlayer, and a `MediaLibraryService`.

Before making non-trivial changes, read:

- `docs/PROJECT_CONTEXT.md`
- `docs/ARCHITECTURE.md`
- `docs/PODCAST_ARCHITECTURE.md`
- `docs/MEDIASESSION_WAZE.md`
- `docs/UI_UX_CONSTRAINTS.md`
- `docs/TELEMETRY.md`
- `docs/STATUS.md`
- `docs/CURRENT_WORK.md`

## Non-negotiable rules

1. Do not modify the working music playback pipeline unless the task explicitly requires it. Music startup/performance is already optimized and considered stable.
2. Never route podcast episodes through YouTube / InnerTube / music matching. Podcast episodes must use the existing RSS/direct-audio path.
3. Do not invent broad or unsafe fallbacks. Never choose `first()`, a random candidate, or a fuzzy match simply to make a request succeed.
4. Preserve exact item identity. Track, Podcast Episode, Album, Podcast Show, Playlist, etc. must not silently change into another entity.
5. Do not update UI metadata before playback identity/source is resolved. Avoid states where Audio != CurrentSongState != Queue.
6. For Media3/external playback, respect Media3 callback contracts.
7. Normal-phone UI must remain pixel-identical when a task is only for small displays.
8. Avoid model-specific checks such as `Build.MODEL.contains("F22")`; use viewport/capability logic.
9. Do not change VISIONOS / InnerTube / YouTube matching / TrackVideoCache while working on unrelated features.
10. Keep podcast work audio-only unless product scope explicitly changes.
11. Prefer audit/research first for architectural changes.
12. Work in small steps, build/test after each step, and report before expanding scope.

## Validation expectations

After implementation:

- Run relevant unit tests.
- Run a debug build.
- Check regular music playback regression.
- Check podcast direct playback.
- For UI changes, verify target small viewport and normal viewport.
- For MediaSession work, validate background/external-controller scenarios.
