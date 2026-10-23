# SpotUI Architecture

## 1. Music playback pipeline

The working music path is performance-sensitive and should be treated as stable:

```text
Spotify metadata / app music item
        ↓
YouTube / InnerTube resolution
        ↓
VISIONOS stream-client optimization
        ↓
ExoPlayer
```

The VISIONOS optimization dramatically improved music cold-start performance and must not be touched during unrelated podcast, Waze, UI, or telemetry tasks.

### Music-only behavior

Do not reuse these for podcasts unless architecture explicitly requires it:

- YouTube lookup / matching
- InnerTube resolution
- VISIONOS stream resolution
- `TrackVideoCache`
- music matching / version matching
- `buildSpotifyPlayQuery`
- Spotify track-only search fallbacks

`SongPlayer.buildSpotifyPlayQuery` is music-only and should use track identity, e.g. `spotify:track:<id>`. Never wrap podcast episode IDs as track IDs.

## 2. Podcast playback pipeline

```text
Spotify Show / Episode metadata
        ↓
RSS discovery / feed resolution
        ↓
Episode matching
        ↓
Direct enclosure URL
        ↓
PlaybackSource.DirectAudio
        ↓
ExoPlayer
```

There must be no YouTube fallback for podcasts.

## 3. PlaybackSource

Conceptually:

```text
PlaybackSource
  ├─ YouTube(videoId)             // music
  └─ DirectAudio(url, mimeType)   // podcast/direct audio
```

A `SongsModel` is metadata/identity. It does not itself prove the item is playable.

For podcasts, playback readiness exists only after the existing episode resolver has produced a valid `DirectAudio` source.

## 4. State consistency

Avoid:

```text
Actual audio       = Track A
CurrentSongState   = Episode B
PlayerScreen queue = Track C
```

Preserve one logical identity across:

- actual player item/source
- `CurrentSongState`
- queue
- MiniPlayer
- full PlayerScreen
- MediaSession metadata

Do not commit UI state before the playback path is known to be valid.

## 5. Known legacy concern

There is an older music metadata/matching edge case around titles containing `" - "`. It is out of scope unless explicitly targeted.

Do not opportunistically rewrite unrelated matching logic.
