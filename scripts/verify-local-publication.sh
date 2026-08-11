#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd -P)
VERIFY_TEMP_PARENT=/tmp
VERIFY_ROOT=$(mktemp -d "$VERIFY_TEMP_PARENT/video-clip-editor-publish.XXXXXX")
LOCAL_REPO="$VERIFY_ROOT/repository"
CONSUMER="$VERIFY_ROOT/consumer"
VERIFY_GNUPG_HOME="$VERIFY_ROOT/gnupg"
VERIFY_KEY_PASSWORD="publication-verification"

case "$VERIFY_ROOT" in
  "$VERIFY_TEMP_PARENT"/video-clip-editor-publish.*) ;;
  *)
    printf 'Refusing unsafe temporary path: %s\n' "$VERIFY_ROOT" >&2
    exit 2
    ;;
esac

cleanup() {
  gpgconf --kill gpg-agent >/dev/null 2>&1 || true
  rm -rf -- "$VERIFY_ROOT"
}
trap cleanup EXIT

mkdir -m 700 "$VERIFY_GNUPG_HOME"
export GNUPGHOME="$VERIFY_GNUPG_HOME"
gpgconf --launch gpg-agent
gpg --batch \
  --pinentry-mode loopback \
  --passphrase "$VERIFY_KEY_PASSWORD" \
  --quick-generate-key \
  'Video Clip Editor Verification <verify@example.invalid>' \
  rsa2048 sign 1d >/dev/null

SIGNING_KEY_ID=$(gpg --batch --with-colons --list-secret-keys |
  awk -F: '$1 == "sec" { print $5; exit }')
test -n "$SIGNING_KEY_ID"
SIGNING_KEY=$(gpg --batch \
  --pinentry-mode loopback \
  --passphrase "$VERIFY_KEY_PASSWORD" \
  --armor \
  --export-secret-keys "$SIGNING_KEY_ID")
test -n "$SIGNING_KEY"

cd "$ROOT_DIR"
env ANDROID_HOME="${ANDROID_HOME:?ANDROID_HOME is required}" \
  ORG_GRADLE_PROJECT_signingInMemoryKey="$SIGNING_KEY" \
  ORG_GRADLE_PROJECT_signingInMemoryKeyPassword="$VERIFY_KEY_PASSWORD" \
  ./gradlew publishToMavenLocal \
  -Dmaven.repo.local="$LOCAL_REPO" \
  --no-configuration-cache

CORE="$LOCAL_REPO/io/github/saurabh-dhami/video-clip-editor-core/0.1.0"
COMPOSE="$LOCAL_REPO/io/github/saurabh-dhami/video-clip-editor-compose/0.1.0"
CORE_POM="$CORE/video-clip-editor-core-0.1.0.pom"
COMPOSE_POM="$COMPOSE/video-clip-editor-compose-0.1.0.pom"

for artifact in \
  "$CORE_POM" \
  "$CORE/video-clip-editor-core-0.1.0.jar" \
  "$CORE/video-clip-editor-core-0.1.0-sources.jar" \
  "$CORE/video-clip-editor-core-0.1.0-javadoc.jar" \
  "$COMPOSE_POM" \
  "$COMPOSE/video-clip-editor-compose-0.1.0.jar" \
  "$COMPOSE/video-clip-editor-compose-0.1.0-sources.jar" \
  "$COMPOSE/video-clip-editor-compose-0.1.0-javadoc.jar"
do
  test -f "$artifact"
done

for pom in "$CORE_POM" "$COMPOSE_POM"
do
  rg -q '<name>Video Clip Editor (Core|Compose)</name>' "$pom"
  rg -q '<description>[^<]+</description>' "$pom"
  rg -q '<url>https://github.com/saurabh-dhami/video-clip-editor</url>' "$pom"
  rg -q '<name>The Apache License, Version 2.0</name>' "$pom"
  rg -q '<id>saurabh-dhami</id>' "$pom"
  rg -q '<scm>' "$pom"
done

rg -q '<groupId>io.github.saurabh-dhami</groupId>' "$COMPOSE_POM"
rg -q '<artifactId>video-clip-editor-core</artifactId>' "$COMPOSE_POM"
rg -q '<version>0.1.0</version>' "$COMPOSE_POM"

mkdir -p "$CONSUMER"
cat > "$CONSUMER/settings.gradle.kts" <<'SETTINGS'
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven(url = uri(providers.environmentVariable("LOCAL_MAVEN_REPO").get()))
        google()
        mavenCentral()
    }
}

rootProject.name = "video-clip-editor-publication-smoke"
SETTINGS

cat > "$CONSUMER/build.gradle.kts" <<'BUILD'
plugins {
    base
}

val smoke by configurations.creating

dependencies {
    smoke("io.github.saurabh-dhami:video-clip-editor-core:0.1.0")
    smoke("io.github.saurabh-dhami:video-clip-editor-compose:0.1.0")
}

tasks.register("resolvePublishedLibraries") {
    doLast {
        val components = smoke.incoming.resolutionResult.allComponents
            .map { it.id.displayName }
            .toSet()

        check(components.any { it.contains("io.github.saurabh-dhami:video-clip-editor-core:0.1.0") }) {
            "Published core coordinate did not resolve: $components"
        }
        check(components.any { it.contains("io.github.saurabh-dhami:video-clip-editor-compose:0.1.0") }) {
            "Published Compose coordinate did not resolve: $components"
        }

        smoke.resolve()
    }
}
BUILD

env LOCAL_MAVEN_REPO="$LOCAL_REPO" \
  "$ROOT_DIR/gradlew" \
  -p "$CONSUMER" \
  resolvePublishedLibraries \
  --no-configuration-cache

printf 'LOCAL_PUBLICATION_VERIFICATION=PASS\n'
printf 'CORE_COORDINATE=io.github.saurabh-dhami:video-clip-editor-core:0.1.0\n'
printf 'COMPOSE_COORDINATE=io.github.saurabh-dhami:video-clip-editor-compose:0.1.0\n'
