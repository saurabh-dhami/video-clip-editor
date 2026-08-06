# Android HEVC Release Gate — 2026-08-06

**Disposition:** HOLD. Automated KMP and Android evidence is green. Required physical Samsung evidence is absent: `RZCX519T5FL` was not connected. Do not publish Android V1 until that device runs the AVC and HEVC smoke.

**Scope:** standalone `video-clip-editor` only. No OneOnOneArena source, integration, or data was used or changed.

## Fresh command evidence

| Gate | Command | Result | Wall time |
| --- | --- | --- | --- |
| KMP, Compose, and iOS simulator | `./gradlew --rerun-tasks :video-clip-editor-core:allTests :video-clip-editor-compose:allTests :video-clip-editor-core:iosSimulatorArm64Test :video-clip-editor-compose:iosSimulatorArm64Test` | `BUILD SUCCESSFUL`; all 33 actionable tasks executed. `iosSimulatorArm64Test` ran for core and Compose. | 24.5 s |
| Android instrumentation and demo build | `ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest :demo-android:assembleDebug` | `BUILD SUCCESSFUL`; 43 instrumentation tests completed; demo debug APK assembled. | 6.4 s |
| Demo launch | `adb -s emulator-5556 shell am start -W -n com.oneononearena.videoclip.demo/.DemoActivity` | `Status: ok`; process `23215`; top resumed activity was `DemoActivity`. | 0.6 s (cold launch 629 ms) |

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

## Required Samsung physical smoke — blocked

Command:

```text
adb devices -l
adb -s RZCX519T5FL shell getprop ro.build.version.sdk
```

Observed result:

```text
List of devices attached
emulator-5556          device product:sdk_phone64_arm64 model:Android_SDK_built_for_arm64 device:emu64a transport_id:7

adb: device 'RZCX519T5FL' not found
```

Required before release: connect `RZCX519T5FL`, record model/API, run the AVC and HEVC fixture exports through the production factory, record result code, output MIME/path while leased, range duration, and `clearTemporaryFile()` result. A typed `DEVICE_ENCODER_UNAVAILABLE` is acceptable only if recorded exactly; no fabricated success or waiver.

## Frozen API and scope

- `git diff --unified=0 92f78412796113f2abe27f55be0125e9373c9f1c HEAD --` on the frozen common contract, iOS factory, and `ClipEditorScreen` produced no output.
- `git diff --check 92f78412796113f2abe27f55be0125e9373c9f1c HEAD` passed.
- The standalone diff contains no OneOnOneArena path.
- The only pre-existing untracked worktree artifacts are documentation under `docs/superpowers/specs/` and JVM `java_pid*.hprof` files. The HPROF files are not part of this gate and must not be committed or deleted by it.

## Release decision

**HOLD:** Android V1 has passing emulator and KMP evidence, but the explicitly required Samsung `RZCX519T5FL` AVC/HEVC physical smoke is not available. Re-run this gate after reconnecting that serial; retain the same frozen API and cleanup assertions.
