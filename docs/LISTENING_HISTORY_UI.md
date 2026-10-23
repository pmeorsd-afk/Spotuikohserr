# Recently Played history and saved check — 2026-10-12

## Scope and visible behavior

Implemented from the user's five screenshots of the original and SpotUI Home/history surfaces. The Home saved badge now draws a centered black vector check inside a 12 dp green circle; it does not depend on the Hebrew font's glyph baseline. Recently Played has a Hebrew Show all action linking to the existing History route. The library's existing history link reaches the same screen.

History uses a Hebrew RTL layout, local calendar dates, Music/Podcasts filter chips, expandable groups and individual item details. Tapping the selected filter again restores All. The circular chevron expands/collapses a group; an individual row opens the exact track or episode detail. Opening history, filtering, expanding and navigating do not call playback. The MiniPlayer and bottom navigation remain managed by the existing navigation host.

Grouping is deliberately limited to what SpotUI records: consecutive known media types within a date. Group labels report the actual number of song/episode listens, not a guessed playlist, show, library-addition event or Spotify session. Filtering does not merge groups that were separated by another media type. Expanded rows are individual lazy items, avoiding composition of the entire history at once. Single entries do not need expansion. Long press retains individual removal. The user subsequently requested removal of the top-left three-dot menu; that menu and its clear-all action are now removed. Deletion does not erase resume bookmarks.

## Storage and exact identity

The existing ListeningHistory SharedPreferences file and its 500-entry retention limit are reused. Each new entry retains the old flat fields for reader compatibility and adds the complete SongsModel using PlaybackSnapshotCodec, including media type, Spotify episode alias, show ID, original source, duration and artwork. No query is built from titles to reconstruct old records.

After device verification, the user authorized automatic cleanup of old, non-navigable history. On first access to ListeningHistory after the update, ListeningHistoryMigration removes only entries without a valid exact identity and saves typed_identity_cleanup_v1 in the same preference edit. Valid typed music/podcast entries keep their original timestamps/order. Reopening the screen/app or installing another update does not repeat the cleanup. Fresh installs mark the migration before recording new listens. The codec still reads old payloads safely, but their media type is never guessed from a numeric UI ID or title. Internal episode show IDs can be recovered from the exact encoded source, without matching a show name.

Cleanup reads/writes only ListeningHistory.entries and its migration marker; it never clears the preference file. Custom/saved playlists, LikedSongs/LikedSongs_Data, LikedAlbums, PlaybackState, PlaybackProgress, LocalListeningTracker and all other stores are untouched. This is a local history migration, not app-data reset or account logout.

The old CurrentSongState metadata-update hook could log an attempted selection before playback succeeded. History recording now observes the existing SongPlayer position watcher after an active, current generation owns the primary Player and it reports isPlaying. It records once per token, including external DirectAudio and promoted crossfade owners. Pending, paused, secondary, revoked and superseded requests cannot record. Consecutive same-item entries on the same local date are deduplicated using typed stable identity; A/B/A remains three events, and the same item on another day can be logged again. An item that continues playing through midnight keeps its original start date.

The new observer changes only logging; it does not set, prepare, play, resolve, seek or take ownership. Existing music and podcast playback engines are unchanged. The experimental disabled Spotify WebView player is not a new supported history source in this change.

History refreshes through a preference listener. Episode progress uses the same typed bookmark, duration and completion listener as Home; no separate progress cache or polling is introduced. Completion still displays 100% while the resume bookmark follows its existing reset behavior.

ItemDetailRegistry keys now include media type, so a track and episode with the same ID text cannot overwrite each other. History supplies the exact model to the existing single-track/single-episode surfaces. PodcastEpisodeUiModel now retains spotifyEpisodeId when ShowScreen converts a single exact episode for display and later actions; otherwise entering details could silently switch its bookmark identity from Spotify ID to RSS source. No RSS/EpisodePlaybackResolver changes were needed. Existing in-memory detail navigation is not a new process-restoration mechanism; reopening History after restart loads its persisted entries and can create the exact detail target again.

## Verification

Cleanup follow-up: User 263/263; Admin 259/263 with exactly the four baseline whitelist failures. Eight new ListeningHistoryMigrationTest cases exercise the production preference adapter against an in-memory SharedPreferences implementation, including unrelated preference files/keys remaining unchanged and the marker preventing repeated writes. Both debug variants compile; diff check passes; existing nine APKs remain unchanged. Evidence: app/build/reports/history-cleanup/. Actual Android upgrade persistence and the header on small/normal viewports remain device checks after a separately requested build.

ListeningHistoryTest covers typed JSON round trips, Spotify episode aliases, legacy/corrupt/conflicting records, blank metadata, deduplication and numeric/title collisions, A/B/A order, a new date, 500-entry retention, local timezone date boundaries, type filtering, expansion, registry separation, shared podcast progress after storage recreation, exact episode display conversion, pending/paused/secondary playback and stale external takeover callbacks.

Final User 255/255; Admin 251/255, with exactly the four previously accepted whitelist failures by class/test/message and no new failures. All 22 new tests pass in both flavors. Validation results and exact baseline comparison are recorded in CURRENT_WORK.md and app/build/reports/listening-history/. App User/Admin unit tasks compiled both debug variants without assembling or packaging an APK. git diff --check passed; all nine existing APK paths and hashes are unchanged. Spotify and InnerTube module tests were not rerun for this UI change.

ADB now sees emulator-5554 at 900 x 1600 / 240 dpi (600 x 1067 dp before insets). The new code was not installed because no APK was requested. Source review checks 240 dp and 600 dp widths: weighted wrapping text, bounded artwork/chevrons, scrollable history and no fixed-width whole row. This does not establish rendered visual acceptance at either viewport.

After a separately requested build, verify:

1. Compare the Home green check at normal and small viewport sizes; it must remain a centered black check. Open Show all and return with Back.
2. Play two tracks and two episodes, then inspect real dates and groups. Expand/collapse, switch filters, clear the filter and scroll while new playback starts.
3. Tap a track or episode row: its exact detail page opens and audio does not start or change. Explicit Play from episode details must use the same saved position and update the same Home/history progress bar.
4. Pause/resume from the notification/Waze, switch A/B/A, and close/reopen the app. Identity, dates and progress should remain consistent; pause/resume should not duplicate the last entry.
5. Attempt an unavailable item, then switch quickly to another. A never-playing or late stale item must not appear as a new listen.
6. Check long Hebrew/English titles, unknown duration, unapproved artwork placeholders, empty filters, Back navigation and MiniPlayer clearance on both small and normal screens. Upgrade an installation with old metadata-only history: those rows disappear once, valid new rows remain across restarts, and saved playlists/liked items/resume positions remain intact. The header has no three-dot menu.

No APK generated, no P1 browsing, no telemetry or whitelist behavior change, and no VISIONOS/InnerTube/YouTube matching/TrackVideoCache changes.
