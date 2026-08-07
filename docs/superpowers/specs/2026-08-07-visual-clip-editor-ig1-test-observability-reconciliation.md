# IG1 Test-Observability Reconciliation

**Status:** BLOCKED before code. VUI-R10 remains unresolved. VUI-R10A records materially new downstream-consumer evidence from author-distinct REJECT. Requires new author-distinct PASS, compile preflight, then API-23 runtime transparency preflight.

**Authority:** Evidence-owned reconciliation. User outcome unchanged.

**Decisions:** original `dec_20260807_181452_13335a` (rejected); repair `dec_20260807_183016_c836b9`. Review feedback `fb_20260807_183016_f875ac`.

**Baseline:** V3 accepted at `661d16c`; public declaration gate, common/iOS, API-23, Samsung passed.

## 1. Trigger, review result, diagnosis

Original IG1 RED:

~~~text
ClipEditorScreenIntegrationTest.kt:55:37 No parameter with name 'previewPortFactory' found.
~~~

VUI-R10 proposed an internal per-composition factory override and real-delegating `RecordingPreviewPort`. Author-distinct review rejected it below 95/100. Reported score: 89/100; Agent Brain outcome text records 88/100. Score discrepancy does not change REJECT.

New exact source evidence:

- `ClipEditorScreen.kt`: lifecycle `activePort` is passed unchanged to `PlatformPreviewSurface`.
- `AndroidPlatformPreview.kt`: `val androidPort = port as? AndroidMedia3PreviewPort ?: return`.
- Proposed lifecycle active port would be `RecordingPreviewPort`, so surface returns before `ContentFrame`. Wrapper records events but creates blank/headless preview. Compile-only preflight cannot expose this.
- `ClipEditorScreen.kt`: `LaunchedEffect(source, editor, lifecycle)` calls `requestReplace`. Inline recreation of `RecordingVideoClipEditor` changes `editor` identity on ordinary recomposition and can cause unintended session/source replacement.
- Existing matrix omitted concrete production open/session/frame and UI pan/scrub/commit wiring.

Why Blueprint First missed it: original repair checked upstream hook visibility and ownership, but not downstream consumer compatibility. A proxy can preserve `PreviewPort` protocol while violating a concrete consumer cast. Testability wiring must prove both interception and transparent passage through every downstream consumer.

## 2. Outcome and exclusions

Actor: IG1 Android instrumentation.

End state: unchanged public `ClipEditorScreen` owns a recording proxy as its lifecycle port; proxy synchronously records delegate events before lifecycle collector receives them; Android surface unwraps exactly one approved proxy hop to exact real `AndroidMedia3PreviewPort`; `ContentFrame` renders; production editor/session/frames/export/lease remain real; UI gestures drive production path.

Exclusions:

- No public/test parameter or global mutable hook.
- No host-visible contract/behavior change.
- No core, exporter, dependency, Media3 version, or iOS actual change.
- No Android/Media3 type in common/public declarations.
- No fake editor/session/frame/export/lease/port satisfying IG1.
- No recursive/general proxy chain.
- No V3 lifecycle order/state-machine/export-lock change.

## 3. Exact allowed files and owners

| File | Owner/change | Forbidden |
| --- | --- | --- |
| `video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/PreviewPort.kt` | Common contract owner: nullable composition local plus internal platform-neutral `PreviewPortSurfaceDelegate` | Platform type, public declaration |
| `video-clip-editor-compose/src/commonMain/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreen.kt` | Common screen owner: default/override factory selection only; public declaration untouched | Test branch, lifecycle redesign |
| `video-clip-editor-compose/src/androidMain/kotlin/com/oneononearena/videoclip/compose/AndroidPlatformPreview.kt` | Android bridge owner: bounded one-hop resolver used by `PlatformPreviewSurface`; exact actual reaches `ContentFrame` | General recursion, dependency change |
| `video-clip-editor-compose/src/commonTest/kotlin/com/oneononearena/videoclip/compose/PreviewPortSurfaceDelegateTest.kt` | Create: platform-neutral proxy/event-order contract | Android type |
| `video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/PreviewPortSurfaceTransparencyDeviceTest.kt` | Create: API-23 runtime bridge preflight | Fake surface/compile-only proof |
| `video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreenIntegrationTest.kt` | Preserve RED, then stable composition + real UI flow | Public parameter, inline wrappers |
| `video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/RecordingClipEditorSession.kt` | Real-delegating port/editor/session/lease recorders | Direct construction replacing factory delegate |

`ClipEditorLifecycleDeviceTest.kt` is rerun, not modified. Any additional source file requires new reconciliation evidence. No build file, manifest, fixture, iOS, core, demo, host, or dependency edit.

## 4. Narrow interface and bridge contract

Common internal declarations:

~~~kotlin
internal val LocalPreviewPortFactoryOverride =
    staticCompositionLocalOf<PreviewPortFactory?> { null }

internal interface PreviewPortSurfaceDelegate {
    val surfacePort: PreviewPort?
}
~~~

Screen body, public signature byte-for-byte frozen:

~~~kotlin
val platformPreviewPortFactory = rememberPlatformPreviewPortFactory()
val previewPortFactory = LocalPreviewPortFactoryOverride.current ?: platformPreviewPortFactory
val lifecycle = remember(previewPortFactory) { ClipEditorLifecycleOwner(previewPortFactory) }
~~~

Test proxy remains lifecycle identity:

~~~kotlin
internal class RecordingPreviewPort(
    private val delegate: PreviewPort,
    private val ledger: RecordedEventLedger,
) : PreviewPort, PreviewPortSurfaceDelegate {
    override val surfacePort: PreviewPort = delegate
    override val events: Flow<PreviewEvent> = delegate.events.onEach(ledger::recordPreviewEvent)
    override fun dispatch(command: PreviewCommand) = delegate.dispatch(command)
}
~~~

`onEach` executes before downstream lifecycle collector receives each event. Thus matching `Released` receives a ledger sequence before lifecycle can acknowledge release and enter session close. Assert sequence IDs, not timestamp races.

Android bridge, exactly one proxy hop:

~~~kotlin
internal fun resolveAndroidPreviewSurfacePort(port: PreviewPort): AndroidMedia3PreviewPort? {
    if (port !is PreviewPortSurfaceDelegate) return port as? AndroidMedia3PreviewPort
    val candidate = port.surfacePort ?: return null
    if (candidate === port || candidate is PreviewPortSurfaceDelegate) return null
    return candidate as? AndroidMedia3PreviewPort
}

@Composable
internal actual fun PlatformPreviewSurface(port: PreviewPort, modifier: Modifier) {
    val androidPort = resolveAndroidPreviewSurfacePort(port) ?: return
    val player = androidPort.playerForSurface ?: return
    ContentFrame(player = player, modifier = modifier, contentScale = ContentScale.Fit)
}
~~~

Required resolver assertions:

- direct `AndroidMedia3PreviewPort` resolves to itself: production default unchanged;
- one recording proxy resolves to its exact real actual;
- null delegate returns null;
- self-cycle returns null;
- nested delegate/two-node cycle returns null; only one hop allowed;
- unrelated `PreviewPort` delegate returns null;
- unrelated proxy type cannot reach `ContentFrame`;
- iOS compiles unchanged and ignores interface.

Factory ownership:

- test `RecordingPreviewPortFactory.create()` calls real factory `create()`, records exact actual identity, returns wrapper;
- lifecycle owns wrapper and all commands/event collection;
- test factory `dispose(wrapper)` verifies known wrapper, unwraps exact actual, calls real factory `dispose(actual)` once;
- null/unrelated/nested wrapper disposal hard-fails; never silently leaks;
- production Android factory remains unchanged and receives exact actual from test factory.

## 5. Stable composition contract

Inside test composition:

~~~kotlin
val productionEditor = remember(activity) { createAndroidVideoClipEditor(activity) }
val recordingEditor = remember(productionEditor, ledger) {
    RecordingVideoClipEditor(productionEditor, ledger)
}
val realFactory = rememberPlatformPreviewPortFactory()
val recordingFactory = remember(realFactory, ledger) {
    RecordingPreviewPortFactory(realFactory, ledger)
}
CompositionLocalProvider(LocalPreviewPortFactoryOverride provides recordingFactory) {
    ClipEditorScreen(
        source = source,
        editor = recordingEditor,
        onResult = onResult,
        onCancel = onCancel,
    )
}
~~~

`ledger`, `source`, production editor, recording editor, real factory, and recording factory remain identity-stable. Force unrelated recomposition; assert same wrapper/editor/factory identities, exactly one production `openSession`, one generation/create, and no source replacement/Release/close before user action.

## 6. Exhaustive criterion wiring matrix

| Criterion | Production owner | Exact hook/visibility | No-fake/proxy condition | Required proof |
| --- | --- | --- | --- | --- |
| Screen receives real editor | Test composition / `ClipEditorScreen` | remembered `RecordingVideoClipEditor` passed through unchanged public parameter | wrapper delegates exact `createAndroidVideoClipEditor(activity)` identity | ordinary recomposition preserves identity; no second open |
| Open/session real | Production `VideoClipEditor` | wrapper records open entry/result around `delegate.openSession(source)` | `OpenSessionResult.Open.session` must be real delegate wrapped once | one open; absolute fixture source; Open result; no fabricated session |
| Metadata real | Production `ClipEditorSession` | wrapper `metadata` getter delegates exact object/value | no cached/fabricated metadata | expected fixture duration/dimensions |
| Frame extraction real | Production session | `frames(request).onEach` records real Frame/Progress/Complete | exact bounded request; no synthetic frames | request count 24; real frames; Complete before Ready UI |
| UI ready/surface visible | Screen + Android bridge | lifecycle active wrapper; `PreviewPortSurfaceDelegate.surfacePort`; resolver; `PlatformPreviewSurface(... Modifier.testTag("ig1-surface-probe"))` in runtime harness | exact actual + `ContentFrame`; early-return path lacks node | wrapper remains active; resolver identity exact; surface-probe node exists on API23 |
| Pan | `ClipRangeSelector` | real gesture via `clip-timeline` tag and changed scroll/gesture semantics | no direct coordinator/presenter call | UI gesture changes viewport; no range/export mutation |
| Scrub | Selector/coordinator | real tap/drag via `clip-playhead`/timeline tag; proxy records delegated Seek and actual Position | no direct `dispatch(Seek)` from test | Seek source position equals UI target; actual position follows |
| Range commit | Presenter/coordinator | real `clip-start-handle`/`clip-end-handle` drag | no direct presenter call; exactly one committed ReplaceRange | visible labels + one ReplaceRange binding equal committed range |
| Binding/config real | Media3 actual | proxy sees Bind/ReplaceRange; actual player/probe inspected after matching Ready | command-only evidence rejected | clipping start/end exact; `REPEAT_MODE_ONE`; exact actual identity |
| Selected loop real | Media3 actual | delegate Position events while playing | synthetic positions forbidden | positions bounded; later position returns near selected start |
| Export real | Production session/exporter | click tagged `done`; wrapper records `createClip` entry/result | no direct `createClip`; no fake result | sourceRange equals committed UI range; owned real output |
| Released before close | Lifecycle owner + proxy | proxy `events.onEach` ledger record before lifecycle collector; session close-entry ledger record | direct-port/timestamp-only proof rejected | `Released` sequence < close-entry; matching generation; one event |
| Session close real | Production session | wrapper records close-entry, delegates `close`, records close-complete | no swallowed/stub close | entry/completion once, after Released; factory dispose order retained |
| First cleanup real | Production lease | successful result wraps exact lease; delegates clear | no stub lease/result | `Cleared`; file absent |
| Second cleanup real | Same lease | second delegated clear | new lease/fake result forbidden | `AlreadyCleared`; still absent |
| Default provider unchanged | Screen + Android actual | no local provider; direct actual resolver path | no wrapper/default override | existing lifecycle test surface and behavior green |
| Public/platform isolation | Frozen declarations | declaration extraction + common import scan + iOS compile | scan alone insufficient | byte-for-byte public baseline; zero platform type in common/public |

## 7. Preflight gates before functional IG1

### A. Compile wiring preflight

Minimal skeleton accesses internal local/interface, remembers stable real editor/factory wrappers, compiles create/wrap/unwrap/dispose, and calls unchanged screen.

~~~bash
./gradlew :video-clip-editor-compose:compileAndroidDeviceTest --rerun-tasks
~~~

Compile PASS proves visibility only. It cannot satisfy proxy transparency.

### B. API-23 runtime proxy-transparency preflight

Focused `PreviewPortSurfaceTransparencyDeviceTest` uses repository fixture, production editor/factory, real lifecycle owner, recording wrapper as `activePort`, and `PlatformPreviewSurface(activePort, Modifier.testTag("ig1-surface-probe"))`.

~~~bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.PreviewPortSurfaceTransparencyDeviceTest --rerun-tasks
~~~

PASS requires:

- lifecycle `activePort` is exact recording wrapper;
- wrapper `surfacePort` is exact factory-created `AndroidMedia3PreviewPort`;
- resolver returns that identity;
- `ig1-surface-probe` exists, proving `ContentFrame` branch did not early-return;
- player reaches Ready and has non-null surface player;
- proxy records matching Released before lifecycle session close-entry;
- exact actual disposed once through real factory;
- null/self/nested/cycle/unrelated delegates resolve null and never expose surface node.

Any failure blocks functional IG1. Compile-only, resolver-unit-only, direct-port, screenshot-only, or fake lifecycle evidence cannot pass.

## 8. Revalidation, review, rollback, integration

Order:

1. Compile wiring preflight.
2. API-23 runtime transparency preflight.
3. Common proxy/event-order tests if created.
4. `./gradlew :video-clip-editor-compose:allTests :video-clip-editor-compose:iosSimulatorArm64Test`.
5. Non-empty byte-for-byte public declaration gate against `92f78412796113f2abe27f55be0125e9373c9f1c`; frozen core/Android/iOS declarations unchanged; common platform scan.
6. `ClipEditorLifecycleDeviceTest` on `ClipEditor_API23`/API 23 and `SM-S928B`/API 36.
7. Author-distinct V3 delta review: composition selection, common proxy interface, Android resolver/ContentFrame path, stable effects, lifecycle/event/disposal order, no-fake matrix, exact diff.
8. Functional IG1 RED/GREEN; then core Android regression.

Rollback: revert common local/interface, screen selection, Android resolver, and focused tests/recorders only. Direct Android surface path must return byte-for-byte to V3 behavior. Retain IG1 RED/report. IG1/V4/V5 stay blocked.

Integration: IG1-O1 preflights + review only authorize functional IG1; they do not complete it. API-23 full IG1 GREEN unlocks V4.

## 9. Backward and forward feasibility

Backward:

release-before-close criterion → lifecycle must collect proxy events → proxy must remain active lifecycle identity → surface consumer must transparently reach exact actual → bounded common delegate contract + Android resolver → runtime ContentFrame proof → stable editor/factory identities → real UI gestures and production session/export/cleanup hooks.

Forward:

production factory remembered → per-composition recording factory selected → lifecycle creates wrapper around exact actual → lifecycle collects `wrapper.events` → screen passes wrapper to platform surface → Android unwraps one hop → exact actual player reaches `ContentFrame` → UI gestures emit real commands → proxy `onEach` records Released before owner ack → owner closes wrapped real session → factory unwraps/disposes exact actual → real lease clears twice.

No owner conflict: lifecycle owns resource/order; common interface declares render target only; Android bridge owns platform resolution; recorders observe/delegate only.

## 10. Reconciliation history

| Trigger | Type/stage | Conflict | Affected | Preserved | Invalidated | Owner/decision | Exact rerun | Count/state | Freeze impact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| VUI-R10 | Evidence-owned, IG1 functional RED | Frozen screen lacked test-visible UI-owned factory selection | Screen factory selection, IG1 observability, V3 delta evidence | Outcome, public/core/iOS/deps, V1/V2/V3 lifecycle behavior | Existing-seam/test-files-only assumption | IG1-O1 owner: internal composition override + real-delegating proxy | Common screen/contract, device wrappers, declaration/common/iOS/lifecycle devices, IG1 | Rerun 1; unresolved | IG1/V4/V5 blocked |
| VUI-R10A | Materially new evidence-owned subtrigger, author-distinct review of VUI-R10 repair | Recording proxy breaks Android concrete surface cast; compile preflight false-positive; editor identity and matrix incomplete | Android surface bridge, proxy contract, stable composition, preflight, criterion matrix | VUI-R10 need for internal per-composition selection; real-delegation; public/default/lifecycle constraints; all unaffected V3 evidence | Claim wrapper alone is transparent; compile-only sufficiency; original “VUI-R10 resolved in blueprint”; inline editor wrapper; incomplete matrix | IG1-O1 owner: common `PreviewPortSurfaceDelegate`, one-hop Android resolver, stable remembered wrappers, runtime API23 preflight | Adds `AndroidPlatformPreview.kt`, focused transparency test, proxy/event tests; then same VUI-R10 affected reruns | First pass for materially new evidence; unresolved pending review/preflights | Original trigger remains open; module unscorable until >=95 PASS |

VUI-R10A does not reset VUI-R10. It preserves valid parts and invalidates false transparency. Same unresolved condition gets no third pass without materially new evidence.

## 11. Reusable Blueprint First wiring checklist

| Required field | Blocking question/evidence |
| --- | --- |
| Criterion | Exact observable value/order? |
| Production state owner | Named production owner, not recorder/fake? |
| Hook + visibility | Exact symbol/type/source set/test target? |
| No-fake identity | Exact production delegate/result/config identity required? |
| Proxy transparency | Does wrapper preserve protocol, identity expectations, concrete casts, rendering, disposal, and every downstream consumer? |
| Bounded unwrapping | Exact hop count; null/self/cycle/nested/unrelated behavior defined and tested? |
| Compile preflight | Exact command; visibility/type topology only? |
| Runtime consumer preflight | Real target proves downstream render/consumer path did not early-return? |
| Stable composition identity | Every `remember` key and every `LaunchedEffect` key reviewed; ordinary recomposition causes no restart? |
| Cleanup ownership | Exact create/release/event/close/dispose/clear order and owner? |

Gate: missing row blocks forward feasibility/module freeze. “Recorder observes events” and compile success alone are not evidence.
