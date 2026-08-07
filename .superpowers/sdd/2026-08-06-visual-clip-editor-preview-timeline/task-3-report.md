## V3 execution — 2026-08-07

Scope: `ClipEditorScreen.kt`, `ClipEditorPreviewCoordinator.kt`, common coordinator tests. No OneOnOneArena, core, public API, V1/V2 contract, dependency, or iOS actual edits.

Graph/SAN: standalone worktree had no graph nodes and no SAN matches. Raw-source fallback used.

RED: `./gradlew :video-clip-editor-compose:allTests` failed expected unresolved `ClipEditorPreviewCoordinator` in `ClipEditorPreviewCoordinatorTest.kt`.

GREEN:

```text
./gradlew :video-clip-editor-compose:iosSimulatorArm64Test
BUILD SUCCESSFUL in 6s

./gradlew :video-clip-editor-compose:allTests
BUILD SUCCESSFUL in 813ms
```

Device attempt: `adb devices` failed: `adb: failed to check server version: cannot connect to daemon`; `could not install *smartsocket* listener: Operation not permitted`.

Pending device command:

```text
./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest
```

Root device verification after handoff (connected `RZCX519T5FL` / `SM-S928B` / API 36): the exact command above, with `--rerun-tasks`, passed 6/6 on 2026-08-07. The earlier agent-side failure was the local ADB smartsocket sandbox boundary, not test or device failure.

Implementation: coordinator owns active preview binding/event filtering, gesture pause/seek, ready/failure control gate, retry revision, and Release acknowledgement fence with bounded fallback before session close. Screen order: fitted black preview surface; labels; selector; Back/Play-Pause/Done.

Commit: `1774142 feat(compose): wire clip preview and range controls`.

## Fix round 1 — 2026-08-07

Commit: `0501e57 fix(compose): fence preview lifecycle replacement`.

RED: `compileTestKotlinIosSimulatorArm64` failed for unresolved `replaceSourceThen`, `PreviewReleaseFence`, and `releaseFence` before minimal lifecycle repair.

GREEN: `./gradlew :video-clip-editor-compose:allTests :video-clip-editor-compose:iosSimulatorArm64Test` — BUILD SUCCESSFUL.

Root device verification after handoff: with `RZCX519T5FL` / `SM-S928B` / API 36, the exact `connectedAndroidDeviceTest` class command with `--rerun-tasks` passed 6/6. Agent-side ADB failure was the local sandbox smartsocket boundary.

## Fix round 2 — 2026-08-07

Commit: `24ec733 fix(compose): own terminal preview ports`.

Fresh common and iOS gates passed. Root device verification after the following one-line test visibility repair `fdfe8e6` passed 7/7 on `RZCX519T5FL` / `SM-S928B` / API 36 with the exact `connectedAndroidDeviceTest` class command and `--rerun-tasks`.

Root cause of `fdfe8e6`: the new expression-bodied JUnit test inferred internal `PreviewEvent` from its final `await` expression. Converting that test to block body returns public `Unit`; the device suite then compiled and ran green.

## V3 reviewer repair — 2026-08-07

Scope: V3 common screen/coordinator/presenter tests only. No core, public API, V1 selector contract, V2 actual, dependency, or iOS actual change.

Root cause: `ClipEditorScreen` generated preview generations with `source.value.hashCode()`, invoked `presenter.start()` before a previous binding had completed its release fence, and disposed the event collector from the composition-owned scope. The original coordinator silently swallowed release timeout and accepted only a partial stale-event regression set.

RED: `./gradlew :video-clip-editor-compose:compileTestKotlinIosSimulatorArm64` failed expected unresolved `replaceSourceThen`, `PreviewReleaseFence`, and `releaseFence`.

Repair:

- Coordinator now issues lifecycle-owned monotonic generations; never derives one from the source path.
- Source replacement releases the old binding, awaits matching `Released` or records `PreviewReleaseFence.Timeout`, then closes the old session before new `presenter.start()`/bind.
- Disposal uses one remembered non-composition cleanup scope. Order: release fence, session close, coordinator event collector disposal, cleanup-scope cancellation.
- Stale `Ready`, `Position`, `RecoverableFailure`, and unrelated `Released` do not mutate active state. Close is exact-once.
- Playhead state renders clamped to the visual range; provisional handle drag pauses preview and bounds an excluded playhead. One committed selector range causes one `ReplaceRange`.
- Done dispatches pause before canonical `createClip`; exporting disables Back and removes timeline controls. Ready layout order is fitted black preview, labels, selector, footer Back / Play-Pause / Done.

Regression coverage: stale all event types; source replacement release ordering; timeout record; exact-once close; canonical range passed to exporter; existing playhead seek/pause and one-ReplaceRange tests.

GREEN:

```text
./gradlew :video-clip-editor-compose:allTests :video-clip-editor-compose:iosSimulatorArm64Test
BUILD SUCCESSFUL in 5s
```

Device attempt: `adb devices` blocked by sandbox: `could not install *smartsocket* listener: Operation not permitted`; root must rerun the existing exact device command.

## V3 reviewer repair round 2 — 2026-08-07

Scope: V3 common lifecycle/screen/presenter plus necessary internal V2 Android/iOS preview seam and device coverage. No public API, core, dependency, or OneOnOneArena edits.

Root cause: Android `PreviewCommand.Release` makes `AndroidMedia3PreviewPort` terminal. The composition remembered/reused that port on source replacement and `AndroidPlatformPreview` auto-disposed it before the asynchronous V3 release/session fence. A detached cleanup launch could also start a presenter after a screen close. Done remained active while UI showed an uncommitted provisional handle range.

Repair:

- Added internal `PreviewPortFactory`, with Android/iOS actuals. Coordinator owns port creation/disposal; a replacement performs matching `Released`/recorded timeout, closes old session, disposes old actual port, then creates/binds a fresh port.
- Removed `AndroidPlatformPreview` automatic `DisposableEffect` disposal. Native disposal now occurs only after release fence + session close.
- Added `ClipEditorScreenLifecycle`: one mutex-authorized source/close path. Cancelled `LaunchedEffect` only requests work; it cannot reopen after lifecycle closure.
- Done disabled in UI and `ClipEditorPresenter.createClip()` inert while a provisional handle range exists. Committed gesture remains the sole canonical `ReplaceRange` point.

Regression coverage:

- Terminal-port source replacement verifies `Released -> session close -> old port disposal -> fresh port Bind`.
- Existing coordinator tests retain matching-vs-wrong-generation fence and timeout fallback coverage.
- Presenter test proves provisional export is inert and canonical range remains unchanged.
- Android device test proves actual port is terminal after `Release`; a new actual port can bind/ready.

RED: prior focused command used unsupported `--tests` on aggregate `allTests`; corrected required aggregate test command then exposed the existing source-replacement fake reuse failure (`ClipEditorPreviewCoordinatorTest.replacementReleasesBeforeOldSessionClosesAndNewBindingStarts[iosSimulatorArm64]`). Test converted to a terminal factory fake; it passed after fresh-port implementation.

GREEN:

```text
./gradlew :video-clip-editor-compose:allTests
BUILD SUCCESSFUL in 6s

./gradlew :video-clip-editor-compose:iosSimulatorArm64Test
BUILD SUCCESSFUL in 570ms
```

Device attempt:

```text
./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest
```

Blocked before compilation: `SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in .../video-clip-editor-visual-worktree/local.properties`.

## V3 device-test visibility fix — 2026-08-07

Root cause: `AndroidMedia3PreviewPortDeviceTest.releaseIsTerminalAndReplacementRequiresAFreshActualPort` was expression-bodied (`= runBlocking { ... }`). Its final nested `freshRecorder.await { it is PreviewEvent.Ready }` returns internal `PreviewEvent`; Kotlin inferred that as the public JUnit method return type and failed with `AndroidMedia3PreviewPortDeviceTest.kt:202:9 public function exposes its internal return type PreviewEvent`.

Diff: converted only that JUnit method to a block body with `runBlocking` inside it. The public method now returns `Unit`; test flow and assertions remain unchanged.

Verification attempt:

```text
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest --rerun-tasks
```

Blocked before compilation/device execution by sandboxed Gradle wrapper lock access:

```text
java.io.FileNotFoundException: /Users/sandeepdhami/.gradle/wrapper/dists/gradle-9.4.1-bin/arn2x92ynaizyzdaamcbpbhtj/gradle-9.4.1-bin.zip.lck (Operation not permitted)
```

## V3 Lifecycle Re-architecture Addendum — 2026-08-07

Exact blueprint/plan commit: `496bf2e docs: redesign V3 preview lifecycle`.

Status: rejected V3 and fix-round instructions superseded by one replacement V3 task. No production/test code changed.

Frozen correction:

- One serialized lifecycle owner owns close/replacement intents, close-wins latch, monotonic generation, port factory/current port, coroutine resources, release waiter/audit, and session ordering.
- Replacement order: terminal `Release` → matching `Released` or durable bounded timeout → old session close → old native port disposal → terminal recheck → fresh port create/Bind. No reuse or command after Release.
- `PlatformPreviewSurface` renders the supplied active port only. It has no disposal authority.
- Durable release audit survives fresh binding and contains generation/revision/outcome/reason/bounded code only; no host media path.
- Provisional range cannot export. Export disables/no-ops every control mutation; public ABI, core, dependencies, OneOnOneArena, and exposed iOS types remain unchanged.
- Completion requires terminal fake, wrong-ack-pending, timeout-then-fresh-bind, exact-order, replace-vs-close, live playhead/gesture, export-lockout, Android actual, API-23, and Samsung proofs.

Sequence preserved: replacement V3 → IG1 → V4 → V5.

Documentation verification: `git diff --check` passed; scope contained the blueprint and executable plan in commit `496bf2e`.

## V3 Blueprint First reconciliation repair — 2026-08-07

Prior review: commit `496bf2e` rejected at 86/100 under Agent Brain decision `dec_20260807_105759_77369c`. Documentation-only repair commit: `37ce269 docs: close V3 lifecycle blueprint gaps`.

Repair:

- Acknowledgement and timeout tests now assert the entire serialized sequence through audit/fence, old-session close, old-port disposal, fresh factory creation, fresh session start `(g2)`, and `Bind(g2)`. The close-race test separately requires zero fresh session starts, creates, and binds.
- Blueprint First reconciliation now records VUI-R7–R9: all three V3 rejection triggers, affected/preserved/invalidated findings, evidence/owner/decision, exact rerun scope/count, state, and module-freeze impact. Original V3 plus both fix rounds are explicitly superseded; V1/V2, public API, approved visual design, core, iOS scope, and IG1 → V4 → V5 remain preserved.
- Release audit now has a closed outcome: `Acknowledged` or `TimedOut(PreviewReleaseDiagnostic.ReleaseTimeout)`. No free-form `String?`/exception can enter the audit. Planned hostile-input tests cover absolute paths, file/content URIs, stack-shaped strings, exception class/messages, and `Throwable` values.
- Replacement V3 gate now extracts the actual working-tree public `ClipEditorScreen` and Android factory declarations and compares them with baseline `92f78412796113f2abe27f55be0125e9373c9f1c`; frozen common/iOS contract sources are compared directly. Filename/import scans are supplemental only.
- Replacement V3 route raised to Sol/high because one serialized owner crosses session, release timeout/acknowledgement, native-port disposal, composition teardown, and close-race concurrency after three rejected rounds. Below-floor execution blocks task start.

Verification:

```text
git diff --check
PASS

Declaration extraction/diff against 92f78412796113f2abe27f55be0125e9373c9f1c
PASS: ClipEditorScreen declaration non-empty and identical
PASS: createAndroidVideoClipEditor declaration non-empty and identical
PASS: frozen common/iOS contract sources identical
```

No production or test source changed. Replacement V3 remains blocked pending author-distinct principal review at >=95/100.

## Replacement V3 execution — 2026-08-07

Route: required Sol/high; child runtime identity was not externally verifiable, so route was reported as a plan limitation. No delegated implementation/review agent. Base and execution HEAD before edits: `37ce269a8631d026e726436be2b3ab130973e811`.

Scope: standalone `video-clip-editor` Compose internals and focused common/Android tests only. No public declaration, core, dependency, demo, OneOnOneArena, fixture, or exposed iOS type change. Added one test-only Android manifest entry for the existing `androidx.activity.ComponentActivity`; Compose 1.11.1 `runComposeUiTest` requires that launcher and the KMP device-test manifest did not merge it automatically. No dependency change.

Graph/SAN: standalone worktree graph contained 0 files/nodes and Agent Brain SAN query returned no match. Raw-source fallback used after both required attempts.

TDD RED evidence:

```text
./gradlew :video-clip-editor-compose:allTests
FAIL: unresolved ClipEditorLifecycleOwner, requestReplace/requestClose/releaseAudit,
      PreviewReleaseAudit/Outcome/Reason/Diagnostic

First minimum implementation:
FAIL: 3 iOS tests
- fresh Bind missing because reducer remained closing after old disposal
- play-from-outside-range Seek missing because bind prematurely clamped playhead

Export-lock RED:
FAIL: 1/36 iOS tests
- exportingLocksCoordinatorAgainstLateControlCallbacks
```

Root-cause repairs were bounded: owner explicitly prepares a fresh reducer only after terminal/factory authorization; initial binding preserves an outside-range source position so Play emits the required bounded Seek; the serialized owner locks coordinator interaction as soon as presenter state becomes `Exporting`.

Implementation:

- Added one independent `SupervisorJob` lifecycle scope and serialized `Channel` intent actor. `requestClose()`/`requestCancel()` synchronously CAS the terminal latch before enqueueing cleanup.
- Owner alone allocates monotonic generations, creates/disposes ports, collects events, owns the matching release waiter, sequences presenter session start/close, and self-cancels only after terminal teardown.
- Exact replacement order is `Release` -> matching `Released` or bounded timeout -> closed durable audit -> old session close -> old-port disposal -> terminal recheck -> fresh create -> fresh session start -> `Bind`.
- Wrong-generation acknowledgements cannot complete the waiter. Close during a suspended replacement produces exact-once old teardown and zero fresh create/start/bind. Terminal fake records zero commands after Release.
- `PreviewReleaseAudit` remains outside binding state and admits only `Acknowledged` or `TimedOut(ReleaseTimeout)` plus generation/revision/reason. Hostile path, file/content URI, stack-shaped, exception message, and `Throwable` inputs cannot enter its fields or `toString()`.
- Coordinator is scope-free reducer/command policy. `PlatformPreviewSurface(port)` stays render-only. Screen effects enqueue owner intents only; no composition-owned cleanup launch or scope cancellation remains.
- Provisional export stays inert. Exporting locks presenter and owner/coordinator control paths; terminal lifecycle close remains enabled.

Fresh common/iOS GREEN:

```text
./gradlew :video-clip-editor-compose:allTests \
  :video-clip-editor-compose:iosSimulatorArm64Test
BUILD SUCCESSFUL
36/36 iOS tests passed
```

Device test harness RED/repair:

```text
Initial compile: unavailable Compose helpers and private cross-file onMain
Repair: current Compose node APIs plus local main-thread helper

Initial Samsung runtime: Unable to resolve activity for:
Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER]
cmp=com.oneononearena.videoclip.compose.test/androidx.activity.ComponentActivity }
Root cause: packaged KMP device-test manifest omitted Compose UI test activity.
Repair: test-only ComponentActivity manifest declaration; no dependency/product change.
```

Focused Android GREEN, repository fixture SHA-256 `8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a`:

```text
ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk

Samsung RZCX519T5FL / SM-S928B / API 36:
:video-clip-editor-compose:connectedAndroidDeviceTest
AndroidMedia3PreviewPortDeviceTest + ClipEditorLifecycleDeviceTest
9/9 passed

emulator-5554 / ClipEditor_API23 / API 23:
:video-clip-editor-compose:connectedAndroidDeviceTest
AndroidMedia3PreviewPortDeviceTest + ClipEditorLifecycleDeviceTest
9/9 passed
```

Device proof: actual Release remains terminal against Bind/ReplaceRange/Retry/Seek/Play; owner observes matching Released, commits audit before close, disposes old actual, creates a distinct actual, starts generation 2, binds revision 1, and reaches Ready. UI device test proves live playhead movement from a real tagged timeline gesture, committed trim reaches export, and held export removes/disables Play/Done/Back mutation. API-23 emitted only the harmless `additionalTestOutput` support notice; no test/device limitation remains.

Declaration/isolation gate:

```text
ClipEditorScreen declaration vs 92f78412796113f2abe27f55be0125e9373c9f1c: identical, non-empty
createAndroidVideoClipEditor declaration vs baseline: identical, non-empty
Frozen common core/iOS contract sources vs baseline: identical
Forbidden Android/Media3/Apple imports in compose commonMain: none
git diff --check: PASS
```

Scoped replacement commit: `85cb583 fix(compose): serialize preview lifecycle ownership`. Cached scope contained only the approved Compose common/commonTest/androidDeviceTest lifecycle files plus the required test-only Android manifest. Worktree/branch preserved for the next author-distinct gate; no review dispatch performed.

## Replacement V3 lifecycle review repair — 2026-08-07

Scope: V3 lifecycle common source/tests and this task report only. No public/core/dependency/demo/OneOnOneArena/device-test source change.

Root causes:

- `PresenterStateChanged` carried no owning generation. A g1 `Ready` collected and queued while replacement awaited g1 `Released` was later dequeued after g2 became active; dequeue-time `active` lookup could bind the fresh g2 port with stale g1 metadata/range and mutate fresh coordinator state.
- Export interaction locking depended on asynchronous presenter `StateFlow` collection. `createClip()` entered `Exporting`, then returned while immediate control callbacks could still dispatch preview commands and mutate coordinator state before the owner actor observed `Exporting`.

TDD RED, independently run with the exact aggregate target:

```text
./gradlew :video-clip-editor-compose:allTests
FAIL: 2 intended failures in the 37-test common/iOS suite
- ClipEditorLifecycleOwnerTest.oldReadyQueuedDuringReleaseCannotBindOrMutateFreshLifecycle
- ClipEditorLifecycleOwnerTest.exportTransitionSynchronouslyLocksImmediateControlCallbacks
```

Repair:

- Every collected presenter state now enters the owner channel with the immutable `active?.generation` captured at collection/enqueue. The actor rejects a generation mismatch before handling any presenter state, including `Ready` and `Exporting`.
- The lifecycle owner injects its coordinator lock as the presenter's synchronous export-transition boundary. Eligibility remains presenter-owned; once export is accepted, the lock executes before `Exporting` publication and before the `createClip` coroutine can start.
- Deterministic stale-Ready coverage queues g1 range/metadata during the release fence, acknowledges g1, starts and binds g2, drains the old event, and proves exactly one fresh binding plus unchanged fresh playhead/coordinator state.
- Immediate export coverage calls all coordinator controls directly after `createClip()` with no `runCurrent`; commands and coordinator state remain byte-for-byte unchanged.

Fresh GREEN, independently run:

```text
./gradlew :video-clip-editor-compose:allTests
BUILD SUCCESSFUL

./gradlew :video-clip-editor-compose:iosSimulatorArm64Test
BUILD SUCCESSFUL

37/37 tests passed.
```

Post-commit device evidence:

- API-23: root retained the final raw log from `ClipEditor_API23` / API 23; both focused classes passed 9/9 with `--rerun-tasks`.
- Samsung: `RZCX519T5FL` detached before the required retained rerun. Samsung evidence remains pending; this repair makes no final Samsung pass claim.

Exact device commands:

```text
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest,com.oneononearena.videoclip.compose.ClipEditorLifecycleDeviceTest --rerun-tasks

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest,com.oneononearena.videoclip.compose.ClipEditorLifecycleDeviceTest --rerun-tasks
```

Review evidence must identify `ClipEditor_API23` / API 23 and Samsung `SM-S928B` / API 36, retain repository fixture SHA-256 `8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a`, show both focused classes/test counts, and preserve the terminal Release → audit → old-session close → old-port disposal → distinct fresh create/Bind/Ready plus live gesture/export-lockout assertions.

Commit: `692b4f9 fix(compose): fence stale lifecycle events`.

## V3 Android lifecycle UI harness repair — 2026-08-07

Scope: `ClipEditorLifecycleDeviceTest` test harness, retained raw logs, and this report only. No product/common/core/dependency/public/iOS source or test-manifest change.

Root cause, accepted under Agent Brain decision `dec_20260807_124649_04e934`: Samsung API 36 failed after the UI assertions completed because Compose v2 `runComposeUiTest` owned the composition. Test-environment teardown cancelled Media3 `ContentFrame`'s `PlayerExtensions.listenImpl` effect through the instrumentation `TestDispatcher`; its `finally` called `ExoPlayer.removeListener` on `Instr: androidx.test.runner.AndroidJUnitRunner`. `AndroidMedia3PreviewPort` construction, dispatch, listener callbacks, and native release were already main-thread fenced. Direct actual-port device tests proved that path. API 23 was timing/platform masking, not evidence for a product fix.

Harness repair:

- `createAndroidComposeRule` is absent from the resolved `ui-test-android:1.11.1` compile surface, and adding `ui-test-junit4` was outside the dependency freeze. `runAndroidComposeUiTest` was also rejected because its implementation installs the same test `WindowRecomposerFactory`.
- Existing test-manifest `androidx.activity.ComponentActivity` is launched explicitly through `ActivityScenario<Activity>`; no new Activity or manifest entry.
- `runEmptyComposeUiTest` owns observation and input only. Once its root registry is active, the Activity attaches `ComposeView` inside `WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware)`. Effects therefore run and cancel on the normal lifecycle-aware Activity main recomposer, while every existing semantics, gesture, live-playhead, range, and export-lock assertion remains real.
- `ComposeView.disposeComposition()` executes through `ActivityScenario.onActivity` before `ActivityScenario.close()`, guaranteeing composition/effect cleanup before Activity teardown.
- Readiness polling now waits for the existing `play-pause` enabled assertion, not mere node existence, because a real Activity recomposer exposes the legitimate pre-`Ready` disabled frame.
- Samsung repetition diagnosis found a separate harness condition: ExoPlayer and both AVC/AAC codecs remained live with no `PlaybackException`, but `dumpsys power` reported `mWakefulness=Dozing` and `mDreamingLockscreen=true`; Activity logged `RESUMED` then immediate `PAUSED`. Test-only compatible window flags keep the Activity awake/visible. No assertion or timeout was weakened.

Focused Samsung command, run three consecutive times against final code with one raw log per run:

```text
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.ClipEditorLifecycleDeviceTest#screenShowsLivePlayheadGestureAndLocksControlsDuringExport' \
  --rerun-tasks --console=plain --no-daemon -Dorg.gradle.jvmargs=-Xmx4g
```

Results: `SM-S928B` / API 36, `1/1` passed for run 1, run 2, and run 3; every run `BUILD SUCCESSFUL`. Raw logs:

- `device-logs/v3-samsung-screen-activity-harness-pass-r1.log`
- `device-logs/v3-samsung-screen-activity-harness-pass-r2.log`
- `device-logs/v3-samsung-screen-activity-harness-pass-r3.log`

Full two-class device command (serial changed per target):

```text
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=<serial> \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest,com.oneononearena.videoclip.compose.ClipEditorLifecycleDeviceTest' \
  --rerun-tasks --console=plain --no-daemon -Dorg.gradle.jvmargs=-Xmx4g
```

Results:

- Samsung `RZCX519T5FL` / `SM-S928B` / API 36: `9/9` passed, `BUILD SUCCESSFUL`; retained `device-logs/v3-samsung-activity-harness-full-9.log`.
- `emulator-5554` / `ClipEditor_API23` / API 23: `9/9` passed, `BUILD SUCCESSFUL`; retained `device-logs/v3-api23-activity-harness-full-9.log`. Only known harmless `additionalTestOutput` API-23 notice.

Common/iOS guard:

```text
./gradlew :video-clip-editor-compose:allTests \
  :video-clip-editor-compose:iosSimulatorArm64Test \
  --console=plain --no-daemon -Dorg.gradle.jvmargs=-Xmx4g
```

Result: `BUILD SUCCESSFUL`; retained `device-logs/v3-activity-harness-common-ios.log`. Existing common-test opt-in warnings unchanged.

Commit: `test(compose): run lifecycle UI on activity main` (this scoped commit).
