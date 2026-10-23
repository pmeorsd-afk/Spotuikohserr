# SpotUI / SpotUI Kosher — Project Context

## Product

SpotUI is a custom Android music and podcast application with a Spotify-inspired UX.

Primary goals:

- Fast music startup.
- Spotify-like navigation and presentation.
- Hebrew-friendly UI and RTL behavior.
- Podcast support with direct RSS audio.
- External playback integration through Android Media3 / MediaSession.
- Reliable background playback.
- Separate admin / analytics functionality.

## Core technologies

- Kotlin
- Jetpack Compose
- AndroidX Media3
- ExoPlayer
- `MediaLibraryService`
- Hilt / app singletons where already used
- OkHttp
- Cloudflare Worker for backend/admin features

## Architectural philosophy

A key invariant is:

> The item shown in the UI, the item stored in current queue/state, and the item actually playing must refer to the same logical media item.

Do not "repair" inconsistencies by displaying queue index 0, selecting the first search result, or silently falling back to another media type.

## Important media types

At minimum:

- `MediaType.TRACK`
- `MediaType.PODCAST_EPISODE`

Podcast episodes are not normal music tracks even if shared models are reused.

## Shared model behavior

`SongsModel` has been extended for podcasts and includes fields such as:

- `mediaType`
- `podcastShowId`
- Spotify item identity fields used by existing code

There is also logic such as `resolvePodcastShowId()` that may derive show identity from explicit fields, encoded episode IDs, or cached show context.

Do not casually expand transitional registries or identity fallbacks without auditing current use.
