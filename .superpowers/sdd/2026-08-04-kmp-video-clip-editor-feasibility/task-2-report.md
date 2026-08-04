# Task 2 report — iOS XCFramework unavailable façade

## Status

BLOCKED for B-5 environment proof. Implementation, static XCFramework
assembly, generated Swift surface, and available-simulator Swift linkage pass.

## Changed scope

- `video-clip-editor-core/build.gradle.kts`: static `VideoClipEditorCore`
  XCFramework; `iosArm64`, `iosSimulatorArm64`, and `iosX64`; exported
  `kotlinx-coroutines-core`.
- `video-clip-editor-core/src/iosMain/kotlin/com/oneononearena/videoclip/IosClipEditorFactory.kt`:
  callback-only `IosClipEditorFactory.shared.create().openSession(sourcePath:completion:)`
  surface. It completes once with `IOS_ENGINE_UNAVAILABLE`; no public
  `Flow`, `suspend`, default parameter, AVFoundation, or media implementation.
- `sample-ios-swift/`: SwiftUI build-only host importing `VideoClipEditorCore`.
- `docs/feasibility/b5-ios-xcframework.txt`: exact tool/slice/header/build evidence.

## Commands and results

- Pre-aggregation Gradle attempt: failed before task execution because sandboxed
  Gradle could not create its home cache lock; it did not prove task absence.
- Final `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :video-clip-editor-core:assembleVideoClipEditorCoreReleaseXCFramework`:
  `BUILD SUCCESSFUL`; iosArm64, iosSimulatorArm64, iosX64 linked.
- `rsync` copied the generated bundle to Swift sample `Frameworks`.
- Required `xcodebuild ... -destination 'platform=iOS Simulator,name=iPhone 16' build`:
  failed, exit `70`; no matching device installed.
- Supplemental `xcodebuild ... -destination 'platform=iOS Simulator,name=iPhone 16e' ... build`:
  `BUILD SUCCEEDED`, exit `0`; `App.swift` compiled and linked
  `-framework VideoClipEditorCore`.
- `git diff --check`: pending.

## Environment concern

Actual Xcode: `26.1.1` / build `17B100`; plan requires Xcode `26.6` and
`iPhone 16`. B-5 remains BLOCKED until that exact environment reruns the
specified command. Full evidence: `docs/feasibility/b5-ios-xcframework.txt`.

## Commit

`test: prove iOS XCFramework unavailable facade`
