# UI / UX Constraints

## General

The app intentionally follows a Spotify-like visual language.

For bug fixes, keep visual changes minimal unless redesign is explicitly requested.

## Small-display Player / Qin F22 Pro

A compact layout adaptation was implemented for very short viewports such as Xiaomi Qin F22 Pro.

Product requirement:

> Normal phones must remain visually unchanged. Adaptation applies only to genuinely short available viewports.

Use available viewport height, not device model.

Conceptually:

```kotlin
BoxWithConstraints {
    val isCompactPlayer = maxHeight < 600.dp
}
```

### Normal branch
For regular phones (~700–900dp available height):

- preserve sizes
- preserve artwork
- preserve spacing/padding
- preserve control sizes
- preserve overall composition
- remain pixel-identical unless explicitly changed

### Compact branch

For very short viewports:

- keep `PlayerTopBar` accessible
- avoid `fillParentMaxHeight()` when it blocks natural scrolling
- avoid large hardcoded vertical heights such as a fixed 300dp control block
- use natural content height
- allow scroll where required
- constrain artwork to a compact maximum
- preserve touch target sizes
- respect safe/system insets

Do not use a whole-screen `scale()` shortcut.
Do not detect `"F22"` with `Build.MODEL`.

### MiniPlayer width (2026-10-22)

Reserve the existing 22dp saved button and 36dp Play control before sizing the
artwork/title row (maximum 260dp). The info row must shrink on narrow viewports,
not the circular saved icon. Keep existing margins, artwork, animations and
geometry wherever both controls already fit (360dp screen width and above).

## Home exact-item semantics

Desired behavior:

- Recents = exact listening item.
- Top Grid = same exact item.
- Recommended remains album-oriented where intended.
- Podcast Episode from Recents/Top Grid = exact Episode detail, not whole Show.
- Do not silently convert track/episode clicks into album/show navigation.

An exact-item detail surface has been discussed; verify repository status before assuming it exists.

## Podcast Following UX

Existing direction:

- Home filters include All / Music / Podcasts.
- Following is associated with Podcasts.
- Empty state follows Spotify-like onboarding.
- Followed-show shortcuts exist.
- Rich followed-episode aggregation may still be future work.
