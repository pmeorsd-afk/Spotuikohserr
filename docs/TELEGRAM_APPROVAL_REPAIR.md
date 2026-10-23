# Telegram approval identity and responsiveness repair

2026-10-23 (Asia/Jerusalem). **Deployed; one verified existing grant restored. No APK/install.**

## Proven cause of the reported missing cover

The two user screenshots identify `youtube:tLTgCQovmpc`, the song
`אבי הטחול (גרסת ברסלב)-דוד שרר` by `דוד שרר`. Public YouTube oEmbed for that
exact video confirms both title and author. This is not the other Spotify song
`אבי הטחול` by `רואי אדם`; that existing grant is unchanged.

The [original October 8 approval](https://github.com/pmeorsd-afk/Spotuikohserr/commit/b24b8f09d5eac7e7dd680d58aa60ae526903e731)
stored only `id: youtube`. The callback parser used `data.split(":")[2]`, losing
the video ID in `appr:trk:youtube:tLTgCQovmpc`. The app could initially allow the
artwork through its exact normalized title/artist fallback.

The [October 14 change](https://github.com/pmeorsd-afk/Spotuikohserr/commit/8dbdfb0026c88a6d11e72a190951309f1aaefbf0)
removed that old row while processing approval of `עונג שבת`. The Worker had
written an approval and then invoked the GAS admin action, writing the same
GitHub file a second time. GAS's duplicate cleanup matched multiple different
recordings under the truncated `youtube` identity and deleted the reported
song's row. Thus the disappearance was a real stored-data collision, not an
expiry of the user's approval. The client could notice it on a later sync.

The production whitelist before repair was version 1667, with the ambiguous
`youtube` row for `עונג שבת`, but no row for the reported song. The original
approved grant was restored under the verified full ID in
[commit f0dbfaab](https://github.com/pmeorsd-afk/Spotuikohserr/commit/f0dbfaab27013304c7369eedb294321341bdfabd),
version 1668. Exactly one row was added; every prior row and unrelated collection
was preserved. No other ambiguous historical approval was guessed or migrated.

## Implementation

- `track_approval.mjs` parses the entire callback suffix, including the provider
  prefix, and validates the message/channel/button and exact ID. Spotify IDs,
  legacy ID-less title+artist requests, captions, approval and removal are covered.
- Track updates use exact IDs. Names can enrich an ID-less legacy row only when
  both title and artist match; they cannot replace a different recording's ID.
  A repeated decision for the same exact track avoids another GitHub write.
- The webhook replies with `answerCallbackQuery` directly, independently of
  persistence, Telegram edits and other callbacks. This uses the documented
  [Telegram webhook response method](https://core.telegram.org/bots/api#making-requests-when-getting-updates).
  Receipt is acknowledged immediately by our handler; success is announced only
  after durable storage. Actual client/network latency is not guaranteed zero.
- One final text/caption+keyboard edit replaces the two speculative UI edits.
  An explicit Telegram 429 retries only that edit, once, respecting `retry_after`
  up to 10 seconds; it never holds another approval's ACK or write. There is no
  evidence log establishing that a 429 caused the user's particular 10–15s stall.
- Worker writes serialize within an isolate; cross-isolate SHA conflicts re-read
  the newest GitHub content and reapply the exact action. In-flight duplicate
  clicks on one request do not duplicate its write. Failed persistence leaves
  retry controls and never claims approval.
- Removed the redundant GAS mutation for bot approvals. Current Android reads
  the Worker/GitHub whitelist already. Legacy builds still reading GAS can remain
  on its cache until refresh/expiry; GAS source/deployment and direct Admin-app
  actions were not changed. Telemetry's separate GAS integration is unchanged.
- Legacy callbacks now require the already configured webhook secret and channel
  administrator check, matching scoped Home/Search protection. No secret values,
  webhook URL, pending updates or connection settings were changed.

## Validation and activation

32/32 offline Node tests pass: 16 existing Home/Search tests and 16 new legacy
identity/callback tests. Includes five rapid clicks while GitHub is held open,
slow Telegram edits, conflict preservation, duplicate clicks/retries, failed
durable writes, rate limiting, authorization, exact IDs and captions.
Wrangler dry-run and both Android Debug Kotlin compile tasks passed. No Android
source/playback/UI changes, new APK or device install; no fresh audible music,
podcast, Android Auto or real Telegram clicking test is claimed.

Preflight verified local pre-task source and scoped modules against the previous
deployed bundle's source map. The live version was still the known
`d74d92b4-80ae-445a-a755-2f27c31e315b`, and both required secret names existed.
Published Worker version `ca76f6cc-a7db-4a9f-b9dc-1ef169e6d706` at 100%,
2026-10-22 22:52:47 UTC (October 23 locally).

Postflight: health/whitelist 200, exact restored row approved at version 1668,
unauthenticated legacy callback 403, unchanged webhook with zero pending updates
and no reported error. No Telegram message or simulated live approval was sent.
Actual repeated-button acceptance on the user's Telegram client remains to check.

Evidence is in ignored `app/build/reports/telegram-approval-repair/`: before
snapshots, source diff/comparison, historical commits, verified restoration plan
and before/after whitelist, test TAP, debug compile, bundled dry-run, deployment
metadata and postflight. Bundles/source snapshots may include existing embedded
credentials and must remain private/ignored.
