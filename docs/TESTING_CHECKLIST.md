# Testing Checklist

Use this as a minimum regression checklist after architectural playback changes.

## Music

- Start a normal music track from inside the app.
- Pause/resume.
- Skip next/previous.
- Background the app.
- Resume from notification/controller.
- Confirm startup performance did not regress.
- Confirm no podcast resolver is involved.

## Podcast in-app

- Open a podcast show.
- Start an episode.
- Confirm direct RSS audio.
- Pause/resume.
- Open MiniPlayer and full Player.
- Confirm episode metadata/cover/show are consistent.
- Confirm no YouTube lookup occurs.

## Waze / MediaSession

- External request for known music.
- External request for known podcast.
- Podcast request while an old music track is paused.
- Invalid episode request.
- External pause/resume.
- External item after service recreation.
- Full Player opened after external playback.
- Queue contains the current logical item.

## UI

For PlayerScreen work:

- very short viewport (~480–550dp)
- normal viewport (~700–900dp)
- top bar visible
- safe insets respected
- no visual changes on normal branch unless explicitly intended

## Failure invariants

Never leave a user-visible state where:

```text
Audio item != CurrentSongState item
```

or:

```text
CurrentSongState item is unrelated to the current queue
```

unless a temporary transition state is explicitly designed and hidden from the user.
