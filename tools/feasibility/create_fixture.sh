#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture="$root/video-clip-editor-core/src/androidDeviceTest/assets/fixtures/avc-aac-10s-30fps.mp4"
sha_file="$root/docs/feasibility/fixture-sha256.txt"
mkdir -p "$(dirname "$fixture")" "$(dirname "$sha_file")"
ffmpeg -y -f lavfi -i "testsrc2=size=640x360:rate=30" -f lavfi -i "sine=frequency=1000:sample_rate=48000" -t 10 -c:v libx264 -pix_fmt yuv420p -g 30 -keyint_min 30 -sc_threshold 0 -c:a aac -b:a 128k -movflags +faststart "$fixture"
checksum="$(shasum -a 256 "$fixture" | awk '{print $1}')"
printf '%s  %s\n' "$checksum" "video-clip-editor-core/src/androidDeviceTest/assets/fixtures/avc-aac-10s-30fps.mp4" > "$sha_file"
