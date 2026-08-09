package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.UnsupportedCode
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoEditFailure
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ClipEditorLifecycleOwnerTest {
    @Test
    fun terminalCompletionRunsOnceAfterSessionEventJobPortActiveAndCoordinatorClear() = runTest {
        val calls = mutableListOf<String>()
        val port = CompletionPort("g1", calls)
        lateinit var owner: ClipEditorLifecycleOwner
        owner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(port, calls),
            dispatcher = StandardTestDispatcher(testScheduler),
            releaseTimeout = 100.milliseconds,
            onTerminalLifecycleComplete = {
                assertEquals(null, owner.activePort.value)
                assertTrue(owner.coordinator.state.value.closing)
                calls += "completion"
            },
            activeClearPostcondition = { cleared ->
                calls += "active-null:$cleared"
                cleared
            },
            clearCoordinatorAfterDisposal = { coordinator ->
                coordinator.clearAfterDisposal()
                calls += "coordinator-clear"
            },
        )
        owner.requestReplace(sourceA, CompletionEditor(CompletionSession("g1", calls)))
        advanceUntilIdle()
        calls.clear()

        owner.requestClose()
        advanceUntilIdle()
        owner.requestClose()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "Release:g1",
                "session-close:g1",
                "event-joined:g1",
                "port-dispose:g1",
                "active-null:true",
                "coordinator-clear",
                "completion",
            ),
            calls,
        )
    }

    @Test
    fun unexpectedOpenFailureLatchesRunsRemainingTerminalOrderAndEmitsZeroCompletion() = runTest {
        val calls = mutableListOf<String>()
        val port = CompletionPort("g1", calls)
        var completions = 0
        val owner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(port, calls),
            dispatcher = StandardTestDispatcher(testScheduler),
            releaseTimeout = 100.milliseconds,
            onTerminalLifecycleComplete = { completions++ },
            activeClearPostcondition = { cleared -> calls += "active-null:$cleared"; cleared },
            clearCoordinatorAfterDisposal = { coordinator ->
                coordinator.clearAfterDisposal()
                calls += "coordinator-clear"
            },
        )
        owner.requestReplace(sourceA, object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
                calls += "open-failure"
                throw IllegalStateException("unexpected open failure")
            }
        })
        advanceUntilIdle()
        calls.clear()

        owner.requestClose()
        advanceUntilIdle()

        assertEquals(
            listOf(
                "Release:g1",
                "event-joined:g1",
                "port-dispose:g1",
                "active-null:true",
                "coordinator-clear",
            ),
            calls,
        )
        assertEquals(0, completions)
        assertTrue(owner.closed.value)
    }

    @Test
    fun eventActorPortDisposeActivePostconditionAndCoordinatorFailuresEachLatchAndEmitZeroCompletion() = runTest {
        suspend fun runFailureCase(
            eventFailure: Throwable? = null,
            disposeFailure: Throwable? = null,
            activeClearPostcondition: (Boolean) -> Boolean = { it },
            clearCoordinatorAfterDisposal: (ClipEditorPreviewCoordinator) -> Unit = { it.clearAfterDisposal() },
        ): Pair<Int, List<String>> {
            val calls = mutableListOf<String>()
            val port = CompletionPort("g1", calls, eventFailure = eventFailure)
            var completions = 0
            val owner = ClipEditorLifecycleOwner(
                portFactory = CompletionPortFactory(port, calls, disposeFailure),
                dispatcher = StandardTestDispatcher(testScheduler),
                releaseTimeout = 100.milliseconds,
                onTerminalLifecycleComplete = { completions++ },
                activeClearPostcondition = activeClearPostcondition,
                clearCoordinatorAfterDisposal = clearCoordinatorAfterDisposal,
            )
            owner.requestReplace(sourceA, CompletionEditor(CompletionSession("g1", calls)))
            advanceUntilIdle()
            calls.clear()
            owner.requestClose()
            advanceUntilIdle()
            return completions to calls
        }

        val actor = runFailureCase(eventFailure = IllegalStateException("actor failed"))
        val dispose = runFailureCase(disposeFailure = IllegalStateException("dispose failed"))
        val active = runFailureCase(activeClearPostcondition = { false })
        val coordinator = runFailureCase(
            clearCoordinatorAfterDisposal = { throw IllegalStateException("coordinator failed") },
        )

        assertEquals(0, actor.first)
        assertTrue(actor.second.contains("port-dispose:g1"))
        assertEquals(0, dispose.first)
        assertTrue(dispose.second.contains("session-close:g1"))
        assertEquals(0, active.first)
        assertTrue(active.second.contains("port-dispose:g1"))
        assertEquals(0, coordinator.first)
        assertTrue(coordinator.second.contains("port-dispose:g1"))
    }

    @Test
    fun terminalAndReplacementOwnerCancellationAreExpectedButPriorUnexpectedFailureRemainsLatched() = runTest {
        suspend fun cleanReplacementAndClose(): Pair<Int, List<String>> {
            val calls = mutableListOf<String>()
            val ports = ArrayDeque(
                listOf(CompletionPort("g1", calls), CompletionPort("g2", calls)),
            )
            var completions = 0
            val owner = ClipEditorLifecycleOwner(
                portFactory = QueueCompletionPortFactory(ports, calls),
                dispatcher = StandardTestDispatcher(testScheduler),
                releaseTimeout = 100.milliseconds,
                onTerminalLifecycleComplete = { completions++ },
            )
            owner.requestReplace(sourceA, CompletionEditor(CompletionSession("g1", calls)))
            advanceUntilIdle()
            owner.requestReplace(sourceB, CompletionEditor(CompletionSession("g2", calls)))
            advanceUntilIdle()
            assertEquals(0, completions)
            owner.requestClose()
            advanceUntilIdle()
            return completions to calls
        }

        val clean = cleanReplacementAndClose()
        assertEquals(1, clean.first)

        val calls = mutableListOf<String>()
        val ports = ArrayDeque(
            listOf(
                CompletionPort("g1", calls, eventFailure = IllegalStateException("prior actor failure")),
                CompletionPort("g2", calls),
            ),
        )
        var completions = 0
        val failedOwner = ClipEditorLifecycleOwner(
            portFactory = QueueCompletionPortFactory(ports, calls),
            dispatcher = StandardTestDispatcher(testScheduler),
            releaseTimeout = 100.milliseconds,
            onTerminalLifecycleComplete = { completions++ },
        )
        failedOwner.requestReplace(sourceA, CompletionEditor(CompletionSession("g1", calls)))
        advanceUntilIdle()
        failedOwner.requestReplace(sourceB, CompletionEditor(CompletionSession("g2", calls)))
        advanceUntilIdle()
        failedOwner.requestClose()
        advanceUntilIdle()

        assertEquals(0, completions)
        assertTrue(failedOwner.closed.value)
    }

    @Test
    fun releaseTimeoutStillDisposesAndCallbackThrowIsolatedWithoutRetryOrRestart() = runTest {
        val calls = mutableListOf<String>()
        val port = CompletionPort("g1", calls, acknowledgeRelease = false)
        var attempts = 0
        val owner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(port, calls),
            dispatcher = StandardTestDispatcher(testScheduler),
            releaseTimeout = 100.milliseconds,
            onTerminalLifecycleComplete = {
                attempts++
                calls += "completion-throw"
                throw IllegalStateException("host callback failed")
            },
        )
        owner.requestReplace(sourceA, CompletionEditor(CompletionSession("g1", calls)))
        advanceUntilIdle()
        calls.clear()

        owner.requestClose()
        runCurrent()
        assertEquals(listOf("Release:g1"), calls)
        advanceTimeBy(100)
        advanceUntilIdle()
        owner.requestClose()
        owner.requestReplace(sourceB, CompletionEditor(CompletionSession("g2", calls)))
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals(1, calls.count { it == "completion-throw" })
        assertEquals(1, calls.count { it == "port-dispose:g1" })
        assertTrue(owner.closed.value)
    }

    @Test
    fun unsupportedNoSessionCompletesAfterDispose() = runTest {
        val calls = mutableListOf<String>()
        val port = CompletionPort("g1", calls)
        var completions = 0
        val owner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(port, calls),
            dispatcher = StandardTestDispatcher(testScheduler),
            releaseTimeout = 100.milliseconds,
            onTerminalLifecycleComplete = { calls += "completion"; completions++ },
        )
        owner.requestReplace(sourceA, object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult =
                OpenSessionResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, null)
        })
        advanceUntilIdle()
        calls.clear()

        owner.requestClose()
        advanceUntilIdle()

        assertEquals(1, completions)
        assertTrue(calls.indexOf("port-dispose:g1") < calls.indexOf("completion"))
    }

    @Test
    fun terminalCloseCoversAwaitingOpeningOpenAndNonOpen() = runTest {
        var awaitingCompletions = 0
        val awaitingOwner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(CompletionPort("awaiting", mutableListOf()), mutableListOf()),
            dispatcher = StandardTestDispatcher(testScheduler),
            onTerminalLifecycleComplete = { awaitingCompletions++ },
        )
        awaitingOwner.requestReplace(sourceA, CompletionEditor(CompletionSession("awaiting", mutableListOf())))
        awaitingOwner.requestClose()
        advanceUntilIdle()
        assertEquals(1, awaitingCompletions)

        val openingCalls = mutableListOf<String>()
        lateinit var openContinuation: Continuation<OpenSessionResult>
        var openingCompletions = 0
        val openingOwner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(CompletionPort("opening", openingCalls), openingCalls),
            dispatcher = StandardTestDispatcher(testScheduler),
            onTerminalLifecycleComplete = { openingCalls += "completion"; openingCompletions++ },
        )
        val lateSession = CompletionSession("opening", openingCalls)
        openingOwner.requestReplace(sourceA, object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult =
                kotlin.coroutines.suspendCoroutine { openContinuation = it }
        })
        runCurrent()
        openingOwner.requestClose()
        runCurrent()
        assertEquals(0, openingCompletions)
        openContinuation.resume(OpenSessionResult.Open(lateSession))
        advanceUntilIdle()
        assertEquals(1, openingCompletions)
        assertTrue(openingCalls.indexOf("session-close:opening") < openingCalls.indexOf("completion"))

        val openCalls = mutableListOf<String>()
        var openCompletions = 0
        val openOwner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(CompletionPort("open", openCalls), openCalls),
            dispatcher = StandardTestDispatcher(testScheduler),
            onTerminalLifecycleComplete = { openCalls += "completion"; openCompletions++ },
        )
        openOwner.requestReplace(sourceA, CompletionEditor(CompletionSession("open", openCalls)))
        advanceUntilIdle()
        openOwner.requestClose()
        advanceUntilIdle()
        assertEquals(1, openCompletions)

        val nonOpenCalls = mutableListOf<String>()
        var nonOpenCompletions = 0
        val nonOpenOwner = ClipEditorLifecycleOwner(
            portFactory = CompletionPortFactory(CompletionPort("non-open", nonOpenCalls), nonOpenCalls),
            dispatcher = StandardTestDispatcher(testScheduler),
            onTerminalLifecycleComplete = { nonOpenCalls += "completion"; nonOpenCompletions++ },
        )
        nonOpenOwner.requestReplace(sourceA, object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult =
                OpenSessionResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, null)
        })
        advanceUntilIdle()
        nonOpenOwner.requestClose()
        advanceUntilIdle()
        assertEquals(1, nonOpenCompletions)
    }

    @Test
    fun wrongAckCannotAdvanceReplacement_thenMatchingAckUsesExactOrder() = runTest {
        val rig = LifecycleRig(StandardTestDispatcher(testScheduler))
        rig.owner.requestReplace(sourceA, rig.firstEditor)
        advanceUntilIdle()
        rig.calls.clear()

        rig.owner.requestReplace(sourceB, rig.secondEditor)
        runCurrent()
        assertEquals(listOf("Release:g1:r1"), rig.calls)

        rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(99)))
        runCurrent()
        assertEquals(listOf("Release:g1:r1"), rig.calls)

        rig.calls += "Released:g1"
        rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(1)))
        advanceUntilIdle()

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
    fun timeoutAuditSurvivesFreshBindingAndUsesExactOrder() = runTest {
        val rig = LifecycleRig(StandardTestDispatcher(testScheduler))
        rig.owner.requestReplace(sourceA, rig.firstEditor)
        advanceUntilIdle()
        rig.calls.clear()

        rig.owner.requestReplace(sourceB, rig.secondEditor)
        runCurrent()
        advanceTimeBy(100)
        advanceUntilIdle()

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
        assertFalse(rig.owner.releaseAudit.value.toString().contains("first.mp4"))
    }

    @Test
    fun timeoutDiagnosticIsClosedAndDropsHostileLowerLayerInputs() = runTest {
        val hostile = listOf<Any>(
            "/private/var/mobile/source.mp4",
            "file:///data/user/0/app/cache/source.mp4",
            "content://media/external/video/42",
            "java.lang.IllegalStateException: decoder\n\tat Player.release(Player.kt:41)",
            IllegalStateException("/secret/source.mp4"),
        )

        hostile.forEach { raw ->
            val rig = LifecycleRig(StandardTestDispatcher(testScheduler), lowerLayerDiagnostic = raw)
            rig.owner.requestReplace(sourceA, rig.firstEditor)
            advanceUntilIdle()
            rig.owner.requestReplace(sourceB, rig.secondEditor)
            runCurrent()
            advanceTimeBy(100)
            advanceUntilIdle()

            val audit = requireNotNull(rig.owner.releaseAudit.value)
            assertEquals(
                PreviewReleaseOutcome.TimedOut(PreviewReleaseDiagnostic.ReleaseTimeout),
                audit.outcome,
            )
            assertFalse(audit.toString().contains(raw.toString()))
            rig.owner.requestClose()
            advanceUntilIdle()
        }
    }

    @Test
    fun terminalCloseWinsReplacementPendingAtReleaseFence() = runTest {
        val rig = LifecycleRig(StandardTestDispatcher(testScheduler))
        rig.owner.requestReplace(sourceA, rig.firstEditor)
        advanceUntilIdle()
        rig.calls.clear()

        rig.owner.requestReplace(sourceB, rig.secondEditor)
        runCurrent()
        assertEquals(listOf("Release:g1:r1"), rig.calls)

        rig.owner.requestClose()
        rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(1)))
        advanceUntilIdle()
        rig.owner.requestReplace(sourceC, rig.thirdEditor)
        advanceUntilIdle()

        assertEquals(1, rig.calls.count { it == "session-close:g1" })
        assertEquals(1, rig.calls.count { it == "port-dispose:g1" })
        assertEquals(0, rig.calls.count { it == "port-create:g2" })
        assertEquals(0, rig.calls.count { it == "session-start:g2" })
        assertEquals(0, rig.calls.count { it == "Bind:g2:r1" })
        assertEquals(true, rig.owner.closed.value)
    }

    @Test
    fun oldReadyQueuedDuringReleaseCannotBindOrMutateFreshLifecycle() = runTest {
        val oldMetadata = VideoMetadata(10.seconds, 100, 100, false)
        val freshMetadata = VideoMetadata(20.seconds, 200, 200, true)
        val freshFrames = MutableSharedFlow<FrameStripEvent>(extraBufferCapacity = 1)
        val rig = LifecycleRig(
            dispatcher = StandardTestDispatcher(testScheduler),
            firstMetadata = oldMetadata,
            secondMetadata = freshMetadata,
            secondFrames = freshFrames,
        )
        rig.owner.requestReplace(sourceA, rig.firstEditor)
        advanceUntilIdle()
        rig.calls.clear()

        rig.owner.requestReplace(sourceB, rig.secondEditor)
        runCurrent()
        assertEquals(listOf("Release:g1:r1"), rig.calls)

        rig.owner.presenter.updateEnd(4.seconds)
        runCurrent()
        rig.calls += "Released:g1"
        rig.oldPort.emit(PreviewEvent.Released(PreviewGeneration(1)))
        runCurrent()
        freshFrames.emit(FrameStripEvent.Complete)
        advanceUntilIdle()

        val freshBinds = rig.freshPort.commandSnapshot().filterIsInstance<PreviewCommand.Bind>()
        assertEquals(1, freshBinds.size)
        assertEquals(sourceB, freshBinds.single().binding.source)
        assertEquals(freshMetadata, freshBinds.single().binding.metadata)
        assertEquals(ClipRange(Duration.ZERO, 20.seconds), freshBinds.single().binding.range)
        assertEquals(Duration.ZERO, rig.owner.coordinator.state.value.playhead)
        assertEquals(freshBinds.single().binding, rig.owner.coordinator.state.value.binding)
    }

    @Test
    fun exportTransitionSynchronouslyLocksImmediateControlCallbacks() = runTest {
        val calls = mutableListOf<String>()
        val port = LifecycleTerminalPort("g1", calls)
        val export = CompletableDeferred<ClipResult>()
        val session = ExportHoldingLifecycleSession(export)
        val owner = ClipEditorLifecycleOwner(
            portFactory = object : PreviewPortFactory {
                override fun create(): PreviewPort = port
                override fun dispose(port: PreviewPort) = Unit
            },
            dispatcher = StandardTestDispatcher(testScheduler),
            releaseTimeout = 100.milliseconds,
        )
        owner.requestReplace(sourceA, LifecycleEditor("g1", session, calls))
        advanceUntilIdle()
        port.emit(PreviewEvent.Ready(PreviewGeneration(1), PreviewRevision(1)))
        runCurrent()
        val commandsAtExportStart = port.commandSnapshot()
        owner.presenter.createClip()
        val stateAtExportStart = owner.coordinator.state.value

        owner.coordinator.togglePlayPause()
        owner.coordinator.pause()
        owner.coordinator.seekPaused(3.seconds)
        owner.coordinator.retry()
        owner.coordinator.replaceRange(
            PreviewBinding(
                PreviewGeneration(1),
                PreviewRevision(2),
                sourceA,
                session.metadata,
                ClipRange(1.seconds, 4.seconds),
                1.seconds,
                false,
            ),
        )

        assertEquals(commandsAtExportStart, port.commandSnapshot())
        assertEquals(stateAtExportStart, owner.coordinator.state.value)
        export.complete(ClipResult.Failed(VideoEditFailure(com.oneononearena.videoclip.FailureCode.EXPORT_FAILED, false, null)))
        advanceUntilIdle()
        owner.requestClose()
        advanceUntilIdle()
    }

    private companion object {
        val sourceA = VideoSourcePath("/first.mp4")
        val sourceB = VideoSourcePath("/second.mp4")
        val sourceC = VideoSourcePath("/third.mp4")
    }
}

private class LifecycleRig(
    dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    lowerLayerDiagnostic: Any? = null,
    firstMetadata: VideoMetadata = VideoMetadata(10.seconds, 100, 100, false),
    secondMetadata: VideoMetadata = VideoMetadata(10.seconds, 100, 100, false),
    secondFrames: Flow<FrameStripEvent> = flowOf(FrameStripEvent.Complete),
) {
    val calls = mutableListOf<String>()
    val oldPort = LifecycleTerminalPort("g1", calls, lowerLayerDiagnostic)
    val freshPort = LifecycleTerminalPort("g2", calls)
    private var createCount = 0
    val owner: ClipEditorLifecycleOwner
    private val firstSession = LifecycleSession("g1", calls, { owner.releaseAudit.value }, firstMetadata)
    private val secondSession = LifecycleSession("g2", calls, { owner.releaseAudit.value }, secondMetadata, secondFrames)
    private val thirdSession = LifecycleSession("g3", calls, { owner.releaseAudit.value })
    val firstEditor = LifecycleEditor("g1", firstSession, calls)
    val secondEditor = LifecycleEditor("g2", secondSession, calls)
    val thirdEditor = LifecycleEditor("g3", thirdSession, calls)

    init {
        owner = ClipEditorLifecycleOwner(
            portFactory = object : PreviewPortFactory {
                override fun create(): PreviewPort {
                    createCount++
                    calls += "port-create:g$createCount"
                    return if (createCount == 1) oldPort else freshPort
                }

                override fun dispose(port: PreviewPort) {
                    val generation = if (port === oldPort) "g1" else "g2"
                    calls += "port-dispose:$generation"
                }
            },
            dispatcher = dispatcher,
            releaseTimeout = 100.milliseconds,
        )
    }
}

private class LifecycleTerminalPort(
    private val label: String,
    private val calls: MutableList<String>,
    @Suppress("unused") private val lowerLayerDiagnostic: Any? = null,
) : PreviewPort {
    private val mutableEvents = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = 16)
    override val events: Flow<PreviewEvent> = mutableEvents
    private var terminal = false
    val commandsAfterRelease = mutableListOf<PreviewCommand>()

    override fun dispatch(command: PreviewCommand) {
        if (terminal) {
            commandsAfterRelease += command
            return
        }
        commandsBeforeRelease += command
        when (command) {
            is PreviewCommand.Bind -> calls += "Bind:$label:r${command.binding.revision.value}"
            is PreviewCommand.Release -> {
                calls += "Release:$label:r1"
                terminal = true
            }
            else -> Unit
        }
    }

    suspend fun emit(event: PreviewEvent) {
        mutableEvents.emit(event)
    }

    fun commandSnapshot(): List<PreviewCommand> = buildList {
        addAll(commandsBeforeRelease)
        addAll(commandsAfterRelease)
    }

    private val commandsBeforeRelease = mutableListOf<PreviewCommand>()
}

private class LifecycleEditor(
    private val label: String,
    private val session: ClipEditorSession,
    private val calls: MutableList<String>,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        calls += "session-start:$label"
        return OpenSessionResult.Open(session)
    }
}

private class LifecycleSession(
    private val label: String,
    private val calls: MutableList<String>,
    private val audit: () -> PreviewReleaseAudit?,
    override val metadata: VideoMetadata = VideoMetadata(10.seconds, 100, 100, false),
    private val frameEvents: Flow<FrameStripEvent> = flowOf(FrameStripEvent.Complete),
) : ClipEditorSession {
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = frameEvents
    override suspend fun createClip(range: ClipRange): ClipResult =
        ClipResult.Failed(VideoEditFailure(com.oneononearena.videoclip.FailureCode.EXPORT_FAILED, false, null))

    override suspend fun close() {
        val currentAudit = requireNotNull(audit())
        calls += when (val outcome = currentAudit.outcome) {
            PreviewReleaseOutcome.Acknowledged ->
                "audit:g${currentAudit.generation.value}:r${currentAudit.revision.value}:Acknowledged:${currentAudit.reason}"
            is PreviewReleaseOutcome.TimedOut ->
                "audit:g${currentAudit.generation.value}:r${currentAudit.revision.value}:TimedOut(${outcome.diagnostic}):${currentAudit.reason}"
        }
        calls += "session-close:$label"
    }
}

private class ExportHoldingLifecycleSession(
    private val export: CompletableDeferred<ClipResult>,
) : ClipEditorSession {
    override val metadata = VideoMetadata(10.seconds, 100, 100, false)
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = flowOf(FrameStripEvent.Complete)
    override suspend fun createClip(range: ClipRange): ClipResult = export.await()
    override suspend fun close() = Unit
}

private class CompletionPort(
    val label: String,
    private val calls: MutableList<String>,
    private val acknowledgeRelease: Boolean = true,
    private val eventFailure: Throwable? = null,
) : PreviewPort {
    private val channel = Channel<PreviewEvent>(Channel.UNLIMITED)
    override val events: Flow<PreviewEvent> = flow {
        try {
            eventFailure?.let { throw it }
            channel.receiveAsFlow().collect { emit(it) }
        } finally {
            calls += "event-joined:$label"
        }
    }

    override fun dispatch(command: PreviewCommand) {
        if (command is PreviewCommand.Release) {
            calls += "Release:$label"
            if (acknowledgeRelease) {
                channel.trySend(PreviewEvent.Released(command.generation))
            }
        }
    }
}

private open class CompletionPortFactory(
    private val port: CompletionPort,
    private val calls: MutableList<String>,
    private val disposeFailure: Throwable? = null,
) : PreviewPortFactory {
    override fun create(): PreviewPort = port
    override fun dispose(port: PreviewPort) {
        calls += "port-dispose:${if (this.port === port) this.port.label else "unknown"}"
        disposeFailure?.let { throw it }
    }
}

private class QueueCompletionPortFactory(
    private val ports: ArrayDeque<CompletionPort>,
    private val calls: MutableList<String>,
) : PreviewPortFactory {
    private val labels = mutableMapOf<PreviewPort, String>()
    private var count = 0

    override fun create(): PreviewPort = ports.removeFirst().also {
        count++
        labels[it] = "g$count"
    }

    override fun dispose(port: PreviewPort) {
        calls += "port-dispose:${labels[port]}"
    }
}

private class CompletionEditor(
    private val session: ClipEditorSession,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = OpenSessionResult.Open(session)
}

private class CompletionSession(
    private val label: String,
    private val calls: MutableList<String>,
) : ClipEditorSession {
    override val metadata = VideoMetadata(10.seconds, 100, 100, false)
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = flowOf(FrameStripEvent.Complete)
    override suspend fun createClip(range: ClipRange): ClipResult =
        ClipResult.Failed(VideoEditFailure(com.oneononearena.videoclip.FailureCode.EXPORT_FAILED, false, null))

    override suspend fun close() {
        calls += "session-close:$label"
    }
}
