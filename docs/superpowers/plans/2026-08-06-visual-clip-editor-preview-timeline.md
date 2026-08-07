# Visual Clip Editor Preview Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement task-by-task. Steps use checkbox (- [ ]) syntax.

**Goal:** Deliver Android V1 visual clip editing: fitted source preview, shared thumbnail timeline, pannable/scrubbable playhead, draggable trim handles, continuous selected-range looping, existing temporary-MP4 export, and safe cleanup.

**Architecture:** Preserve every public core declaration and the exact ClipEditorScreen signature. Common Compose owns selector geometry and canonical editing state. An internal PreviewPort bridges it to Android-only Media3 playback; Android owns one main-thread ExoPlayer and clips its MediaItem at source level. iOS supplies only an internal unavailable actual for future AVFoundation replacement.

**Tech Stack:** Kotlin Multiplatform; Compose Multiplatform 1.11.0; Android API 23+; Media3 1.10.1; coroutines/Flow; Android instrumentation; API-23 emulator; Samsung SM-S928B/API-36.

## Global constraints

- Work only in /Users/sandeepdhami/Documents/GitHub/OneOnOneArena-Workspace/video-clip-editor-visual-worktree on chore/visual-editor-execution. This is an isolated worktree of the standalone video-clip-editor repository.
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
internal interface PreviewPortFactory {
    fun create(): PreviewPort
    fun dispose(port: PreviewPort)
}

@Composable internal expect fun rememberPlatformPreviewPortFactory(): PreviewPortFactory
@Composable internal expect fun PlatformPreviewSurface(port: PreviewPort, modifier: Modifier = Modifier)
~~~

Replacement V3 supersedes the original V3 chunk at `1774142` and rejected fixes `0501e57` and `24ec733..fdfe8e6`, not only direct-port ownership. Those revisions are historical evidence, never implementation input. The screen uses internal `rememberPlatformPreviewPortFactory()`; one lifecycle owner creates/disposes ports; `PlatformPreviewSurface` only renders its supplied active port. The legacy `rememberPlatformPreviewPort()` helper must be removed or left unused and cannot participate in V3 lifecycle. Release audit and lifecycle intent types remain internal and are frozen by blueprint §6A. The audit uses the closed `PreviewReleaseOutcome.Acknowledged` or `PreviewReleaseOutcome.TimedOut(PreviewReleaseDiagnostic.ReleaseTimeout)` contract; no free-form `String?` or exception is representable.

## Ordered task map

| Task | Scope/ownership | Dependencies | Hard handoff |
| --- | --- | --- | --- |
| V1 | Common geometry, selector, port contract, fake-port tests | Approved blueprint | V2/V3 consume exact seam |
| V2 | Android Media3 actual, iOS unavailable actual, device tests | V1 green | V3 gets source-clipped preview |
| Replacement V3 | Serialized lifecycle owner, terminal port factory/order, durable closed audit, export mutation gate; **Sol/high floor** | V1/V2 green; VUI-R7–R9 reconcile three rejected V3 rounds | IG1 gets accepted assembled screen |
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

### Task 3: Replacement V3 — serialized lifecycle ownership and terminal preview ports

This single task supersedes the rejected V3 implementation and every earlier V3 fix-round instruction. Do not split it into another production chunk. IG1 remains the next task.

**Model route:** Sol/high, ordered single owner. Required by cross-coroutine/session/native-port concurrency, close-versus-replace races, and three successive V3 rejection triggers VUI-R7–R9. Terra or any lower route is a below-floor override and blocks task start. The author-distinct principal reviewer must verify the observed execution route.

#### Scope

Re-architect only internal Compose preview/session lifecycle ownership and focused common/Android tests. No public API, core, dependency, standalone demo, OneOnOneArena, or exposed iOS type change.

**Files:**

- Create: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorLifecycleOwner.kt
- Modify: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/PreviewPort.kt
- Modify: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorPreviewCoordinator.kt
- Modify: video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt
- Create: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorLifecycleOwnerTest.kt
- Modify: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorPreviewCoordinatorTest.kt
- Modify: video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorPresenterTest.kt
- Modify: video-clip-editor-compose/src/androidMain/kotlin/com/oneononearena/videoclip/compose/AndroidPlatformPreview.kt only to keep factory-owned disposal and render-only surface behaviour
- Modify: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/AndroidMedia3PreviewPortDeviceTest.kt
- Create: video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorLifecycleDeviceTest.kt

#### Responsibilities

- `ClipEditorLifecycleOwner` is the only replacement/close authority. It owns one independent lifecycle scope/actor, presenter lifetime, event collector, monotonic generation epoch, current port/factory, release waiter, durable release audit, and session start/close ordering.
- `requestClose()` synchronously latches terminal state before enqueuing cleanup. It wins over queued, suspended, and late replacements. After every suspension, replacement rechecks the latch before session start, factory create, and Bind.
- Replacement order is exact on both branches: old `Release` → matching `Released` or bounded timeout → acknowledgement/timeout audit committed → old session close → old native port disposal → terminal recheck → fresh factory create → fresh presenter/session start at the next generation → fresh `Bind` for that generation. Android Release remains terminal. No post-Release command or port reuse.
- Composable effects enqueue intents only. They never own cleanup jobs or cancel lifecycle resources. Owner self-cancels only after terminal release/session close/native disposal completes.
- `ClipEditorPreviewCoordinator` becomes scope-free reducer/command policy if retained. It does not allocate generations, launch/collect, create/dispose ports, or sequence sessions.
- `PlatformPreviewSurface` renders the supplied active port only. No native disposal or release ordering.
- Preserve a separate latest `PreviewReleaseAudit` across fresh binding: generation, revision, closed acknowledged/timed-out outcome, and source-replacement/terminal-close reason only. `TimedOut` carries only allowlisted enum `PreviewReleaseDiagnostic.ReleaseTimeout`. No free-form string, path, URI, media value, stack fragment, exception type/message, `Throwable`, or host data is representable.
- Preserve range/playhead semantics. Provisional range cannot export. Export-in-progress disables/no-ops Back, Done, Play/Pause, Retry, seek, playhead, and trim mutations; terminal lifecycle close remains enabled.

#### Interfaces

Consume unchanged V1 `PreviewBinding`/commands/events/port and V2 Android actual. Retain internal `PreviewPortFactory.create()/dispose()` and `rememberPlatformPreviewPortFactory()`. Add only internal lifecycle intent/audit types from blueprint §6A, including sealed `PreviewReleaseOutcome` and enum `PreviewReleaseDiagnostic`. `PlatformPreviewSurface(port)` stays render-only. Preserve exact public `ClipEditorScreen`, `ClipResult`, `ClipEditorSession`, and callback contracts.

#### Dependencies

Accepted V1/V2 commits and existing Media3 1.10.1 actual. Existing coroutines test scheduler supplies deterministic timeout/race control. No Gradle, core, iOS exposed API, host, or fixture dependency changes.

#### Acceptance Criteria

1. Terminal fake records/rejects all commands after Release. Wrong-generation `Released` while the matching fence is pending performs no audit/close/dispose/create/start/bind. Matching acknowledgement asserts the entire exact order: `Release:g1:r1`, `Released:g1`, `audit:g1:r1:Acknowledged:SourceReplacement`, `session-close:g1`, `port-dispose:g1`, `port-create:g2`, `session-start:g2`, `Bind:g2:r1`.
2. Timeout asserts the entire exact order: `Release:g1:r1`, `audit:g1:r1:TimedOut(ReleaseTimeout):SourceReplacement`, `session-close:g1`, `port-dispose:g1`, `port-create:g2`, `session-start:g2`, `Bind:g2:r1`. The audit precedes close and remains observable after fresh Bind with exact old generation/revision/reason.
3. Controlled replace-versus-close race proves close wins, old session/port teardown exact once, zero fresh factory creates, zero fresh session starts, zero fresh binds, and queued/late replacements no-op.
4. Generations increase monotonically and never derive from source/path or reset after teardown. No detached child, composition-cancelled cleanup, or session-close-first route.
5. Matching live Position updates playhead. Real tagged trim/playhead gestures pause and stay range-bounded; one completed trim emits one ReplaceRange. Existing 500 ms/source-time rules unchanged.
6. Provisional Done is disabled/inert. Once export starts, every control mutation is disabled/inert; only committed canonical range reaches createClip once.
7. Android actual is terminal after Release; owner disposes old actual in required order; distinct factory-created actual binds and emits Ready. Focused device UI proves live playhead/gesture and export lockout.
8. Common/iOS regressions pass. A declaration-aware gate extracts the actual working-tree public `ClipEditorScreen` declaration and compares it byte-for-byte to baseline commit `92f78412796113f2abe27f55be0125e9373c9f1c`; it also compares the frozen public core/Android/iOS declarations with that baseline. Filename/import scans alone cannot pass this gate. No public, core, dependency, OneOnOneArena, or exposed iOS type diff.
9. Hostile diagnostic tests inject arbitrary absolute paths, `file://`/`content://` URIs, stack-shaped strings, exception class/messages, and `Throwable` values at the lower timeout seam. Every audit contains only `TimedOut(ReleaseTimeout)` and none of the injected data.

#### Test Strategy

Common tests use a terminal fake, virtual timeout, release barrier, lifecycle-intent barrier, and exact call recorder. Android instrumentation uses the repository fixture and actual port. Fake-only, compile-only, arbitrary-delay, or wrong-device evidence cannot satisfy device acceptance.

- [ ] **Step 1: Write terminal-fence, durable-audit, and exact-order RED tests**

~~~kotlin
@Test
fun wrongAckCannotAdvanceReplacement_thenMatchingAckUsesExactOrder() = runTest {
    val rig = lifecycleRig(terminalPort = true)
    rig.owner.requestReplace(sourceA)
    rig.awaitBound(sourceA)
    rig.owner.requestReplace(sourceB)
    rig.awaitCall("Release:g1:r1")

    rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(99)))
    runCurrent()
    assertEquals(listOf("Release:g1:r1"), rig.calls)

    rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(1)))
    rig.awaitBound(sourceB)
    assertEquals(
        listOf(
            "Release:g1:r1",
            "Released:g1",
            "audit:g1:r1:Acknowledged:SourceReplacement",
            "session-close:g1",
            "port-dispose:g1",
            "port-create:g2",
            "session-start:g2",
            "Bind:g2:r1",
        ),
        rig.calls,
    )
    assertEquals(emptyList(), rig.oldPort.commandsAfterRelease)
}

@Test
fun timeoutAuditSurvivesFreshBindingWithoutMediaPath() = runTest {
    val rig = lifecycleRig(terminalPort = true, sourcePath = "/fixture.mp4")
    rig.owner.requestReplace(sourceA)
    rig.awaitBound(sourceA)
    rig.owner.requestReplace(sourceB)
    advanceTimeBy(RELEASE_TIMEOUT.inWholeMilliseconds)
    rig.awaitBound(sourceB)

    assertEquals(
        PreviewReleaseAudit(
            PreviewGeneration(1),
            PreviewRevision(1),
            PreviewReleaseOutcome.TimedOut(PreviewReleaseDiagnostic.ReleaseTimeout),
            PreviewReleaseReason.SourceReplacement,
        ),
        rig.owner.releaseAudit.value,
    )
    assertEquals(
        listOf(
            "Release:g1:r1",
            "audit:g1:r1:TimedOut(ReleaseTimeout):SourceReplacement",
            "session-close:g1",
            "port-dispose:g1",
            "port-create:g2",
            "session-start:g2",
            "Bind:g2:r1",
        ),
        rig.calls,
    )
    assertFalse(rig.owner.releaseAudit.value.toString().contains("fixture.mp4"))
}

@Test
fun timeoutDiagnosticIsClosedAndDropsArbitrarySensitiveInputs() = runTest {
    val hostile = listOf(
        "/private/var/mobile/source.mp4",
        "file:///data/user/0/app/cache/source.mp4",
        "content://media/external/video/42",
        "java.lang.IllegalStateException: decoder\n\tat Player.release(Player.kt:41)",
    )

    hostile.forEach { raw ->
        val audit = lifecycleRig(releaseTimeoutCause = IllegalStateException(raw)).timeoutAudit()
        assertEquals(
            PreviewReleaseOutcome.TimedOut(PreviewReleaseDiagnostic.ReleaseTimeout),
            audit.outcome,
        )
        assertFalse(audit.toString().contains(raw))
    }
}
~~~

- [ ] **Step 2: Write close-wins, live-gesture, and export-lockout RED tests**

~~~kotlin
@Test
fun terminalCloseWinsReplacementPendingAtReleaseFence() = runTest {
    val rig = lifecycleRig(terminalPort = true)
    rig.owner.requestReplace(sourceA)
    rig.awaitBound(sourceA)
    rig.owner.requestReplace(sourceB)
    rig.awaitReleasePending(PreviewGeneration(1))
    rig.owner.requestClose()
    rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(1)))
    rig.awaitClosed()
    rig.owner.requestReplace(sourceC)
    runCurrent()

    assertEquals(1, rig.calls.count { it == "session-close:g1" })
    assertEquals(1, rig.calls.count { it == "port-dispose:g1" })
    assertEquals(0, rig.calls.count { it == "port-create:g2" })
    assertEquals(0, rig.calls.count { it == "session-start:g2" })
    assertEquals(0, rig.calls.count { it == "Bind:g2:r1" })
}

@Test
fun provisionalAndExportingStatesRejectEveryControlMutation() = runTest {
    val rig = readyScreenRig()
    rig.dragHandleWithoutRelease(end = 5.seconds)
    rig.clickDone()
    assertEquals(0, rig.session.createClipCalls)
    rig.releaseHandle()
    rig.clickDoneAndHoldExport()
    rig.performAllControlGestures()
    assertEquals(listOf(ClipRange(2.seconds, 5.seconds)), rig.session.createClipRanges)
    assertEquals(rig.commandsAtExportStart, rig.port.commands)
}
~~~

Also emit a matching live Position before real tagged handle/playhead gestures; assert visible playhead movement, pause command, bounded playhead, and exactly one ReplaceRange after trim release.

- [ ] **Step 3: Verify RED with the supported aggregate target**

Run: `./gradlew :video-clip-editor-compose:allTests`

Expected: FAIL because serialized lifecycle owner/audit and close-wins guarantees are absent, timeout audit is overwritten, or terminal fake rejects current reuse.

- [ ] **Step 4: Implement the minimum serialized owner**

Use one owner scope and one serialized intent loop. `requestClose()` latches terminal synchronously. Allocate generation only when a fresh binding is authorized. Keep release audit outside replaceable preview state. A matching waiter alone advances release. Commit the closed acknowledgement/timeout audit, close old session, dispose old port, recheck terminal, then optionally create the fresh port, start the fresh presenter/session at the next generation, and Bind that same generation. Surface receives `owner.activePort`; it never disposes. Remove screen use of the legacy direct-port helper and every detached/composition-owned cleanup launch.

Export gate is centralized at the presenter/owner boundary and mirrored by disabled UI semantics. Range commit remains the only ReplaceRange producer.

- [ ] **Step 5: Verify common/iOS GREEN, declaration-level API compatibility, and isolation**

Run:

~~~bash
./gradlew :video-clip-editor-compose:allTests \
  :video-clip-editor-compose:iosSimulatorArm64Test
BASELINE=92f78412796113f2abe27f55be0125e9373c9f1c
SCREEN=video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt
ANDROID_FACTORY=video-clip-editor-core/src/androidMain/kotlin/com/oneononearena/videoclip/AndroidVideoClipEditor.kt
API_TMP=$(mktemp -d)
git show "$BASELINE:$SCREEN" | perl -0ne 'print "$1\n" if /(\@Composable\nfun ClipEditorScreen\([\s\S]*?\n\))/m' > "$API_TMP/screen-baseline"
perl -0ne 'print "$1\n" if /(\@Composable\nfun ClipEditorScreen\([\s\S]*?\n\))/m' "$SCREEN" > "$API_TMP/screen-current"
git show "$BASELINE:$ANDROID_FACTORY" | perl -0ne 'print "$1\n" if /(public fun createAndroidVideoClipEditor\([\s\S]*?\): VideoClipEditor)(?:\s*=|\s*\{)/m' > "$API_TMP/android-baseline"
perl -0ne 'print "$1\n" if /(public fun createAndroidVideoClipEditor\([\s\S]*?\): VideoClipEditor)(?:\s*=|\s*\{)/m' "$ANDROID_FACTORY" > "$API_TMP/android-current"
test -s "$API_TMP/screen-baseline" && test -s "$API_TMP/screen-current"
test -s "$API_TMP/android-baseline" && test -s "$API_TMP/android-current"
diff -u "$API_TMP/screen-baseline" "$API_TMP/screen-current"
diff -u "$API_TMP/android-baseline" "$API_TMP/android-current"
git diff --exit-code "$BASELINE" -- \
  video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/VideoClipEditorContract.kt \
  video-clip-editor-core/src/commonMain/kotlin/com/oneononearena/videoclip/FeasibilityMarker.kt \
  video-clip-editor-core/src/iosMain/kotlin/com/oneononearena/videoclip/IosClipEditorFactory.kt
rm -r "$API_TMP"
rg -n 'android\.|androidx\.media3|ExoPlayer|MediaItem|AVFoundation|UIKit' \
  video-clip-editor-compose/src/commonMain
git diff --name-only HEAD^ -- video-clip-editor-core OneOnOneArena gradle/libs.versions.toml
~~~

Expected: `BUILD SUCCESSFUL`; both extracted public declarations match baseline byte-for-byte; frozen common/iOS contract sources have zero diff; platform and scope scans are supplemental and empty. All terminal/wrong-ack/timeout/diagnostic/race/live-gesture/export tests pass. Any empty extraction is a gate failure, preventing a false pass.

- [ ] **Step 6: Prove actual/device behavior on both required targets**

Run on API 23, then Samsung SM-S928B/API 36:

~~~bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest,com.oneononearena.videoclip.compose.ClipEditorLifecycleDeviceTest --rerun-tasks

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest,com.oneononearena.videoclip.compose.ClipEditorLifecycleDeviceTest --rerun-tasks
~~~

Expected: both focused classes green. Evidence identifies model/API and fixture hash; actual Release is terminal, old actual is disposed before distinct actual creation/Ready, and device UI proves live playhead/gesture plus export lockout.

- [ ] **Step 7: Author-distinct V3 review and replacement commit**

Reviewer receives blueprint §6A, this replacement task, rejected V3 report, exact diff, common/iOS logs, and both device logs. Reject transient audit, wrong-ack advancement, port reuse, surface disposal, detached cleanup, close-loses race, provisional export, mutable controls during export, or API/scope drift.

~~~bash
git add video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose \
  video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose \
  video-clip-editor-compose/src/androidMain/kotlin/com/oneononearena/videoclip/compose/AndroidPlatformPreview.kt \
  video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose
git commit -m "fix(compose): serialize preview lifecycle ownership"
~~~

#### Rollback Strategy

Revert only the replacement V3 commit; leave V1/V2 frozen and V3 blocked. Never roll back to released-port reuse, transient audit, surface-owned disposal, detached cleanup, or session-close-first ordering.

#### Integration Strategy

An author-distinct PASS completes only replacement V3. Then continue the existing order unchanged: Task 4 IG1 → Task 5 V4 → Task 6 V5. IG1 remains the real Media3 + production exporter flow and cannot be weakened by V3 fake/device proofs.

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

- Coverage: V1 selector/geometry, V2 Media3 source clipping/iOS seam, formally reconciled VUI-R7–R9 replacement V3 serialized terminal lifecycle/closed durable audit/export gate, IG1 real flow, V4 demo/device evidence, V5 independent traceability.
- Placeholder scan: no deferred markers. Each task contains scope, responsibility, interfaces, dependencies, acceptance, test strategy, rollback, integration, RED/GREEN, review, and commit.
- Type consistency: PreviewBinding/Command/Event/Port exactly match approved blueprint. Release audit accepts only the closed `Acknowledged` or `TimedOut(ReleaseTimeout)` outcome. No task alters public factory, ClipEditorScreen signature, ClipResult, or failure enums.
- Ordering: V1 → V2 → replacement V3 → IG1 → V4 → V5. V4 requires API-23 IG1 green; V5 requires both device targets and independent PASS.
- Route/API gate: replacement V3 floor is Sol/high; exact working-tree public declarations are extracted and compared with baseline `92f78412796113f2abe27f55be0125e9373c9f1c`. Filename/import scans are supplemental only.
