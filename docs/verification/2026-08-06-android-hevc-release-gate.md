# Android HEVC Release Gate — 2026-08-06

**Disposition:** R1 EVIDENCE PASS. Automated KMP and Android evidence is green; Samsung physical AVC/HEVC smoke is green; fresh Android API-23 validation is green. No R1 verification hold remains. Independent final review still decides acceptance and publication.

**Scope:** standalone `video-clip-editor` only. No OneOnOneArena source, integration, or data was used or changed.

## Fresh command evidence

| Gate | Command | Result | Wall time |
| --- | --- | --- | --- |
| KMP, Compose, and iOS simulator | `./gradlew --rerun-tasks :video-clip-editor-core:allTests :video-clip-editor-compose:allTests :video-clip-editor-core:iosSimulatorArm64Test :video-clip-editor-compose:iosSimulatorArm64Test` | `BUILD SUCCESSFUL`; all 33 actionable tasks executed. `iosSimulatorArm64Test` ran for core and Compose. | 24.5 s |
| Android instrumentation and demo build | `ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest :demo-android:assembleDebug` | `BUILD SUCCESSFUL`; 43 instrumentation tests completed; demo debug APK assembled. | 6.4 s |
| Demo launch | `adb -s emulator-5556 shell am start -W -n com.oneononearena.videoclip.demo/.DemoActivity` | `Status: ok`; process `23215`; top resumed activity was `DemoActivity`. | 0.6 s (cold launch 629 ms) |
| Samsung focused instrumentation | `ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest` | `BUILD SUCCESSFUL`; `SM-S928B - 16` test log records `OK (17 tests)`. | 2.424 s test runtime |
| API-23 device instrumentation | `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest --rerun-tasks` | `Starting 43 tests on ClipEditor_API23(AVD) - 6.0`; `Finished 43 tests`; `BUILD SUCCESSFUL in 14s`. XML: 43 tests, 0 failures, 0 errors, 0 skipped. | 14 s Gradle wall; 6.692 s XML aggregate; 5.902 s instrumentation log |
| Final Samsung decoder/HEVC/lease proof | `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.Media3DecoderCapabilityPreflightTest,com.oneononearena.videoclip.HevcClipRoundTripTest,com.oneononearena.videoclip.AndroidVideoClipEditorIntegrationTest,com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest --rerun-tasks` | 20 tests on `SM-S928B - 16`; `BUILD SUCCESSFUL in 10s`; raw JUnit XML: 0 failures/errors/skips. | 10 s Gradle wall; 3.437 s XML; 2.715 s instrumentation |
| Post-repair API-23 full suite | Current full `ClipEditor_API23` suite after decoder repair `8391171`. | 46/46 passed. | Current post-repair result |

The first Android command without `ANDROID_HOME` failed before compilation because the worktree has no `local.properties` and the SDK variables were unset. The SDK directory existed. Supplying the verified SDK path only to the process resolved that environment defect; no repository file changed.

The host emits an expected `iosX64Test` architecture warning: iOS x64 cannot run on this ARM64 macOS host. ARM64 simulator tests were executed. The `commonTest` Android-host-test configuration warnings are non-fatal; Android device tests are the V1 Android test target.

## Fixture integrity

| Fixture | SHA-256 |
| --- | --- |
| `video-clip-editor-core/src/androidDeviceTest/assets/fixtures/avc-aac-10s-30fps.mp4` | `8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a` |
| `video-clip-editor-core/src/androidDeviceTest/assets/fixtures/aosp-cts-hevc-aac-480x360-10s.mp4` | `887363f6bcb9c270fb6e84b61177b65ae2db2a6fb65f132a94bac4a1d518dda5` |

## Android device evidence

Available test device:

| Serial | Model | Android | API | ABI |
| --- | --- | --- | --- | --- |
| `emulator-5556` | Android SDK built for arm64 | 16 | 36 | arm64-v8a |

Result XML: `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/TEST-ClipEditor_API36(AVD) - 16-_video-clip-editor-core-.xml`.

- 43 tests; 0 failures; 0 errors; 0 skipped; 3.244 s total.
- `HevcClipRoundTripTest.productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource` passed in 0.447 s. Its log selected `c2.android.avc.encoder` and reports `video/avc` from Media3 1.10.1.
- The test verified the HEVC fixture source hash, 500–2500 ms range, three thumbnails, an absolute library-cache output path under `Context.cacheDir/video-clip-editor`, output `video/avc` and `audio/mp4a-latm` tracks, output duration 1500–2500 ms, source preservation, `TempDeleteResult.Cleared`, file absence after cleanup, then `TempDeleteResult.AlreadyCleared`.
- The output path is random per export and is intentionally absent by the end of the passing test because cleanup is part of the test. The durable evidence is the asserted absolute internal-cache prefix; no host path or test artifact was retained.
- `AndroidTemporaryClipLeaseTest` passed 10 ownership/cleanup cases, including idempotent clear, host/source/foreign-path refusal, replacement-file refusal, symlink refusal, and session-close cleanup.
- `AndroidVideoClipEditorIntegrationTest` passed six cases, including source-host-copy preservation, cancellation not publishing a final file, engine routing, and temporary-lease cleanup.

## Samsung physical smoke — passed

Current device identity:

```text
adb devices -l
adb -s RZCX519T5FL shell getprop ro.build.version.sdk
adb -s RZCX519T5FL shell getprop ro.product.model
```

Observed 2026-08-06:

```text
List of devices attached
RZCX519T5FL            device usb:2-1.2 product:e3qxins model:SM_S928B device:e3q transport_id:11

36
SM-S928B
```

The final physical run exercised decoder repair `8391171`. Its raw artifacts are retained under `docs/verification/evidence/2026-08-06-samsung-r1/`, with original artifact paths and SHA-256 hashes in that directory's `README.md`.

- The final focused suite reports `OK (20 tests)` in `2.715` seconds; JUnit XML aggregate time is 3.437 seconds, with 0 failures, 0 errors, and 0 skipped. The Gradle command completed in 10 seconds.
- `Media3DecoderCapabilityPreflightTest` passed all three cases: unavailable HEVC and AVC decoders return typed unsupported before session/export starts, while an available AVC decoder retains the normal session-open path.
- `AndroidVideoClipEditorIntegrationTest.factoryFlowKeepsHostCopyAfterClearingLibraryLease` passed with the AVC/AAC fixture `avc-aac-10s-30fps.mp4`; all six integration tests and all ten temporary-lease tests passed.
- `HevcClipRoundTripTest.productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource` passed. Its final durable physical log line is:

  ```text
  HEVC_OUTPUT path=/data/user/0/com.oneononearena.videoclip.test/cache/video-clip-editor/acac8789-008d-4d0b-ba4e-ee4b668974f7/9803d95d-77bd-4083-bb15-fbdc105f3993.mp4 videoMime=video/avc audioMime=audio/mp4a-latm cleanup=Cleared existsAfterCleanup=false
  ```

  The path was absolute and within the test app's library-owned cache root while leased. The full raw JUnit XML, instrumentation stream, and HEVC log are tracked evidence, not merely ephemeral build-output citations.

This closes the required Samsung gate. It does not establish compatibility for every Android device; devices without a usable codec must still return the typed `DEVICE_ENCODER_UNAVAILABLE` result.

## API-23 emulator gate — passed

`/Users/sandeepdhami/Library/Android/sdk/emulator/emulator -list-avds` reports:

```text
ClipEditor_API23
ClipEditor_API36
Pixel_9a
```

The temporary read-only emulator booted as `emulator-5554`, Android 6.0/API23. It was stopped cleanly after testing; current `adb devices -l` no longer lists it.

Fresh result XML: `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/TEST-ClipEditor_API23(AVD) - 6.0-_video-clip-editor-core-.xml`.

- XML timestamp: `2026-08-06T10:44:46`; 43 tests, 0 failures, 0 errors, 0 skipped; aggregate test time 6.692 s.
- Instrumentation log: `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/ClipEditor_API23(AVD) - 6.0/testlog/test-results.log`; `OK (43 tests)` in 5.902 s.
- The same API23 HEVC round-trip retained a durable lease line: `HEVC_OUTPUT path=/data/user/0/com.oneononearena.videoclip.test/cache/video-clip-editor/e53b8ed4-1ba9-4d74-b69d-39079a0018b1/f6809b1f-b5af-45f7-99d0-bd283bbfe81e.mp4 videoMime=video/avc audioMime=audio/mp4a-latm cleanup=Cleared existsAfterCleanup=false`.

This replaces the earlier host-capacity hold. No emulator, repository, or host files were deleted to obtain this result.

After decoder repair `8391171`, the current full API23 suite passed 46/46. This supersedes the earlier 43-test pre-repair API23 result.

## Frozen API and scope

- Reproducible scoped API proof: `git diff --quiet 92f78412796113f2abe27f55be0125e9373c9f1c HEAD -- video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/VideoClipEditorContract.kt video-clip-editor-core/src/iosMain/kotlin/com/oneononearena/videoclip/IosClipEditorFactory.kt video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt` exited `0` (no diff).
- Android public factory proof: the baseline and current signature are identical: `public fun createAndroidVideoClipEditor(context: Context, configuration: VideoClipEditorConfiguration = VideoClipEditorConfiguration()): VideoClipEditor`. Internal overloads are implementation detail and intentionally excluded from the frozen public surface.
- `git diff --check 92f78412796113f2abe27f55be0125e9373c9f1c HEAD` passed.
- The standalone diff contains no OneOnOneArena path.
- The only unrelated untracked worktree artifact is `docs/superpowers/specs/2026-08-06-kmp-video-clip-editor-hevc-v1-design.md`; it is outside this evidence commit. The tracked raw Samsung evidence is intentionally under `docs/verification/evidence/2026-08-06-samsung-r1/`.

## Release decision

**R1 EVIDENCE PASS:** Samsung physical AVC/HEVC smoke and fresh API-23 validation both pass, alongside the recorded KMP, iOS simulator, API36, cleanup, and demo evidence. The only remaining process gate is independent final review; this evidence report does not self-accept the release.
