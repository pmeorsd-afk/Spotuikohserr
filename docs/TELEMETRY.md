# Telemetry / Live Analytics

## Status

The project has an earlier telemetry implementation and a proposed Telemetry v2 architecture.

Treat Telemetry v2 as separate from playback P0 work.

## Target presence model

Derived from:

- `isUiForeground`
- `isAudioPlaying`

States:

```text
ACTIVE_IN_APP_PLAYING  = foreground + playing
ACTIVE_IN_APP_IDLE     = foreground + not playing
BACKGROUND_LISTENING   = background + playing
OFFLINE                = background + not playing
```

Online presence and listening are separate concepts.

`OFFLINE` is server-derived:

```text
explicit client leave
OR WebSocket close
OR lease expiry
```

## Backend direction

Preferred free architecture:

- Cloudflare Worker router
- one named Durable Object: `global_presence`
- WebSocket Hibernation
- SQLite only for historical analytics, not every heartbeat

Avoid KV as authoritative live-presence state.

## Hibernation safety

Do not keep sole session truth only in:

```js
this.sessions = new Map()
```

because heap is lost during hibernation.

Use WebSocket attachments and reconstruct with:

- `serializeAttachment`
- `deserializeAttachment`
- `getWebSockets(...)`

## Historical analytics

Do not write SQLite on every heartbeat.

Use live memory/attachments for current state.

Persist historical data at meaningful boundaries, such as track start/end or coarse accounting.

Prefer stable content IDs:

```text
track:<spotifyId>
episode:<spotifyId>
```

## Reconnect semantics

Wi‑Fi ↔ cellular reconnect should keep the same logical `sessionId`.

A new `sessionId` should be created after a true OFFLINE → ONLINE transition.

Old sockets must be superseded with ownership/generation guards so stale close callbacks cannot delete the replacement.

## Playback counting

Play counting must be idempotent across reconnects.

A robust design should use a stable per-playback instance ID.

## Scope warning

Do not modify telemetry while implementing Waze podcast synchronization unless explicitly requested.
