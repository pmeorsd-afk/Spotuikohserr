# Waze floating button and artwork

Implemented 2026-10-14 after the user's approval. Scope: draggable button bounds/default placement, suppression over observable input/menu windows, and permitted artwork in the mini player and Show List. This is the phone's Waze accessibility overlay, separate from Android Auto.

## Position

The button remains movable. Its coordinate system is physical LEFT/TOP with an explicitly LTR button view; its default X is the rightmost safe position, regardless of Hebrew/English layout. The default Y is 125 dp below the safe area's top, based on the supplied map screenshot's upper controls. It is not attached to a Waze resource ID or guaranteed to track the speaker after a Waze redesign; the user can adjust it.

The complete 54 dp button is clamped inside current screen bounds minus system bars, cutouts and an 8 dp margin. Extremely small windows shrink the button only as needed to remain contained. The button no longer has FLAG_LAYOUT_NO_LIMITS. Android 30+ uses current WindowMetrics and explicit fit-inset handling; Android 26–29 uses display/window insets and cutout data when available. Geometry is recalculated on configuration/window changes and the existing safety tick. Rotating during a gesture cannot trigger a stale drag release as a player tap. Drag slop uses ViewConfiguration; cancellation never opens the player.

Position is stored as bounded fractions in a dedicated `waze_overlay_position_v2` preference, separately for portrait and landscape. Size/inset changes therefore keep the selected relative position in bounds. Old START-relative raw-pixel positions are left untouched but are not imported, because their meaning changes under RTL. The first display after this update defaults to the right; subsequent completed drags are remembered. Corrupt/nonfinite coordinates safely use the default or clamp.

## Visibility

The service inspects the current top application window and ignores its own accessibility button/player windows. It no longer treats the last remembered event package as evidence that Waze is still foreground. It hides both overlay windows immediately for:

- another foreground app or active unrelated system window;
- a visible input-method window;
- a focused editable Waze field;
- multiple Waze application windows / an exposed dialog class;
- exposed pane or heading titles identifying Search, Settings, Menu or sound settings in Hebrew/English.

Returning to the map without these signals restores the button at its saved position. Hiding/removing the overlay never pauses, restarts or resolves audio. Accessibility inspection is bounded to 240 nodes; ordinary map labels are not matched as menu titles, and no typed query or accessibility tree is logged or persisted.

This is observable-state detection, not a claim of recognizing every Waze screen. A canvas-only popup or menu without accessible pane/heading/input/window signals may not hide the button. The user's installed Waze version needs device acceptance. The button remains movable for this reason. No speculative resource IDs or phone-model checks were added.

## Artwork

The previous overlay passed only image URLs into GlideImage. That path depends on an in-memory set of allowed URLs, which phone screens populate while checking exact item permissions. Visiting SpotUI could therefore make an already-approved track's image appear in Waze later.

The mini player now obtains the exact typed model from the owned playback snapshot when it agrees with displayed metadata, otherwise an unambiguous current queue item or a typed persisted item for the empty live-state resume surface. It never selects a different queue entry to fill a missing cover. A headless owned item can provide artwork without the phone queue being loaded. Conflicting pending metadata cannot borrow the previous owner's permission.

Both the mini player and Show List call the existing song permission check directly and supply its result to GlideImage. They observe whitelist revision changes; an allowed image needs no phone-screen warmup, while revocation and unknown items retain existing User/Admin behavior. Missing/legacy ambiguous models keep placeholders. Artwork loading, whitelist rules, music/source matching, RSS/direct podcast resolution and phone layouts are unchanged.

## Validation and device acceptance

Production helper tests cover complete-button bounds across normal/compact/landscape/cutout frames, drag endpoints, fractional resize/rotation, corrupt positions, observable visibility transitions, cold allowed-artwork lookup, revocation, exact identity, saved episodes and background ownership. Full User/Admin results and original APK hashes are in `app/build/reports/waze-overlay/`; CURRENT_WORK.md records final totals.

No APK is packaged or installed. ADB has no connected device. Numeric viewport tests and debug compilation do not establish Android WindowManager placement or Waze accessibility behavior on a device. After a separately requested device build:

1. Open Waze fresh in Hebrew and English: default is right below the upper controls; drag against all four edges.
2. Hide/reopen the overlay and recreate its service; verify the chosen position persists. Rotate and check both saved orientations, small/normal viewports, cutouts and system bars.
3. Open/close Waze search, keyboard, settings and modal messages. Confirm identified screens hide the button/player without interrupting audio, and the map restores the button. Record any unexposed menu needing further device evidence.
4. Start a permitted track from Waze without first opening SpotUI; verify cover in the mini player and list. Repeat with a blocked cover, changed whitelist, paused/resumed music and direct-audio podcast.
5. Open our own player/list, drag then cancel, leave Waze, and return. Check there is no self-hiding loop, accidental tap, stale cover or audio/queue identity change.
