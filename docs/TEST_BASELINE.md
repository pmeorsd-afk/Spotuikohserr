# Playback validation baseline

Recorded after approved Step 2 on 2026-10-09. Keep running these tests; this is not an exclusion list.

Step 2 results: User debug 116/116 passed; Admin debug 111/116 passed. The 16 ownership tests passed in both flavors. User/Admin debug APKs built successfully.

## Known Admin failures

| Test class | Test | Baseline failure |
| --- | --- | --- |
| ExampleUnitTest | testNewPipeExtractorStreams | Live request expected HTTP 206 but received HTTP 403. |
| HomeNavigationAndTrackingTest | testKosherWhitelist_blocksUnapprovedPodcastImages | Test expects blocking; Admin explicitly bypasses the whitelist. |
| RecentItemsAndSafeAlbumTest | testKosherWhitelist_playlistWhitelisting | Test expects blocking; Admin explicitly bypasses the whitelist. |
| RecentItemsAndSafeAlbumTest | testKosherWhitelist_blocksUnapprovedEpisodesAndShows | Test expects blocking; Admin explicitly bypasses the whitelist. |
| RecentItemsAndSafeAlbumTest | testKosherWhitelist_podcastEpisodeWhitelisting | Test expects blocking; Admin explicitly bypasses the whitelist. |

All classes are under com.music.spotui (the whitelist tests are in its playback package).
The whitelist implementation and these tests were not modified in Step 2 or Step 3.
The NewPipe test uses the live network and can pass or fail independently of playback ownership/identity changes.

On each subsequent run, compare test names **and failure details**. Report any additional or changed failure separately; do not label it pre-existing merely because it is in the same test class.
Device playback, Waze, process recreation and startup performance still require physical-device validation.

## Podcast Home and multiselect following (2026-10-15)

Final User 398/398; Admin 394/398 with exactly the four preceding whitelist failures by class/test/message. All 19 new tests pass in both flavors: 15 catalog/cache/identity/ranking/concurrency cases and four actual batch-follow preference/revision cases. Existing playback/direct-podcast/ownership tests pass, including the live NewPipe test in both flavors. Both debug variants compile via unit-test tasks; diff check passes; all nine APK paths/hashes are unchanged. Evidence: app/build/reports/podcast-home-implementation/. No APK packaging/install and no connected ADB device. Rendered normal/small viewport, UI search/category/Back interactions and live authenticated playback remain device checks.

## Waze movable button/artwork follow-up (2026-10-14)

Final full run: User 378/379; Admin 375/379. All 21 new placement, visibility and artwork tests pass in both flavors. Admin failures match the preceding four whitelist failures exactly by class/test/message. User's sole failure was ExampleUnitTest.testNewPipeExtractorStreams expecting HTTP 206 beyond the 1 MiB boundary and receiving HTTP 403. One isolated retry passed (1/1); the full-run result remains 378/379, not 379/379. Both debug variants compile through unit-test tasks, diff check passes, and all nine existing APK paths/hashes remain unchanged. No APK packaged or installed. Full-run summaries and the failing XML were preserved before the retry replaced Gradle's User test-results directory; evidence is in app/build/reports/waze-overlay/. No connected ADB device: actual Waze visibility/placement, rendered viewports and music/direct-podcast/background playback remain acceptance checks.

## Android Auto presentation/control follow-up (2026-10-14)

User 358/358; Admin 354/358. Exactly four whitelist failures match the preceding Android Auto run by class/test/message. All 15 new browse-window, search-history, radio-identity and session-adapter tests pass in both flavors. The initial User live NewPipe request returned HTTP 403; the final full run passed it in both flavors. Initial/final evidence is retained in app/build/reports/auto-polish/. Both debug variants compile via unit-test tasks, without APK packaging; all nine original APK paths/hashes are unchanged. No connected ADB device: actual car/controller, regular music/direct-podcast and viewport acceptance remain device checks.

## Step 3 comparison (2026-10-09)

User: 142/142 passed. Admin: 138/142 passed. The four whitelist failures matched the Step 2 test names and failure messages exactly; no new/changed failures. The live NewPipe test passed in both flavors this time. All 26 Waze resume tests and all 16 ownership tests passed in both flavors. Spotify: 19/19; InnerTube: 6/6. Both debug APKs built; git diff --check passed. No device was connected.

## Step 4 comparison (2026-10-10)

User: 164/164 passed. Admin: 160/164 passed. The four whitelist failures match the Step 3 baseline exactly by class, test and assertion message (machine comparison: no differences). NewPipe passed in both flavors. All 96 Steps 1–4 focused tests passed, including the 22 new headless integration tests, in both flavors. Spotify 19/19 and InnerTube 6/6 remained UP-TO-DATE. Both debug APKs built; User APK signature and git diff --check verified. Combined Gradle exit 1 is attributable solely to the four accepted Admin failures. No device connected; physical Waze acceptance is pending.

## Per-item progress comparison (2026-10-11)

Final User: 182/182 passed. Admin: 178/182 passed. Exactly the same four whitelist failures by class, test and assertion message; no new failures. All 18 new PlaybackProgressStoreTest cases passed in both flavors, alongside existing playback tests. No APK build requested or performed; both debug variants compiled via unit tests. git diff --check passed. Existing APK hashes are unchanged.

## Playback performance follow-up (2026-10-12)

Final User: 221/221; Admin: 217/221. The four whitelist failures match the Step 3 baseline exactly by class, test and message, with no differences. All 27 new app helper/playlist tests pass in both flavors; live NewPipe tests pass too. Spotify 22/22 and InnerTube 6/6 (UP-TO-DATE in the final run; the three new Spotify parser tests passed in the earlier module run). Both debug variants compiled via unit-test tasks. No APK packaged; all 10 output APK hashes are unchanged. Evidence: app/build/reports/playback-performance-fix/results.json and validation-complete.log. Do not treat these results as a phone startup benchmark or reproduction of the affected VPN device.
