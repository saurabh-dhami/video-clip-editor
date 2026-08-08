# KMP Video Clip Editor — Visual Preview and Clip Range Selector Blueprint

**Status:** Draft — visual design approved; no production implementation may begin until the Blueprint First review and user blueprint-approval gates pass.

**Decision records:** initial blueprint `dec_20260806_183806_e9acff`; V3 lifecycle repair `dec_20260807_110710_be71b4`; rejected IG1 observability `dec_20260807_181452_13335a`; VUI-R10A repair `dec_20260807_183016_c836b9`; VUI-R11 diagnosis `dec_20260807_192317_532e5c`; rejected VUI-R11 docs repair `dec_20260807_193521_620060`; rejected review `fb_20260807_194642_fcc9d0`; second VUI-R11 docs repair `dec_20260807_231947_2f75f6`.

**VUI-R11 approval state:** `ARCHITECTURE_APPROVED`, not `PLAN_FROZEN`. The current Full manifest and normative plan require a new author-distinct review before any Kotlin/test edit. Accepted V1–V3/O1 history remains historical; the new policy governs only pending/future VUI-R11 work.

**Scope boundary:** Standalone `video-clip-editor` repository only. No file in OneOnOneArena is changed, imported, or used as a test fixture.

**Predecessor baseline:** The committed [V1 API baseline](2026-08-06-kmp-video-clip-editor-v1-api-baseline.md), the committed [Android release gate](../../verification/2026-08-06-android-hevc-release-gate.md), and the implementation accepted through `ddf47e0`. This blueprint fills the missing visual-editor outcome; it does not reopen the input, export, ownership, or cleanup contract. The separate untracked historical HEVC design document is not compatibility evidence for this delivery.

## 1. Observable outcome

### Actor and end state

After a host supplies an existing, accepted local MP4 through the already-published `ClipEditorScreen`, an Android user sees an editor page rather than the current minimal bar and detached thumbnail row.

The page contains:

1. A fitted preview of the selected source video.
2. A continuous horizontally pannable strip of extracted thumbnail frames.
3. Two visually distinct trim handles over that strip, marking source start and end.
4. A movable playhead over the same coordinate system.
5. Start/end time labels, Back, Play/Pause, and Done controls.

The user can pan the timeline, tap or drag the playhead to scrub, and drag either trim handle. The range always respects the frozen 500 ms minimum. Preview begins paused at the selected start. Play starts from the current playhead if it is inside the selected range, otherwise from selected start. It never plays outside the selected range. On reaching the selected end it immediately seeks to selected start and continues playing: **continuous selected-range looping is approved behaviour**.

Done invokes the existing `ClipEditorSession.createClip(range)` with the displayed range, yielding the already-defined temporary absolute-path MP4 lease. Back invokes existing cancellation/close behaviour. Neither action changes host navigation ownership.

### Explicit exclusions

* No file picker, host navigation, upload, permanent storage, or playback in the core module.
* No zoom gesture, waveform, audio editing, crop, rotation, filters, captions, effects, speed, merge, split, or multi-track timeline.
* No new public core types, result codes, factory, or `ClipEditorScreen` parameters.
* No iOS media playback implementation in this delivery. iOS source sets must compile against the same internal UI seam.
* No promise that every device decodes every accepted AVC/HEVC profile. Existing capability preflight and typed results remain authoritative.

### Objective acceptance evidence

| Criterion | Required evidence |
| --- | --- |
| Preview page | Samsung screen recording/screenshot after MP4 selection shows source preview, range selector, controls, and time labels. |
| Shared selector | Common Compose test verifies thumbnail/range/playhead layout state and gesture mapping without Android or Media3 types. |
| Panning and scrub | Android device test drives horizontal panning and playhead seek; preview position changes to the requested bounded time. |
| Range behaviour | Unit/Compose tests prove handles cannot cross, honour 500 ms, and time/pixel mapping is stable under scroll. |
| Looping | Android device test starts near trim end and proves preview returns to trim start while remaining playing. |
| Export connection | Integration test drags range, chooses Done, and inspects `ClipResult.Success.sourceRange` and produced temporary MP4. |
| Lifecycle/failure | Device tests prove preview release on close/disposal, no platform exception is exposed, and retry/cancel leaves no export lease. |
| Compatibility | Targeted UI/device suite passes on API 23 emulator and connected Samsung SM-S928B/API 36. |

## 2. Approved interaction design

```text
┌──────────────────────────────────────┐
│             source preview           │
│              (fitted video)          │
├──────────────────────────────────────┤
│  00:02                         00:15 │
│  ┌────────────────────────────────┐  │
│  │ | thumb thumb │ thumb thumb |  │  │
│  │ ^             ^              ^ │  │
│  │ start      playhead          end│  │
│  └────────────────────────────────┘  │
│       horizontal pan for all source   │
├──────────────────────────────────────┤
│ Back             Play/Pause      Done │
└──────────────────────────────────────┘
```

The selection interior has a high-contrast outline. Before-start and after-end regions are visibly dimmed but remain tappable for seeking. The playhead is visually different from the trim handles and always has the highest z-order. Each trim handle has a 48dp minimum touch target even when its visible grip is narrower. The preview preserves source aspect ratio inside a black surface; portrait video is not cropped to landscape.

### Gesture rules

| Gesture | Result |
| --- | --- |
| Drag start handle | Clamp start to `[0, end - 500ms]`; seek preview to new start if current playhead is outside range. |
| Drag end handle | Clamp end to `[start + 500ms, source duration]`; if preview/playhead is beyond new end, seek it to start before resuming loop. |
| Tap thumbnail track | Pause; seek playhead and preview to tapped time, clamped to current range. |
| Drag playhead | Pause on gesture start; seek preview as it moves; remain paused when released. |
| Drag bare track | Horizontal pan only. Panning does not change range or preview time. |
| Play | Seek to start when current position is outside range, then play. |
| Playback reaches end | The source-level clipped media period repeats; no position-poll callback decides the boundary. |
| Back/Cancel | Pause, release preview, close session, call existing `onCancel`. |
| Done | Pause and use current range for existing export flow. |

Timeline content has 24 bounded frames already permitted by `VideoClipEditorConfiguration`. Each is presented at a fixed display width; the content is therefore wider than a phone viewport and always supports natural horizontal panning. Frame timestamps remain source-time based. This delivery deliberately does not add a second unbounded or windowed frame-extraction API; future density/zoom can change the internal frame source without changing the public editor contract.

## 3. Current architecture evidence

| Concern | Observed current state | Required change |
| --- | --- | --- |
| Frozen common contract | `video-clip-editor-core/src/commonMain/.../VideoClipEditorContract.kt` exposes `VideoClipEditor`, `ClipEditorSession`, `ClipRange`, `ThumbnailFrame`, result types, and `FrameStripRequest`; no playback type exists. | Preserve every public signature and code. Introduce only internal Compose preview state/ports. |
| Existing selector | `video-clip-editor-compose/src/commonMain/.../ClipEditorScreen.kt` uses a fixed 48dp dark `Box`, two handles, then a separate `Row` of 48×32 images. | Replace with one common `ClipRangeSelector` composition where frames, dimming, handles, and playhead share scroll coordinates. |
| Existing state owner | `ClipEditorPresenter` opens the session, collects up to 24 frames, owns `ClipRange`, and invokes `createClip(ready.range)`. | Retain ownership of metadata/range/export/close. Add internal preview intent and playhead state; do not move export into UI/player. |
| Existing Android engine | `Media3ClipMediaEngine` probes, extracts frames, and uses Transformer for output. `video-clip-editor-core/build.gradle.kts` already pins Media3 Transformer 1.10.1. | Do not couple preview to `ClipMediaEngine`; add an optional Compose-module Android playback implementation. |
| Compose module | `video-clip-editor-compose` is optional and depends only on common Compose/core today. | Add Android-only Media3 preview dependencies. Core remains free of playback UI dependencies. |
| Demo | `demo-android/.../DemoActivity.kt` imports an MP4 and displays `ClipEditorScreen`, but declares playback intentionally absent. | Use the upgraded library screen; demo remains an integration/manual-verification host only. |
| iOS | Core iOS factory returns typed engine unavailable. Compose has iOS targets but no native preview abstraction. | Compile an iOS internal unavailable preview actual. AVFoundation remains a later implementation of that internal seam. |

Graph discovery returned no indexed communities for `video-clip-editor`, and Agent Brain reports no SAN directory. Source paths above and targeted raw reads are the architecture evidence for this blueprint.

## 4. Public compatibility and module boundary

The public ABI remains exactly the committed `2026-08-06-kmp-video-clip-editor-v1-api-baseline.md`. In particular, these do **not** change:

```kotlin
@Composable
fun ClipEditorScreen(
    source: VideoSourcePath,
    editor: VideoClipEditor,
    onResult: (ClipResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`ClipRangeSelector` is an **internal shared Compose component**, not a new public API. It receives only common value models, decoded `ImageBitmap` values, state, and callbacks. `Context`, `Uri`, `ExoPlayer`, `Player`, `Surface`, `MediaItem`, AVFoundation, UIKit, and Swift values are forbidden from its signature.

An internal common preview seam will be introduced in the Compose module. Its declarations are internal, but frozen for V1 before V1 implementation begins:

```kotlin
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
```

The presenter is the only producer of command generations/revisions and accepts an event only when both values match the active binding. `Bind` and `ReplaceRange` carry a complete snapshot rather than a delta. Android must emit `Ready` before a command for that revision can make Play available. `Release` is terminal for its generation; release completion is acknowledged by `Released`, and the screen must not close the `ClipEditorSession` until that acknowledgement or a bounded, recorded failure fallback. The controller never invokes export, creates/clears temporary files, navigates, or exposes a native type.

| Port state | Accepted command | Required next event/state |
| --- | --- | --- |
| Unbound | `Bind(binding)` | `Binding(generation, revision)` then exactly one `Ready` or `RecoverableFailure` for that revision |
| Binding | `ReplaceRange(binding)` | Keep only the highest revision as `pendingBinding`; pause and never make an obsolete revision playable |
| Binding | `Ready` for obsolete revision | Detach obsolete clipped item and bind `pendingBinding`; do not emit Ready to the presenter for the obsolete revision |
| Binding | `Ready` for latest revision | Transition ready-paused or ready-playing according to binding's `playWhenReady` |
| Binding | `RecoverableFailure` for latest revision | Transition recoverable-failure; disable Play/Done |
| Ready-paused | `Seek`, `SetPlayWhenReady(false)` | Matching `Position`; remain ready-paused |
| Ready-paused | `SetPlayWhenReady(true)` | Matching `Position(isPlaying=true)`; transition ready-playing |
| Ready-playing | `Seek`, `SetPlayWhenReady(false)` | Matching `Position`; remain paused or resume only after explicit true command |
| Ready-* | `ReplaceRange(binding)` | Pause/detach old revision; then exactly one `Ready` or `RecoverableFailure` for new revision |
| Recoverable-failure | `Retry(binding)` | Binding of the same generation at the next revision; exactly one `Ready` or `RecoverableFailure` |
| Any non-terminal | `Release(generation)` | No later event for that generation except one `Released`; transition terminal |

During a trim-handle gesture the common selector updates only provisional visual range state and pauses playback. On gesture end it emits one complete `ReplaceRange` for the latest range. Therefore a normal drag produces one source reconfiguration. Rapid completed gestures still obey the `pendingBinding` latest-wins rule above. `Retry` is the only recovery command and always carries a complete, clamped binding with the next revision. The presenter accepts no `Position`, `Ready`, or failure event whose generation/revision is not currently active.

`PreviewPort` itself is an internal common interface. The platform-specific port instance and preview surface are created through an internal `expect/actual` Compose helper. Common tests use a fake `PreviewPort`; Android device tests use the Media3 actual. The expect/actual helper and all port declarations remain internal.

`internal expect/actual` is permitted for this private platform surface. It is explicitly **not** a public common factory and cannot force future host call-site or core-contract changes. Android creates its player only in the Compose module; a Views-only host still depends only on core.

## 5. Playback architecture evaluation

### Evidence

The repository pins `androidx.media3` 1.10.1 and already carries `media3-exoplayer` in the version catalog. Official Android guidance requires ExoPlayer access on one application thread, usually main, and requires release when done. The current Compose guidance provides lifecycle-aware `PlayerSurface`/`ContentFrame` for a custom Compose UI and warns that `PlayerView` wrapped through `AndroidView` can have surface-size/crop/leak problems on API 34.

Sources: [Media3 Compose UI](https://developer.android.com/media/media3/ui/compose), [Media3 Compose surfaces](https://developer.android.com/media/media3/ui/surface), [ExoPlayer lifecycle and threading](https://developer.android.com/media/media3/exoplayer/hello-world), [Android playback lifecycle](https://developer.android.com/media/implement/playback-app).

### Comparison

| Approach | API 23+ | Custom selector fidelity | Lifecycle/threading | Size/build | Future iOS shape | Decision |
| --- | --- | --- | --- | --- | --- | --- |
| Media3 `media3-ui-compose` `PlayerSurface` + ExoPlayer | Supported by the existing Media3 line; device codec rules still apply | Full custom Compose controls/timeline | Main-thread player; lifecycle-aware surface; explicit release | Adds Android-only Media3 UI artifact | Same internal seam, AVFoundation actual later | **Choose** |
| `PlayerView` in `AndroidView` | Supported | Surface plus custom external controls | Extra interop lifecycle and documented Compose surface risk | Adds `media3-ui` | Same seam, but Android implementation more fragile | Reject |
| Android `VideoView`/`MediaPlayer` | API compatible but weak device/error surface | Insufficient control over range loop and state reporting | Manual lifecycle/error mapping | Smaller | No reusable design advantage | Reject |
| Third-party trimmer/player | Varies | May look closer initially | Vendor/lifecycle/API risk | Licensing/transitive dependency burden | Android-only; duplicates timeline | Reject |

### Chosen Android design

The Android actual owns one `ExoPlayer` on the Android main thread. It sets the host-owned source file in a range-clipped `MediaItem`, supplies the video surface using `media3-ui-compose` foundational surface APIs, and releases the player when the composable leaves composition. No Android player object reaches common state or the public API.

Range enforcement is source-level, not a UI poll: for every `PreviewBinding`, Android creates an ExoPlayer media item with `MediaItem.ClippingConfiguration` from `range.start` to `range.endExclusive`, keeps clipping relative to the local source's zero position, and does not allow unseekable-media clipping. The player receives only that clipped media period and uses `REPEAT_MODE_ONE`; it therefore cannot advance into source samples after the selected end before a UI callback occurs. Player positions are clip-relative and the adapter reports source time as `range.start + clipRelativePosition`.

On `ReplaceRange`, Android first pauses, replaces the entire clipped media item using the new complete binding, seeks the clip-relative position corresponding to the clamped source playhead, waits for `Ready`, then restores the requested play intent. The old source/revision is detached before the new revision is accepted. This is the only route that mutates playback range. Thumbnail extraction remains based on the complete source and is intentionally independent of preview clipping.

Preview errors are sanitized into internal retry state with a diagnostic safe for UI; platform exceptions are neither rethrown nor added to the frozen public result enum. Core probe remains the first codec/capability gate. A preview failure disables Play/Done only until retry or Back, releases the player, and does not create an export lease.

## 6. State ownership, concurrency, and lifecycle

```text
ClipEditorPresenter (common)
  owns: opened session, metadata, frames, selected range, export/close
  emits: Editor state + preview commands
        |                       ^
        v                       |
ClipRangeSelector (common) -- user range/seek intents
        |                       ^
        v                       |
PlatformPreviewController (internal common seam)
        |                       ^
        v                       |
Android Media3 actual (main thread ExoPlayer + PlayerSurface)
```

Rules:

1. `ClipEditorPresenter` remains the sole owner of `ClipEditorSession` and calls `createClip` only after Done.
2. The selector never controls a native player directly. It emits intent tagged with the current source/session generation.
3. Android player callbacks are marshalled to the common controller state; stale callbacks from an old source generation are ignored.
4. Player commands and release occur on the Android main thread. Player position is sampled at a bounded UI cadence, not every video frame.
5. Session disposal first pauses/releases preview, then closes the session using the existing independent cleanup scope. Export cancellation/temporary ownership rules are unchanged.
6. Range and playhead updates are clamped centrally. The player is a mirror of canonical common range/playhead state, never an alternate source of truth.
7. Recomposition must not recreate a player for an unchanged source/session generation. Source change releases the prior player before preparing the next one.

## 6A. V3 Lifecycle Re-architecture Addendum (normative)

This addendum and the replacement V3 chunk supersede the original V3 chunk, its implementation at `1774142`, and rejected fix rounds `0501e57` and `24ec733..fdfe8e6`. Those revisions are historical rejection evidence only and are not implementation or integration inputs. V1 selector semantics, V2 source-clipped Media3 behaviour, the approved visual design, the public ABI, core contracts, dependencies, iOS scope, and the IG1 → V4 → V5 sequence remain frozen. One replacement V3 task must satisfy this complete chunk before IG1 starts; no instruction in an earlier V3 chunk or report can weaken it.

### Scope

Re-architect only the internal Compose preview lifecycle and its focused fake/Android-device proofs. `PreviewPort`, `PreviewPortFactory`, lifecycle intents/audit, and platform helpers remain `internal`. No public API, `video-clip-editor-core`, dependency, OneOnOneArena, or exposed iOS type changes are permitted.

### Responsibilities

- One serialized `ClipEditorLifecycleOwner` accepts source-replacement and terminal-close intents. It owns the lifecycle scope/actor, event collector, current port, monotonic generation epoch, release waiter, durable release audit, and presenter session start/close sequencing. A composable may enqueue intents only; effect cancellation or disposal cannot launch cleanup, start a session, dispose a port, or cancel the owner before cleanup finishes.
- Terminal close latches synchronously and wins over queued, suspended, or late replacement. After the latch, every replacement is a no-op. A replacement already awaiting release may finish teardown, but must not start a new session, create a new port, or bind after terminal close.
- Android `PreviewCommand.Release` is terminal. For replacement, the owner must execute exactly: old-port `Release` → matching `Released` or bounded timeout → matching acknowledgement/timeout audit committed → old `ClipEditorSession.close()` → old native port disposal → terminal-latch recheck → fresh factory creation → fresh presenter/session start with the next generation → fresh `Bind` for that generation. No released port may receive `Bind`, `Retry`, range, seek, or play commands.
- `ClipEditorPreviewCoordinator` may remain only as a scope-free preview reducer/command policy. It cannot own generation allocation, coroutine jobs, port creation/disposal, session sequencing, or lifecycle intent serialization.
- `PlatformPreviewSurface(port)` renders the supplied active port. It neither creates nor disposes a native port and has no `DisposableEffect` teardown authority.
- Release evidence is stored separately from binding state as a durable internal audit. The latest record survives a fresh binding and contains only generation, revision, closed outcome (`Acknowledged` or `TimedOut(ReleaseTimeout)`), and reason (`SourceReplacement` or `TerminalClose`). No free-form diagnostic string or exception is stored. A lower-layer path, URI, stack fragment, exception type/message, media content, or host callback value cannot be represented in the audit.
- Canonical range/playhead rules remain unchanged. A provisional range can render and pause preview but cannot be exported. Once export starts, Back, Done, Play/Pause, retry, seek, playhead, and trim mutations are disabled/no-op; lifecycle disposal/terminal close still runs.

### Interfaces

The frozen `PreviewBinding`, `PreviewCommand`, `PreviewEvent`, and `PreviewPort` declarations remain unchanged. The internal ownership seam is:

```kotlin
internal interface PreviewPortFactory {
    fun create(): PreviewPort
    fun dispose(port: PreviewPort)
}

internal enum class PreviewReleaseReason { SourceReplacement, TerminalClose }
internal enum class PreviewReleaseDiagnostic { ReleaseTimeout }

internal sealed interface PreviewReleaseOutcome {
    data object Acknowledged : PreviewReleaseOutcome
    data class TimedOut(
        val diagnostic: PreviewReleaseDiagnostic,
    ) : PreviewReleaseOutcome
}

internal data class PreviewReleaseAudit(
    val generation: PreviewGeneration,
    val revision: PreviewRevision,
    val outcome: PreviewReleaseOutcome,
    val reason: PreviewReleaseReason,
)
```

`PreviewReleaseDiagnostic` is the complete V1 allowlist. Timeout infrastructure may observe arbitrary raw text or `Throwable` values, but the owner discards that payload and records only `PreviewReleaseDiagnostic.ReleaseTimeout`; acknowledgement records `PreviewReleaseOutcome.Acknowledged`. Adding another diagnostic requires a reviewed blueprint/API-internal-contract revision and its own sanitization tests. No `String?`, path, URI, stack, exception class/message, or platform error object enters `PreviewReleaseAudit`.

`rememberPlatformPreviewPortFactory()` supplies the lifecycle-owned factory. `PlatformPreviewSurface(port)` receives only the current active port. The legacy direct-port helper must not be used by the screen. None of these declarations becomes public or enters core/iOS host contracts.

### Dependencies

V1 and V2 accepted commits plus the existing internal Media3 actual. No new library, Gradle coordinate, core seam, public parameter/result, iOS exposed type, host navigation contract, or OneOnOneArena fixture. The owner runs on the existing UI-safe dispatcher; timeout is deterministic under coroutine test time.

Backward necessity: terminal Android release requires a fresh port; fresh creation requires old native disposal; safe disposal requires release evidence and old-session close ordering; replacement/close safety requires one authority and a close-wins latch. Forward feasibility: intent → serialized owner → matching fence → closed audit → old-session close → native disposal → terminal recheck → optional fresh factory create → fresh session start at the next generation → Bind for that generation. A wrong acknowledgement, timeout, or close race has an explicit bounded route and verification point.

### Acceptance Criteria

1. A terminal fake rejects/records every command after `Release`; no production path binds or reuses it.
2. While release is pending, wrong-generation `Released` causes no audit completion, session close, disposal, factory creation, fresh session start, or bind. Matching acknowledgement proves the entire exact sequence: `Release(g1,r1)` → `Released(g1)` → `Audit(g1,r1,Acknowledged,SourceReplacement)` → old session close → old-port disposal → fresh factory create → fresh presenter/session start `(g2)` → `Bind(g2,r1)`.
3. Timeout proves the entire exact sequence: `Release(g1,r1)` → `Audit(g1,r1,TimedOut(ReleaseTimeout),SourceReplacement)` → old session close → old-port disposal → fresh factory create → fresh presenter/session start `(g2)` → `Bind(g2,r1)`. The audit is committed before close and retains the old generation/revision/reason after the fresh binding.
4. In a controlled replace-versus-close race, close wins: exact-once old-session close/disposal, zero fresh factory creation, zero fresh presenter/session start, zero fresh bind, and all pending/late replacements no-op. No detached child or composition-owned cleanup job survives.
5. Live matching `Position` updates the playhead; a trim/playhead gesture pauses and remains range-bounded; one completed range gesture emits one `ReplaceRange`. Existing 500 ms and source-time semantics remain unchanged.
6. Done is disabled/no-op for a provisional range. After export begins, all control mutations are disabled/no-op and the committed canonical range alone reaches `createClip`.
7. Android actual proof shows `Release` is terminal, native disposal is owner-ordered, and a distinct newly created actual can bind/emit `Ready`. Focused UI device proof covers live playhead/gesture and export lockout.
8. A declaration-aware V3 gate extracts the actual public `ClipEditorScreen` declaration from the working tree and compares it byte-for-byte with the declaration at baseline commit `92f78412796113f2abe27f55be0125e9373c9f1c`; the frozen public core/Android/iOS contract sources are also compared with that baseline. Filename and forbidden-import scans are supplemental, not API compatibility evidence. Core, dependencies, OneOnOneArena, and exposed iOS types remain unchanged; common and iOS tests compile.

### Test Strategy

- Common coroutine tests use a terminal fake, controllable release waiter, virtual timeout, call-order recorder, and close/replacement barrier. Acknowledgement and timeout cases each assert their complete audit/fence → old close → old disposal → fresh create → fresh session start `(g2)` → `Bind(g2)` sequence. The close-race case asserts zero fresh session starts as well as zero fresh create/bind. Tests also prove monotonic generations, exact-once teardown, and zero commands after terminal release.
- Diagnostic tests inject arbitrary absolute paths, `file://` and `content://` URIs, stack-shaped strings, exception class/messages, and `Throwable` values at the lower-layer timeout seam. Every case yields only `TimedOut(ReleaseTimeout)`; neither the audit fields nor `toString()` contains an injected value.
- Common presenter/Compose tests emit live matching positions, drive actual tagged handle/playhead gestures, assert one committed range replacement, assert provisional Done lockout, then hold export pending and assert every control callback is inert.
- Android instrumentation uses the repository fixture and actual `AndroidMedia3PreviewPort`: release old actual, attempt forbidden post-release commands, dispose it through the factory, create a distinct actual, bind, and await matching `Ready`. A focused screen test proves live playhead/gesture and export lockout on a test device. Run the focused classes on API 23 and Samsung SM-S928B/API 36; fake-only or compile-only evidence cannot pass.
- Regression gates: `:video-clip-editor-compose:allTests`, `:video-clip-editor-compose:iosSimulatorArm64Test`, public/common platform scans, and focused Android device tests.

### Rollback Strategy

Revert only the replacement V3 commit. Leave V1/V2 frozen and V3 blocked. Do not restore released-port reuse, surface-owned disposal, session-close-first cleanup, transient timeout evidence, or detached cleanup launches. IG1 remains blocked until a corrected replacement V3 passes again.

### Integration Strategy

This is one replacement V3 chunk, not V3a/V3b or an IG1 substitute. Author-distinct principal review must verify the exact lifecycle order, durable bounded audit, terminal fake/actual evidence, close-wins race, export gate, public/API isolation, and all focused tests before acceptance. After V3 PASS, continue unchanged: IG1 real editor flow → V4 demo/device evidence → V5 independent completion audit.

## 6B. IG1 Test-Observability Reconciliation (normative)

V3 passed at `661d16c`. IG1 first failed with `ClipEditorScreenIntegrationTest.kt:55:37 No parameter with name 'previewPortFactory' found.` VUI-R10 proposed per-composition factory selection. Author-distinct review rejected that first repair: lifecycle would own `RecordingPreviewPort`, but the Android surface directly cast to `AndroidMedia3PreviewPort`, allowing a blank/headless preview despite compile success. VUI-R10A added the transparent one-hop surface bridge, stable wrapper identity, runtime preflight, and exhaustive no-fake matrix. That bounded observability repair is implemented and accepted at `eb0690162405c25f8962eb17364116ce9afbab1c` with author-distinct 98/100 PASS and required API23/Samsung/common/iOS/declaration evidence. VUI-R10/R10A are resolved; their accepted product and test seams remain frozen.

Normative detail: `docs/superpowers/specs/2026-08-07-visual-clip-editor-ig1-test-observability-reconciliation.md`.

Narrow seam:

~~~kotlin
internal val LocalPreviewPortFactoryOverride =
    staticCompositionLocalOf<PreviewPortFactory?> { null }

internal interface PreviewPortSurfaceDelegate {
    val surfacePort: PreviewPort?
}

// Inside the unchanged public ClipEditorScreen body:
val platformPreviewPortFactory = rememberPlatformPreviewPortFactory()
val previewPortFactory = LocalPreviewPortFactoryOverride.current ?: platformPreviewPortFactory
val lifecycle = remember(previewPortFactory) { ClipEditorLifecycleOwner(previewPortFactory) }
~~~

Android bridge accepts direct actual or exactly one delegate hop:

~~~kotlin
internal fun resolveAndroidPreviewSurfacePort(port: PreviewPort): AndroidMedia3PreviewPort? {
    if (port !is PreviewPortSurfaceDelegate) return port as? AndroidMedia3PreviewPort
    val candidate = port.surfacePort ?: return null
    if (candidate === port || candidate is PreviewPortSurfaceDelegate) return null
    return candidate as? AndroidMedia3PreviewPort
}
~~~

Scope/responsibility:

- Local is internal, nullable, per-composition, stable for one screen composition, and non-global. It selects a factory only; it owns no port/session/resource.
- Default production path still calls and selects exact `rememberPlatformPreviewPortFactory()`.
- Recording wrapper remains lifecycle `activePort`; `surfacePort` exposes exact real factory-created port. Proxy events use `delegate.events.onEach(record)`, so matching Released is sequenced before lifecycle collector/ack/session close.
- Android `PlatformPreviewSurface` owns bounded resolution and passes exact actual player to `ContentFrame`. Direct actual stays unchanged. Null/self/nested/cyclic/unrelated delegate returns unavailable; recursion forbidden.
- Recording factory unwraps exact known actual for one real factory dispose; unrelated proxy hard-fails.
- Production editor, recording editor, real factory, and recording factory are remembered with stable keys. Ordinary recomposition must not change `editor`/`lifecycle` effect keys or trigger replacement.
- Production open/session/metadata/frame extraction, tagged pan/scrub/commit, actual config/loop, export, release, close, and twice cleanup have exact owner/hook/no-fake evidence in normative matrix.
- Public declarations, core, host, V3 lifecycle order, iOS actual, dependencies, and Media3 1.10.1 remain frozen. Common interface contains no platform type.

Two preflights precede functional IG1. Compile verifies visibility/type topology only:

~~~bash
./gradlew :video-clip-editor-compose:compileAndroidDeviceTest --rerun-tasks
~~~

API-23 runtime transparency then proves wrapper remains active lifecycle port while exact actual reaches `ContentFrame`:

~~~bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.PreviewPortSurfaceTransparencyDeviceTest --rerun-tasks
~~~

PASS required active wrapper identity, exact actual resolver identity, a `ContentFrame`-path test tag, real Ready, synchronous Released-before-close sequence, exact disposal, and null/cycle/unrelated rejection. Common/iOS, declaration baseline, targeted V3 lifecycle on API23/Samsung, and author-distinct V3 delta review passed before functional IG1 began. Evidence is accepted at `eb06901`; do not rerun or redesign O1 for VUI-R11.

## 6C. VUI-R11 Android Repeat-Intent Reconciliation (normative)

Strict API23 IG1 proved correct source/range, `REPEAT_MODE_ONE`, high in-range playing position, then return to low/start buffering with no later playing position and no failure. Direct same-port source-end and interior clips both reproduced `2000 -> 0`, `BUFFERING true/false`, then `READY false/false`. Cause is confirmed: `AndroidMedia3PreviewPort.onPlaybackStateChanged(READY)` restores `player.playWhenReady` from `appliedBinding`, while an accepted `SetPlayWhenReady` mutates only Player state and leaves `appliedBinding.playWhenReady=false`. Changing that single stored field false-to-true produced `READY true/true` 41 ms later and `Position(7.061s,true)`.

Normative detail: `docs/superpowers/specs/2026-08-07-visual-clip-editor-vui-r11-repeat-intent-reconciliation.md`.

Machine-readable Full evidence: `docs/superpowers/evidence/2026-08-08-vui-r11-repeat-intent.manifest.json`, baseline `eb0690162405c25f8962eb17364116ce9afbab1c`, digest `815ddf933f48b1f59a8097aab19296176fa553bb2ecfb37153872e7f96b8c73b`. Full hard triggers are device, lifecycle/concurrency, multiple state owners on one causal path, integration, and no existing deterministic full oracle.

Bounded repair:

- Canonical repair scope remains `AndroidMedia3PreviewPort.kt` and focused `AndroidMedia3PreviewPortDeviceTest.kt`. One test-only disposition edit is additionally authorized after evidence retention: delete only temporary `diagnostic_sourceEndAndInteriorClipsExposeFirstRepeatPlayerState` from existing untracked `ClipEditorScreenIntegrationTest.kt`. Its null-resume assertions prove the defect and are not enduring acceptance. The strict method `editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose` remains semantically unchanged.
- For a matching ready generation/revision, accepted `SetPlayWhenReady(value)` first copies only `playWhenReady=value` into `appliedBinding`, then mutates Player, emits Position from the updated binding, and updates the ticker.
- It never changes generation, revision, source, metadata, range, source position, or `pendingBinding`. Stale, wrong, preparing, released, and terminal commands remain zero-mutation.
- Complete `Bind`, `ReplaceRange`, and `Retry` snapshots remain authoritative; their explicit playback intent wins.
- Focused RED proofs cover real first repeat while playing, stale/preparing rejection, replace/retry precedence, and accepted pause through later Ready.
- After `PLAN_FROZEN`, the only pre-code/edit sequence is: (1) archive original strict RED, source-end/interior traces, stored-intent discriminator, exact diagnostic source/diff, and hashes; (2) add all four focused tests and run their selected real-port API23 RED; (3) delete only the archived defect diagnostic and require the strict-method extractor digest `a7d9fc1fac78e9795211d40cb6602ebe9e36e7607def5b96f460572b201b1c4a`; (4) apply the minimal adapter patch and run the focused direct real-port repeat method GREEN on API23 as the early vertical proof. Then one canonical final sequence: full direct adapter class API23 then Samsung → core regressions plus V3 lifecycle smoke on both devices plus common/iOS/declaration guards → author-distinct code review >=95 → exact strict functional IG1 method API23 then Samsung **last**. Tests cannot run before they exist. The whole IG1 class is not the strict VUI-R11 command.
- After both strict passes, only evidence/docs may change. Any production/test source edit invalidates strict evidence and requires the whole sequence again. Samsung unavailable blocks completion. This is still VUI-R11 reconciliation count `1`, not a new/reset trigger.

No public/core/common contract, iOS, dependency, Media3 version, V3 ownership, O1 seam, host/demo, or OneOnOneArena change. No other IG1 test byte may change. Config-only, paused-only, source-end-only, player-only, fake, command-only, relaxed, whole-class substitution, and single-device evidence cannot pass.

## 7. Backward necessary-condition pass

| Outcome criterion | Direct predecessor | Why necessary | Evidence/assumption | Owner | Stop condition |
| --- | --- | --- | --- | --- | --- |
| Preview shows selected source | Lifecycle-safe Android player surface bound to local path | Preview cannot render from JPEG frames | Existing Android source path, Media3 dependency; official surface guidance | Android preview module | Player cannot initialize/type error is sanitized and retryable |
| Timeline resembles approved design | One coordinate system for frames, selection, playhead, scroll | Separate bar/row cannot align visuals/gestures | Current separate controls observed | Shared Compose module | Selector test proves overlay mapping |
| User can pan/scrub | Stable time↔content coordinate transform | Player seek and visible playhead must agree under horizontal scroll | `VideoMetadata.duration` and 24 ordered frames exist | Shared Compose module | Invalid duration or missing frames stays loading/failure |
| Handles select valid clip | Central range clamp uses frozen minimum | Export must receive valid range | Existing `CommonValidation.clipRange` and presenter range logic | Shared Compose module | Bounds test fails |
| Loop only selection and remain playing | Source-level clipping plus one-period repeat **and** persisted latest accepted `appliedBinding.playWhenReady` | Clipping/repeat bounds media but cannot preserve Play when repeat READY replays stored intent; stale stored false pauses at start | Strict IG1/root-cause traces: high playing → low BUFFERING → READY false/false; single-variable stored-true discriminator → READY true/true and low/start `Position(true)` | Android preview adapter | Direct real-wrap or exact strict method fails on either API23/Samsung |
| Done produces existing output | Presenter keeps existing `createClip(ready.range)` | Player must not bypass exporter/lease | Current code uses exactly this call | Presenter integration | Existing integration regression fails |
| Optional Compose and future iOS | Internal expect/actual seam only | Public frozen API must not change | Existing Compose iOS targets and iOS core contract | Compose platform layer | iOS compile fails or public API diff changes |
| API 23+ and no leak | Correct API lifecycle/release path | Video decoder/surface is scarce | Official lifecycle guidance; API 23 test required | Android preview module | API 23 test or release assertion fails |

## 8. Prerequisite and blocker register

| ID | Required condition | Why required | Classification | Owner | Evidence | Affected capability | Status | Resolution |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| VUI-01 | Approved visual interaction and loop policy | Determines range/playhead semantics | user-owned | User | Approval in this thread | All UI modules | Resolved | Continuous selected-range loop |
| VUI-02 | Frozen public API remains unchanged | Future iOS/host compatibility | contract | Core/Compose | Existing API baseline | All modules | Resolved | Internal-only seam |
| VUI-03 | Main-thread, release-safe Android player | Decoder/surface safety | technical | Android preview | Official Media3 docs | Preview | Open | Instrumented lifecycle tests |
| VUI-04 | Timeline coordinate model and gestures are testable common code | MPP fidelity | technical | Compose selector | Existing duration/frames | Selector | Open | Pure/common Compose tests |
| VUI-05 | Accepted fixture decodes on API 23 and Samsung | Physical preview proof | integration | QA | Existing AVC/HEVC fixtures and devices | Preview | Open | Two-device evidence |
| VUI-06 | `media3-ui-compose` license/version compatibility | Compose preview dependency | external/licensing | Android preview | AndroidX artifact at pinned 1.10.1 | Preview | Open | Dependency/NOTICE review |
| VUI-07 | No preview failure leaks a native exception or creates temp output | Public and storage safety | security | Integration | Existing typed core boundary | Failure behavior | Open | Device failure/retry test |

No open prerequisite is a reason to start production code. VUI-03 through VUI-07 are completion gates for their chunks, not user-owned ambiguity.

## 9. Forward feasibility pass

| State owner | Input | Transition | Output | Failure/recovery | Verification | Unresolved dependency |
| --- | --- | --- | --- | --- | --- |
| Presenter | `source`, `editor` | Existing open/probe/frame collection completes | `Ready(metadata, frames, full range)` | Existing typed terminal/retry state | Existing common/device tests | None |
| Android preview actual | Complete `PreviewBinding` | Create main-thread clipped Media3 item; attach Compose surface; repeat its one clipped period | `Ready` then clip-relative position mapped to source time | Release; sanitized revision-scoped retry state | Android instrumentation | VUI-03 |
| Android preview accepted-intent owner | Matching ready `SetPlayWhenReady(value)` | Copy only `playWhenReady=value` into `appliedBinding` before mirroring Player; leave generation/revision/source/range/position and `pendingBinding` unchanged | Persisted same-binding accepted intent | Stale/wrong/preparing/released command is ignored with zero stored/Player mutation | Focused intent/isolation device tests | VUI-R11 direct gate |
| Media3 repeat transition | High in-range playback reaches clipped-period AUTO_TRANSITION/BUFFERING then READY | READY reapplies persisted `appliedBinding.playWhenReady=true` | Low/start source `Position(isPlaying=true)` within same range | Stale stored false route yields READY false/false and pauses at start; repair via accepted-intent synchronization, never UI polling/manual seek | Root-cause traces, real-wrap adapter test, exact strict IG1 method | Required Samsung evidence |
| Common selector | Frames, canonical range/playhead/scroll | Draw track/overlays; map gestures to complete snapshot commands | `PreviewCommand` only | Ignore stale/invalid input; clamp centrally | Common Compose tests | VUI-04 |
| Preview controller | `PreviewCommand` generation/revision | Acknowledge only active binding; source-level clipped player enforces bounds | `PreviewEvent` with matching generation/revision | Pause/release/retry; stale event ignored | Unit + Android device clip/repeat test | VUI-03 |
| Presenter | Done | Existing exporter receives selected range | Frozen `ClipResult` | Existing typed failure path | End-to-end output inspection | None |
| Lifecycle coordinator | Source change/Back/disposal | Send terminal Release; await matching `Released` or bounded recorded fallback; then close session | No active decoder/session | Idempotent terminal fence | Leak/release device test | VUI-03 |
| iOS actual | Same internal call site | Compile unavailable/no-op preview implementation | No Android type leak | Internal unavailable state | iOS compile test | VUI-06 |

The forward pass has no contradictory state owner: range/export remain presenter-owned; native playback remains Android-owned; visual selection stays common.

## 10. Reconciliation history and module-freeze decision

| Trigger ID | Trigger type | Discovered at stage | Conflict | Affected findings | Preserved findings | Invalidated findings | Required input/evidence | Owner | Decision/rationale | Rerun scope | Rerun count | State | Module-freeze impact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- |
| VUI-R1 | user-owned | Outcome definition | Stop/reset versus loop | Playback terminal behaviour | Shared selector, Media3 adapter, frozen API | Stop/reset assumption | User decision | User | Continuous selected-range looping approved | Playback rules only | 1 | Resolved | No public API effect |
| VUI-R2 | evidence-owned | Architecture review | Existing UI has frames but not a selector | Visual completion claim | Core/export/temp contract, 24-frame bound | Claim that visual outcome was complete | Current source read | Codex | Replace detached bar/row with shared selector; no core contract change | Compose/UI integration | 1 | Resolved | Compose modules only |
| VUI-R3 | technical | Preview evaluation | `PlayerView` AndroidView would add Compose surface risk | Android preview surface choice | Media3 dependency/version, Android player choice | PlayerView wrapper proposal | Official Media3 surface guidance | Codex | Use Compose-native Media3 surface APIs | Android preview | 1 | Resolved | Android Compose actual |
| VUI-R4 | evidence-owned | First independent principal review | Position polling after trim end could render unselected media | Range enforcement | Media3 preview, range loop requirement, public API isolation | UI-poll loop decision | Principal finding + Media3 clipping API | Codex | Replace poll boundary with source-level `ClippingConfiguration` and one-period repeat | V2 playback rules and failure matrix | 1 | Resolved | V2 acceptance strengthened |
| VUI-R5 | evidence-owned | First independent principal review | Preview contract did not define command order, stale events, retry, or close fence | Preview protocol | Internal-only seam and presenter ownership | Responsibility-only seam description | Principal finding | Codex | Freeze generation/revision binding, commands/events/port, and state table | V1–V3 | 1 | Resolved | V1/V2/V3 interfaces frozen |
| VUI-R6 | evidence-owned | First independent principal review | Historical predecessor blueprint was untracked | Compatibility evidence | Accepted implementation and committed API/release baseline | Link to untracked file as evidence | `git ls-tree` of `f7c868e` | Codex | Link committed baseline/release gate; label historical file non-evidence | Compatibility evidence | 1 | Resolved | No production module effect |
| VUI-R7 | evidence-owned | Original V3 review rejection | `1774142` used source-derived generations, could start a new presenter before the old release fence completed, and let composition-owned cleanup cancel teardown | Original V3 lifecycle ownership, generation, close/replacement order | Approved visual composition, canonical range/export ownership, V1/V2 contracts, public/core/iOS scope | Original V3 lifecycle implementation and its coordinator-owned jobs/generation | Rejection record in task-3 report plus source/diff evidence | Replacement V3 owner; principal reviewer verifies | Replace with one serialized owner, monotonic generations, audit-before-close ordering, and no composition-owned cleanup | `compose/commonMain` lifecycle/screen/coordinator plus focused `commonTest`; no V1/V2 production rerun | 1 | Resolved; executed and accepted at `661d16c` | Original V3 remains invalid; replacement V3 accepted; V1/V2 preserved |
| VUI-R8 | evidence-owned | V3 fix-round-1 rejection | `0501e57` attempted to reuse terminal Android ports and left disposal, close-race, and provisional/export mutation gaps | Port factory ownership, terminal disposal, replace-vs-close, export gate | V1 selector semantics, V2 clipped/repeat player behaviour, public/core/iOS scope | Released-port reuse, surface disposal authority, close-loses paths, provisional export | Fix-round-1 review and terminal actual evidence | Replacement V3 owner + Android adapter reviewer | Factory owns create/dispose; render-only surface; close latch wins; export mutation gate is centralized | `compose/commonMain`, `compose/androidMain/AndroidPlatformPreview.kt`, focused common/Android device tests; V2 media semantics unchanged | 1 | Resolved; executed and accepted at `661d16c` | Replacement V3 owns Android factory/surface boundary; V2 remains frozen |
| VUI-R9 | evidence-owned | V3 fix-round-2 rejection | `24ec733..fdfe8e6` left timeout evidence transient and omitted executable terminal-fence and replacement-vs-close proofs | Durable release audit and lifecycle verification | Fresh-port ownership/order repair, canonical range, visual design, public/core/iOS scope | Transient/free-form diagnostic record and partial order/race test claims | Fix-round-2 review, `496bf2e` review at 86/100, exact lifecycle sequence requirement | Replacement V3 owner; author-distinct Sol/high principal review | Closed allowlisted audit; acknowledgement and timeout each assert full order through `start(g2)`/`Bind(g2)`; close race asserts zero fresh start | Replacement V3 blueprint/plan and `commonTest` lifecycle harness plus focused Android lifecycle proof; IG1/V4/V5 order unchanged | 1 | Resolved; 98/100 author-distinct PASS at `661d16c` | Replacement V3 accepted; no other module invalidated |
| VUI-R10 | evidence-owned | IG1 functional RED after accepted V3 `661d16c` | Frozen screen owns real factory internally, but approved recorder had no internal hook to wrap that UI-owned port; attempted public parameter failed compile | IG1 testability, common screen factory selection, affected V3 lifecycle/API evidence | Outcome, all public declarations, V1/V2, V3 lifecycle state machine/order/audit/export lock, core/export/lease, Android actual/version, iOS actual/scope, V4/V5 order | Existing-seam testability assumption and IG1 test-files-only scope | IG1-O1 owner; author-distinct principal reviewer | Internal nullable per-composition override/default production path plus VUI-R10A transparent surface bridge | Common selector/screen + Android bridge/preflights/wrappers; declaration; common/iOS; lifecycle devices; IG1 | 1 | Resolved and accepted at `eb06901` | V3 product acceptance and bounded O1 seam preserved |
| VUI-R10A | evidence-owned, materially new subtrigger | Author-distinct review of VUI-R10 repair | Recording wrapper becomes lifecycle active port but Android surface concrete-casts direct actual and returns; compile preflight false-passes. Inline editor identity and criterion matrix incomplete | Android surface bridge, common proxy contract, wrapper/editor stability, runtime preflight, full IG1 traceability | VUI-R10 need for internal per-composition selection; exact production default; real delegation; public/core/iOS/dependency/V3 lifecycle constraints | Wrapper-alone transparency, compile-only sufficiency, original resolved claim, inline editor wrapper, incomplete matrix | IG1-O1 owner; author-distinct V3 delta reviewer | Add common platform-neutral surface delegate, one-hop Android resolver, stable remembered wrappers, API23 ContentFrame preflight, exhaustive owner/hook/no-fake matrix | `AndroidPlatformPreview.kt`, focused common/Android proxy tests, API23/Samsung transparency, affected V3 reruns | 1 | Resolved; author-distinct 98/100 PASS at `eb06901` | O1 accepted; functional IG1 permitted |
| VUI-R11 | evidence-owned | Strict functional IG1 after accepted O1 `eb06901`; same-trigger 84/100 docs review | Accepted matching `SetPlayWhenReady(true)` mutates Player only; later repeat `READY` restores stale `appliedBinding.playWhenReady=false`; first docs also required an impossible full IG1 class containing a defect-asserting diagnostic and contradicted gate order | V2 Android intent persistence/real repeat proof; strict IG1 loop criterion; diagnostic lifecycle; canonical one-time gate order | V1, V3, accepted O1, public/core/common/iOS contracts, exact clipping/repeat config, release/close/export/lease evidence, original strict RED/diagnostic traces, V4/V5 order | Clipping + repeat/config-only sufficiency; generic fixture/device/decoder/source-end hypotheses; player-only intent; defect diagnostic as enduring acceptance; strict-before-regression ordering | Confirmed traces/discriminator + `fb_20260807_194642_fcc9d0`; V2 adapter owner; author-distinct Full review `fb_20260808_154041_279458` | Persist accepted matching intent; archive evidence first → write tests and run RED → delete only archived diagnostic → minimal fix plus early vertical GREEN → canonical final sequence | Original production/focused-test pair plus one test-only diagnostic deletion; direct full adapter class API23/Samsung → core/V3/common/iOS/declaration → code review → exact strict method API23/Samsung last | **1; unchanged.** `PLAN_FROZEN` | V2 implementation may start; V1–V3/O1 acceptance preserved; IG1/V4/V5 blocked |

**Current module-freeze status: `PLAN_FROZEN` at author-distinct 100/100 review `fb_20260808_154041_279458`.** V3 acceptance at `661d16c` and O1/VUI-R10/R10A acceptance at `eb06901` remain preserved. Strict/no-fake IG1 evidence is valid RED evidence. Only VUI-R11's frozen code/test scope may now start. After all direct/regression/review gates, only the exact strict functional method runs on API23/Samsung last. Samsung unavailable blocks completion. No user ambiguity.

## 11. Provisional module structure

| Module | Responsibilities | Forbidden responsibilities |
| --- | --- | --- |
| `video-clip-editor-compose/commonMain` | Internal selector, timeline mapping, state reducer/presenter integration, image display, semantics | Android/Apple media imports, temp files, export implementation, navigation |
| `video-clip-editor-compose/androidMain` | Internal Media3 controller, Compose player surface, main-thread lifecycle/release, sanitized events | Public factory/API, exporter/temp ownership, host navigation |
| `video-clip-editor-compose/iosMain` | Internal compile-safe unavailable actual | AVFoundation media implementation in V1, public contract changes |
| `demo-android` | Select source then display library editor; manual visual evidence | Library-only behavior or permanent output ownership |
| `video-clip-editor-compose/commonTest` | Pure mapping/state/component tests with fake preview controller | Android SDK/Media3 assertions |
| `video-clip-editor-compose/androidDeviceTest` or demo Android tests | Player, loop, lifecycle, and end-to-end visual/device verification | Replacing common selector tests |

## 12. Model routing and execution record

| Work | Floor / profile | Topology and dependency | Mapping/review record | Override | Observed execution |
| --- | --- | --- | --- | --- | --- |
| V1 common selector/state | Terra / medium | Ordered first; freezes the pure common port used by V2/V3 | Workspace routing policy: normal Compose/state implementation; independent review required | None | Blueprint author model profile not externally verifiable; record as planned until execution |
| V2 Android Media3 adapter | Sol / high | Depends on frozen V1; ordered before V3 | High-risk decoder/surface/lifecycle/concurrency boundary; principal review and device evidence required | None | Planned |
| Replacement V3 lifecycle integration | **Sol / high** | Depends on frozen V1+V2; one owner spans common screen/session lifecycle and Android port disposal, so it cannot parallelize | Floor raised for concurrency-sensitive serialized teardown and three successive V3 rejection triggers (VUI-R7–R9); author-distinct principal re-review required | No below-floor override | Accepted at `661d16c`; 98/100 review and required device/declaration evidence |
| IG1-O1 observability prerequisite | Sol / high | Ordered after accepted V3, before IG1; common selection/proxy + Android surface bridge + tests | First repair rejected; VUI-R10A added runtime bridge/preflights and complete evidence | None | Accepted at `eb06901`; author-distinct 98/100 PASS |
| V2-R11 repeat-intent repair | Sol / high | Ordered after strict IG1 diagnosis; bounded Android adapter state correction/focused device tests plus test-only diagnostic disposition | Real repeat transition, same-port discriminator, canonical strict-last sequence; author-distinct blueprint/code reviews >=95 | None | `ARCHITECTURE_APPROVED`; new independent review required for `PLAN_FROZEN`; code blocked |
| IG1 integration gate | Sol / high | Depends on accepted V1–V3/O1 and V2-R11; integration-only | Cross-module lifecycle/range/export proof; strict test unchanged; principal review | None | VUI-R11 RED retained; one rerun pending |
| V4 device/demo evidence | Terra / medium | Depends on IG1 | Bounded device verification and evidence collection | None | Planned |
| V5 final audit | Sol / high | Depends on V4 | Independent architecture/API/security/device audit | None | Planned |

Mapping digest: the active workspace routing policy selects Terra for normal implementation and Sol for cross-cutting/high-risk architecture. Replacement V3 is explicitly Sol/high because serialized concurrency spans presenter sessions, release acknowledgement/timeout, terminal native ports, composition disposal, and a close race, and the same chunk has failed three review rounds. The target library has no local `AGENTS.md`; the user-approved scope, this blueprint, and the frozen public baseline are the target-specific authority. No below-floor override is permitted. There is no replacement V3 execution record yet.

## 13. Ordered delivery chunks

Each chunk must independently pass its completion gate. A later chunk cannot repair an earlier incomplete contract.

### V1 — Selector geometry and common preview model

* **Scope:** Common internal `ClipRangeSelector`, pure range/playhead/scroll geometry, and presenter state extension.
* **Responsibilities:** Align thumbnails, handles, dim overlays, time labels, playhead, semantics, clamping, and user intents. Keep 24-frame bounded memory model.
* **Interfaces:** Exact internal `PreviewGeneration`, `PreviewRevision`, `PreviewBinding`, `PreviewCommand`, and `PreviewEvent` declarations in §4; selector callbacks. Existing public `ClipEditorScreen` signature unchanged.
* **Dependencies:** Existing `ThumbnailFrame`, `VideoMetadata`, `ClipRange`, Compose foundation/material3.
* **Acceptance criteria:** Pure tests prove time/pixel inverse mapping at scroll offsets, handle non-crossing/minimum duration, playhead range clamp, and selected/outside semantics. Compose tests locate all controls by test tag.
* **Test strategy:** RED common unit/Compose test before implementation; screenshot/golden-style semantics state where available; all existing Compose tests green.
* **Rollback strategy:** Revert only new internal selector/model files; restore current presenter state without touching core/export.
* **Integration strategy:** Render selector through a temporary fake preview in common tests before Android player wiring.

### V2 — Android internal Media3 preview adapter

* **Scope:** Android Compose actual for the internal preview seam and Android-only dependency declarations.
* **Responsibilities:** Main-thread ExoPlayer creation, Compose-native surface attachment, source-level clipping for every binding, `REPEAT_MODE_ONE` on that clipped period, source/clip time conversion, sanitized revision-scoped events, and idempotent release fence.
* **Interfaces:** Implements the exact V1 internal common preview seam in §4 only. Exposes no Media3/Android type outside `androidMain`.
* **Dependencies:** Pinned `media3-exoplayer` and `media3-ui-compose` 1.10.1; Android lifecycle/Compose runtime.
* **Acceptance criteria:** Player renders accepted fixture, starts paused at clip start, is given clipping configuration equal to selected source range, repeats only that clipped period, reports source position within range, and releases on disposal/source replacement. No public ABI diff.
* **Test strategy:** Android instrumentation with a real local fixture; inspect applied clipping configuration and repeat mode, loop/release tests, stale-revision test, failure mapping test with controllable controller seam where feasible.
* **Rollback strategy:** Remove Android actual/dependency and retain common selector behind unavailable preview state; core remains operational.
* **Integration strategy:** Wire only through `ClipEditorScreen` after V1 tests pass; use original existing export integration tests unchanged.

### V3 — `ClipEditorScreen` composition and lifecycle integration

**Superseded.** This original V3 chunk is retained only for historical traceability. It is not executable and cannot be used as an implementation or review input. The normative replacement is §6A plus implementation-plan Task 3, at a Sol/high floor.

* **Scope:** Replace current `EditorControls` bar/row with preview + selector + footer, connect common presenter and internal controller.
* **Responsibilities:** Loading/retry/render transitions, player command dispatch, Back/Done controls, disposal ordering, disable controls during export/failure.
* **Interfaces:** Existing `ClipEditorScreen` public API and callbacks only; V1/V2 internal interfaces.
* **Dependencies:** V1 common model/selector and V2 Android actual.
* **Acceptance criteria:** Selected range rendered is exactly export range; cancel/result callbacks sent once; player release precedes session close; source change cannot accept stale callbacks.
* **Test strategy:** Compose presenter tests with fake controller, Android instrumentation flow, regression of cleanup/disposal tests.
* **Rollback strategy:** Revert composition wiring; V1/V2 internal files remain unused or revert as one ordered unit.
* **Integration strategy:** Run existing core device suite plus new Compose/device suites before demo change.

### IG1 — Integration-only editor-flow gate

* **Scope:** Complete §6B internal selection + common surface delegate + Android one-hop bridge; pass compile and API23 runtime transparency preflights; then assemble full screen flow. No public/global hook or reusable product behavior.
* **Responsibilities:** Prove production open/session/metadata/frames → wrapper-active real ContentFrame → tagged pan/scrub/commit → actual binding/loop → Done/production export → proxy-synchronous Released → real close/dispose → real twice cleanup. Remember editor/factory wrappers so ordinary recomposition causes no replacement.
* **Interfaces:** Unchanged public `ClipEditorScreen`; frozen port; internal composition local + platform-neutral surface delegate; Android one-hop resolver; `ClipResult`; real-delegating test wrappers. No public/platform type leak.
* **Dependencies:** Accepted V1/V2/V3 and O1/VUI-R10/R10A at `eb06901`; V2-R11 direct adapter API23/Samsung, regressions/guards, and author-distinct code review >=95 PASS; archived diagnostic evidence and deleted temporary diagnostic method; legal fixture. Exact strict functional method remains the final VUI-R11 gate.
* **Acceptance criteria:** Normative §6 matrix fully green. Active lifecycle identity is wrapper while exact actual reaches ContentFrame; UI gestures—not direct calls—produce committed range; matching Released ledger sequence precedes close-entry; cleanup is `Cleared` then `AlreadyCleared`.
* **Test strategy:** Compile visibility then API23 real runtime surface preflight; common/iOS/declaration/lifecycle-device delta gates; full real Android flow. Compile-only/resolver-only/direct-port/fake/timestamp evidence rejected. Existing core suite unchanged.
* **Rollback strategy:** Revert only IG1-O1 internal selection and its tests if seam fails; otherwise repair the owning V1–V3/IG1-O1 contract. Never weaken IG1 or add a public parameter.
* **Integration strategy:** **IG1 is a hard gate.** V4 cannot start until IG1 is green on an API 23 emulator. Samsung evidence may begin only after IG1's API 23 pass.

### V4 — Demo and visual acceptance evidence

* **Scope:** Adjust only standalone Android demo to display the completed screen and add legal deterministic visual fixture/evidence.
* **Responsibilities:** Verify actual host flow after document import; record screenshot/screen capture without user media; expose no extra library functionality.
* **Interfaces:** Existing `ClipEditorScreen` and typed result/lease cleanup callbacks.
* **Dependencies:** IG1; repository-owned AVC and HEVC fixtures.
* **Acceptance criteria:** Samsung and API 23 evidence show preview, thumbnail selector, panning, drag, scrub, selected-range loop, export, and temp cleanup.
* **Test strategy:** Instrumented device tests plus manual checklist using non-sensitive repository fixture; archive test output only, not device personal media.
* **Rollback strategy:** Revert demo/evidence changes; no library contract impact.
* **Integration strategy:** Final release gate runs core + Compose + demo checks on both device targets.

### V5 — Independent audit and completion report

* **Scope:** Principal-engineer review, API diff, licensing/security review, full acceptance traceability.
* **Responsibilities:** Verify objective rather than code intent; reject incomplete visual/device evidence.
* **Interfaces:** No production interface changes.
* **Dependencies:** V1–V4 and IG1 evidence.
* **Acceptance criteria:** Independent reviewer PASS >=95/100; every objective criterion has direct evidence; no OneOnOneArena diff.
* **Test strategy:** Review exact commits, run `git diff --check`, API baseline comparison, dependency/NOTICE review, API23 and Samsung results.
* **Rollback strategy:** Revert the failing bounded chunk, preserve approved evidence and public API baseline.
* **Integration strategy:** Only V5 PASS authorizes goal completion.

## 13. Testing and compatibility matrix

| Layer | Scenario | Proof |
| --- | --- | --- |
| Common unit | 0/half/full scroll mapping; range constraints; stale event generation | Deterministic unit tests |
| Common Compose | Loading, ready selector, handles, playhead, time labels, Back/Play/Done semantics | Compose UI tests |
| Android player | AVC and HEVC selected sources, portrait/landscape aspect fit, play/pause/seek/loop | Instrumentation on API 23 and Samsung |
| Android lifecycle | Activity/screen disposal, source replacement, retry after preview failure | No active player/session resource and no crash |
| Export regression | Edited range invokes existing Media3 Transformer output and lease cleanup | Existing plus augmented integration test |
| Typed failure | Core codec preflight rejection and player setup failure | Typed core result or sanitized internal retry, never native exception |
| iOS compile | Internal unavailable actual compiles and core factory retains typed unavailable | iOS compile/test target |
| API safety | Source/API baseline and package scan | No Android/native type in common public surface |

## 14. Performance, memory, security, and licensing

### Performance/memory

* Retain at most the existing 24 bounded JPEG thumbnails and their on-screen decoded image representations. Do not decode video frames for every scroll pixel or allocate a complete video into memory.
* Keep playback position updates bounded to UI cadence. Do not perform a Compose state write for every video render frame.
* Use a single player per active screen/source generation. Release it before session closure/replacement.
* Timeline panning is UI-only; it does not re-extract frames or launch export work.
* Record startup-to-preview, loop accuracy, memory, and export regression observations on API 23/Samsung rather than asserting unmeasured budgets.

### Security/ownership

* Preview opens the host source read-only and never deletes it.
* Preview cannot create, publish, or clear an output; only the existing exporter/lease path may do so.
* Diagnostics exclude the source's contents and raw platform stack traces. Test screenshots use repository fixtures only.
* All player teardown is idempotent. A stale player cannot seek a new source or mutate a new range generation.

### Licensing

* Media3 is AndroidX/Apache-2.0 and is already an accepted project dependency for Transformer. Adding its Compose UI artifact must retain applicable notices and pin the same 1.10.1 version.
* No copied Android trimmer code, FFmpeg binary, or GPL component is introduced.
* Thumbnail/test media must remain repository-owned or have a recorded redistributable license and attribution.

## 15. Failure matrix

| Condition | UI behaviour | Public/output behaviour |
| --- | --- | --- |
| Open/probe rejects source | Existing terminal typed message | Existing typed result; no preview/player/temp file |
| Frame extraction fails | Existing retry/terminal flow | Existing typed failure; no player/export |
| Player prepare/runtime error | Sanitized retry state; release player | No platform exception; no output lease created |
| Start/end handle invalid move | Clamp; keep UI responsive | Existing valid range invariant |
| Playhead tap outside selection | Clamp to nearest selected boundary | No export change |
| Player reaches trim end | Media3 repeats the source-level clipped period | No source/export mutation; no UI-poll overshoot path |
| Repeat transition enters `READY` after accepted play intent | Restore authoritative `appliedBinding.playWhenReady`; resume playing inside the same clipped period | No public/common state mutation; no export/source/range change |
| Source changes during playback | Release old player, ignore stale events, prepare new | Old session closes; no host file deletion |
| Back/disposal | Pause/release then existing close | No output unless user previously chose Done; existing lease semantics |
| Done/export failure | Disable player while exporting; show typed result | Existing typed `ClipResult`; partial output cleanup unchanged |
| iOS V1 preview attempt | Compile-safe internal unavailable view/state | Existing common iOS typed engine-unavailable contract unchanged |

## 16. Blueprint release gate and traceability

| Outcome | Backward condition | Forward module/chunk | Direct evidence | Status |
| --- | --- | --- | --- | --- |
| Video preview | VUI-03 | V2, V3, IG1 | Android player/lifecycle test | Pending |
| Visual clip selector | VUI-04 | V1, V3, IG1 | Common Compose test + Samsung screenshot | Pending |
| Pan/scrub | VUI-04 | V1, V3, IG1 | Geometry + Android seek test | Pending |
| Range looping | VUI-01/VUI-03/VUI-R11 | V2-R11, IG1 | Real repeat-transition adapter class on API23/Samsung, regressions/review, then exact strict functional method on both targets last | `ARCHITECTURE_APPROVED`; `PLAN_FROZEN` pending author-distinct review |
| Existing clip/export lease | Existing contract | V3, IG1, V4 | Export/cleanup regression | Pending |
| IG1 proxy transparency + release-before-close | VUI-R10/VUI-R10A | IG1-O1, IG1 | Compile + API23 ContentFrame runtime preflights; synchronous real-delegating ledger | Accepted at `eb06901` |
| API/platform isolation | VUI-02/VUI-06 | V1–V5 | Public API diff + iOS compile | Pending |
| API 23/Samsung support | VUI-05 | V4, V5 | Both device results | Pending |
| No OneOnOneArena changes | Scope boundary | V5 | Repository diff/path audit | Pending |

### Generic Blueprint First prevention

- Select route from exact Direct/Lite/Full predicates; record every Full hard trigger.
- Validate a machine-readable manifest against the workspace; freeze immutable baseline hashes and a reproducible evidence digest while recording planned mutable scope separately.
- Give every critical row a named executable oracle/future executor path and full requirement → invariant → task → oracle → evidence → integration traceability.
- Name early vertical producer/consumer proof separately from the final integration gate.
- Classify every test as pre-fix diagnostic, enduring acceptance, regression, or evidence helper before freezing gates. A defect-asserting diagnostic needs evidence retention plus explicit post-fix deletion/non-acceptance conversion.
- Freeze one order: archive source/evidence hash/diff → write tests and run RED → retire only archived diagnostic → minimal fix and early vertical GREEN → direct both-device gate → regressions/guards → author-distinct review → strict both-device final gate.
- Publish one canonical gate order. One-time/high-cost acceptance runs occur only after every code/test edit, regression/compatibility gate, and code review; any later source change invalidates that evidence and requires the whole sequence again.
- Required target unavailability is a completion blocker. Same-trigger documentation repair preserves reconciliation ID/count.

**Next gate:** new author-distinct principal review of the Full manifest and normative VUI-R11 plan. Only that review may advance `ARCHITECTURE_APPROVED` to `PLAN_FROZEN`. Then archive pre-fix evidence, write/run focused RED tests, delete only the temporary diagnostic, apply the minimal one-variable repair plus early API23 direct real-port GREEN, and run the canonical final order: full adapter API23/Samsung → core/V3/common/iOS/declaration → author-distinct code review >=95 → exact strict functional method API23/Samsung last.
