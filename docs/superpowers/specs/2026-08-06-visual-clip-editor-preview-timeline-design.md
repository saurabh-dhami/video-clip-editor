# KMP Video Clip Editor — Visual Preview and Clip Range Selector Blueprint

**Status:** Draft — visual design approved; no production implementation may begin until the Blueprint First review and user blueprint-approval gates pass.

**Decision record:** `dec_20260806_183806_e9acff`.

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

## 7. Backward necessary-condition pass

| Outcome criterion | Direct predecessor | Why necessary | Evidence/assumption | Owner | Stop condition |
| --- | --- | --- | --- | --- | --- |
| Preview shows selected source | Lifecycle-safe Android player surface bound to local path | Preview cannot render from JPEG frames | Existing Android source path, Media3 dependency; official surface guidance | Android preview module | Player cannot initialize/type error is sanitized and retryable |
| Timeline resembles approved design | One coordinate system for frames, selection, playhead, scroll | Separate bar/row cannot align visuals/gestures | Current separate controls observed | Shared Compose module | Selector test proves overlay mapping |
| User can pan/scrub | Stable time↔content coordinate transform | Player seek and visible playhead must agree under horizontal scroll | `VideoMetadata.duration` and 24 ordered frames exist | Shared Compose module | Invalid duration or missing frames stays loading/failure |
| Handles select valid clip | Central range clamp uses frozen minimum | Export must receive valid range | Existing `CommonValidation.clipRange` and presenter range logic | Shared Compose module | Bounds test fails |
| Loop only selection | Source-level media clipping plus one-period repeat | ExoPlayer full-media repeat would include excluded video; UI polling is too late | User-approved loop behaviour; official Media3 clipping API | Android preview module | Clipped-period/repeat device test fails |
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
| Common selector | Frames, canonical range/playhead/scroll | Draw track/overlays; map gestures to complete snapshot commands | `PreviewCommand` only | Ignore stale/invalid input; clamp centrally | Common Compose tests | VUI-04 |
| Preview controller | `PreviewCommand` generation/revision | Acknowledge only active binding; source-level clipped player enforces bounds | `PreviewEvent` with matching generation/revision | Pause/release/retry; stale event ignored | Unit + Android device clip/repeat test | VUI-03 |
| Presenter | Done | Existing exporter receives selected range | Frozen `ClipResult` | Existing typed failure path | End-to-end output inspection | None |
| Lifecycle coordinator | Source change/Back/disposal | Send terminal Release; await matching `Released` or bounded recorded fallback; then close session | No active decoder/session | Idempotent terminal fence | Leak/release device test | VUI-03 |
| iOS actual | Same internal call site | Compile unavailable/no-op preview implementation | No Android type leak | Internal unavailable state | iOS compile test | VUI-06 |

The forward pass has no contradictory state owner: range/export remain presenter-owned; native playback remains Android-owned; visual selection stays common.

## 10. Reconciliation history and module-freeze decision

| Trigger ID | Trigger type | Discovered at stage | Conflict | Preserved findings | Invalidated findings | Required input/evidence | Owner | Decision/rationale | Rerun scope | Rerun count | State | Module impact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | ---: | --- | --- |
| VUI-R1 | user-owned | Outcome definition | Stop/reset versus loop | Shared selector, Media3 adapter, frozen API | Stop/reset assumption | User decision | User | Continuous selected-range looping approved | Playback rules only | 1 | Resolved | No public API effect |
| VUI-R2 | evidence-owned | Architecture review | Existing UI has frames but not a selector | Core/export/temp contract, 24-frame bound | Claim that visual outcome was complete | Current source read | Codex | Replace detached bar/row with shared selector; no core contract change | Compose/UI integration | 1 | Resolved | Compose modules only |
| VUI-R3 | technical | Preview evaluation | `PlayerView` AndroidView would add Compose surface risk | Media3 dependency/version, Android player choice | PlayerView wrapper proposal | Official Media3 surface guidance | Codex | Use Compose-native Media3 surface APIs | Android preview | 1 | Resolved | Android Compose actual |
| VUI-R4 | evidence-owned | First independent principal review | Position polling after trim end could render unselected media | Media3 preview, range loop requirement, public API isolation | UI-poll loop decision | Principal finding + Media3 clipping API | Codex | Replace poll boundary with source-level `ClippingConfiguration` and one-period repeat | V2 playback rules and failure matrix | 1 | Resolved | V2 acceptance strengthened |
| VUI-R5 | evidence-owned | First independent principal review | Preview contract did not define command order, stale events, retry, or close fence | Internal-only seam and presenter ownership | Responsibility-only seam description | Principal finding | Codex | Freeze generation/revision binding, commands/events/port, and state table | V1–V3 | 1 | Resolved | V1/V2/V3 interfaces frozen |
| VUI-R6 | evidence-owned | First independent principal review | Historical predecessor blueprint was untracked | Accepted implementation and committed API/release baseline | Link to untracked file as evidence | `git ls-tree` of `f7c868e` | Codex | Link committed baseline/release gate; label historical file non-evidence | Compatibility evidence | 1 | Resolved | No production module effect |

**Module-freeze status: BLOCKED pending independent principal-engineer review.** Outcome and architecture evidence are recorded; no user-owned ambiguity remains. Modules stay provisional until the reviewer verifies state ownership, lifecycle, API isolation, device compatibility, and every chunk at >=95/100 readiness.

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
| V3 screen integration | Terra / medium | Depends on V1+V2; cannot parallelize because it owns common screen composition | Existing presenter and disposal integration | None | Planned |
| IG1 integration gate | Sol / high | Depends on V1–V3; integration-only | Cross-module lifecycle/range/export proof; principal review | None | Planned |
| V4 device/demo evidence | Terra / medium | Depends on IG1 | Bounded device verification and evidence collection | None | Planned |
| V5 final audit | Sol / high | Depends on V4 | Independent architecture/API/security/device audit | None | Planned |

Mapping digest: the active workspace routing policy selects Terra for normal implementation and Sol for cross-cutting/high-risk architecture. The target library has no local `AGENTS.md`; the user-approved scope, this blueprint, and the frozen public baseline are the target-specific authority. No below-floor override is permitted. There is no implementation execution record yet.

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

* **Scope:** Replace current `EditorControls` bar/row with preview + selector + footer, connect common presenter and internal controller.
* **Responsibilities:** Loading/retry/render transitions, player command dispatch, Back/Done controls, disposal ordering, disable controls during export/failure.
* **Interfaces:** Existing `ClipEditorScreen` public API and callbacks only; V1/V2 internal interfaces.
* **Dependencies:** V1 common model/selector and V2 Android actual.
* **Acceptance criteria:** Selected range rendered is exactly export range; cancel/result callbacks sent once; player release precedes session close; source change cannot accept stale callbacks.
* **Test strategy:** Compose presenter tests with fake controller, Android instrumentation flow, regression of cleanup/disposal tests.
* **Rollback strategy:** Revert composition wiring; V1/V2 internal files remain unused or revert as one ordered unit.
* **Integration strategy:** Run existing core device suite plus new Compose/device suites before demo change.

### IG1 — Integration-only editor-flow gate

* **Scope:** No new reusable feature. Compose V1, Android V2, and screen V3 are assembled in one library-owned Android test host.
* **Responsibilities:** Prove the cross-module ordering that unit tests cannot: open source → extract frames → bind clipped preview → pan/scrub/commit range → selected-range loop → Done/export → preview release/session close/temp lease cleanup.
* **Interfaces:** The frozen §4 internal port and existing public `ClipEditorScreen`/`ClipResult` only. No demo-only hook becomes public.
* **Dependencies:** Accepted V1, V2, and V3 completion gates; legal deterministic local fixture.
* **Acceptance criteria:** One test session observes no stale generation event, selected source range equals exported range, clipped preview configuration equals committed selector range, playback repeats the selected clipped period, release completes before session close, and lease cleanup remains idempotent.
* **Test strategy:** Android instrumentation against the real Media3 actual and repository fixture; a test-only event recorder timestamps port events, player release, session close, export result, and cleanup result. Existing core HEVC/AVC integration suite also passes unchanged.
* **Rollback strategy:** Revert V1–V3 as one ordered unit if integration exposes a contract conflict; do not hide an integration failure by weakening IG1.
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
| Range looping | VUI-01/VUI-03 | V2, V3, IG1 | Device loop test | Pending |
| Existing clip/export lease | Existing contract | V3, IG1, V4 | Export/cleanup regression | Pending |
| API/platform isolation | VUI-02/VUI-06 | V1–V5 | Public API diff + iOS compile | Pending |
| API 23/Samsung support | VUI-05 | V4, V5 | Both device results | Pending |
| No OneOnOneArena changes | Scope boundary | V5 | Repository diff/path audit | Pending |

**Next gate:** independent principal-engineer review. A PASS at >=95/100 freezes V1–V5 and IG1 contracts. Then the user reviews this written blueprint. Only user approval of the reviewed blueprint permits the implementation plan and production code.
