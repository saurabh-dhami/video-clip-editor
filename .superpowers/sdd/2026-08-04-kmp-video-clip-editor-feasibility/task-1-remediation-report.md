# Task 1 remediation report

## Status

PASS

## Remediation

- Replaced `com.android.library` / `androidTarget()` with `com.android.kotlin.multiplatform.library` and `kotlin { android { ... } }` in both modules.
- Core Android namespace: `com.oneononearena.videoclip`.
- Compose Android namespace: `com.oneononearena.videoclip.compose`.
- Both Android targets use `compileSdk = 36`, `minSdk = 23`; core retains all three iOS target declarations.
- Removed `android.newDsl=false`, `android.builtInKotlin=false`, top-level Android blocks, and Task 2 XCFramework aggregation.
- Enabled root `dependencyLocking { lockAllConfigurations() }`.

## Verification

- Bootstrapped ZIP SHA-256: `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb` — verified.
- Generated wrapper JAR SHA-256: `55243ef57851f12b070ad14f7f5bb8302daceeebc5bce5ece5fa6edb23e1145c` — verified before first wrapper execution.
- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew --version` — Gradle `9.4.1`, Launcher JVM `21.0.12`.
- Task discovery selected `:video-clip-editor-core:compileAndroidMain` and `:video-clip-editor-compose:compileAndroidMain`.
- `--write-locks` compile — `BUILD SUCCESSFUL`.
- `--write-verification-metadata sha256` compile — `BUILD SUCCESSFUL`.
- Generated lockfiles: `settings-gradle.lockfile`, `video-clip-editor-core/gradle.lockfile`, `video-clip-editor-compose/gradle.lockfile`.
- Generated `gradle/verification-metadata.xml` with SHA-256 metadata.
- Full commands/results: `docs/feasibility/b7-android-build.txt`; discovered tasks: `docs/feasibility/b7-android-task-list.txt`; toolchain proof: `docs/feasibility/toolchain.txt`.

## Concern

Gradle Java HTTPS validation/download timed out after 10 seconds although the separately recorded official curl probe reached HTTP 200. The wrapper used the permitted `--no-validate-url` fallback once, after ZIP verification; its cache was seeded from that same verified ZIP. `validateDistributionUrl=false` remains recorded in wrapper properties as the generated fallback setting.

The host warns that `iosX64Test` is disabled on macOS ARM64. Android-main compilation passed; Task 2 owns XCFramework aggregation.

## Commit

`chore: remediate KMP feasibility baseline` (this commit)
