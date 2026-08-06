# Android HEVC Release Gate — 2026-08-06

**Disposition:** HOLD. Automated KMP and Android evidence is green, and the required Samsung physical AVC/HEVC smoke is now green. Fresh Android API-23 validation cannot run: `ClipEditor_API23` cannot be booted with only 641 MiB free on the host data volume. Do not publish Android V1 until that API-23 gate is re-run successfully.

**Scope:** standalone `video-clip-editor` only. No OneOnOneArena source, integration, or data was used or changed.

## Fresh command evidence

| Gate | Command | Result | Wall time |
| --- | --- | --- | --- |
| KMP, Compose, and iOS simulator | `./gradlew --rerun-tasks :video-clip-editor-core:allTests :video-clip-editor-compose:allTests :video-clip-editor-core:iosSimulatorArm64Test :video-clip-editor-compose:iosSimulatorArm64Test` | `BUILD SUCCESSFUL`; all 33 actionable tasks executed. `iosSimulatorArm64Test` ran for core and Compose. | 24.5 s |
| Android instrumentation and demo build | `ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest :demo-android:assembleDebug` | `BUILD SUCCESSFUL`; 43 instrumentation tests completed; demo debug APK assembled. | 6.4 s |
| Demo launch | `adb -s emulator-5556 shell am start -W -n com.oneononearena.videoclip.demo/.DemoActivity` | `Status: ok`; process `23215`; top resumed activity was `DemoActivity`. | 0.6 s (cold launch 629 ms) |
| Samsung focused instrumentation | `ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest` | `BUILD SUCCESSFUL`; `SM-S928B - 16` test log records `OK (17 tests)`. | 2.424 s test runtime |

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

Physical result artifact: `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/SM-S928B - 16/testlog/test-results.log`.

- The physical suite reports `OK (17 tests)` in `2.424` seconds, including the HEVC production-factory round trip, the AVC host-flow fixture, and temporary-lease cleanup cases.
- `AndroidVideoClipEditorIntegrationTest.factoryFlowKeepsHostCopyAfterClearingLibraryLease` passed with the AVC/AAC fixture `avc-aac-10s-30fps.mp4`.
- `HevcClipRoundTripTest.productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource` passed. Its durable physical log line is:

  ```text
  HEVC_OUTPUT path=/data/user/0/com.oneononearena.videoclip.test/cache/video-clip-editor/1317a7e3-0808-4534-a6e2-f7cbfb46f2e7/208afc2c-c5dc-4a68-be57-522757726d1e.mp4 videoMime=video/avc audioMime=audio/mp4a-latm cleanup=Cleared existsAfterCleanup=false
  ```

  The path was absolute and within the test app's library-owned cache root while leased. The test then verified the cleared file did not remain. The log resides at `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/SM-S928B - 16/logcat-com.oneononearena.videoclip.HevcClipRoundTripTest-productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource.txt`.

This closes the required Samsung gate. It does not establish compatibility for every Android device; devices without a usable codec must still return the typed `DEVICE_ENCODER_UNAVAILABLE` result.

## API-23 emulator gate — blocked by host capacity

`/Users/sandeepdhami/Library/Android/sdk/emulator/emulator -list-avds` reports:

```text
ClipEditor_API23
ClipEditor_API36
Pixel_9a
```

The API-23 emulator exists but must not be booted/re-run on the current host state. `df -h /Users/sandeepdhami/Documents/GitHub/video-clip-editor/.worktrees/feasibility` observed:

```text
Filesystem      Size    Used   Avail Capacity
/dev/disk3s5   228Gi   193Gi   641Mi   100%
```

The existing AVD directory alone is 13 GiB (`du -sh /Users/sandeepdhami/.android/avd`). With only 641 MiB available, a new API-23 boot has already failed capacity checks and cannot safely produce fresh test XML or timing. No emulator, repository, or host files were deleted to work around this condition. Re-run the API-23 `connectedAndroidDeviceTest` after restoring sufficient host capacity; record its XML and test time before publishing Android V1.

## Frozen API and scope

- Reproducible scoped API proof: `git diff --quiet 92f78412796113f2abe27f55be0125e9373c9f1c HEAD -- video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/VideoClipEditorContract.kt video-clip-editor-core/src/iosMain/kotlin/com/oneononearena/videoclip/IosClipEditorFactory.kt video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt` exited `0` (no diff).
- Android public factory proof: the baseline and current signature are identical: `public fun createAndroidVideoClipEditor(context: Context, configuration: VideoClipEditorConfiguration = VideoClipEditorConfiguration()): VideoClipEditor`. Internal overloads are implementation detail and intentionally excluded from the frozen public surface.
- `git diff --check 92f78412796113f2abe27f55be0125e9373c9f1c HEAD` passed.
- The standalone diff contains no OneOnOneArena path.
- The only pre-existing untracked worktree artifacts are documentation under `docs/superpowers/specs/` and JVM `java_pid*.hprof` files. The HPROF files are not part of this gate and must not be committed or deleted by it.

## Release decision

**HOLD:** Samsung `RZCX519T5FL` AVC/HEVC physical smoke now passes, but fresh Android API-23 validation is blocked by host capacity. Restore host disk capacity without deleting library-owned/host-owned test evidence, boot `ClipEditor_API23`, run the same device suite, and add the XML/timing result before publication.
