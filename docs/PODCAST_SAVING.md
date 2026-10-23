# Podcast saving — stages 4 and 5

Implemented in source on 2026-10-16. The user approved stage 4 and subsequently explicitly asked to finish it and continue to the next stage. Both stages are included. No APK packaging or installation is part of this work.

## Visible behavior

- The episode plus changes to a green circle with a black check, using a 280 ms rotation/scale transition. Opening/recycling an already-saved row does not replay the animation. The touch target remains stationary.
- First save goes immediately to **הפרקים שלכם** for an episode or **שירים שאהבתם** for music, then opens the destination sheet. An item already saved in a known destination opens the sheet without silently adding it to the default collection again.
- Episode rows on Show and Podcast Home, the full Player and MiniPlayer use the shared save presentation. The selected full media snapshot remains fixed while playback can advance behind the sheet.
- Your Episodes has green bookmark artwork, a count, a Library entry in both list/grid, search/filter support and an exact typed episode list. Liked Songs retains the purple heart artwork and music records.
- The sheet opens at 232 dp, capped at 70% of the available viewport. Its compact background does not dim the underlying Show: reference pixels at (100,100) and (100,900) are identical in the supplied original Show and original compact-save screenshots. The preceding SpotUI screenshot dimmed this area.
- Reference styling: #1F1F1F surface, #1ED760 selected circle, #B3B3B3 secondary text/outline, 32 x 4 dp handle, 48 dp cover, 16 sp regular row title, 13 sp count, 18 sp bold heading, 16 dp horizontal padding. RTL ordering follows the reference. Text can wrap on narrow viewports.
- A short upward drag (24 dp threshold), upward fling or handle tap expands the sheet. Snapping uses a 300 ms eased transition. The list cooperates with the sheet through nested scroll; small/interrupted drags return to an anchor. Downward dragging from full height first collapses; from compact height it dismisses.
- Full expansion shows top Cancel/Done, the full scrollable playlist list, New Playlist and a pinned green Done button. Header/footer controls appear smoothly; navigation/status/keyboard insets are respected. Empty sheet space does not activate the outside-dismiss scrim.

Timing follows the user's rotation/smoothness description; static screenshots do not establish exact Spotify animation curves. Pixel-perfect rendered equivalence has not been verified.

## Save and cancel semantics

The default save happens before opening the sheet. Destination changes and newly named playlists are drafts until Done. Cancel, Back, outside tap or downward dismissal discard uncommitted edits and preserve the initial default save. New Playlist stages a local SpotUI playlist; it is created on Done, including when the user deselects the item before confirming. UUIDs keep multiple same-name playlists distinct.

Only explicitly touched destinations are committed. Untouched destinations and unrelated concurrent Library changes survive. Failed destinations remain selected in the open sheet with an error and retry; successfully committed destinations are accepted individually and are not repeated on retry. Cancel after a partial failure discards only the remaining uncommitted edits, not successful operations. A new local playlist is not recreated when another destination fails.

All local playlists are read from CustomPlaylistStore. Spotify playlists are loaded through all library pages, including flattened folders, filtered to owned/collaborative editable destinations. Same-name playlists retain their separate IDs. Visible remote membership checks have concurrency limited to four and examine every playlist page. Remote removal requires actual playlist-scoped UIDs. RSS-only episodes have no invented Spotify identity and cannot be sent to Spotify playlists.

The initial local collection remains available when Spotify loading fails. Loading and membership errors can be retried; timeouts are displayed as failures rather than leaving controls permanently busy. Existing music Liked Songs sync remains the existing best-effort SpotifySync path. Your Episodes is local to SpotUI. Creating a playlist here creates one local SpotUI playlist; it does not also create a duplicate remote playlist.

Last confirmed remote membership is persisted with typed item URIs and a hash of the signed-in session as its namespace. It is updated after successful membership reads/writes and pruned only after a complete successful editable-playlist listing. This keeps a remote-only save checked after reopening without sharing state across accounts. Remote edits made outside SpotUI are reconciled when those destinations are checked again; this is not a live Spotify subscription. Credentials are not written to this cache.

## Typed storage and playback boundary

SavedEpisodePref stores complete episode models in SavedPodcastEpisodes, separately from LikedSongs_Data. Keys use exact episode identity or an explicitly typed concrete RSS enclosure URL. It does not infer identities from a title, numeric UI hash or a fuzzy match. The full media type, Spotify episode ID, show ID and direct source are preserved.

Explicitly typed or episode-prefixed legacy liked records are read through without deleting the originals. Removal tombstones prevent their reappearance. If a music record reuses an old storage key, the positively identified episode is preserved in the new store first. Known episodes are excluded from music liked counts/queries, and music removals cannot delete their old records. Ambiguous old data that already lost all episode identity is not guessed into a podcast.

Custom playlists serialize mediaType/show ID and use typed episode keys. Only legacy keys backed by actual episode metadata are upgraded. Stored song/episode IDs can collide without merging those entities.

Spotify playlist episode URIs are decoded as episodes before the existing music mapper. Invalid/conflicting episode IDs cannot fall through to music queries. Saved podcast and mixed-playlist selection uses the existing SongPlayer.resumeResolvedPlayback entry and commits UI/queue only on successful preparation. The new SavedEpisodesViewModel accepts the same exact typed enclosure identities as the save store, including episodes with no Spotify ID; it does not apply external-controller eligibility to an in-app saved selection. Podcast playback remains RSS/direct audio. Bulk music download does not send saved episodes to the music downloader.

Player/MiniPlayer save actions use the owned full media model or a unique matching queue model. They no longer reconstruct a music query from displayed podcast metadata. SongPlayer, PlaybackService, ShowViewModel, SpotifySync and the completed stage-3 Show menu/header are unchanged in this stage.

## Validation and remaining device acceptance

Final full regression: User 462/462; Admin 458/462 with exactly the four established whitelist failures by class, name and message. All 27 added tests pass per flavor (13 storage/legacy/remote-cache tests, 12 draft/geometry/identity tests and 2 additional Library collection tests). The three relevant suites total 33 passing cases per flavor. Earlier full runs encountered the already-known live NewPipe HTTP 403; the final full run passes that test in both flavors. Both debug variants and preview sources compile.

Protected-file hashes and all nine existing APK paths/hashes are unchanged. Diff whitespace checks pass. Evidence is under app/build/reports/podcast-save-step4: validation-complete.log, results.json, final-user-xml, final-admin-xml, scope-verification.json and verify.py. Presentation-compile.log records the successful post-reference-brightness compile. No APK packaging/install ran.

Preview configurations compile for 600 x 1067 dp, 320 x 480 dp and 240 x 320 dp with 1.3 font scale, using production save rows/covers/buttons. Geometry is unit tested across normal, small and keyboard-sized available heights. ADB has no connected device. Previews were compiled, not rendered; real touch/nested-scroll behavior, animation smoothness, large-font full-sheet layout, remote account mutations and audible music/direct-podcast regression remain device acceptance checks. No claim of live end-to-end verification is made.

When a device build is separately requested, check: first plus and green transition; initial sheet size/brightness; small upward/downward drag and interrupted animation; full list after many playlists; duplicate names; New Playlist then Cancel/Done; selecting multiple destinations; partial network failure/retry; switching playback behind the open sheet; reopening Library/Your Episodes; a direct RSS-only saved episode; ordinary music, mixed playlists and background playback.
