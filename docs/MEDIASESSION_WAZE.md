# MediaSession / Waze / External Playback

2026-10-22 Radio previews: a separate foreground player has no session or metadata
binding. The existing main session keeps its track/podcast while temporarily paused.
An optional preview-interruption hook before external Play/Pause/Stop/setPlayWhenReady
prevents an already-paused session command from later being undone by preview auto-
resume. Existing forwarding/ownership contracts still handle transport. The hook is
inactive when no preview surface is open. Both flavors' playback tests and compilation
pass apart from the four established Admin whitelist tests; actual Bluetooth/Auto,
background, focus and audio validation remains pending. See RADIO_PREVIEWS.md.

2026-10-20 artwork follow-up: exact Home approvals now extend to Android Auto
browse metadata/provider reads. Permission revisions notify returned browse pages;
cover digest URIs prevent stale folder-image reuse. Shared Now Playing/Waze and
playback preparation/ownership/callback contracts are unchanged. See
HOME_AUTO_ARTWORK_AND_SHELVES.md for cross-device refresh, tests and device limits.

2026-10-16 dashboard stage 4: Car presentation now prioritizes Save before Radio/Repeat to use the free Previous slot alongside standard Play/Pause and Next. The global layout refreshes on CarConnection changes, including the Media3 1.3.1 legacy/notification bridge, and restores standalone order on disconnect; recognized modern car controllers also receive an initial/current override. Existing local Liked Songs persistence/Spotify sync now use exact-identity car checks; repeated Save does not remove a like. User 500/500, Admin 496/500 (same four failures), eight new tests pass per flavor; 64 protected hashes/nine APKs unchanged. No APK/install/device. Source policy is verified, actual host layout/background playback is not. Phone UI and source engines unchanged; see ANDROID_AUTO_REFINEMENT.md.

2026-10-16 stages 2–3: exact artist-search actions now prepare a randomly selected track from that verified artist's top tracks; other artist folders remain browsable. Home/Music use actual successful-playback history with Media3 children-change notifications, plus real server-provided Recommended stations playlists when available. Car-only green theme added; A–Z suppression remains unresolved and rendered icon contrast requires DHU. User 492/492, Admin 488/492 (same four known failures); no APK/install/device. Phone/playback/API engines unchanged. Dashboard stage 4 was subsequently authorized and is recorded above; see ANDROID_AUTO_REFINEMENT.md.

2026-10-16 Android Auto refinement stage 1: car-only presentation permissions now hide Previous/public queue through the Media3 1.3.1 notification-controller bridge. CarConnection restores standalone permissions on disconnect. Radio now prepares and starts a new ranked exact recommendation before committing its queue, with cancellation/ownership guards. No source engine or podcast resolver changes. User 472/472, Admin 468/472 (same four known failures); no APK/install/device acceptance. Host progress geometry is unchanged. See ANDROID_AUTO_REFINEMENT.md for the subsequently authorized stages 2–4.

2026-10-15 Bluetooth follow-up: registered the filtered Media3 media-button receiver and completed bounded foreground startup/cancellation. onPlaybackResumption returns a real playable item for both typed media paths; music's already-prepared instance is handed back without a second reset. Source failure/cancel cannot trigger Media3's fallback Play on a newer paused item. User 415/415, Admin 411/415 (four unchanged failures), all 15 new tests pass. No SongPlayer changes or APK/install; real car/device acceptance remains pending. See BLUETOOTH_RESUMPTION.md.

2026-10-15 background audit: fixed dropped Java default listener callbacks in AutoSessionPlayer using an explicit forwarding adapter. The connected phone's old build played audio with a stale BUFFERING/empty MediaSession and no foreground service; Android recorded an excessive-CPU background kill. User 400/400 and Admin 396/400 tests (four known failures); no APK/install, so live repaired behavior remains unverified. The missing Bluetooth route found in that audit is implemented in the follow-up above. See BLUETOOTH_BACKGROUND_AUDIT.md.

Waze overlay follow-up (2026-10-14): the approved button remains draggable within safe bounds, defaults to physical right and persists positions per orientation. Observable input/menu windows hide only the overlay. Exact model/owned-item artwork permissions now work without first visiting phone screens. No playback/source/MediaSession engine changes in this follow-up. See WAZE_OVERLAY.md for detection limits and device acceptance.

Android Auto follow-up (2026-10-13): Home/Recently/Browse/Library now include the phone feed, category playlists/Podcast Hub, saved content and combined online catalog search. Persisted typed auto-folder/ collection identities and auto/ playable tokens preserve exact selection across recreation. Artist/album lookup uses stable IDs; strict unique voice-title selection uses combined online/local results. Existing music compatibility and podcast DirectAudio ownership flows remain. Phone-task dismissal preserves ongoing playback. See ANDROID_AUTO.md for artwork, scope, tests and pending car/device acceptance. The statement below that P0 added no podcast browse nodes describes P0 only; the later Auto task adds them.

Updated for the Steps 1–4 implementation, 2026-10-10. P0 is awaiting physical-device/Waze acceptance; it is not closed.

## Historical failure and current status

Previously, onSetMediaItems depended on trackById. An unknown podcast ID could leave the old music item loaded while a subsequent play command resumed it. UI metadata and the queue could describe other items. MainActivity's Waze resume route could also reconstruct podcast metadata as a music query.

The current implementation resolves typed identity independently of the browse cache, reserves new ownership before resolution, and rejects unresolved requests. The old root cause describes the original code, not the current callback.

## Actual Player and callback version

PlaybackService creates a MediaLibrarySession with a ForwardingPlayer over SongPlayer's ExoPlayer. Crossfade promotion rebinds the session through onPlayerSwapped. There is no second podcast playback engine.

The installed Media3 version is 1.3.1. Its onPlaybackResumption takes MediaSession and ControllerInfo only. There is NO isForPlayback argument in this version; metadata-only semantics from later versions are not implemented or claimed here.

The installed MediaSessionStub also routes add AND replace through onAddMediaItems. Both are explicitly rejected with a failed future. Arbitrary playable URI items cannot bypass canonical resolution. onSetMediaItems resolves the explicitly selected item from the requested list. INDEX_UNSET uses the list default (index 0); an empty list or out-of-range index fails rather than choosing a different item. The existing app queue handles subsequent music tracks.

## Identity resolution — Step 1

ExternalMediaIdentityResolver reads mediaId, requestMetadata.mediaUri and localConfiguration.uri, together with the explicit media type.

- Stable identity takes precedence over metadata and must not conflict with other identifiers/types.
- Internal episode identifiers are parsed separately from Spotify episode identifiers.
- Exact known models are sought in the current queue, browse cache, persisted playback and local recent history.
- An unknown Spotify episode ID may be resolved by exact Spotify episode lookup.
- Metadata fallback is exact and unique, only without an explicit stable identifier. Title-only guessing is forbidden.
- Track cannot become Podcast through metadata fallback.
- LocalListeningTracker conversions preserve the Spotify episode identity.

An unknown identity fails. There is no first-item, fuzzy, or previous-music fallback.

## External podcast flow — Steps 2 and 4

    onSetMediaItems / onPlaybackResumption
      → pause/save previous position and reserve external ownership
      → canonical identity / validated persisted snapshot
      → new podcast ownership ticket
      → existing EpisodePlaybackResolver
      → require PlaybackSource.DirectAudio
      → playable MediaItem with stable mediaId and in-process ticket tag
      → Media3 sets, prepares and plays on the shared ExoPlayer
      → onMediaItemTransition verifies actual item and current ticket
      → adopt ownership, start position watch, commit exact queue/current state

ExternalPlaybackRequests is shared by set and resumption. Request epoch and SongPlayer token are checked after asynchronous identity/source lookup. Destroying the service invalidates outstanding requests. The forwarding Player rejects empty/stale/untagged set results; prepare/play cannot revive a previous owner's item.

SongPlayer cancels prior resolution, upgrade, recovery, position watch and crossfade work. Generation checks prevent delayed results from committing after takeover. External podcast adoption does not call playSong again.

The tag is an in-process guard, not durable identity. Stable mediaId and PlaybackStatePref reconstruct identity after process death; new playback gets a fresh token.

## Music compatibility boundary

Known music still uses the existing SongPlayer engine through resumeResolvedPlayback, including its established stream resolution. It commits queue/state only in its guarded prepared callback.

For onSetMediaItems, this branch still returns an empty MediaItemsWithStartPosition as a legacy compatibility result. The forwarding Player ignores that empty setter; SongPlayer performs music set/prepare/play. Invalid identity and podcast-source failure return failed futures, not this compatibility success.

The later Bluetooth follow-up changes onPlaybackResumption only: it waits for the existing guarded SongPlayer preparation callback, returns the actual MediaItem and saved position, and prevents a second setter from resetting that exact owned stream. It does not migrate the whole music engine or the onSetMediaItems compatibility branch. See BLUETOOTH_RESUMPTION.md for the scoped completion guard required by Media3 1.3.1.

No changes were made to VISIONOS, InnerTube, YouTube matching, TrackVideoCache, EpisodePlaybackResolver or RSS.

## Waze overlay / MainActivity — Step 3

The overlay transports a full PlaybackSnapshot when available. MainActivity resolves it with WazeResumeResolver:

- Valid complete PlaybackStatePref wins over partial Waze display metadata.
- Contradictory/malformed identities fail safely.
- Same-item resume uses SongPlayer's actual tagged active ownership, not CurrentSongState alone.
- Otherwise resumeResolvedPlayback preserves the exact model, type and saved position.
- Podcasts use the existing podcast route; no buildSpotifyPlayQuery is constructed by this resume path.
- UI/queue commit follows successful source preparation.

## Persistence and recreation

PlaybackSnapshotCodec stores the complete SongsModel and positionMs. Both types retain their mediaType, URL and Spotify identity. Podcasts additionally have an explicit spotifyEpisodeId JSON alias and podcastShowId; the shared model still names the identity field spotifyTrackId. Legacy generic-ID snapshots remain readable when consistent.

WazeResumePref is a display/Intent bridge; full PlaybackStatePref is authoritative when present. onPlaybackResumption validates the saved snapshot, resolves the same episode to DirectAudio and returns the saved position. Recreating a service with a live owned Player synchronizes from that Player while preserving a compatible existing queue.

A failed new resolution leaves the last accepted display/queue in place, with old audio paused and old transport ownership invalidated. It does not publish the failed item. After such failure, choose an exact item again; stale Play cannot revive old music.

## Queue and PlayerScreen safety — Step 4

PlaybackQueueIdentity projects the accepted exact model into an unambiguous queue position; conflicting queues become a singleton of the accepted item. CurrentSongState.commitPlayback uses this projection.

Missing or duplicate numeric current IDs yield index -1. PlayerScreen uses its existing current-item artwork branch in that state, never queue[0]. Pager initialization/programmatic synchronization cannot start playback; an actual drag must settle under the same ownership token. PlayerViewModel and service skip logic also reject a missing current index.

Late radio results cannot alter a newer ownership generation, and podcast end/next cannot seed music radio. Radio matching itself is unchanged. No layout, styling or compact/F22 dimensions changed.

## Verification boundary and remaining acceptance

PlaybackIntegrationTest exercises production identity/persistence/ownership/coordinator/queue/pager logic with a simulated Player boundary. It covers late music, A-after-B, failed resolution, pause/resume identity, fresh-process tokens, saved episode position, service cancellation and queue projection. Steps 1–3 tests remain required.

These JVM tests do not execute an Android MediaController, real ExoPlayer event ordering, Waze or Compose rendering. Use P0_DEVICE_TESTS.md for physical acceptance, including normal and small viewports. Validation results are recorded in CURRENT_WORK.md; pre-existing Admin failures are in TEST_BASELINE.md.

## P1 remains excluded

No Podcast/Show browse nodes were added. Their absence is distinct from the original wrong-audio failure. If Waze offers no podcast-selection entry, report that limitation: in-app podcast selection plus Waze Play/Pause does not prove external onSetMediaItems selection.
## Follow-up: per-item playback progress

PlaybackStatePref still identifies the last item, while separate PlaybackProgress preferences retain positions for all selected items across process restarts. PlaybackProgressStore keys include media type plus Spotify ID (or exact internal/source identity); numeric UI IDs and display titles are not identity. The old single-item position is migrated lazily.

SongPlayer captures the outgoing tagged owner before ownership changes, independent of earlier UI metadata updates. It saves during playback, on pauses and seeks, and on lifecycle/owner changes. Ordinary selection and overlay selection use the saved position. MediaSession set requests with an unset position use the same store; explicit positions, including zero, are honored. Resumption uses the saved full model and its per-item position.

Natural completion resets only the completed item's position to zero. Crossfade also restores the incoming item's position and marks the outgoing item complete. Existing ownership guards prevent old callbacks from recording progress against a newer item. Storage uses asynchronous SharedPreferences writes; normal lifecycle transitions flush pending writes, while abrupt process/device termination can lose the most recent periodic update.

User device feedback after Step 4 was positive; this follow-up specifically addresses returning to previously selected items. No new APK was requested for the follow-up.

## Follow-up: external Play/Pause button synchronization (2026-10-11)

Device feedback confirmed per-item resume works, but pausing from the lock screen/notification left the app showing Pause. ExoPlayer transport changed correctly; its existing onIsPlayingChanged listener saved progress and notified Presence without updating CurrentSongState.

SongPlayer now projects the owned primary Player's final onEvents snapshot into CurrentSongState.playingState. The same state feeds the full Player, MiniPlayer and overlay. playWhenReady plus READY/BUFFERING determines the button: buffering keeps Pause available, an external pause during buffering shows Play, and IDLE/ENDED shows Play. This deliberately does not use isPlaying alone, which may already be false during buffering. Presence still receives its existing actual-audio callback.

Ownership token and primary-player checks reject old/unowned/secondary-player events. Binding state or promoting a crossfade player also samples the current Player, without needing a new event. Updates change only playing state when it differs; no queue/metadata reconstruction or UI layout change is involved.

PlaybackControlStateTest covers this production projection with JVM tests; it does not emulate Android callback scheduling or physical lock-screen controls. Device acceptance: for both a track and episode, pause externally, return to full Player and MiniPlayer (Play expected), resume externally (Pause expected), and repeat during buffering. No APK was requested for this fix.
