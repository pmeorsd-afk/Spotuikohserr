# Podcast Home text covers — implemented 2026-10-17, validated 2026-10-18

The user approved text-and-gradient episode covers after supplying an annotated example. This changes the Home Podcasts feed and its Following thumbnails only. Show pages, the chooser/Hub, Library, music, MiniPlayer, full Player and Android Auto keep their existing artwork. No playback, episode identity, metadata, save or navigation behavior is changed.

## Presentation

- Large covers show the exact episode title in bold centered text, followed by its publisher (or the same episode's show name when publisher is blank). Long titles use up to three lines, then ellipsis; the existing metadata below remains available. Accessibility announces the full title/creator/duration.
- Each card uses a two-color gradient derived from the permitted episode artwork. The bitmap itself is not displayed. Text is solid black or white, chosen with gradient colors constrained to maintain at least 4.5:1 contrast across the interpolation, including its middle.
- Missing, forbidden or failed artwork uses a deterministic palette keyed to the same show (then show name/episode ID when unavailable). This is a visual fallback, not a fallback to another content item. It never reads forbidden artwork or reuses its cached colors.
- The green library placeholder is absent from both the large covers and the small metadata/Following swatches on this surface. Other screens retain the shared placeholder. `EpisodeCardItem` has an optional cover slot; all existing callers keep the original rendering by default.
- The normal container preserves 852:360 with 16 dp outer margins, 12 dp corners and the duration badge. Typography adapts to available width, with a minimum 16 sp title. A short/narrow cover grows only if large accessibility text would otherwise collide with the creator/badge; no device-model checks.

## Loading and lifecycle

`PodcastHomeContent` continues to observe the existing whitelist version and exact episode model. Permission is checked before either the in-memory color cache or image loading. Changing the URL/show or revoking permission immediately recreates the visible palette state with the correct fallback; the prior effect is cancelled.

Glide loads a 64×64 bitmap with a six-second network timeout and an eight-second coroutine deadline. Palette analysis runs on `Dispatchers.Default`; targets are cleared in `finally` after analysis/cancellation, and only 64 resulting color pairs are retained in memory. The bitmap is not retained in the palette cache. Offline/invalid images preserve the designed fallback, and cancelled work cannot update another item. These composables are instantiated only for visible podcast feed rows; the shared music `Palette` utility is unchanged.

References: [Android Palette generation and caching](https://developer.android.com/develop/ui/views/graphics/palette-colors), [Glide target lifecycle](https://bumptech.github.io/glide/doc/targets.html). No dependency or build configuration changes were needed.

## Validation boundary

Production-composable previews cover 600/360/320/240 dp widths, Hebrew/mixed text, dark/light artwork colors, absent duration and 1.3×/2× text. JVM checks exercise whole-gradient contrast with opposing hues, stable fallback palettes, normal reference ratios and non-overlapping vertical text budgets through 3× font scale. These geometry checks do not measure actual Android glyph rendering.

Final validation: User **504/504**, Admin **500/504**, with exactly the four established whitelist failures by class/test/message. All four new tests pass in each flavor; both debug variants and the six preview configurations compile. Previews are **compiled, not rendered**, and no ADB device is connected. Full regression includes the existing music/direct-podcast/ownership tests, but does not establish actual audible playback or font rendering on a device.

Of 71 recorded baseline files, 70 are unchanged; the intentional exception is PodcastHomeContent. Shared playback, whitelist/palette utilities, other screens, API/resolver files and all nine APK paths/hashes are unchanged. The scoped EpisodeCardItem diff adds only an optional thumbnail slot, preserving its default body. Evidence: `app/build/reports/podcast-cover-design/` (`results.json`, `scope-verification.json`, `task-changes.diff`, final XML and logs). No APK packaging or installation is included. Device acceptance requires checking long titles, live permission changes, scrolling/recycled rows, offline artwork, exact episode/show clicks and ordinary music/direct-podcast playback on small and normal viewports.
