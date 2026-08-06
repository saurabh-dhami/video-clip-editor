# Visual Clip Editor Preview Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement task-by-task. Steps use checkbox (- [ ]) syntax.

**Goal:** Deliver Android V1 visual clip editing: fitted source preview, shared thumbnail timeline, pannable/scrubbable playhead, draggable trim handles, continuous selected-range looping, existing temporary-MP4 export, and safe cleanup.

**Architecture:** Preserve every public core declaration and the exact ClipEditorScreen signature. Common Compose owns selector geometry and canonical editing state. An internal PreviewPort bridges it to Android-only Media3 playback; Android owns one main-thread ExoPlayer and clips its MediaItem at source level. iOS supplies only an internal unavailable actual for future AVFoundation replacement.

**Tech Stack:** Kotlin Multiplatform; Compose Multiplatform 1.11.0; Android API 23+; Media3 1.10.1; coroutines/Flow; Android instrumentation; API-23 emulator; Samsung SM-S928B/API-36.

## Global constraints

- Work only in /Users/sandeepdhami/Documents/GitHub/video-clip-editor/.worktrees/feasibility on chore/feasibility.
- Never modify, import, build, or use fixtures from OneOnOneArena.
- Preserve docs/superpowers/specs/2026-08-06-kmp-video-clip-editor-v1-api-baseline.md, including the exact ClipEditorScreen signature.
- Core stays playback/UI-free. Context, Uri, ExoPlayer, Player, Surface, MediaItem, AVFoundation, UIKit, and Swift types are forbidden from common/public signatures.
- Input, temporary absolute-path output, issued lease, and cleanup semantics remain unchanged.
- ClipRange is canonical. Keep its frozen 500-ms minimum. Player mirrors selected range; it never chooses export range.
- Keep the existing bounded 24-frame request. No frame extraction during pan, scrub, or playback.
- Every binding must use MediaItem.ClippingConfiguration plus Player.REPEAT_MODE_ONE. Never implement selected-end loop with UI polling.
- Preview failure is internal/sanitized/retryable. It does not add a public result, throw to the host, or create an output lease.
- Each task requires observed RED, minimal GREEN, regression GREEN, independent review, its own rollback point, and commit.
- Preserve the pre-existing untracked docs/superpowers/specs/2026-08-06-kmp-video-clip-editor-hevc-v1-design.md unchanged.

## Frozen internal contract

Create video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/PreviewPort.kt exactly as follows. All declarations are internal.

~~~
internal data class PreviewGeneration(val value: Long)
internal data class PreviewRevision(val value: Long)

internal data class PreviewBinding(
    val generation: PreviewGeneration,
    val revision: PreviewRevision,
    val source: VideoSourcePath,
    val metadata: VideoMetadata,
    val range: ClipRange,
    val sourcePosition: Duration,
    val playWhenReady: Boolean,
)

internal sealed interface PreviewCommand {
    data class Bind(val binding: PreviewBinding) : PreviewCommand
    data class Seek(val generation: PreviewGeneration, val revision: PreviewRevision, val sourcePosition: Duration) : PreviewCommand
    data class SetPlayWhenReady(val generation: PreviewGeneration, val revision: PreviewRevision, val value: Boolean) : PreviewCommand
    data class ReplaceRange(val binding: PreviewBinding) : PreviewCommand
    data class Retry(val binding: PreviewBinding) : PreviewCommand
    data class Release(val generation: PreviewGeneration) : PreviewCommand
}

internal sealed interface PreviewEvent {
    data class Ready(val generation: PreviewGeneration, val revision: PreviewRevision) : PreviewEvent
    data class Position(val generation: PreviewGeneration, val revision: PreviewRevision, val sourcePosition: Duration, val isPlaying: Boolean) : PreviewEvent
    data class Released(val generation: PreviewGeneration) : PreviewEvent
    data class RecoverableFailure(val generation: PreviewGeneration, val revision: PreviewRevision, val diagnostic: String?) : PreviewEvent
}

internal interface PreviewPort {
    val events: Flow<PreviewEvent>
    fun dispatch(command: PreviewCommand)
}

~~~

Generation changes only for new source/session. Revision increases for each completed range change or retry. Presenter accepts Ready, Position, and RecoverableFailure only when generation and revision both match active binding. Release is terminal; screen waits for matching Released, with bounded recorded fallback, before ClipEditorSession.close().

V1 freezes the value and port declarations above, which compile without platform code. V2 adds the internal helper declarations below with Android/iOS actuals in the same commit; this keeps V1 independently green while retaining the exact internal future seam.

~~~
@Composable internal expect fun rememberPlatformPreviewPort(): PreviewPort
@Composable internal expect fun PlatformPreviewSurface(port: PreviewPort, modifier: Modifier = Modifier)
~~~

## Ordered task map

| Task | Scope/ownership | Dependencies | Hard handoff |
| --- | --- | --- | --- |
| V1 | Common geometry, selector, port contract, fake-port tests | Approved blueprint | V2/V3 consume exact seam |
| V2 | Android Media3 actual, iOS unavailable actual, device tests | V1 green | V3 gets source-clipped preview |
| V3 | Screen/presenter wiring and release fence | V1/V2 green | IG1 gets assembled screen |
| IG1 | Real Android integration flow test only | V1-V3 accepted | V4 blocked until API-23 green |
| V4 | Standalone demo and API-23/Samsung evidence | IG1 green | V5 audit input |
| V5 | Independent traceability/API/security/license audit | V1-V4/IG1 green | only PASS completes goal |

---

### Task 1: V1 — common selector geometry and preview protocol

**Scope:** Replace the detached dark handle bar plus Row thumbnail strip with one shared internal selector and freeze only the common port value/interface contract. No Android, Media3, iOS, core engine, demo, or public API work.

**Files:**

- Create: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/PreviewPort.kt
- Create: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipRangeSelector.kt
- Create: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipRangeSelectorTest.kt
- Modify: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt
- Modify: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorPresenterTest.kt

**Responsibilities:**

- Common selector has one horizontally scrollable content coordinate system for fixed-width thumbnail images, dimming overlays, selected outline, start/end handles, and highest-z-order playhead.
- Each handle paints a narrow grip but owns a 48dp touch target.
- Mapping converts source time to content pixels. A tap or drag converts viewport pixels plus scroll offset back to source time; it never uses viewport coordinate alone.
- Bare-track drags pan only. Thumbnail tap clamps and seeks. Playhead drag pauses, seeks, and stays paused. Handle drag pauses and updates visual provisional range; drag end emits exactly one full ReplaceRange.
- Presenter keeps metadata, frame list, canonical range, export, session closure, and result/cancel callbacks.

**Interfaces:**

- Consumes ThumbnailFrame, VideoMetadata, ClipRange, Duration, existing decodeJpegForRender.
- Produces ClipRangeSelector; RangeBoundary; sourceTimeToContentPx; viewportPxToSourceTime; clampRangeBoundary; clampPlayhead; exact PreviewPort value/interface contract. V2 owns the expect/actual Compose helper functions.
- Preserves ClipEditorScreen(source, editor, onResult, onCancel, modifier) and existing createClip(ready.range) call.

**Acceptance:**

- 24 fixed frames create a pannable strip on phone widths.
- Start/end cannot cross or violate 500 ms.
- Mapping is inverse at zero, half, and maximum scroll.
- Tap outside selection clamps to nearest selected boundary.
- Semantics tags exist: clip-timeline, clip-start-handle, clip-end-handle, clip-playhead, clip-start-time, clip-end-time, back, play-pause, done.
- commonMain has no platform imports.

**Test strategy:** common pure geometry tests, Compose semantics/gesture tests with RecordingPreviewPort, and all existing presenter tests.

**Rollback:** revert V1 files only; core/export behavior remains intact.

**Integration:** V2 implements exact port only after V1 pass. V3 consumes callbacks, not selector internals.

- [ ] **Step 1: Write failing tests**

~~~
@Test
fun viewportTimeMapping_isInverseAtZeroHalfAndMaximumScroll() {
    val duration = 10_000.milliseconds
    val contentWidth = 2_400f
    listOf(0f, 1_000f, 2_000f).forEach { scrollPx ->
        val content = sourceTimeToContentPx(7_500.milliseconds, duration, contentWidth)
        assertEquals(
            7_500.milliseconds,
            viewportPxToSourceTime(
                viewportPx = content - scrollPx,
                scrollPx = scrollPx,
                viewportWidthPx = 400f,
                contentWidthPx = contentWidth,
                duration = duration,
            ),
        )
    }
}

@Test
fun endHandle_cannotCrossStartOrBreakMinimumRange() {
    assertEquals(
        1_500.milliseconds,
        clampRangeBoundary(100.milliseconds, 1.seconds, 10.seconds, RangeBoundary.End),
    )
}

@Test
fun completedHandleDrag_emitsOneRangeReplacement() {
    val port = RecordingPreviewPort()
    val presenter = readyPresenter(port)
    presenter.beginRangeGesture()
    presenter.updateEndFromSelector(4.seconds)
    presenter.updateEndFromSelector(5.seconds)
    presenter.commitRangeGesture()

    assertEquals(1, port.commands.filterIsInstance<PreviewCommand.ReplaceRange>().size)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-compose:allTests --tests '*ClipRangeSelectorTest'

Expected: compile failure because selector, port, and mapping helpers do not exist.

- [ ] **Step 3: Implement minimal common behavior**

~~~
internal fun clampRangeBoundary(
    requested: Duration,
    fixedOtherBoundary: Duration,
    duration: Duration,
    boundary: RangeBoundary,
): Duration = when (boundary) {
    RangeBoundary.Start -> requested.coerceIn(Duration.ZERO, fixedOtherBoundary - 500.milliseconds)
    RangeBoundary.End -> requested.coerceIn(fixedOtherBoundary + 500.milliseconds, duration)
}

internal fun clampPlayhead(value: Duration, range: ClipRange): Duration =
    value.coerceIn(range.start, range.endExclusive)
~~~

Use a horizontal ScrollState/Row or LazyRow with fixed frame width. Overlays and all markers are children of the same content Box. Keep existing JPEG frame bytes bounded; never request frames during scroll. Retain old mapping helpers only until all callers are moved and tests prove replacement behavior.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-compose:allTests

Expected: BUILD SUCCESSFUL. Existing 500-ms/callback/disposal tests and new mapping/gesture tests pass.

- [ ] **Step 5: Boundary scan**

Run: rg -n 'android\.|androidx\.media3|ExoPlayer|MediaItem|AVFoundation|UIKit' video-clip-editor-compose/src/commonMain

Expected: zero matches.

- [ ] **Step 6: Independent V1 review and commit**

Review mapping, 48dp targets, one ReplaceRange per completed drag, test tags, public ABI, and common-only boundary. Reject viewport-only time mapping or platform imports.

~~~
git add video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/PreviewPort.kt \
  video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipRangeSelector.kt \
  video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt \
  video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose
git commit -m "feat(compose): add shared clip range selector"
~~~

---

### Task 2: V2 — Android Media3 preview actual and iOS unavailable actual

**Scope:** Implement platform actuals and Android-only dependencies. Do not wire screen/presenter or modify core exporter.

**Files:**

- Modify: gradle/libs.versions.toml
- Modify: video-clip-editor-compose/build.gradle.kts
- Create: video-clip-editor-compose/src/androidMain/kotlin/com/oneononearena/videoclip/compose/AndroidMedia3PreviewPort.kt
- Create: video-clip-editor-compose/src/androidMain/kotlin/com/oneononearena/videoclip/compose/AndroidPlatformPreview.kt
- Create: video-clip-editor-compose/src/iosMain/kotlin/com/oneononearena/videoclip/compose/IosPlatformPreview.kt
- Create: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/AndroidMedia3PreviewPortDeviceTest.kt
- Create: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/PreviewFixtureFiles.kt
- Create: video-clip-editor-compose/src/androidDeviceTest/assets/fixtures/avc-aac-10s-30fps.mp4

**Responsibilities:**

- Add media3-ui-compose catalog alias at version-ref media3. Add Android main dependencies for existing media3-exoplayer and new media3-ui-compose. Enable Android device test source set with AndroidX runner/JUnit/Compose test dependencies.
- Android actual owns one main-thread ExoPlayer. Compose-native PlayerSurface attaches video; never use PlayerView in AndroidView.
- Bind full source/range snapshot as a clipped MediaItem. Set Player.REPEAT_MODE_ONE. Translate clip-relative time to source range.
- Coalesce old binding while new one prepares. A Ready for old revision does not reach presenter. Release clears/release exactly once and emits Released once.
- iOS actual returns internal unavailable port/surface only. It does not import AVFoundation or change host code.

**Interfaces:** Exact V1 PreviewPort value/interface declarations plus V2's internal expect/actual rememberPlatformPreviewPort and PlatformPreviewSurface helpers. Test inspection remains internal/package-private and does not enter common/public code.

**Dependencies:** V1 accepted commit; Media3 1.10.1.

**Acceptance:**

- Accepted local AVC fixture starts paused at selected source start and renders fitted.
- Clipping configuration equals source range; repeat mode equals ONE.
- All reported source times stay inside selected range.
- Replacement cannot accept old generation/revision.
- Release has one acknowledgement. iOS simulator test compiles.

**Test strategy:** Android instrumentation copies repository asset to test cache and deletes only that copy. Test player state through an internal test probe, not public getter.

**Rollback:** revert V2 dependencies, actuals, and tests. V1 selector remains common/unavailable.

**Integration:** V3 injects the port only after V2 device and iOS compile gates pass.

- [ ] **Step 1: Write failing device tests**

~~~
@Test
fun bindingUsesExactSourceClipAndOnePeriodLoop() = runTest {
    val port = AndroidMedia3PreviewPort(context, mainDispatcherRule.dispatcher)
    val binding = fixtureBinding(
        range = ClipRange(2.seconds, 4.seconds),
        sourcePosition = 3.seconds,
        playWhenReady = false,
    )
    port.dispatch(PreviewCommand.Bind(binding))

    assertEquals(2_000L, port.testPlayer().mediaItem.clippingConfiguration.startPositionMs)
    assertEquals(4_000L, port.testPlayer().mediaItem.clippingConfiguration.endPositionMs)
    assertEquals(Player.REPEAT_MODE_ONE, port.testPlayer().repeatMode)
}

@Test
fun obsoleteReadyDoesNotMakeLatestRangePlayable() = runTest {
    val port = controlledPreviewPort()
    port.dispatch(PreviewCommand.Bind(binding(revision = 1)))
    port.dispatch(PreviewCommand.ReplaceRange(binding(revision = 2)))
    port.completeReady(revision = 1)

    assertTrue(port.events.none { it is PreviewEvent.Ready && it.revision == PreviewRevision(1) })
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest :video-clip-editor-compose:iosSimulatorArm64Test

Expected: source set/actual/port is unresolved.

- [ ] **Step 3: Implement minimal actuals**

~~~
val item = MediaItem.Builder()
    .setUri(Uri.fromFile(File(binding.source.value)))
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(binding.range.start.inWholeMilliseconds)
            .setEndPositionMs(binding.range.endExclusive.inWholeMilliseconds)
            .build(),
    )
    .build()
player.setMediaItem(item)
player.repeatMode = Player.REPEAT_MODE_ONE
~~~

On ReplaceRange: pause; detach previous item; retain highest pending revision; bind exact new item; seek to (binding.sourcePosition - binding.range.start).coerceAtLeast(Duration.ZERO); prepare; emit one matching Ready; restore binding.playWhenReady. On listener error, emit RecoverableFailure with bounded errorCodeName/message only—no stack trace/path/content. Enforce main Looper on create/dispatch/listener/release. Source time emitted is:

~~~
(binding.range.start + player.currentPosition.milliseconds)
    .coerceIn(binding.range.start, binding.range.endExclusive)
~~~

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-compose:allTests :video-clip-editor-compose:iosSimulatorArm64Test :video-clip-editor-compose:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest

Expected: BUILD SUCCESSFUL. Device test proves exact clip/repeat, stale revision rejection, safe failure event, and single Released.

- [ ] **Step 5: Dependency/platform scans**

Run:

~~~
rg -n 'androidx\.media3|ExoPlayer|MediaItem|android\.' video-clip-editor-compose/src/commonMain
./gradlew :video-clip-editor-compose:dependencies --configuration androidMainCompileClasspath
~~~

Expected: no common platform imports; Android classpath resolves Media3 1.10.1 only.

- [ ] **Step 6: Independent V2 review and commit**

Review source-level clipping, loop proof, Android thread/lifecycle safety, pending revision semantics, single release event, sanitized errors, and iOS no-op boundary. Reject UI-poll loops, PlayerView interop, or public factory.

~~~
git add gradle/libs.versions.toml video-clip-editor-compose/build.gradle.kts \
  video-clip-editor-compose/src/androidMain video-clip-editor-compose/src/iosMain \
  video-clip-editor-compose/src/androidDeviceTest
git commit -m "feat(android): add Media3 clip preview adapter"
~~~

---

### Task 3: V3 — screen composition, presenter ownership, release fence

**Scope:** Wire V1/V2 into ClipEditorScreen and presenter. Do not change core engine/export/lease code or standalone demo.

**Files:**

- Modify: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt
- Create: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorPreviewCoordinator.kt
- Create: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorPreviewCoordinatorTest.kt
- Modify: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorPresenterTest.kt
- Modify: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/AndroidMedia3PreviewPortDeviceTest.kt

**Responsibilities:**

- Screen order: fitted black preview surface; source start/end labels; selector; Back, Play/Pause, Done footer.
- Presenter owns session, metadata, frames, canonical range, export, cleanup, result/cancel. Coordinator owns active binding and port command/event filtering.
- Initial binding is paused at range start. Play starts current position only if inside range; otherwise starts selected range start. Ready gate enables Play. Preview failure disables Play/Done and permits Retry.
- Handle drag pauses and provisional visual change; only commit sends one ReplaceRange. Playhead gestures pause/seek/remain paused. Export disables player/control mutations.
- On disposal/cancel/source replacement: dispatch Release, wait matching Released with bounded timeout/fallback record, then presenter.close. No session close first.

**Interfaces:** V1 common selector/port; V2 actual; frozen public ClipEditorScreen/ClipResult/ClipEditorSession only.

**Dependencies:** V1 + V2 independent green gates.

**Acceptance:**

- Rendered range is the range passed to existing createClip.
- Stale source/revision events never change playhead/state.
- onResult/onCancel still fire once.
- Player release acknowledgement precedes session close.
- No new public parameter/type/result code.

**Test strategy:** Common fake-port tests, Compose semantics tests, V2 device control test. Core regression runs later at IG1.

**Rollback:** revert V3 files only. V1/V2 remain unused; core untouched.

**Integration:** IG1 uses real screen + Media3 actual + production core session/export.

- [ ] **Step 1: Write failing coordinator tests**

~~~
@Test
fun stalePositionDoesNotMutateCurrentBinding() = runTest {
    val coordinator = ClipEditorPreviewCoordinator(fakePort)
    coordinator.bind(binding(generation = 7, revision = 1, sourcePosition = 2.seconds))
    coordinator.replaceRange(binding(generation = 7, revision = 2, sourcePosition = 2.seconds))
    fakePort.emit(PreviewEvent.Position(PreviewGeneration(7), PreviewRevision(1), 8.seconds, true))

    assertEquals(2.seconds, coordinator.state.value.playhead)
}

@Test
fun closeReleasesPreviewBeforeSession() = runTest {
    val calls = mutableListOf<String>()
    val port = RecordingPreviewPort(onRelease = { calls += "released" })
    val coordinator = ClipEditorPreviewCoordinator(port)

    coordinator.closeThen { calls += "session" }

    assertEquals(listOf("released", "session"), calls)
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-compose:allTests --tests '*ClipEditorPreviewCoordinatorTest'

Expected: coordinator/release ordering API unresolved.

- [ ] **Step 3: Implement minimal wiring**

~~~
private fun accepts(event: PreviewEvent, active: PreviewBinding): Boolean = when (event) {
    is PreviewEvent.Ready -> event.generation == active.generation && event.revision == active.revision
    is PreviewEvent.Position -> event.generation == active.generation && event.revision == active.revision
    is PreviewEvent.RecoverableFailure -> event.generation == active.generation && event.revision == active.revision
    is PreviewEvent.Released -> event.generation == active.generation
}
~~~

Use one LaunchedEffect per port/source generation, not per recomposition. Use withTimeoutOrNull only around awaiting Released. On timeout prevent further commands for old generation, record fallback internally, then close exactly once. Do not create one cleanup scope per recomposition.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :video-clip-editor-compose:allTests :video-clip-editor-compose:iosSimulatorArm64Test

Expected: BUILD SUCCESSFUL. Old disposal/range/callback tests and new stale-event/release/export-range tests pass.

- [ ] **Step 5: Device behavior check**

Run: ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest

Expected: initial pause at selected start; scrub stays range-bounded; Play loops clipped item; release event precedes recorded session close.

- [ ] **Step 6: Independent V3 review and commit**

Review public ABI, one-export gate, range canonicality, source/revision filtering, failure behavior, fitted preview, and Release → session close. Reject any cleanup path that closes session first.

~~~
git add video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt \
  video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorPreviewCoordinator.kt \
  video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose \
  video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose
git commit -m "feat(compose): wire clip preview and range controls"
~~~

---

### Task 4: IG1 — integration-only real editor-flow gate

**Scope:** Add no reusable production feature. Assemble V1–V3 in library-owned Android test host with real local fixture, Media3 actual, production editor/exporter, and test-only event recorder.

**Files:**

- Create: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreenIntegrationTest.kt
- Create: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/RecordingClipEditorSession.kt
- Modify: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/PreviewFixtureFiles.kt
- Modify: video-clip-editor-compose/build.gradle.kts only if test host proves an added test dependency necessary

**Responsibilities:**

- Prove sequence: open source → extract frames → bind clipped preview → pan/scrub → commit range → selected-range loop → Done/export → preview Released → session close → issued lease first/second clear.
- Test recorder timestamps commands/events/release/session close/export/cleanup. It is test-only and never public/demo.
- Test real Media3 actual and production core exporter; no fake can satisfy this gate.

**Interfaces:** frozen ClipEditorScreen, ClipResult.Success.sourceRange, internal port, test-only recorder only.

**Dependencies:** V1–V3 accepted. V4 cannot start without API-23 IG1 success.

**Acceptance:**

- Export sourceRange equals committed UI range.
- Applied clip config equals committed source range and repeat mode ONE.
- Source positions remain range-bounded and prove return to selected start while playing.
- Released appears before wrapped session close.
- First lease clear is Cleared; second is AlreadyCleared.

**Test strategy:** Android instrumentation using repository AVC fixture copied to cache; Compose test tags and state/event waits only, no arbitrary sleeps.

**Rollback:** never weaken IG1. Revert/fix owning V1/V2/V3 task then rerun IG1.

**Integration:** green API-23 evidence unlocks V4.

- [ ] **Step 1: Write failing real flow test**

~~~
@Test
fun editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose() = runTest {
    val events = mutableListOf<RecordedEvent>()
    val result = launchScreenAndInteract(
        start = 2.seconds,
        end = 4.seconds,
        events = events,
    )

    val success = assertIs<ClipResult.Success>(result)
    assertEquals(ClipRange(2.seconds, 4.seconds), success.sourceRange)
    assertTrue(events.indexOfFirst { it == Released } < events.indexOfFirst { it == SessionClosed })
    assertTrue(events.any { it is SourcePosition && it.value == 2.seconds && it.isPlaying })
    assertEquals(TempDeleteResult.Cleared, success.output.clearTemporaryFile())
    assertEquals(TempDeleteResult.AlreadyCleared, success.output.clearTemporaryFile())
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.ClipEditorScreenIntegrationTest

Expected: missing integration test/recorder, or an observable cross-module defect.

- [ ] **Step 3: Add only test observability**

Wrap VideoClipEditor.openSession and ClipEditorSession.close; record internal preview events through a test-only wrapper. Use production source/open/export and actual player. Copy fixture to test cache, delete it in finally, and never touch user files.

- [ ] **Step 4: Verify GREEN on API 23**

Run:

~~~
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.ClipEditorScreenIntegrationTest --rerun-tasks
~~~

Expected: BUILD SUCCESSFUL. Test record proves range, loop, export, release-before-close, and idempotent cleanup. Reconnect/start existing API-23 emulator before claiming blocker.

- [ ] **Step 5: Core regression proof**

Run:

~~~
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest --rerun-tasks
~~~

Expected: all existing core session/frame/export/temporary ownership tests pass unchanged.

- [ ] **Step 6: Independent IG1 review and commit**

Reject fake-only proof, arbitrary correctness delay, public test hook, or screen flow that skips real Media3/prod exporter.

~~~
git add video-clip-editor-compose/src/androidDeviceTest video-clip-editor-compose/build.gradle.kts
git commit -m "test(android): prove visual clip editor integration flow"
~~~

---

### Task 5: V4 — standalone demo lifecycle and device/visual evidence

**Scope:** Upgrade only demo-android host lifecycle and capture approved visual/device evidence with legal fixture. No OneOnOneArena work, no public library API.

**Files:**

- Modify: demo-android/src/main/kotlin/com/oneononearena/videoclip/demo/DemoActivity.kt
- Modify: demo-android/src/main/kotlin/com/oneononearena/videoclip/demo/BoundedDocumentImporter.kt
- Modify: demo-android/src/test/kotlin/com/oneononearena/videoclip/demo/BoundedDocumentImporterTest.kt
- Create: docs/verification/2026-08-06-visual-clip-editor-android-gate.md
- Modify only by append: docs/verification/2026-08-06-android-hevc-release-gate.md

**Responsibilities:**

- Remove DemoActivity's intentional no-op releasePlayer assumption.
- Make SessionTrackingEditor return a forwarding ClipEditorSession that completes a deferred only after screen presenter actually closes it.
- Demo cleanup: hide editor → await screen-owned session close (V3 releases player first) → clear issued lease → delete demo-owned imported copy → clear UI.
- Keep picker/navigation/upload host-owned. Never delete picker source/original media.
- Capture screenshots/video only from repository fixture. Do not retain personal device media.

**Interfaces:** existing ClipEditorScreen, TemporaryClipLease, TempDeleteResult; internal demo SessionTrackingEditor/DemoCleanupCoordinator only.

**Dependencies:** IG1 green on API 23.

**Acceptance:**

- Demo presents fitted preview/timeline/handles/playhead/labels/Back/Play/Done.
- Cleanup order is release screen/session then output lease then demo input source/UI, with no raw session close before screen disposal.
- API-23 and Samsung evidence separately record pan, scrub, range commit, selected loop, export, and cleanup.
- HEVC unavailable decoder produces existing typed result, never crash.

**Test strategy:** extend demo unit lifecycle tests; assemble demo; run Compose instrumented suite API-23/Samsung; manual fixture checklist/document hash.

**Rollback:** revert only V4 demo/docs files.

**Integration:** V5 consumes evidence/report.

- [ ] **Step 1: Write failing demo lifecycle test**

~~~
@Test
fun demoClear_waitsForScreenOwnedSessionCloseBeforeDeletingImportedSource() = runBlocking {
    val calls = mutableListOf<String>()
    val coordinator = DemoCleanupCoordinator(
        releasePlayer = { calls += "screen-release" },
        clearOutput = { calls += "lease-clear"; DemoClearResult.Cleared },
        closeSession = { calls += "session-close" },
        deleteSource = { calls += "source-delete"; true },
        clearUi = { calls += "ui-clear" },
    )

    assertEquals(DemoClearResult.Cleared, coordinator.clear())
    assertEquals(
        listOf("screen-release", "lease-clear", "session-close", "source-delete", "ui-clear"),
        calls,
    )
}
~~~

- [ ] **Step 2: Verify RED**

Run: ./gradlew :demo-android:testDebugUnitTest --tests '*BoundedDocumentImporterTest*'

Expected: no implementation for screen-owned close acknowledgement; current DemoActivity still has no-op release.

- [ ] **Step 3: Implement safe demo handoff**

SessionTrackingEditor.openSession returns a forwarding session whose close completes CompletableDeferred after delegate close; add awaitActiveSessionClosed under sessionMutex. In DemoActivity releasePlayer, set editorVisible = false then await editor.awaitActiveSessionClosed. This allows V3 composable disposal to release player before close. Do not call closeActiveSession from clear before hiding editor. Retain it only for non-composed recovery if tests prove no race.

- [ ] **Step 4: Verify GREEN**

Run: ./gradlew :demo-android:testDebugUnitTest :demo-android:assembleDebug

Expected: BUILD SUCCESSFUL. Import cap/error and safe-order lifecycle tests pass.

- [ ] **Step 5: API-23/Samsung proof**

Run:

~~~
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest --rerun-tasks

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest --rerun-tasks

adb -s RZCX519T5FL shell getprop ro.build.version.sdk
~~~

Expected: suite green on both targets. Record model/SDK, fixture SHA-256, visual checklist, ranges, output MIME/path or typed result, cleanup result, and no-personal-media statement in new verification doc.

- [ ] **Step 6: Independent V4 review and commit**

Review source ownership, demo order, fixture provenance, evidence completeness, and absence of copied third-party/GPL trimmer code.

~~~
git add demo-android/src/main demo-android/src/test docs/verification
git commit -m "docs: verify visual clip editor on Android devices"
~~~

---

### Task 6: V5 — independent completion audit and traceability

**Scope:** No feature code. Independently audit every delivered V1–V4/IG1 commit/evidence against approved blueprint and objective.

**Files:**

- Create: docs/verification/2026-08-06-visual-clip-editor-traceability.md
- Modify only if evidence requires: docs/verification/2026-08-06-visual-clip-editor-android-gate.md

**Responsibilities:**

- Compare core/public screen declarations with committed API baseline.
- Audit common platform leakage, source-level clipping/repeat, lifecycle/release ordering, failure sanitization, output/source ownership, license/version, fixture provenance, API-23/Samsung evidence, and no OneOnOneArena diff.
- Produce criterion → prerequisite → task → direct evidence → result → residual-risk table.
- Obtain principal review by an author-distinct reviewer. PASS requires V1/V2/V3/IG1/V4/V5 each >=95/100, overall >=95/100, no critical veto.

**Interfaces:** no production interface changes.

**Dependencies:** V1–V4/IG1 accepted commits and verification records.

**Acceptance:** Direct current evidence exists for preview, selector, pan/scrub, valid range, looping, existing lease/export/cleanup, iOS compile seam, API isolation, API-23/Samsung, and scope. Unit tests alone cannot prove device/integration claims.

**Test strategy:** full matrix, scans, and independent review.

**Rollback:** hold completion; repair/review owning bounded task, rerun IG1 if V1–V3 change, then rerun V5.

**Integration:** only V5 PASS permits goal completion.

- [ ] **Step 1: Full matrix**

~~~
./gradlew :video-clip-editor-core:allTests \
  :video-clip-editor-compose:allTests \
  :video-clip-editor-core:iosSimulatorArm64Test \
  :video-clip-editor-compose:iosSimulatorArm64Test \
  :demo-android:testDebugUnitTest \
  :demo-android:assembleDebug

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest \
  :video-clip-editor-compose:connectedAndroidDeviceTest --rerun-tasks

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-core:connectedAndroidDeviceTest \
  :video-clip-editor-compose:connectedAndroidDeviceTest --rerun-tasks
~~~

Expected: BUILD SUCCESSFUL. Only existing typed device capability results may be documented; no crash.

- [ ] **Step 2: API/scope/dependency scans**

~~~
git diff --check 55be20d..HEAD
git diff --name-only 55be20d..HEAD | rg '(^|/)OneOnOneArena(/|$)' && exit 1 || true
rg -n 'android\.|androidx\.media3|ExoPlayer|MediaItem|AVFoundation|UIKit|android\.net\.Uri' \
  video-clip-editor-compose/src/commonMain video-clip-editor-core/src/commonMain
git diff --word-diff=porcelain 55be20d..HEAD -- \
  video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/VideoClipEditorContract.kt \
  video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt
./gradlew :video-clip-editor-compose:dependencies --configuration androidMainRuntimeClasspath
~~~

Expected: whitespace clean; no OneOnOneArena path; zero forbidden common imports; public declarations unchanged; Media3 Android-only at 1.10.1.

- [ ] **Step 3: Write traceability table**

| Objective criterion | Backward prerequisite | Delivery task | Direct evidence | Result | Residual risk |
| --- | --- | --- | --- | --- | --- |
| Fitted preview | VUI-03 | V2/V3/IG1/V4 | | | |
| Shared thumbnails/handles/playhead | VUI-04 | V1/V3/V4 | | | |
| Pan and scrub | VUI-04 | V1/V3/IG1 | | | |
| Valid 500-ms range | frozen validation | V1/IG1 | | | |
| Continuous selected loop | VUI-01/VUI-03 | V2/IG1/V4 | | | |
| Existing export/temp lease | core contract | V3/IG1/V4 | | | |
| Release/cleanup/source safety | VUI-03/VUI-07 | V3/IG1/V4 | | | |
| iOS future seam compiles | VUI-02 | V2/V5 | | | |
| API-23 and Samsung | VUI-05 | IG1/V4 | | | |
| No OneOnOneArena change | scope boundary | V5 | | | |

- [ ] **Step 4: Independent principal review**

Supply approved blueprint, this plan, commit range, full matrix output, device evidence, traceability report, and scan output. Reviewer must not be V1–V4 implementer. Repair any blocker and re-review.

- [ ] **Step 5: Audit commit and handoff**

~~~
git add docs/verification/2026-08-06-visual-clip-editor-traceability.md \
  docs/verification/2026-08-06-visual-clip-editor-android-gate.md
git commit -m "docs: audit visual clip editor delivery"
git status --short
git log --oneline 55be20d..HEAD
~~~

Expected: independent PASS >=95/100 and no untracked/modified work except expressly preserved historical spec.

## Plan self-review

- Coverage: V1 selector/geometry, V2 Media3 source clipping/iOS seam, V3 screen ownership/release, IG1 real flow, V4 demo/device evidence, V5 independent traceability.
- Placeholder scan: no deferred markers. Each task contains scope, responsibility, interfaces, dependencies, acceptance, test strategy, rollback, integration, RED/GREEN, review, and commit.
- Type consistency: PreviewBinding/Command/Event/Port exactly match approved blueprint. No task alters public factory, ClipEditorScreen signature, ClipResult, or failure enums.
- Ordering: V1 → V2 → V3 → IG1 → V4 → V5. V4 requires API-23 IG1 green; V5 requires both device targets and independent PASS.
