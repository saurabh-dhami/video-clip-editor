#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$root/video-clip-editor-core/src/androidDeviceTest/assets/fixtures/avc-aac-10s-30fps.mp4"
sha_file="$root/docs/feasibility/fixture-sha256.txt"
test -f "$fixture"
test -f "$sha_file"
expected="$(awk '{print $1}' "$sha_file")"
actual="$(shasum -a 256 "$fixture" | awk '{print $1}')"
test "$actual" = "$expected"
printf 'PASS fixture SHA-256 %s\n' "$actual"
