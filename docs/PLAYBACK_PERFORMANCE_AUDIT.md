# Playback / playlist latency and network audit — 2026-10-11

Historical audit: the paths, line references and findings below describe the pre-fix code. The approved implementation on 2026-10-12 is documented in the final section; do not interpret the original findings as the current implementation.

## Scope and outcome

Read-only audit of production code, comparison with current BitChord, existing regression suites, and opt-in live JVM network probes. No production source, dependencies, playback settings, matching, VISIONOS, RSS, UI or APK changed.

The user reports occasional slow starts and one device on which no track plays. Their screenshot shows a VPN-style key icon, 0:00 position/duration, HQ and a Pause button. These do not identify the failing network stage, prove that audio is ready, or establish the phone model/Android version. HQ is also a fallback label for an empty quality string (PlayerScreen.kt:1457).

There is no connected Android device. The affected device, VPN provider/settings, app version and behavior with VPN disabled are unknown. We cannot reproduce that user's failure or measure audible startup/Compose playlist rendering from this host.

## Measured evidence

The diagnostic harness called the actual project YouTube API and YTPlayerUtils resolver, with anonymous public requests. No account credentials were read. Android ConnectivityManager was a JVM mockable-jar instance; HIGH quality was explicit. These are network/resolver measurements, not device playback benchmarks.

| Probe | Observed result |
| --- | --- |
| Search for the screenshot's title/artist | 20 results; 377/405 ms in one run, 1579/390 ms in another |
| Direct VISIONOS request for existing test video qLwLOI4di7I | Initially UNPLAYABLE; a later direct probe after visitor initialization returned ClientRequestException |
| Production YTPlayerUtils resolver, same video | Success in 2172/1770 ms in the first production probe |
| Repeated production probe in a fresh test invocation | Success in 1825/158 ms; both returned URLs identified VISIONOS |
| First media request from those two resolved URLs | HTTP 206, audio/webm, at least 4096 readable bytes; response headers after 61/41 ms respectively |
| Installed Media3 defaults read from its actual classes | Start threshold 2500 ms of buffered media; rebuffer threshold 5000 ms |

The direct-client refusal was not persistent. A global VISIONOS outage is not established. Successful resolution on this host does not exclude VPN/CDN/IP/DNS/TLS failures on another network. The samples are too few for percentiles or representative performance claims. Initial media response time excludes audio decoder startup and full buffer filling.

Evidence and reproducible opt-in harness: app/build/reports/playback-performance-audit/
- bitchord-revision.json and downloaded source snapshots
- network-probe-initial.xml, network-probe.xml, media-probe.xml
- probes/PlaybackNetworkAuditProbe.kt and audit.init.gradle
- validation.log, probe-validated.log, probe-media.log, validation-final.log
- results.json

The diagnostic JUnit methods collect outcomes, rather than asserting that an external service is always available. Do not count successful diagnostic execution as an Android regression test. Their source is under ignored build reports and is included only by the explicit Gradle init script; no permanent live-network tests were added.

## Exact current paths

Track selection -> SongPlayer.playSong -> pause/save outgoing item and create ownership generation -> resolveStreamUrl -> local/download/cache OR selected Deezer attempt OR YouTube -> resolveYtPlayback -> cached video ID OR Spotify-to-YouTube search -> YTPlayerUtils player/client fallback and media validation -> setMediaItem/prepare/restore position/playWhenReady -> ExoPlayer opens/buffers/decodes media.

The successful YouTube fast path already precedes optional lossless work. Quality upgrade starts in the background after 1200 ms. Explicit Deezer-primary selection can add its own initial attempt. Podcast direct audio is separate and was not modified.

PlaylistViewModel.loadPlaylist starts separate metadata and track flows. SpotifyTokenProvider serializes token renewal with a mutex, so token refresh is already protected. Spotify metadata uses fetchPlaylist(limit=25), while songs use another fetchPlaylist(limit=100); both fetch overlapping content. The first song page is emitted immediately, then subsequent pages are emitted. For YouTube playlists, playlist() calls playlistDetails(), while the metadata flow separately calls playlistDetails() again.

## Confirmed code findings

1. **Current speed log is not time to audio.**
   SongPlayer.kt:541 logs "start audio" immediately after playWhenReady=true, before STATE_READY/isPlaying/audio output. It excludes some synchronous selection work and all subsequent buffering. Optimize against a monotonic tap-to-first-playing measurement, not this log.

2. **Concurrent work for the same track is not coalesced.**
   SongPlayer.kt:587 and :603 check only a completed streamCache entry before launching work. PlaylistScreen.kt:95 launches prefetch whenever its songs list changes (including pagination). PlayerScreen.kt:303 also prefetches neighbors. A tap before these jobs finish can perform another resolution. List prefetch launches into SongPlayer's long-lived scope, so cancellation of the screen's LaunchedEffect does not cancel its child work. This is a code-level concurrency opportunity; its actual device impact has not been benchmarked.

3. **List prefetch warms URLs, not audio bytes.**
   SongPlayer.kt:603 resolves the first four songs sequentially but does not call cacheIntro; the separate prefetch() path does. Disabling the preload setting only guards cacheIntro (:675), not these URL-resolution jobs. A URL cache hit therefore does not guarantee immediate audible playback.

4. **URL caches lack lifetime/network invalidation.**
   streamCache is query -> URL. PlaybackData.streamExpiresInSeconds is returned by YTPlayerUtils but not stored with that cache entry. Disk CacheDataSource has no custom key; default URI keys mean refreshed signed URLs can create separate audio-cache entries. Recovery exists, but stale URLs can first fail and require a new resolution. A VPN/network switch is a possible trigger, not a proven cause here.

5. **Default startup buffering differs from BitChord.**
   SongPlayer.createPlayerWithFilter (:1849) does not configure LoadControl. The installed defaults are 2500/5000 ms of audio; current BitChord configures 500/2000 ms. These are media-buffer amounts, not fixed wall-clock waits. Reducing them without testing weak networks could replace startup delay with stuttering.

6. **Failure chains can take a long time and can fail quietly.**
   InnerTube.kt:92, :127 allows 30-second connect / 60-second request limits. YTPlayerUtils uses 20-second connect/read limits for media validation (:574), with sequential client fallbacks and NewPipe. SongPlayer.resolveYtPlayback (:1762) tries candidate IDs without an overall interactive deadline. A failed direct/cached video is not added to the later tried set, so it can be attempted again through resolveVideoCandidates; a cache hit there also prevents a fresh search in that call.
   A null result eventually displays a brief toast; however playSong's outer Exception catch (:547) only logs. Pending resolution has not yet committed an owned media item, so the prior Play/Pause synchronization fix cannot itself turn a pending request into an error display. A network-wide failure may also auto-advance through the queue via PlaybackService.onStreamFailed.

7. **Playlist requests can be reduced without changing stream clients.**
   The two metadata/content requests overlap as described above. Re-entering a new PlaylistViewModel has no playlist data cache in these functions. The per-ViewModel playlistKey prevents repeated calls for the same ID, even after an error, so simply requesting the same ID again in that ViewModel does not retry.

8. **No explicit VPN rejection found.**
   No VPN transport/device model rejection or Wi-Fi-only playback gate was found in the examined app/InnerTube/Spotify sources. ConnectivityManager.isActiveNetworkMetered selects quality; it does not deny playback. The merged User manifest includes INTERNET and ACCESS_NETWORK_STATE. Network security config already trusts system and user certificates. Do not add trust-all TLS, bypass filters, or change DNS/IP-family handling on speculation.

## Comparison source

Inspected BitChord main at commit fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f (2026-10-10). This is a comparison with today's source, not proof it equals the revision originally copied into SpotUI.

- [StreamResolver](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/StreamResolver.kt): per-video in-flight coalescing, URL lifetime checks and bounded failure handling.
- [AudioCache](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/app/src/main/java/com/music/bitchord/playback/AudioCache.kt): stable video/rendition cache keys, controlled queue read-ahead and delayed background traffic.
- [PlaybackService](https://github.com/kushagrasinghx/BitChord/blob/fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f/app/src/main/java/com/music/bitchord/playback/PlaybackService.kt): explicit start/rebuffer thresholds.

Do not transplant these implementations wholesale: SpotUI maps Spotify identities, preserves typed podcast ownership and supports source/quality changes. A stable audio cache key must include rendition/format/source identity so bytes from different files cannot be mixed.

## Minimal safe follow-up order

1. Add local, privacy-conscious stage timings and terminal error classification: selection, identity lookup, source resolution, prepare, first isPlaying; distinguish cancellation, DNS/TLS/HTTP/refusal/timeout. Never log tokens/cookies/signed media URLs. Ensure an old request cannot update the new request's error state. This is diagnostics, not server telemetry.
2. Coalesce identical in-flight *source* work and bound background prefetch; prioritize the user's active request and retain Step 2 generation guards for every commit. Include identity, source, quality and network/session lifetime in reuse decisions. Do not share a callback that mutates current UI across unrelated owners.
3. Share playlist metadata + first-page loading and allow retry after failure; retain first-page display, order, full pagination and whitelist behavior.
4. Add stream URL expiry/invalidation, then evaluate rendition-safe disk cache reuse separately.
5. Only after device measurements, trial a smaller audio start buffer while retaining a safer rebuffer margin.

Required regression cases: same-track prefetch + tap performs one source request; cancellation of a prefetch waiter does not cancel active playback; A finishes after B and cannot commit; source/quality changes do not reuse wrong bytes; expired URL/network change recovers exact identity; playlist header and songs use one first-page result; pagination order and retry remain correct; DNS timeout/HTTP 403 leads to bounded visible failure, not endless skip/retry. Retain all podcast/Waze/progress tests.

## Affected-user diagnosis

Request one controlled reproduction with app version, same track, VPN on/off (where the user can do so), and Wi-Fi versus cellular. Observe whether public YouTube audio also loads under that same connection. If VPN-off works, investigate that VPN/filter/CDN path; this alone would still not identify DNS versus HTTP/TLS versus IP reputation. Capture only relevant sanitized playback error/stage logs. Reinstalling does not rule out a network issue and may also restore backed-up settings.

No claim that this user's issue is fixed, or that all devices/networks are unblocked.

## Validation result

Normal project configuration was restored after the opt-in probes, and the full app suites were run again: User 194/194; Admin 190/194. The four Admin failures exactly match the accepted Step 3 baseline by class/test/message. All prior ownership, identity, progress and button-state tests remain green. Spotify 19/19 and InnerTube 6/6 module results were UP-TO-DATE in the requested combined run; they were not fresh network benchmarks.

Debug sources compiled/up-to-date through unit-test tasks. No assemble/package APK task ran, and existing APK hashes match the pre-existing snapshot. git diff --check passed. Production source and permanent tests were not edited for this audit; only this report and CURRENT_WORK.md were updated.

## Approved implementation follow-up — 2026-10-12

The user approved fixing the report's safe items, retaining the no-APK constraint.

### Current behavior

- Identical YouTube source work is shared between prefetch and playback; each caller retains its own ownership/Player commit. Keys include query, matching inputs, requested quality, source settings, account/session and network context. Cancelling one waiter does not cancel another caller's work. Failed work can be retried. A cached/direct video ID is not attempted twice in the same candidate pass.
- In-memory stream and PlaybackData caches are bounded to 128 entries, use monotonic expiry and a conservative five-minute maximum, and subtract a safety margin from available URL lifetimes. Stream URLs also honor the signed expire parameter. Network/account/quality/settings changes produce a miss; recovery increments the source revision. A stale cache reader cannot evict a new-context entry. Disk audio-cache keys remain URI-based.
- List and neighbor prefetch use one worker, with at most four queued items and a delay before each item. New lists replace queued work. Preloading respects the existing preference; list items register the same metadata used on selection and can warm intro bytes. New background work waits while interactive music startup is pending. A preload already in flight can finish; it is not an unconditionally preempted transfer. Background quality upgrade also waits for startup to finish.
- PlaybackTiming reports generation, stage and monotonic elapsed milliseconds: request, source_resolved, prepare, first_playing and terminal failure/pause. It measures from accepted request ownership through first isPlaying, not physical speaker output or time spent before SongPlayer accepts the request. New timing/failure logs exclude titles, account data and signed URLs; existing legacy logs are outside this change.
- Shared YouTube resolution has a cooperative 30-second budget. Music startup and recovery have a 45-second Main-thread watchdog that revokes stale ownership, even if a blocking network call finishes later. A music IO failure gets one automatic recovery attempt per generation; exhaustion stops with feedback instead of silently traversing the queue. Play explicitly retries the exact item under a new generation. Retry actions are token-bound and updated on crossfade promotion; an external podcast takeover clears the old music retry. Normal completion still advances the queue.
- DNS/TLS/HTTP/timeout causes are classified when available. Lower-level resolvers sometimes return null without the cause, so those failures remain generic unavailable errors; diagnostics do not prove a specific VPN mechanism. Pause during an unresolved music request cancels its ownership safely and retains an explicit same-item retry.
- PlaylistViewModel uses one combined metadata/content request. Spotify parses the existing header and track models from the same response; YouTube calls playlistDetails once. The first page is emitted immediately. Further pages preserve order and advance by raw server items consumed, including unavailable entries. Cancellation stops an old list update; failures allow a same-ID retry and retain pages already displayed. The old standalone playlist APIs remain for other consumers; no account-crossing playlist cache was added.

### Scope and validation limits

New app helpers: SharedResolution, PlaybackSourceCache, BackgroundPreloader, PlaybackStartup, PlaybackFailure, PlaybackRetry and PlaylistContentLoader. PlaybackOwnership adds revocation. Integration changes are in SongPlayer, Api, AppRepository, PlaylistViewModel and prefetch call sites in Playlist/Album/Artist/LikedSongs/Search screens. Spotify reuses its existing metadata/track parsing through playlistPage. No UI layout, music matching algorithm, TrackVideoCache, underlying InnerTube/VISIONOS clients, RSS, EpisodePlaybackResolver or telemetry changes.

Buffer thresholds and rendition-safe disk keys remain a separate device-tested step. JVM tests exercise production helpers and parsers, not Android callbacks, actual audio, Compose rendering or VPN transport. No physical device is connected. Final validation is recorded in CURRENT_WORK.md and app/build/reports/playback-performance-fix/results.json.

### Device checks when a build is separately requested

1. Compare cold and repeated starts for the same tracks on the same connection; record time from tap to audible audio. Include a prefetched track, an uncached track and an offline download.
2. Open Spotify, YouTube and custom playlists; check header, order, full pagination (including more than 100 items), unavailable entries and retry after a network failure.
3. Tap A then B quickly; B must remain selected/audible. Check crossfade A to B, then a playback failure and Play: only B may retry.
4. Test no connection and a refused audio request: bounded feedback, no endless queue skipping; restore connectivity and tap Play to retry the same item. Test pause while loading as well as notification/lock-screen pause/resume.
5. On the affected phone, compare the same item with its VPN on/off if possible, and Wi-Fi/cellular. Record app version and the visible error. Do not infer the cause from the VPN key icon alone.
6. Retest music to podcast via Waze, rapid podcast A/B, saved positions after switching and process recreation, and MiniPlayer/full-player identity and button state. Podcast audio must never enter music matching.
