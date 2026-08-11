# Maven Central Release Design

## Outcome

Publish the standalone Kotlin Multiplatform video clip editor as an open-source GitHub repository and release version `0.1.0` to Maven Central.

Repository:

- `https://github.com/saurabh-dhami/video-clip-editor`

Consumer coordinates:

- `io.github.saurabh-dhami:video-clip-editor-core:0.1.0`
- `io.github.saurabh-dhami:video-clip-editor-compose:0.1.0`

The Kotlin Multiplatform publication may generate target-specific child artifacts in addition to these root coordinates. Consumers use the root coordinates above and Gradle selects the correct platform variant.

## Scope

Included:

- Public GitHub repository owned by `saurabh-dhami`.
- Apache License 2.0.
- Project README with prerequisites, dependency declarations, Android usage, and `TemporaryClipLease.clearTemporaryFile()` cleanup.
- Maven Central publication metadata, source artifacts, documentation artifacts, checksums, and signatures.
- A manual GitHub Actions release workflow.
- Local publication and POM validation before any remote release.
- Git tag and GitHub release `v0.1.0` after successful Central publication.

Excluded:

- Publishing the Android demo application.
- GitHub Packages or JitPack publication.
- iOS implementation work.
- Public API changes.
- Automatic releases from ordinary pushes.

## Architecture

Use `com.vanniktech.maven.publish` version `0.37.0` in the two library modules. The plugin supports Kotlin Multiplatform projects using `com.android.kotlin.multiplatform.library` and the Central Publisher Portal.

Shared project identity:

- Group: `io.github.saurabh-dhami`
- Version: `0.1.0`
- License: Apache-2.0
- Developer ID: `saurabh-dhami`
- SCM: the public GitHub repository

Each library module owns its artifact ID, display name, and description. The `demo-android` module remains unpublished.

## Release Flow

1. Generate and validate every Maven publication locally.
2. Publish all publications to a temporary local Maven repository and verify their POMs and dependency relationships.
3. Build and run the existing common, iOS-simulator, Android demo, API 23, and Samsung verification gates where relevant to the publishing change.
4. Create the public GitHub repository and push the complete current history as `main`.
5. Configure Maven Central credentials and signing material as GitHub repository secrets.
6. Run the manual release workflow for version `0.1.0`.
7. Confirm both root coordinates are visible in Maven Central.
8. Create and push tag `v0.1.0`, then create the GitHub release.

The workflow must stop before tagging if Central rejects validation or publication.

## Credentials and Security

Required private values:

- Maven Central publisher username/token name.
- Maven Central publisher password/token value.
- ASCII-armored PGP private signing key.
- PGP signing-key password.

These values must exist only in the local environment or GitHub Actions secrets. They must never be written to tracked files, logs, generated evidence, Gradle configuration committed to Git, or the README.

The workflow receives credentials through `ORG_GRADLE_PROJECT_*` environment variables. It uses the repository-scoped `GITHUB_TOKEN` only for GitHub release operations.

## Failure Handling

- Missing Central namespace, credentials, or signing key: stop before upload and report the exact setup gate.
- Invalid POM, missing sources/docs, or signing failure: fail local validation; do not push a release tag.
- Central validation or deployment failure: retain the GitHub repository and branch, but do not claim `0.1.0` is published.
- GitHub push failure: do not attempt Maven publication because the required SCM source is unavailable.
- Coordinate/version conflict: never overwrite an immutable Central release; increment the version after explicit approval.

## Verification

Required checks:

- The old public declarations remain unchanged.
- Both library modules produce publishable Kotlin Multiplatform root publications and Android variants.
- Generated POMs contain name, description, URL, Apache-2.0 license, developer, and SCM metadata.
- `video-clip-editor-compose` declares its dependency on the published `video-clip-editor-core` coordinate rather than a local-only project reference in generated metadata.
- Source and documentation artifacts exist for every required publication.
- Signing configuration validates without exposing key material.
- A clean consumer fixture resolves both root coordinates from the temporary local Maven repository.
- Existing project tests and builds remain green.
- Maven Central search resolves both version `0.1.0` root coordinates before completion is claimed.

## Rollback

Before Central publication, remove or correct the GitHub workflow/configuration in a normal follow-up commit. After Central publication, version `0.1.0` is immutable and cannot be replaced; corrections require a new version. A failed or partially configured GitHub release must not be represented as a successful Maven Central release.
