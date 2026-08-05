# Task 3 report — Android Media3 B-6 proof

Evidence attempt commit: `4750f01`

Source-repair commits: `4750f01`, `426111a`

## Implemented

- Supported Android KMP `withDeviceTest` configuration, using `AndroidJUnitRunner`.
- Deterministic 10-second AVC/AAC fixture. SHA-256: `8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a`.
- Fixture verifier fails closed; tests never generate the asset.
- Feasibility-only Media3 `Transformer` exporter writes `<random>.partial`, renames only on terminal success, and deletes partial/final output on cancellation or terminal error.
- Explicit H.264/AAC output request and `setEnsureFileStartsOnVideoFrameEnabled(true)`.
- No calls to `experimentalSetMp4EditListTrimEnabled` or `experimentalSetTrimOptimizationEnabled`.
- MP4 scanner supports 32-bit, 64-bit, and `size == 0` lengths; descends through `moov`, `trak`, `mdia`, `minf`, `stbl`, and `edts`.
- Device test assertions cover edit-list absence, first video PTS zero, duration tolerance, A/V start skew, and cancellation cleanup.

## Fresh verification

- `tools/feasibility/verify_fixture.sh`: PASS.
- `./gradlew :video-clip-editor-core:assembleAndroidTest`: PASS.
- First test compile intentionally failed before implementation: unresolved `exportFixture`, `Mp4BoxScanner`, `probe`, and `startExportFixture`.

## Device validation

- API 23: BLOCKED. `:video-clip-editor-core:connectedAndroidDeviceTest` failed with `com.android.builder.testing.api.DeviceException: No connected devices!`; installed SDK platforms are 34, 35, 36, and 36.1 only.
- API 36: BLOCKED. Same connected-device failure. Installed AVD `Pixel_9a` was launched headlessly, then exited before `adb` detected it; the launch log was empty.
- Exact reports: `docs/feasibility/b6-api23.json`, `docs/feasibility/b6-api36.json`.

No PTS/duration/skew/codec/cancellation runtime values are claimed until both connected-device executions pass.
