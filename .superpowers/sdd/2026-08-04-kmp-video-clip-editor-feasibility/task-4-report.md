# Task 4 report — frozen common KMP contract

Commit: `feat(core): add frozen video clip contract`

## Implemented

- Common-only `VideoSourcePath`, `ClipRange`, metadata, configuration, and `FrameStripRequest(frameCount)` models.
- Frozen `VideoClipEditor`, session, output lease, result/event, typed code, failure, and deletion-result contracts.
- Defensive bounded thumbnail bytes: `160x90`, `64 KiB`, constructor copy, and copy-on-read.
- Internal pure validation for source shape/temp-root exclusion, frame count, and clip range.
- Common tests for configuration defaults, request/range validation, thumbnail ownership/bounds, and exact closed code sets.
- `commonTest` Kotlin test dependency plus Gradle verification checksums.

## TDD evidence

Before implementation, `./gradlew --write-verification-metadata sha256 :video-clip-editor-core:compileTestKotlinIosSimulatorArm64` failed as expected with unresolved common contract symbols. The command also recorded trusted `kotlin-test:2.4.10` verification checksums.

## Fresh verification

- `./gradlew :video-clip-editor-core:allTests`: PASS. iOS simulator-arm64 tests executed; iOS x64 test skipped because host is macOS arm64.
- `git diff --check`: PASS.

## Scope

No `expect`/`actual`, Android adapter, Compose/UI, native media types, or edits to the existing iOS facade/device feasibility code.
