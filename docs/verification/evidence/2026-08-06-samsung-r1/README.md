# Samsung R1 Raw Test Evidence — 2026-08-06

**Device:** `RZCX519T5FL`, SM-S928B, Android 16 / API36 (`getprop ro.build.version.sdk` = `36`).

**Repair under test:** `8391171 fix(android): preflight video decoder capability`.

**Command:**

```text
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.Media3DecoderCapabilityPreflightTest,com.oneononearena.videoclip.HevcClipRoundTripTest,com.oneononearena.videoclip.AndroidVideoClipEditorIntegrationTest,com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest --rerun-tasks
```

**Result:** exit `0`; 20 tests on `SM-S928B - 16`; `BUILD SUCCESSFUL in 10s`. The retained instrumentation stream reports `OK (20 tests)` in `2.715` seconds. The raw JUnit XML reports 20 tests, zero failures, zero errors, zero skipped, and 3.437 seconds aggregate time.

## Byte-identical retained artifacts

Each tracked file below is an exact copy of the original build artifact. SHA-256 was calculated over both source and tracked copy; each pair matched.

| Original build artifact | Tracked copy | SHA-256 | Bytes |
| --- | --- | --- | ---: |
| `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/TEST-SM-S928B - 16-_video-clip-editor-core-.xml` | `TEST-SM-S928B-16-video-clip-editor-core.xml` | `22e2564611d425b66af67c5fdd67c15cf59c3163d8cdf3fe0758b8f5d470705d` | 5,400 |
| `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/SM-S928B - 16/testlog/test-results.log` | `samsung-instrumentation-test-results.log` | `43017080e385250a6d05a6668d3ef032778822d656563c5d63d399cffd53186f` | 15,043 |
| `video-clip-editor-core/build/outputs/androidTest-results/connected/androidMain/SM-S928B - 16/logcat-com.oneononearena.videoclip.HevcClipRoundTripTest-productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource.txt` | `hevc-round-trip.log` | `78e1e703f11e14dc006c4232b87b8faafe03bf2dee87896fa95610af24603708` | 166,347 |

The retained XML uses the source artifact's CRLF line endings and the instrumentation stream retains its source blank line at EOF. Consequently, a broad `git diff --check` reports those two raw-artifact formatting findings. They are intentional: normalizing either file would break its recorded hash. The authored Markdown evidence files pass the scoped whitespace check.

The JUnit XML proves all three decoder preflight cases passed: unavailable HEVC decoder maps to typed unsupported before session/export, unavailable AVC decoder maps likewise, and available AVC retains the normal open-session path. It also proves the HEVC round trip, six integration tests, and ten temporary-lease tests passed on the final Samsung run.

## Raw HEVC runtime line

The following unmodified line appears in the retained `hevc-round-trip.log`:

```text
HEVC_OUTPUT path=/data/user/0/com.oneononearena.videoclip.test/cache/video-clip-editor/acac8789-008d-4d0b-ba4e-ee4b668974f7/9803d95d-77bd-4083-bb15-fbdc105f3993.mp4 videoMime=video/avc audioMime=audio/mp4a-latm cleanup=Cleared existsAfterCleanup=false
```

This records the leased output's absolute app-cache path, H.264/AAC output MIME values, successful cleanup, and absence after cleanup. The full raw log, not merely this excerpt, is retained beside this manifest.
