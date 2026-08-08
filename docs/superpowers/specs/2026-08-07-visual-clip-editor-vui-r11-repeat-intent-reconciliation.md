# VUI-R11 Android Repeat-Intent Reconciliation

**Status:** `PLAN_FROZEN`. Author-distinct Full review passed at 100/100 with no Critical/Important findings (`fb_20260808_154041_279458`). Only the bounded VUI-R11 implementation and proof matrix may now proceed.

**Full evidence manifest:** `docs/superpowers/evidence/2026-08-08-vui-r11-repeat-intent.manifest.json` (schema v1, immutable baseline `eb0690162405c25f8962eb17364116ce9afbab1c`, evidence digest `815ddf933f48b1f59a8097aab19296176fa553bb2ecfb37153872e7f96b8c73b`).

**Decision records:** diagnosis `dec_20260807_192317_532e5c`; rejected blueprint repair `dec_20260807_193521_620060`; rejected review feedback `fb_20260807_194642_fcc9d0`; rejected second docs-only repair `dec_20260807_231947_2f75f6`; Full-route upgrade `dec_20260808_151643_2cd35b`.

**Trigger type:** evidence-owned. Not user requirement change. Not generic device/decoder/fixture issue.

## 1. Observable outcome

Actor: user presses Play in real Android `ClipEditorScreen` after selected clip binds paused.

End state: selected clip remains continuously playing across first real Media3 `REPEAT_MODE_ONE` wrap. Transient BUFFERING may emit `isPlaying=false`; recovery READY must restore latest accepted same-generation/revision playback intent and emit a low/near-start `Position(..., isPlaying=true)`.

Exclusions:

- No public/core/common contract/iOS/dependency/Media3-version/V3 lifecycle/O1/host/OneOnOneArena change.
- No range/source/generation/revision mutation from play/pause.
- No stale/mismatched/preparing command acceptance.
- No UI polling or manual end-seek loop.
- No fake, relaxed predicate, player-only assertion, or `REPEAT_MODE_ONE` configuration-only proof.

## 2. Confirmed evidence and cause

Strict API23 IG1 RED:

- selected range/config correct;
- `REPEAT_MODE_ONE` correct;
- high `Position(isPlaying=true)` near selected end;
- AUTO_TRANSITION to low/start, BUFFERING with `playWhenReady=true/isPlaying=false`;
- READY 24–38 ms later with `playWhenReady=false/isPlaying=false`;
- no later low/start playing event in 45 seconds;
- no `RecoverableFailure`.

Direct same-port tests reproduced for equal-length source-end and interior clips. Both transitioned clip position `2000→0`, BUFFERING true/false, then READY false/false. Source-end metadata overrun did not discriminate.

Single-variable discriminator: same real port/range, only binding-stored `playWhenReady=false→true`. At repeat: BUFFERING true/false, then 41 ms later READY true/true and `Position(7.061s, true)`. Code cause confirmed.

Source:

~~~kotlin
private fun setPlayWhenReady(command: PreviewCommand.SetPlayWhenReady) {
    val binding = matchingReadyBinding(command.generation, command.revision) ?: return
    player?.playWhenReady = command.value
    emitPosition(binding)
    updateTicker()
}

// Every READY, including repeat recovery:
player?.playWhenReady = binding.playWhenReady
~~~

`SetPlayWhenReady` mutates live Player only. `appliedBinding.playWhenReady` remains initial false. Repeat READY replays stale false. Media3 repeat succeeds; adapter silently converts Play to Pause.

Evidence: `.superpowers/sdd/2026-08-06-visual-clip-editor-preview-timeline/task-4-report.md` Phase 1–3 and `.superpowers/logs/ig1-loop-diagnostic-*`.

### Architecture evidence coverage

The standalone worktree's code-review graph currently reports zero communities and zero matching nodes for the VUI-R11 adapter/screen/test symbols. Source-linked Agent Brain has 167 files for the registered parent Android repository, but `get_san` returned `No SAN file` for the relevant standalone `ClipEditorScreen.kt`, `ClipEditorPreviewCoordinator.kt`, `ClipEditorLifecycleOwner.kt`, and `VideoClipEditorContract.kt`. Targeted raw-source reads therefore supplied the exact fallback evidence for this standalone worktree. This zero standalone graph/SAN coverage is recorded evidence limitation, not an executable oracle and not a reason to weaken device/integration proof.

## 3. Scope, responsibility, interfaces, dependencies

### Scope

Only:

- modify `video-clip-editor-compose/src/androidMain/kotlin/com/oneononearena/videoclip/compose/AndroidMedia3PreviewPort.kt`;
- modify focused `video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/AndroidMedia3PreviewPortDeviceTest.kt`;
- after the pre-fix diagnostic log and reconciliation evidence below are preserved, delete only the temporary diagnostic test method `diagnostic_sourceEndAndInteriorClipsExposeFirstRepeatPlayerState` from the existing untracked `video-clip-editor-compose/src/androidDeviceTest/kotlin/com/oneononearena/videoclip/compose/ClipEditorScreenIntegrationTest.kt`; and
- rerun the semantically unchanged strict method `editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose` after every other code/test gate is complete.

The canonical repair remains the two-file production/focused-test scope above. The IG1-file change is a third, test-only disposition edit: deletion of that one temporary diagnostic method. No helper, recorder, strict assertion, predicate, timeout, gesture, production seam, or other test byte is authorized. No other production/test source change is authorized by VUI-R11.

### Diagnostic-test lifecycle and disposition

`diagnostic_sourceEndAndInteriorClipsExposeFirstRepeatPlayerState` is pre-fix diagnosis, not enduring acceptance. Its `assertEquals(null, endResume)` and `assertEquals(null, interiorResume)` encode the defect. A correct authoritative-intent repair makes those assertions fail. It therefore cannot remain in a post-fix full-class acceptance gate.

Before deleting that method, preserve all of the following as immutable pre-fix evidence: the original strict IG1 RED log, both source-end/interior direct traces, the stored-intent discriminator trace, the exact diagnostic source/diff, and reconciliation decision `dec_20260807_192317_532e5c`. Record paths/hashes in the implementation report. Only then delete the diagnostic method. Deletion does not hide or relax the original strict RED; the strict functional method remains semantically unchanged and is selected directly at the final gate. The focused V2 adapter class is also selected and run separately; the whole IG1 class is never the VUI-R11 acceptance command.

### Responsibility

`AndroidMedia3PreviewPort` is authoritative owner of:

- applied generation/revision/source/range/position snapshot;
- latest accepted playback intent for that exact applied binding;
- live Media3 Player intent mirroring;
- READY reapplication after preparation/repeat recovery.

Player remains execution mechanism, not sole authoritative intent store.

### Interfaces

No interface/declaration change. Existing:

~~~kotlin
data class SetPlayWhenReady(
    val generation: PreviewGeneration,
    val revision: PreviewRevision,
    val value: Boolean,
) : PreviewCommand
~~~

Minimal semantic patch shape:

~~~kotlin
private fun setPlayWhenReady(command: PreviewCommand.SetPlayWhenReady) {
    val binding = matchingReadyBinding(command.generation, command.revision) ?: return
    val bindingWithIntent = binding.copy(playWhenReady = command.value)
    appliedBinding = bindingWithIntent
    player?.playWhenReady = command.value
    emitPosition(bindingWithIntent)
    updateTicker()
}
~~~

Implementation may factor a private helper, but semantics must remain identical.

### Dependencies

Existing Media3 1.10.1, Android main thread, frozen `PreviewBinding`/commands/events, accepted V1–V3 and O1 commit `eb06901`. No new dependency.

### Full route, immutable baseline, and planned mutable scope

Full is mandatory: Android device behavior, Media3 lifecycle/callback concurrency, four state owners on the coordinator → `appliedBinding` → Player READY → screen/export/cleanup causal path, cross-boundary integration, and no existing deterministic full oracle. Route is process depth, independent of model tier.

The manifest freezes pre-code git `eb0690162405c25f8962eb17364116ce9afbab1c` plus SHA-256 values for the public `ClipEditorScreen` file, public/core contract, `FeasibilityMarker`, Android public factory, and iOS factory. Those five protected files are byte-identical to the baseline. The planned mutable scope is recorded separately and is not a public-contract waiver:

1. `AndroidMedia3PreviewPort.kt`: one accepted-intent ownership correction only.
2. `AndroidMedia3PreviewPortDeviceTest.kt`: four named focused direct tests only.
3. `ClipEditorScreenIntegrationTest.kt`: after archival, delete only the defect-asserting diagnostic. The strict method extractor digest remains `a7d9fc1fac78e9795211d40cb6602ebe9e36e7607def5b96f460572b201b1c4a`.

Any protected baseline drift marks affected evidence `STALE` and returns to `ARCHITECTURE_APPROVED` review. `RecordingClipEditorSession.kt`, public/common/core/iOS, M3, V3, O1, dependencies, and all other production/test files remain out of scope.

## 4. State invariant

For current applied binding `B=(generation, revision, source, metadata, range, sourcePosition, playWhenReady)`:

1. Accepted `SetPlayWhenReady(g, r, value)` requires `matchingReadyBinding(g, r)`.
2. Before any later READY can read intent, adapter atomically on main thread replaces only `B.playWhenReady` with `value` and mirrors `player.playWhenReady=value`.
3. `generation`, `revision`, `source`, `metadata`, `range`, and `sourcePosition` remain unchanged.
4. Stale generation, stale revision, wrong media item, terminal state, or preparing state performs zero mutation to applied intent and Player.
5. `Bind`, `ReplaceRange`, and `Retry` retain their explicit incoming `binding.playWhenReady`; prior binding live intent never leaks into a new accepted binding.
6. Pending/newer binding ordering remains unchanged. `SetPlayWhenReady` never edits `pendingBinding`.
7. READY may occur multiple times for same item/revision. Each READY reapplies latest authoritative applied intent, not original intent.
8. Release remains terminal; no post-release intent change.

## 5. Acceptance criteria

- Initial binding `playWhenReady=false` reaches Ready paused.
- Matching accepted Set true updates both authoritative applied intent and live player before repeat.
- One real Media3 wrap produces same-generation/revision high playing → low/start transient false → later low/start `Position(isPlaying=true)`; no failure.
- Matching accepted Set false updates authoritative intent symmetrically; later READY cannot resurrect Play.
- Stale generation/revision Set causes no Player or authoritative mutation.
- Set during preparation causes no Player or authoritative mutation.
- Newer ReplaceRange explicit false overrides prior accepted true.
- Newer Retry explicit true/false is honored exactly; prior live intent does not leak.
- Range/source/mediaId/clipping/generation/revision unchanged by Set.
- Existing stale Retry, source-position bounds, failure sanitization, release terminality, surface, and factory behavior remain green.
- Strict IG1 loop predicate remains unchanged; relaxed diagnostic cannot pass.
- Pre-fix diagnostic evidence is archived before its defect-asserting method is deleted; the exact strict functional method remains semantically unchanged.

## 6. Exact RED tests

Add to `AndroidMedia3PreviewPortDeviceTest.kt` before production edit.

### A. Accepted play survives first real wrap

`acceptedPlayIntentSurvivesFirstRealRepeat`:

1. Bind real fixture interior clip `2s..<4s`, `playWhenReady=false`.
2. Await matching Ready; assert paused.
3. Dispatch matching `SetPlayWhenReady(true)`.
4. Require same-generation/revision `Position(isPlaying=true)` near end (`>=3.6s`).
5. Require low/start transient `Position(isPlaying=false)` (`<=2.4s`) after high.
6. Require later low/start `Position(isPlaying=true)` (`<=2.4s`) after transient within bounded event timeout.
7. Assert no `RecoverableFailure`, same item/range/config, `REPEAT_MODE_ONE`.

This prevents player-only fix: without updating authoritative applied intent, real repeat READY fails exact step 6.

### B. Rejected commands mutate nothing

`staleAndPreparingPlayCommandsCannotMutateAppliedIntent`:

- while binding prepares, matching Set true is ignored; first Ready remains explicit binding false;
- with ready binding explicit true, stale generation/revision Set false leaves Player true;
- after real READY re-entry/wrap, latest valid intent remains true;
- item id, clip config, range, generation/revision unchanged.

### C. Replacement/retry binding intent wins

`replacementAndRetryUseExplicitBindingPlaybackIntent`:

- start ready false; accepted Set true;
- ReplaceRange with newer revision and explicit false → Ready false;
- Retry with next newer revision and explicit true → Ready true;
- stale old Set cannot change either transition;
- exact source/range/revision from each new binding retained.

### D. Accepted pause remains authoritative

`acceptedPauseCannotBeOverwrittenByLaterReady`:

- begin binding explicit true or accepted Set true;
- matching Set false;
- induce/observe later READY for same item through real Media3 state transition;
- assert Player false and no later `Position(isPlaying=true)` until new accepted Set true/new binding.

## 7. Test and integration gates

No Kotlin/test edit before a new author-distinct review advances this Full plan from `ARCHITECTURE_APPROVED` to `PLAN_FROZEN`. After `PLAN_FROZEN`, the only pre-code/edit sequence is:

1. **Archive before mutation:** preserve the original strict IG1 RED, source-end/interior traces, stored-intent discriminator, exact diagnostic source and working-tree diff; record every source/evidence SHA-256 and the pre-code adapter/focused-test hashes in the evidence index.
2. **Write tests, then prove RED:** add all four named focused adapter tests. Run their exact selected methods against the real port on API23 and retain the defect-specific RED. Tests cannot run before they exist.
3. **Retire only the defect diagnostic:** after step 1 archival, delete only `diagnostic_sourceEndAndInteriorClipsExposeFirstRepeatPlayerState`. Run the strict-method extractor/hash guard and require `a7d9fc1fac78e9795211d40cb6602ebe9e36e7607def5b96f460572b201b1c4a`; no helper, recorder, strict assertion, predicate, timeout, gesture, or other byte may change.
4. **Minimal fix plus early vertical GREEN:** apply the one-field V2 adapter patch, then run `AndroidMedia3PreviewPortDeviceTest#acceptedPlayIntentSurvivesFirstRealRepeat` on API23 against the direct real port. This is `EVP-API23-DIRECT-REAL-PORT-WRAP`: high playing → low transient false → later low playing true. Finish every authorized production/test edit here.

Then run exactly one normative post-edit sequence. No plan section or report may reorder it:

1. **Direct adapter gate:** full `AndroidMedia3PreviewPortDeviceTest` on API23, then Samsung, including real-wrap, stale/preparing, replacement/retry precedence, pause, failure, release, surface, and factory coverage.
2. **Regression/compatibility gate:** core host regression; core connected-device regression on API23 and Samsung; affected V3 `ClipEditorLifecycleDeviceTest` smoke on API23 and Samsung; Compose common/iOS suites; non-empty byte-for-byte declaration/frozen-contract guard and scope scan.
3. **Author-distinct code review:** no Critical/Important finding and score >=95/100 against the exact post-regression diff/evidence. Any requested code/test correction returns to sequence step 1.
4. **Strict functional IG1 gate LAST:** select only `ClipEditorScreenIntegrationTest#editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose`, once on API23 and once on Samsung. This paired target execution is the one VUI-R11 strict acceptance gate. Never run the whole IG1 class as this gate.

After both strict passes, only evidence/report/docs changes are permitted. Any production or test source change invalidates the strict evidence and requires the complete sequence to run again; it does not create a new reconciliation trigger or reset VUI-R11's rerun count. Samsung unavailable is a completion blocker: no single-device waiver, substitution, relaxed evidence, or partial VUI-R11 acceptance.

The integration record is explicit: early vertical proof is the API23 direct real-port wrap in pre-code/edit step 4; final integration is the exact strict UI flow on API23 then Samsung after Canonical 1–3. Unit/fake/configuration evidence cannot substitute for either boundary proof.

Device commands:

~~~bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest --rerun-tasks

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.AndroidMedia3PreviewPortDeviceTest --rerun-tasks
~~~

Run the core connected-device suite and exact V3 lifecycle class during canonical sequence step 2, before review and strict IG1. Do not run the full IG1 class.

Exact final strict method commands, run only after direct/regression/review gates:

~~~bash
env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=emulator-5554 \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.ClipEditorScreenIntegrationTest#editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose --rerun-tasks

env ANDROID_HOME=/Users/sandeepdhami/Library/Android/sdk ANDROID_SERIAL=RZCX519T5FL \
  ./gradlew :video-clip-editor-compose:connectedAndroidDeviceTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.oneononearena.videoclip.compose.ClipEditorScreenIntegrationTest#editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose --rerun-tasks
~~~

Host/declaration guard:

~~~bash
./gradlew :video-clip-editor-core:allTests \
  :video-clip-editor-compose:allTests \
  :video-clip-editor-compose:iosSimulatorArm64Test
~~~

Run plan Task 3 Step 5 non-empty byte-for-byte declaration comparison against `92f78412796113f2abe27f55be0125e9373c9f1c`; common platform scan; no core/iOS/dependency/OneOnOneArena diff.

No fake, player-property-only, paused binding, configuration-only, source-end-only, single-device-only, relaxed IG1, or arbitrary sleep evidence passes.

## 8. Rollback and integration

Rollback: revert only authoritative-intent mutation in `AndroidMedia3PreviewPort.kt` and new focused direct tests. Restore the deleted temporary diagnostic method only when further pre-fix diagnosis is explicitly required; it must never be treated as enduring acceptance. Restore strict RED/block. Do not change range/repeat implementation, V3/O1, public/common/core/iOS/dependencies, or weaken IG1.

Integration: VUI-R11 repairs V2 adapter only. Direct V2 GREEN precedes strict IG1. IG1 downstream export/release/cleanup evidence is accepted only after strict continuous loop passes. V4/V5 remain blocked.

## 9. Backward and forward feasibility

### Backward necessary-condition pass

| Outcome/criterion | Directly necessary predecessor | Causal reason | Evidence | Owner | Stop condition |
| --- | --- | --- | --- | --- | --- |
| Continuous selected-range loop | Low/near-start `Position(isPlaying=true)` after first repeat READY | Configuration alone is insufficient if READY restores Pause | Strict IG1 high-playing → low false → no resumed playing | Android adapter | Exact strict method fails on either target |
| Repeat READY resumes Play | READY reads latest accepted applied intent `true` | READY assigns Player intent from `appliedBinding` | Source-end/interior probes show READY false/false with stored false; discriminator shows READY true/true 41 ms later with stored true | `AndroidMedia3PreviewPort.appliedBinding` | Any READY reads stale original intent |
| Applied intent is current | Matching ready `SetPlayWhenReady(value)` copies only `playWhenReady=value` before Player mutation | Later callbacks need persisted accepted intent, not transient Player state | Root-cause source trace of `setPlayWhenReady` and listener READY assignment | Android adapter | Identity/range/source/revision changes or copy occurs after Player mutation |
| Binding isolation | Stale/wrong/preparing/released commands mutate neither stored intent nor Player; ReplaceRange/Retry snapshots win | Old intent must not cross revision/lifecycle fences | `matchingReadyBinding` discriminator plus focused RED matrix | Android adapter test owner | Any rejected command or old intent affects later Ready |
| Enduring acceptance | Real adapter test and semantically unchanged strict method pass independently on API23/Samsung | Temporary defect diagnostics cannot be acceptance tests | Review feedback `fb_20260807_194642_fcc9d0`; current null-resume assertions | QA/integration owner | Diagnostic evidence missing, strict predicate changed, full IG1 class substituted, or Samsung unavailable |

Persisted accepted intent is therefore a necessary predecessor alongside source clipping and `REPEAT_MODE_ONE`; the old claim that clipping + repeat alone was sufficient is invalidated for continuous playback.

### Forward feasibility pass

| State owner | Input | Transition | Desired output | Failure/recovery route | Verification | Unresolved dependency |
| --- | --- | --- | --- | --- | --- | --- |
| `AndroidMedia3PreviewPort.appliedBinding` | Matching ready `SetPlayWhenReady(true)` | Copy only `playWhenReady=true` into applied snapshot before mirroring Player; do not touch `pendingBinding` | Persisted accepted intent `true` for same generation/revision/source/range | Stale/wrong/preparing/released input is ignored with zero mutation | Focused stored-intent/isolation tests | None after direct green |
| Media3 Player | Updated applied intent + same clipped item | Mirror `player.playWhenReady=true`; play toward selected end | High in-range `Position(true)` | Player error routes to existing sanitized `RecoverableFailure`; no public/output change | Full direct class on both targets | Device availability |
| Media3 repeat transition | AUTO_TRANSITION/BUFFERING then `STATE_READY` | Listener reads updated `appliedBinding.playWhenReady=true` and reapplies it | Low/near-start `Position(true)` within same clipped period | **Known stale route:** stored false yields READY false/false and pause at start; strict test fails. Recovery is applied-intent synchronization, never manual seek/polling | Root-cause traces + real-wrap test + final strict exact method | Samsung required |
| `AndroidMedia3PreviewPort.appliedBinding` | Matching ready `SetPlayWhenReady(false)` | Copy only false, then mirror Player | Later READY remains paused; no unexpected `Position(true)` | Rejected/stale input zero mutation | Accepted-pause test | None |
| Binding state machine | `ReplaceRange`/`Retry` complete snapshot | Install explicit new binding intent under existing revision rules | New snapshot intent wins; no prior live intent leak | Stale old Set ignored | Replacement/retry precedence test | None |

No contradictory owner: adapter owns persisted accepted intent, Player executes it, coordinator issues commands, and V3 lifecycle ownership remains unchanged.

## 10. Reconciliation record

| Trigger | Type/stage | Conflict | Affected findings | Preserved findings | Invalidated findings | Owner/decision | Exact rerun scope | Count/state | Freeze impact |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| VUI-R11 | Evidence-owned, functional IG1 strict loop RED after accepted O1 | Player live true diverges from stale applied binding false; repeat READY silently restores false; first docs also made defect diagnostic an impossible enduring gate and contradicted strict-run order | V2 playback-intent state ownership; continuous-loop acceptance; V2 direct loop coverage; strict IG1 loop; diagnostic disposition and gate ordering | V1 selector/common port contract; V2 clipping/config/repeat mode/failure/release/stale Retry behavior; accepted V3 lifecycle; accepted O1 transparency; public/core/iOS/deps; original strict no-fake RED and archived diagnostic evidence | Claim clipping + `REPEAT_MODE_ONE` or config/paused tests prove continuous loop; immutable original binding intent; source-end/fixture/decoder/generic-device cause; player-only fix; defect-asserting diagnostic as enduring acceptance; strict-before-regression ordering | V2 adapter owner; persist matching accepted intent; archive → write tests/RED → delete only archived diagnostic → minimal fix/early GREEN → canonical final sequence; author-distinct Full review 100/100 | Original two repair files plus one test-only diagnostic deletion; direct full adapter class API23/Samsung → core/V3/common/iOS/declaration → code review → exact strict method API23/Samsung last | **Count remains 1.** `PLAN_FROZEN` at `fb_20260808_154041_279458`; execution may start | Reopens only V2 playback-intent/loop gate. V1/V3/O1 remain accepted; IG1/V4/V5 blocked |

One affected reconciliation rerun only. This second docs repair preserves VUI-R11 count `1`; it does not create or reset a trigger. Same unresolved trigger cannot get a third analysis pass without materially new evidence. Samsung variation is validation, not alternate root cause unless new contrary evidence appears.

### Approval-state mapping

- `ARCHITECTURE_APPROVED` — completed. Outcome, causal ownership, bounded scope, baseline, and future proof paths were feasible.
- `PLAN_FROZEN` — current. Author-distinct Full review passed at 100/100; all critical rows retain named executable/future executor paths and no critical `BLOCKED`, `STALE`, or `ASSUMPTION` row.
- `TASK_PROVEN` — requires the bounded implementation, focused RED/GREEN, early vertical API23 proof, and Canonical 1–3 evidence.
- `INTEGRATION_PROVEN` — requires the exact strict functional method API23 then Samsung last, with no later source/test edit.
- `DELIVERY_READY` — remains downstream of IG1/V4/V5 completion.

Accepted V1–V3/O1 history remains historical evidence. The updated Blueprint First skill governs pending/future VUI-R11 work; author-distinct review independently promoted only VUI-R11 to `PLAN_FROZEN`.

## 11. Generic Blueprint First prevention checklist

- [ ] Select `Direct`, `Lite`, or `Full` from exact predicates before choosing plan depth; record every Full hard trigger.
- [ ] Create and validate the route evidence manifest against the active workspace before plan review and after any baseline change.
- [ ] Freeze immutable git/file/contract baselines plus a reproducible evidence digest; record planned mutable paths separately.
- [ ] Give every critical claim a real named executable oracle or named future executor path; prose/readiness scores never substitute.
- [ ] Map requirement → invariant/contract → task → oracle → evidence → integration result → residual risk.
- [ ] Name the first compatible producer/consumer early vertical proof and keep it separate from the final integration gate.
- [ ] Classify every new test before module freeze: pre-fix diagnostic proof, enduring acceptance, regression, or evidence-only helper.
- [ ] For each pre-fix diagnostic, record evidence-retention location/hash and explicit post-fix disposition: delete, or convert to a non-acceptance diagnostic. Never include a defect-asserting diagnostic in an enduring full-class acceptance command.
- [ ] Preserve strict original RED semantics. Diagnostic removal/conversion cannot relax, replace, or hide the strict acceptance predicate.
- [ ] Publish one canonical gate order in one normative section; all plans/reports reference it instead of restating a different sequence.
- [ ] Order once: archive evidence/source hash/diff → write focused tests and run RED → retire only the archived defect diagnostic → minimal fix and early vertical GREEN → full direct gates → regression/compatibility → author-distinct review → exact strict final gate.
- [ ] Put one-time/high-cost acceptance runs last after all code/test edits, regressions, compatibility guards, and code review.
- [ ] Declare invalidation: any later production/test change voids one-time-run evidence and requires the canonical sequence again. Evidence/docs-only changes do not.
- [ ] Treat every required target as a gate. Unavailable required hardware is a completion blocker, not a waiver.
- [ ] Reconciliation fixes for the same evidence trigger retain its ID/count; wording or test disposition never resets analysis history.

## 12. Residual risks

- Media3 callback timing differs by device; event ordering predicates must be state-based with bounded timeout, not millisecond equality.
- Accepted Set false later-READY induction may need a deterministic real-player transition; test must not mutate production state through a fake callback.
- Updating `appliedBinding` changes object snapshot used by emit/ticker; tests must prove only `playWhenReady` changes.
- Samsung unavailable blocks completion, not diagnosis. Confirmed API23 code cause must not be relabeled generic device behavior.
- Deleting the temporary diagnostic before preserving logs/source would erase useful causal evidence; its evidence-retention gate is mandatory.
- Any source edit after final strict passes invalidates both target results and forces the full canonical sequence again.
