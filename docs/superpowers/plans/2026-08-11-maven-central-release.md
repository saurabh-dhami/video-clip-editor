# Maven Central Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish version `0.1.0` of the standalone KMP video clip editor to a public GitHub repository and Maven Central.

**Architecture:** Apply `com.vanniktech.maven.publish` to only the two library modules, share immutable release identity from the root build, and validate generated Kotlin Multiplatform publications in an isolated local Maven repository. A manually dispatched GitHub Actions workflow performs the signed Central Portal upload; the demo module remains unpublished.

**Tech Stack:** Gradle 9.4.1, Kotlin 2.4.10, Android Gradle Plugin 9.2.0, Compose Multiplatform 1.11.0, `com.vanniktech.maven.publish` 0.37.0, Maven Central Publisher Portal, GitHub Actions.

## Global Constraints

- Repository: `https://github.com/saurabh-dhami/video-clip-editor`.
- Group: `io.github.saurabh-dhami`.
- Release version: `0.1.0`; immutable tag: `v0.1.0`.
- Root consumer artifacts: `video-clip-editor-core` and `video-clip-editor-compose`.
- License: Apache License 2.0.
- `demo-android` must never be published.
- No public API, iOS implementation, codec, or editor behavior changes.
- No Maven Central or PGP secrets in tracked files, logs, retained build evidence, or documentation.
- Preserve all pre-existing unrelated dirty documentation/evidence files.
- Do not create the release tag or claim Maven completion until Maven Central resolves both root coordinates.

---

## File Structure

- `gradle/libs.versions.toml`: Maven publishing plugin version and alias.
- `build.gradle.kts`: shared group/version and plugin declaration only.
- `video-clip-editor-core/build.gradle.kts`: core publication coordinates and POM.
- `video-clip-editor-compose/build.gradle.kts`: optional Compose publication coordinates and POM.
- `LICENSE`: Apache-2.0 license text.
- `README.md`: consumer prerequisites, installation, usage, cleanup, and platform status.
- `scripts/verify-local-publication.sh`: isolated publication/POM/consumer-resolution gate.
- `.github/workflows/release-maven-central.yml`: manually dispatched signed release.

### Task 1: Publishable Kotlin Multiplatform Modules

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Modify: `video-clip-editor-core/build.gradle.kts`
- Modify: `video-clip-editor-compose/build.gradle.kts`

**Interfaces:**
- Consumes: existing publications created by `org.jetbrains.kotlin.multiplatform`.
- Produces: publication tasks for the two approved root coordinates and their platform variants.

- [ ] **Step 1: Run the publication-task RED check**

```bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:generatePomFileForKotlinMultiplatformPublication
```

Expected: FAIL because the Maven publication task does not exist.

- [ ] **Step 2: Add the publishing plugin catalog entry**

Add to `gradle/libs.versions.toml`:

```toml
[versions]
maven-publish = "0.37.0"

[plugins]
maven-publish = { id = "com.vanniktech.maven.publish", version.ref = "maven-publish" }
```

- [ ] **Step 3: Declare shared release identity**

Add to root `build.gradle.kts` while preserving dependency locking:

```kotlin
plugins {
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "io.github.saurabh-dhami"
    version = "0.1.0"

    dependencyLocking {
        lockAllConfigurations()
    }
}
```

- [ ] **Step 4: Configure the core publication**

Apply `alias(libs.plugins.maven.publish)` and add:

```kotlin
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "video-clip-editor-core", version.toString())

    pom {
        name.set("Video Clip Editor Core")
        description.set("Kotlin Multiplatform contracts and Android engine for local MP4 video clipping.")
        inceptionYear.set("2026")
        url.set("https://github.com/saurabh-dhami/video-clip-editor")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("saurabh-dhami")
                name.set("Saurabh Dhami")
                url.set("https://github.com/saurabh-dhami")
            }
        }
        scm {
            url.set("https://github.com/saurabh-dhami/video-clip-editor")
            connection.set("scm:git:git://github.com/saurabh-dhami/video-clip-editor.git")
            developerConnection.set("scm:git:ssh://git@github.com/saurabh-dhami/video-clip-editor.git")
        }
    }
}
```

- [ ] **Step 5: Configure the Compose publication**

Apply the same plugin and POM contract in `video-clip-editor-compose/build.gradle.kts`, changing only:

```kotlin
coordinates(group.toString(), "video-clip-editor-compose", version.toString())
name.set("Video Clip Editor Compose")
description.set("Shared Compose Multiplatform clip-editor screen with Android Media3 preview integration.")
```

- [ ] **Step 6: Run publication-task GREEN checks**

```bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew :video-clip-editor-core:generatePomFileForKotlinMultiplatformPublication :video-clip-editor-compose:generatePomFileForKotlinMultiplatformPublication
```

Expected: BUILD SUCCESSFUL and both root POMs exist.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts video-clip-editor-core/build.gradle.kts video-clip-editor-compose/build.gradle.kts
git commit -m "build: configure Maven Central publications"
```

### Task 2: Open-Source Metadata and Consumer Documentation

**Files:**
- Create: `LICENSE`
- Create: `README.md`

**Interfaces:**
- Consumes: coordinates/version from Task 1 and existing public editor APIs.
- Produces: Central-compatible licensing and consumer instructions.

- [ ] **Step 1: Add Apache-2.0**

Create `LICENSE` from the unmodified text at `https://www.apache.org/licenses/LICENSE-2.0.txt`.

- [ ] **Step 2: Document dependencies**

Include:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.saurabh-dhami:video-clip-editor-core:0.1.0")
    implementation("io.github.saurabh-dhami:video-clip-editor-compose:0.1.0") // optional UI
}
```

Document Android API 23+, local absolute file paths, MP4 H.264/H.265, typed unsupported results, library-owned temporary output, `TemporaryClipLease.clearTemporaryFile()`, host-input ownership, Android implementation status, and iOS contract-only status.

- [ ] **Step 3: Add Android usage**

Use existing public APIs only:

```kotlin
val editor = createAndroidVideoClipEditor(applicationContext)

ClipEditorScreen(
    source = VideoSourcePath(input.absolutePath),
    editor = editor,
    onCancel = { /* host navigation */ },
    onResult = { result -> /* retain or clear the returned lease */ },
)
```

- [ ] **Step 4: Validate**

```bash
test -f LICENSE
rg -n "Apache License|io.github.saurabh-dhami:video-clip-editor-(core|compose):0.1.0|API 23|TemporaryClipLease.clearTemporaryFile" README.md LICENSE
git diff --check -- LICENSE README.md
```

- [ ] **Step 5: Commit**

```bash
git add LICENSE README.md
git commit -m "docs: add public library usage and license"
```

### Task 3: Isolated Local Publication Verification

**Files:**
- Create: `scripts/verify-local-publication.sh`

**Interfaces:**
- Consumes: Task 1 publication tasks.
- Produces: one deterministic local publication and clean-consumer resolution gate.

- [ ] **Step 1: Write the verification script**

Core structure:

```bash
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd -P)
VERIFY_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/video-clip-editor-publish.XXXXXX")
LOCAL_REPO="$VERIFY_ROOT/repository"
CONSUMER="$VERIFY_ROOT/consumer"
trap 'rm -rf "$VERIFY_ROOT"' EXIT

cd "$ROOT_DIR"
env ANDROID_HOME="${ANDROID_HOME:?ANDROID_HOME is required}" ./gradlew publishToMavenLocal -Dmaven.repo.local="$LOCAL_REPO" --no-configuration-cache

CORE="$LOCAL_REPO/io/github/saurabh-dhami/video-clip-editor-core/0.1.0"
COMPOSE="$LOCAL_REPO/io/github/saurabh-dhami/video-clip-editor-compose/0.1.0"
test -f "$CORE/video-clip-editor-core-0.1.0.pom"
test -f "$COMPOSE/video-clip-editor-compose-0.1.0.pom"
mkdir -p "$CONSUMER"
```

Create `$CONSUMER/settings.gradle.kts` with the isolated repository, `google()`, and `mavenCentral()`. Create `$CONSUMER/build.gradle.kts` with a resolvable configuration containing both root coordinates and a `resolvePublishedLibraries` task that fails unless both component identifiers occur in `resolutionResult.allComponents`.

Invoke:

```bash
env LOCAL_MAVEN_REPO="$LOCAL_REPO" "$ROOT_DIR/gradlew" -p "$CONSUMER" resolvePublishedLibraries --no-configuration-cache
```

Assert each POM contains name, description, GitHub URL, Apache license, developer, and SCM nodes. Assert Compose publication metadata names the published core coordinate.

- [ ] **Step 2: Run RED/GREEN**

```bash
chmod +x scripts/verify-local-publication.sh
ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk scripts/verify-local-publication.sh
```

Expected final result: exit 0 and both root coordinates resolved from the isolated repository.

- [ ] **Step 3: Run project regressions**

```bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ./gradlew -Dorg.gradle.jvmargs=-Xmx4g :video-clip-editor-compose:iosSimulatorArm64Test :demo-android:testDebugUnitTest :demo-android:assembleDebug
```

Expected: BUILD SUCCESSFUL and zero test failures.

- [ ] **Step 4: Commit**

```bash
git add scripts/verify-local-publication.sh
git commit -m "test: verify local Maven publications"
```

### Task 4: Manual GitHub Actions Release

**Files:**
- Create: `.github/workflows/release-maven-central.yml`

**Interfaces:**
- Consumes: four repository secrets and Task 1 publication tasks.
- Produces: manual, signed Maven Central release.

- [ ] **Step 1: Add workflow**

```yaml
name: Release Maven Central

on:
  workflow_dispatch:
    inputs:
      version:
        description: Release version
        required: true
        default: 0.1.0

permissions:
  contents: read

jobs:
  publish:
    if: ${{ inputs.version == '0.1.0' }}
    runs-on: macos-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
      - name: Verify release version
        run: test "$(grep -E '^version = "' build.gradle.kts | head -1 | cut -d\\" -f2)" = "${{ inputs.version }}"
      - name: Publish and release
        env:
          ORG_GRADLE_PROJECT_mavenCentralUsername: ${{ secrets.MAVEN_CENTRAL_USERNAME }}
          ORG_GRADLE_PROJECT_mavenCentralPassword: ${{ secrets.MAVEN_CENTRAL_PASSWORD }}
          ORG_GRADLE_PROJECT_signingInMemoryKey: ${{ secrets.SIGNING_IN_MEMORY_KEY }}
          ORG_GRADLE_PROJECT_signingInMemoryKeyPassword: ${{ secrets.SIGNING_IN_MEMORY_KEY_PASSWORD }}
        run: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

- [ ] **Step 2: Validate workflow and secret safety**

```bash
ruby -e 'require "yaml"; YAML.load_file(".github/workflows/release-maven-central.yml")'
rg -n "MAVEN_CENTRAL_|SIGNING_IN_MEMORY_KEY" .github/workflows/release-maven-central.yml
! rg -n "BEGIN PGP PRIVATE KEY|mavenCentralPassword\\s*=|signingInMemoryKey\\s*=" . --glob '!docs/superpowers/**'
git diff --check -- .github/workflows/release-maven-central.yml
```

Expected: YAML parses; only secret references appear; no secret value appears.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/release-maven-central.yml
git commit -m "ci: add Maven Central release workflow"
```

### Task 5: GitHub and Maven Central Publication

**Files:**
- No new tracked files.

**Interfaces:**
- Consumes: Tasks 1–4 plus Central namespace/token and PGP credentials.
- Produces: public GitHub repository, immutable Maven Central version, tag, and GitHub release.

- [ ] **Step 1: Run final local gates**

```bash
git diff --check
git status --short
ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk scripts/verify-local-publication.sh
```

Confirm only the known pre-existing documentation/evidence paths remain dirty.

- [ ] **Step 2: Create and push the public repository**

```bash
gh repo create saurabh-dhami/video-clip-editor --public --description "Kotlin Multiplatform MP4 video clipping library with optional Compose UI" --source . --remote origin
git push -u origin HEAD:main
```

Expected: `https://github.com/saurabh-dhami/video-clip-editor` resolves and remote `main` points at the verified release commit.

- [ ] **Step 3: Configure secrets**

Require the user to create or confirm the Central Portal account and `io.github.saurabh-dhami` namespace. Set values without printing them:

```bash
gh secret set MAVEN_CENTRAL_USERNAME
gh secret set MAVEN_CENTRAL_PASSWORD
gh secret set SIGNING_IN_MEMORY_KEY
gh secret set SIGNING_IN_MEMORY_KEY_PASSWORD
gh secret list
```

- [ ] **Step 4: Dispatch and monitor**

```bash
gh workflow run release-maven-central.yml -f version=0.1.0
gh run watch --exit-status
```

Expected: workflow succeeds and Central publishes the deployment.

- [ ] **Step 5: Verify Central before tagging**

Query Maven Central until both exact coordinates resolve at `0.1.0`. Verify their POM metadata and artifact availability.

- [ ] **Step 6: Create immutable GitHub release**

```bash
git tag -a v0.1.0 -m "Video Clip Editor 0.1.0"
git push origin v0.1.0
gh release create v0.1.0 --repo saurabh-dhami/video-clip-editor --title "Video Clip Editor 0.1.0" --notes "Initial Android/Kotlin Multiplatform video clipping release."
```

- [ ] **Step 7: Report completion**

Report repository/release/Central links, coordinates, commit, tag, tests, and iOS limitation. Never claim Maven completion if Central has not resolved both coordinates.
