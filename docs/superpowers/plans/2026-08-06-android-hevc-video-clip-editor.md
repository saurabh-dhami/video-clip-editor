# Android H.264/H.265 Video Clip Editor V1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Deliver Android API-23+ clipping for local SDR H.264/H.265 MP4 input, returning a library-owned H.264/AAC temporary MP4 and safe cleanup, with the frozen common/iOS API unchanged.

**Architecture:** VideoClipEditor remains the public boundary. Internal pure-Kotlin engine contracts isolate Media3 1.10.1. Android-only code probes source topology, maps AVC/HEVC input to internal codec families, exports H.264/AAC through Media3, and owns the app-internal temporary lease. Compose consumes only the common session contract.

**Tech Stack:** Kotlin Multiplatform; Android API 23+; AndroidX Media3 Transformer 1.10.1; MediaExtractor; MediaMetadataRetriever; coroutines/Flow; Compose Multiplatform; Android device tests.

## Global Constraints

- Work only in /Users/sandeepdhami/Documents/GitHub/video-clip-editor/.worktrees/feasibility.
- Never touch OneOnOneArena.
- Preserve every declaration in docs/superpowers/specs/2026-08-06-kmp-video-clip-editor-v1-api-baseline.md.
- Input: absolute regular MP4 with one SDR AVC/HEVC track and zero/one AAC audio track.
- Output: H.264/AAC MP4 under Context.cacheDir/video-clip-editor, returned by absolute path plus issued lease.
- Cleanup accepts no host path, never recurses, and never deletes source/host-copy data.
- iOS remains typed unavailable. Compose is optional. Library navigation is forbidden.
- Every production edit follows observed RED, minimal GREEN, and relevant regression GREEN.
- A task completes only with its own test gate, rollback point, and integration handoff.

## Ownership Map

| Chunk | Owns | Does not own |
| --- | --- | --- |
| E1 | internal engine models/interfaces and Android injection | Media3 implementation/cancel state |
| L1 | session state and frame closure semantics | Media3 export mutex |
| P1 | internal topology policy | export capability |
| X1 | Media3 adapter, export mutex, temporary store/lease | public API/session state |
| U1 | Compose presenter/screen | navigation and engine |
| I1/R1 | host evidence/tests/demo/sample/docs | production media implementation |

---

### Task 1: Internal engine contract (E1)

**Scope:** Extract internal pure-Kotlin media types and dependency injection. No public type, Media3 implementation, iOS code, or export mutex change.

**Files:**

- Create: video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/internal/engine/ClipMediaContracts.kt
- Create: video-clip-editor-core/src/commonTest/kotlin/com/oneononearena/videoclip/internal/engine/ClipMediaContractsTest.kt
- Modify: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditor.kt

**Interfaces:**

- Consumes: VideoMetadata, ClipRange, ThumbnailFrame, common typed results.
- Produces: EngineSource, EngineVideoCodec { AVC, HEVC, OTHER }, EngineAudioCodec { AAC, OTHER }, EngineStreamTopology, EngineProbeResult, EngineFrameRequest, EngineFrameEvent, EngineExportRequest, EngineExportResult, ClipMediaEngine.
- ClipMediaEngine has suspend probe(EngineSource), frames(EngineSource, EngineFrameRequest), suspend export(EngineExportRequest), and suspend cancelActiveExport(). Its signatures contain no Android/Media3/File type.

**Dependencies:** Approved blueprint only.

**Acceptance:** Existing AVC public behaviour remains unchanged behind an Android adapter. Source-policy code has no Media3 MIME import.

**Test strategy:** Common test validates HEVC topology is representable; Android compilation confirms adapter can be injected.

**Rollback:** Revert this extraction commit alone.

**Integration:** L1, P1, X1 consume the internal contract only.

- [ ] **Step 1: Write failing common test**

~~~
@Test
fun hevcTopologyIsRepresentableWithoutPlatformTypes() {
    val topology = EngineStreamTopology(
        videoCodec = EngineVideoCodec.HEVC,
        audioCodec = EngineAudioCodec.AAC,
        isHdr = false,
    )

    assertEquals(EngineVideoCodec.HEVC, topology.videoCodec)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-core:compileTestKotlinIosSimulatorArm64

Expected: compile failure because EngineStreamTopology is unresolved.

- [ ] **Step 3: Implement minimal contract**

~~~
internal enum class EngineVideoCodec { AVC, HEVC, OTHER }

internal interface ClipMediaEngine {
    suspend fun probe(source: EngineSource): EngineProbeResult
    fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent>
    suspend fun export(request: EngineExportRequest): EngineExportResult
    suspend fun cancelActiveExport()
}
~~~

Route existing Android code through an internal adapter. Keep Media3 imports in androidMain.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-core:allTests :video-clip-editor-core:compileAndroidMain

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

~~~
git add video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/internal/engine video-clip-editor-core/src/commonTest/kotlin/com/oneononearena/videoclip/internal/engine video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditor.kt
git commit -m "refactor(core): isolate clip media engine contract"
~~~

### Task 2: Session lifecycle and typed close behaviour (L1)

**Scope:** Session open/closed state and frame-emission fence. X1 retains Media3 cancellation and export mutual exclusion.

**Files:**

- Create: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidSessionLifecycle.kt
- Create: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/AndroidSessionLifecycleTest.kt
- Modify: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditor.kt
- Modify: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/FrameEmissionGateTest.kt

**Interfaces:**

- Consumes: FrameStripEvent, ValidationCode.SESSION_CLOSED, ClipMediaEngine.
- Produces: AndroidSessionLifecycle with close and isClosed; frames returns typed closed event before registering worker.

**Dependencies:** Task 1.

**Acceptance:** frames after close emits exactly InvalidRequest(SESSION_CLOSED, null) then completes. Collector-triggered close cannot deadlock. Platform exceptions never leave public API.

**Test strategy:** Fake engine/device test with one-frame collector and one-second timeout.

**Rollback:** Revert lifecycle coordinator/test commit only.

**Integration:** U1 and X1 use deterministic close state.

- [ ] **Step 1: Write failing lifecycle tests**

~~~
@Test
fun framesAfterCloseEmitsSessionClosed() = runTest {
    val session = sessionWithFakeEngine()
    session.close()

    assertEquals(
        listOf(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null)),
        session.frames(FrameStripRequest(1)).toList(),
    )
}

@Test
fun collectorCloseDoesNotDeadlockFrameEmission() = runTest {
    val session = sessionWithOneFrameFake()
    withTimeout(1.seconds) {
        session.frames(FrameStripRequest(1)).collect { event ->
            if (event is FrameStripEvent.Frame) session.close()
        }
    }
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidSessionLifecycleTest

Expected: current closed flow completes empty or collector close times out.

- [ ] **Step 3: Implement minimal lifecycle state**

~~~
internal class AndroidSessionLifecycle {
    private val closeMutex = Mutex()
    @Volatile private var closed = false

    suspend fun close(block: suspend () -> Unit) = closeMutex.withLock {
        if (!closed) {
            closed = true
            block()
        }
    }

    fun isClosed(): Boolean = closed
}
~~~

Do not hold a mutex while Flow.emit invokes a downstream collector.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidSessionLifecycleTest,com.oneononearena.videoclip.FrameEmissionGateTest

Expected: BUILD SUCCESSFUL without timeout.

- [ ] **Step 5: Commit**

~~~
git add video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip
git commit -m "fix(core): make clip session closure deterministic"
~~~

### Task 3: SDR HEVC source admission (P1)

**Scope:** Input topology admission only. Actual decoder/encoder success remains X1 capability-dependent.

**Files:**

- Create: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidSourceTopologyPolicy.kt
- Create: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/AndroidSourceTopologyPolicyTest.kt
- Modify: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditor.kt
- Modify: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/AndroidSourcePolicyTest.kt

**Interfaces:**

- Consumes: EngineStreamTopology.
- Produces: AndroidSourceTopologyPolicy.validate(topology): UnsupportedCode?.

**Dependencies:** Task 1.

**Acceptance:** AVC/HEVC SDR with zero/one AAC is admitted. HDR returns HDR_UNSUPPORTED. Unsupported/extra video returns UNSUPPORTED_VIDEO_CODEC. Other/multiple audio returns UNSUPPORTED_AUDIO_CODEC. Existing DRM/container/size/duration behaviour stays typed.

**Test strategy:** Policy tests plus Android fixture probe.

**Rollback:** Revert codec accepted set to AVC only; no public change.

**Integration:** X1 receives admitted source only.

- [ ] **Step 1: Write failing topology tests**

~~~
@Test
fun acceptsSdrHevcWithAac() {
    assertNull(AndroidSourceTopologyPolicy.validate(
        EngineStreamTopology(EngineVideoCodec.HEVC, EngineAudioCodec.AAC, isHdr = false),
    ))
}

@Test
fun rejectsHdrHevc() {
    assertEquals(
        UnsupportedCode.HDR_UNSUPPORTED,
        AndroidSourceTopologyPolicy.validate(
            EngineStreamTopology(EngineVideoCodec.HEVC, EngineAudioCodec.AAC, isHdr = true),
        ),
    )
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidSourceTopologyPolicyTest

Expected: unresolved policy or HEVC rejected.

- [ ] **Step 3: Implement policy and Android mapping**

~~~
internal object AndroidSourceTopologyPolicy {
    fun validate(topology: EngineStreamTopology): UnsupportedCode? = when {
        topology.isHdr -> UnsupportedCode.HDR_UNSUPPORTED
        topology.videoCodec !in setOf(EngineVideoCodec.AVC, EngineVideoCodec.HEVC) ->
            UnsupportedCode.UNSUPPORTED_VIDEO_CODEC
        topology.audioCodec !in setOf(null, EngineAudioCodec.AAC) ->
            UnsupportedCode.UNSUPPORTED_AUDIO_CODEC
        else -> null
    }
}
~~~

Map Media3 AVC/HEVC MIME values in Android adapter, never in this policy.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidSourcePolicyTest,com.oneononearena.videoclip.AndroidSourceTopologyPolicyTest

Expected: all accepted/rejected cases report exact typed values.

- [ ] **Step 5: Commit**

~~~
git add video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip
git commit -m "feat(core): admit SDR HEVC clip sources"
~~~

### Task 4: Media3 export and temporary lease ownership (X1)

**Scope:** Media3 adapter/export mutex and app-internal temporary store. No public/API/session-lifecycle ownership change.

**Files:**

- Create: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/Media3ClipMediaEngine.kt
- Create: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidOwnedTempFileStore.kt
- Modify: video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditor.kt
- Modify: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/AndroidTemporaryClipLeaseTest.kt
- Create: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/HevcClipRoundTripTest.kt

**Interfaces:**

- Consumes: Tasks 1–3 contracts/policy and Task 2 lifecycle state.
- Produces: EngineExportResult.Success with issued lease; editor maps this to existing ClipResult.Success.

**Dependencies:** Tasks 1–3.

**Acceptance:** AVC/HEVC fixture succeeds on capable device with video/avc plus audio/mp4a-latm MP4 at an absolute internal-cache path. Device decode/encoder/export limits return existing typed result. Normal issued lease clears; second clear is AlreadyCleared; source/foreign root cannot clear; terminal symlink is refused; cancelled export publishes no final file.

**Test strategy:** Device MediaExtractor validates MIME/path/range/source preservation; cleanup tests assert ownership boundary.

**Rollback:** Revert adapter/store commit. Revert P1 admitted set separately if a reproducible HEVC regression requires it.

**Integration:** I1 uses production factory/session/export/lease path, not feasibility exporter.

- [ ] **Step 1: Write failing HEVC and cleanup tests**

~~~
@Test
fun hevcSdrInputExportsOwnedH264AacTemporaryMp4() = runTest {
    val session = createAndroidVideoClipEditor(context)
        .openSession(VideoSourcePath(copyFixture("hevc_sdr_aac.mp4")))
        .requireOpen()

    val success = session
        .createClip(ClipRange(500.milliseconds, 1500.milliseconds))
        .requireSuccess()

    assertTrue(success.output.file.absolutePath.startsWith(context.cacheDir.absolutePath))
    assertEquals("video/avc", extractorVideoMime(success.output.file.absolutePath))
    assertEquals("audio/mp4a-latm", extractorAudioMime(success.output.file.absolutePath))
}

@Test
fun issuedLeaseClearIsIdempotent() = runTest {
    val lease = createSuccessfulLease()
    assertEquals(TempDeleteResult.Cleared, lease.clearTemporaryFile())
    assertFalse(File(lease.file.absolutePath).exists())
    assertEquals(TempDeleteResult.AlreadyCleared, lease.clearTemporaryFile())
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.HevcClipRoundTripTest,com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest

Expected: HEVC source returns UNSUPPORTED_VIDEO_CODEC.

- [ ] **Step 3: Implement adapter/store minimally**

~~~
val transformer = Transformer.Builder(context)
    .setVideoMimeType(MimeTypes.VIDEO_H264)
    .setAudioMimeType(MimeTypes.AUDIO_AAC)
    .build()
~~~

Create one random session directory in Context.cacheDir/video-clip-editor, export to .partial, publish final only after success, and issue the opaque lease. Map encoder unavailability to DEVICE_ENCODER_UNAVAILABLE and other Media3 failure to existing FailureCode. Remove only library partial/final entries.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.HevcClipRoundTripTest,com.oneononearena.videoclip.AndroidVideoClipEditorIntegrationTest,com.oneononearena.videoclip.AndroidTemporaryClipLeaseTest

Expected: AVC/HEVC round trip succeeds or returns documented typed device result; cleanup tests pass.

- [ ] **Step 5: Commit**

~~~
git add video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip
git commit -m "feat(android): export SDR HEVC clips through Media3"
~~~

### Task 5: Optional Compose and iOS host contracts (U1/I1)

**Scope:** Prove optional UI lifecycle and both frozen iOS result languages. Repair Compose only after RED proves current behaviour wrong. No AVFoundation/media implementation.

**Files:**

- Modify if RED: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt
- Modify: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreenTest.kt
- Modify: video-clip-editor-core/src/iosTest/kotlin/com/oneononearena/videoclip/IosVideoClipEditorTest.kt
- Modify: sample-ios-swift/App.swift
- Modify: demo-android/src/main/kotlin/com/oneononearena/videoclip/demo/DemoActivity.kt
- Modify: video-clip-editor-core/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditorIntegrationTest.kt

**Interfaces:** Frozen ClipEditorScreen, createAndroidVideoClipEditor, createIosVideoClipEditor, IosClipEditorFacade, TemporaryClipLease.

**Dependencies:** Tasks 2 and 4.

**Acceptance:** Host owns navigation. Demo opens ClipEditorScreen and exposes lease cleanup. Core-only Android flow copies output to host storage before clearing lease. Primary iOS factory returns common OpenSessionResult.Unsupported(IOS_ENGINE_UNAVAILABLE); legacy facade returns IosOpenSessionResult.IosEngineUnavailable.

**Test strategy:** Android production integration, common Compose tests, iOS simulator tests, Swift sample compile.

**Rollback:** Revert UI/test/demo/sample commit only.

**Integration:** R1 consumes these proof artefacts.

- [ ] **Step 1: Write failing production integration test**

~~~
@Test
fun factoryFlowKeepsHostCopyAfterClearingLibraryLease() = runTest {
    val success = createHevcClipSuccess()
    val hostCopy = File(context.filesDir, "host-copy.mp4")

    File(success.output.file.absolutePath).copyTo(hostCopy)
    success.output.clearTemporaryFile()

    assertTrue(hostCopy.isFile)
    assertFalse(File(success.output.file.absolutePath).exists())
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.AndroidVideoClipEditorIntegrationTest

Expected: fails until Task 4 HEVC factory flow is complete.

- [ ] **Step 3: Wire host proof only**

Demo calls ClipEditorScreen(source, editor, onResult, onCancel). It does not receive a library navigation/upload/playback API. Add separate iOS tests for common and legacy unavailable result types.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest :video-clip-editor-core:iosSimulatorArm64Test :video-clip-editor-compose:iosSimulatorArm64Test :demo-android:assembleDebug

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

~~~
git add video-clip-editor-core/src/androidDeviceTest video-clip-editor-core/src/iosTest video-clip-editor-compose/src/commonMain video-clip-editor-compose/src/commonTest sample-ios-swift demo-android
git commit -m "test: prove video clip editor host contracts"
~~~

### Task 6: Device, security, performance, and release evidence (R1)

**Scope:** Evidence only. Return any failure to owning task. Never weaken API/device/ownership claims to pass.

**Files:**

- Create: docs/verification/2026-08-06-android-hevc-release-gate.md
- Modify: docs/feasibility/summary.md
- Modify: docs/superpowers/specs/2026-08-06-kmp-video-clip-editor-v1-api-baseline.md only by appending evidence, never public declarations.

**Dependencies:** Tasks 1–5.

**Acceptance:** Core/Compose/iOS tests pass; API-23/current devices pass; Samsung AVC/HEVC smoke recorded; output is H.264/AAC temp MP4 where device works or an exact typed result otherwise; temp ownership tests pass; no OneOnOne diff.

**Test strategy:** Keep commands, fixture hashes, device/API, XML, output MIME/path, cleanup result, and timings in verification document.

**Rollback:** Hold release and return defect to owner. No source change in this task.

**Integration:** Final Android V1 handoff. iOS media engine stays deferred.

- [ ] **Step 1: Run KMP/Compose suites**

Run: ./gradlew :video-clip-editor-core:allTests :video-clip-editor-compose:allTests :video-clip-editor-core:iosSimulatorArm64Test :video-clip-editor-compose:iosSimulatorArm64Test

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run Android/device suites**

Run: ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest :demo-android:assembleDebug

Expected: all tests green or documented typed capability result.

- [ ] **Step 3: Run Samsung smoke**

Run: adb -s RZCX519T5FL shell getprop ro.build.version.sdk

Expected: record SDK; select AVC/HEVC fixtures; record output MIME/path/cleanup.

- [ ] **Step 4: Verify scope and frozen API**

Run: git diff --check && git status --short

Expected: no OneOnOne path and no unapproved public API drift.

- [ ] **Step 5: Commit evidence**

~~~
git add docs/verification docs/feasibility/summary.md docs/superpowers/specs/2026-08-06-kmp-video-clip-editor-v1-api-baseline.md
git commit -m "docs: record Android HEVC release verification"
~~~

## Plan Self-Review

- Coverage: E1 port, L1 lifecycle, P1 source policy, X1 Media3/temp lease, U1/I1 UI and iOS contract, R1 full verification.
- Placeholder scan: clean. Every task lists scope, files, interfaces, dependencies, acceptance, test, rollback, integration, RED/GREEN commands, and commit.
- Type consistency: all public VideoClipEditor, ClipEditorSession, TemporaryClipLease, result, Android/iOS factory, and ClipEditorScreen declarations remain frozen.
