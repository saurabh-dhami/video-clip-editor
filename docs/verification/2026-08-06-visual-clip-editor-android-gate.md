# Visual Clip Editor — Android V1 Verification

State: PASS on API23 emulator and Samsung SM-S928B. iOS runtime implementation remains deferred; common/iOS contracts compile and execute in simulator tests.

## Verified product scope

- Local MP4 input through absolute private file path.
- H.264/AVC + AAC input.
- H.265/HEVC + AAC SDR input when the device decoder is available.
- Typed `Unsupported`/failure results when codec capability is unavailable; no platform exception exposed.
- Video preview, 24-frame thumbnail strip, pan, scrub, draggable start/end handles, selected-range playback loop, and Done export.
- Temporary MP4 output through an absolute library-owned path.
- Idempotent temporary-output cleanup.
- Demo-owned imported input deleted only after terminal editor teardown completes.
- Host/provider source remains unchanged.
- Android host need not be KMP. Compose remains a separate optional module.

## Frozen repository fixtures

- AVC: `video-clip-editor-compose/src/androidDeviceTest/assets/fixtures/avc-aac-10s-30fps.mp4`
  - SHA-256: `8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a`
- HEVC: `video-clip-editor-core/src/androidDeviceTest/assets/fixtures/aosp-cts-hevc-aac-480x360-10s.mp4`
  - SHA-256: `887363f6bcb9c270fb6e84b61177b65ae2db2a6fb65f132a94bac4a1d518dda5`
- HEVC notice: `video-clip-editor-core/src/androidDeviceTest/assets/fixtures/AOSP_CTS_HEVC_AAC_NOTICE.md`
  - SHA-256: `6a24070f6a283125c708ed16203b1ef8efc20cb2ef60a38c7f26851f4759af79`

## Fresh verification — 2026-08-11

### Common, iOS contract, and demo

- Core iOS-simulator tests: 14/14, zero failures.
- Compose iOS-simulator tests: 47/47, zero failures.
- Demo JVM tests: 16/16, zero failures.
- iOS test binary link, Android device-test compile, and demo debug APK assembly: PASS.
- Demo APK SHA-256: `0802e33fd1812fb9b0831ce84ebb55a9635cca5a1f91433e9043520984a22e4c`.

### Android API23

- Target: `ClipEditor_API23`, Android 6.0 / API23.
- Core connected tests: 46/46, zero failures.
- Compose connected tests: 19/19, zero failures.
- Aggregate Compose runner originally exhausted the API23 test APK's 48 MiB normal heap during all-class discovery. Filtered Media3 class passed 11/11. Test-only `android:largeHeap="true"` raised only the instrumentation APK limit; unchanged aggregate suite then passed 19/19. Production library/demo manifests are unaffected.
- Real early demo evidence: `docs/verification/evidence/2026-08-09-v4-r2-api23-early/evidence-index.json`.

Early demo evidence proves real `OpenDocument`, fitted preview, thumbnails, handles, playhead, Done export, absolute temporary output, Clear, removal of library/demo-owned files, provider-source hash preservation, resumed process, and zero demo crash/ANR matches.

### Samsung SM-S928B

- Serial: `RZCX519T5FL`.
- Android platform report: API36.
- Core connected tests: 46/46, zero failures.
- Compose connected tests: 19/19, zero failures.
- Core result XML SHA-256: `e8c4cba9ee3aff27805d2079d5dfabe534900fdc591df7b88acbde101c6eaedd`.
- Compose result XML SHA-256: `91c65126559f18ad871a6031bab9279051edbc4fbb9108fac5e777eade6c6e17`.
- Real HEVC round-trip passed: `productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource`.
- Typed no-decoder preflights passed for AVC and HEVC.
- Strict real editor flow passed: preview, thumbnails, pan/scrub, range commit, selected loop, export, Released-before-close, lease cleanup, and second cleanup.
- Verified demo APK installed and cold-launched successfully. Live UI hierarchy showed `SurfaceView`, thumbnail scroller, range controls, playhead, Back, Play, Done, and Clear temp.

## API stability

- Original five-parameter `ClipEditorScreen` declaration retained.
- Additive overload supplies `onTerminalLifecycleComplete` without exposing Android/iOS/Media3 types.
- Common lifecycle owns terminal ordering and callback semantics; future iOS implementation can use the same contract.
- Android production engine remains internal.

## Privacy and ownership

- No personal media copied into repository evidence.
- Retained API23 visual evidence uses only the frozen repository AVC fixture.
- Library cleanup targets only files it created.
- Demo cleanup targets only its canonical app-private imported copy.
- Picker/provider originals are never deleted or mutated.

## Deferred

- Native iOS clipping implementation and iOS device validation.
- Publication coordinates/signing and external Maven distribution, if required for release.
