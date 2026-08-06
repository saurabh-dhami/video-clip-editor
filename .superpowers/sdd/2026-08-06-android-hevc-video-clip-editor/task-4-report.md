# Task 4 report — X1 Media3 export and temporary ownership

Initial commit: `7d9a45f feat(android): export SDR HEVC clips through Media3`

Repair commit: `bccb310 fix(android): bind temporary leases to file identity`

## Rejected review and repair

Independent X1 review `dec_20260806_135846_2901ef` rejected the initial commit: lease deletion had no immutable file identity, same-path replacement could be deleted, and real HEVC production evidence was absent.

Repair changes:

- An issued lease now records canonical library root/session parent, final basename, opaque id, `st_dev`, `st_ino`, and size.
- Before `Os.remove`, clear verifies issued identity, direct canonical containment, terminal regular-file type using `lstat`, and recorded device/inode/size. Unknown, foreign, replaced, directory, and symlink entries return `TempDeleteResult.Failed`; only the lease object after a successful clear reports `AlreadyCleared`.
- Session close now removes only tracked pending paths; it does not enumerate/delete unknown entries in the session directory.
- Added a provenance-verified, immutable AOSP CTS HEVC/AAC asset and a real production factory HEVC round-trip test.

## Delivered

- Added `Media3ClipMediaEngine`, the Android production `ClipMediaEngine` adapter. It probes AVC/HEVC SDR topology, emits thumbnails, clips through Media3 Transformer, and requests H.264 video plus AAC audio output.
- Made each production session use its own engine cancellation state. Session closure cancels the active export before acquiring its existing export mutex.
- Added `AndroidOwnedTempFileStore`. It creates random directories only below `Context.cacheDir/video-clip-editor`, writes `.partial`, publishes `.mp4` only after engine success, and issues opaque `TemporaryClipLease` values.
- Lease clearing is bounded to the issued immutable identity; unissued/foreign/replacement anomalies fail without deletion. Terminal symlinks are refused by `lstat`; no cleanup path is recursive.
- Session close preserves issued leases, so the host can clear an already-returned temporary file after closing the editing session.

## TDD and validation

- RED: `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest`
  - Failed at `compileAndroidDeviceTest`: unresolved `AndroidOwnedTempFileStore` and its store-issued lease surface.
- GREEN compile: `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:compileAndroidMain :video-clip-editor-core:compileAndroidDeviceTest`
  - Passed.
- GREEN ownership: same device command above
  - Passed: 6 tests on `ClipEditor_API36`.
- Regression: `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest`
  - Passed: 32 tests on `ClipEditor_API36`.
- `git diff --check` passed.

Repair RED:

- The lease identity suite failed as expected before repair: unissued source/host/foreign cleanup returned `AlreadyCleared`, and same-path regular replacement was deleted.

Repair GREEN:

- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest`
  - Passed: 7 tests on `ClipEditor_API36`.
- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidVideoClipEditorIntegrationTest,com.oneononearena.videoclip.HevcClipRoundTripTest`
  - Passed: production factory output/cleanup, deterministic cancellation cleanup, and HEVC round trip.
- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest`
  - Passed: 35 tests on `ClipEditor_API36`.

## Re-review exception-safety repair

The X1 re-review found two remaining platform-exception paths: unguarded canonicalization during temporary session creation and unguarded identity capture after rename. Either could escape a typed result or leave an output without a lease.

- `AndroidOwnedTempFileStore.createSession()` now contains `IOException` and `SecurityException` from all root/session canonicalization behind `TempStoreCreateException`, which the editor maps to `FailureCode.TEMP_CREATE_FAILED`.
- Publication records the pending file's `lstat` identity before rename. It captures and compares the published identity after rename; a failed/changed capture rolls back only the generated destination when its device, inode, and size still match the pre-rename identity. A pre-existing or replaced output is never removed.
- The store injection seam is internal-only. No public API or KMP/iOS contract changed.
- Focused tests cover typed canonicalization failure, failed identity capture with generated-output rollback, and an identity-capture race that replaces the final path; the replacement remains intact.

Re-review RED:

- Added focused device tests before the production exception seams. Compilation failed because the store injection/factory seams did not yet exist.

Re-review GREEN:

- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest`
  - Passed: 10 tests on `ClipEditor_API36`.
- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest`
  - Passed: 38 tests on `ClipEditor_API36`.
- `git diff --check` passed.

## Re-review Media3 failure classification repair

The retained X1 typed-failure review found that decoder initialization/format failures were incorrectly returned as `UnsupportedCode.DEVICE_ENCODER_UNAVAILABLE`.

- `media3ExportFailure` is an internal-only classifier used by the Media3 listener. Only `ExportException.ERROR_CODE_ENCODER_INIT_FAILED` returns `UnsupportedCode.DEVICE_ENCODER_UNAVAILABLE`.
- HEVC decoder initialization, decoder-format, encoder-format, and all other Media3 export failures return `EngineExportResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, ...))`.
- No public contract, iOS, Compose, demo, or OneOnOneArena code changed.

Failure-mapping RED:

- Added `Media3ExportFailureMappingTest` before the classifier. The focused Android device-test compile failed with unresolved `media3ExportFailure`.

Failure-mapping GREEN:

- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.Media3ExportFailureMappingTest`
  - Passed: 4 tests on `ClipEditor_API36`.
- `env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest`
  - Passed: 42 tests on `ClipEditor_API36`.
- `git diff --check` passed.

## HEVC fixture and device evidence

- Added `assets/fixtures/aosp-cts-hevc-aac-480x360-10s.mp4` (961 KiB) from immutable AOSP CTS revision `5b43cf9`; its source URL, SHA-256 `887363f6bcb9c270fb6e84b61177b65ae2db2a6fb65f132a94bac4a1d518dda5`, HEVC/AAC SDR topology, attribution, and CC BY 3.0 URI are in `assets/fixtures/AOSP_CTS_HEVC_AAC_NOTICE.md`.
- `HevcClipRoundTripTest` passed on `emulator-5556`: public production factory -> open session -> three frames -> HEVC clip -> H.264/AAC MP4, cache-owned absolute path, expected duration range, unchanged source SHA-256, and clear/twice-clear semantics.
- Current `adb devices -l`: only `emulator-5556`; physical Samsung proof remains required when the Samsung reconnects. This report does not waive that device validation.

## HEVC runtime-evidence log

- `HevcClipRoundTripTest` now records the actual returned absolute `TemporaryClipFile` path, extracted output video MIME, extracted output audio MIME, `clearTemporaryFile()` result, and whether the file remains after cleanup. The log tag is `VideoClipEditorHevc` and the runtime line is `HEVC_OUTPUT path=<actual path> videoMime=<actual MIME> audioMime=<actual MIME> cleanup=<actual typed result> existsAfterCleanup=<actual boolean>`.
- The values are captured from the real export before the test's `finally` cleanup. The test first asserts library-root ownership below `Context.cacheDir/video-clip-editor`, AVC/AAC output topology, source SHA-256 preservation, and duration; it then calls the public cleanup API, records `Cleared` plus `existsAfterCleanup=false`, verifies a second clear returns `AlreadyCleared`, and keeps `output.delete()` only as a failure-path safety net.
- This is test-only runtime instrumentation. It does not change directory generation, lease identity, random library-owned output placement, cleanup behavior, or any production API. The existing emulator evidence above remains the latest completed run; this log addition has not been run concurrently with the pending Samsung smoke gate.

## Scope

- No public contract, iOS, Compose, demo, OneOnOneArena, or pre-existing untracked document/heap-dump files changed.
- Independent task review is required. This report is not self-acceptance.
