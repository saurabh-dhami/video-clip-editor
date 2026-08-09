package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Sole authority for preview ports and presenter-session replacement/terminal teardown. */
internal class ClipEditorLifecycleOwner(
    private val portFactory: PreviewPortFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val releaseTimeout: Duration = 100.milliseconds,
    onTerminalLifecycleComplete: () -> Unit = {},
    private val activeClearPostcondition: (Boolean) -> Boolean = { it },
    private val clearCoordinatorAfterDisposal: (ClipEditorPreviewCoordinator) -> Unit = {
        it.clearAfterDisposal()
    },
) {
    private val ownerJob = SupervisorJob()
    private val scope = CoroutineScope(ownerJob + dispatcher)
    private val terminalFailure = MutableStateFlow<Throwable?>(null)
    val coordinator = ClipEditorPreviewCoordinator()
    val presenter = ClipEditorPresenter(
        scope = scope,
        onExportTransition = coordinator::lockInteractions,
        onUnexpectedOperationFailure = ::recordTerminalFailure,
    )

    private val intents = Channel<LifecycleIntent>(Channel.UNLIMITED)
    private val terminalLatched = MutableStateFlow(false)
    private val backingClosed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = backingClosed.asStateFlow()
    private val backingActivePort = MutableStateFlow<PreviewPort?>(null)
    val activePort: StateFlow<PreviewPort?> = backingActivePort.asStateFlow()
    private val backingReleaseAudit = MutableStateFlow<PreviewReleaseAudit?>(null)
    val releaseAudit: StateFlow<PreviewReleaseAudit?> = backingReleaseAudit.asStateFlow()

    private var generationValue = 0L
    private var active: ActiveLifecycle? = null
    private var eventJob: Job? = null
    private var releaseWaiter: Pair<PreviewGeneration, CompletableDeferred<Unit>>? = null
    private var terminalCompletion = onTerminalLifecycleComplete
    private var terminalCompletionAttempted = false

    init {
        scope.launch {
            presenter.state.collect { state ->
                intents.send(LifecycleIntent.PresenterStateChanged(active?.generation, state))
            }
        }
        scope.launch {
            for (intent in intents) {
                when (intent) {
                    is LifecycleIntent.Replace -> replace(intent.source, intent.editor)
                    is LifecycleIntent.PresenterStateChanged -> presenterStateChanged(intent.generation, intent.state)
                    is LifecycleIntent.PortEvent -> portEvent(intent.generation, intent.event)
                    is LifecycleIntent.Close -> close(intent.cancel)
                }
            }
        }
    }

    fun updateCallbacks(
        onResult: (com.oneononearena.videoclip.ClipResult) -> Unit,
        onCancel: () -> Unit,
        onTerminalLifecycleComplete: () -> Unit,
    ) {
        presenter.updateCallbacks(onResult, onCancel)
        terminalCompletion = onTerminalLifecycleComplete
    }

    fun requestReplace(source: VideoSourcePath, editor: VideoClipEditor) {
        if (terminalLatched.value) return
        intents.trySend(LifecycleIntent.Replace(source, editor))
    }

    fun requestClose() = requestTerminalClose(cancel = false)

    fun requestCancel() = requestTerminalClose(cancel = true)

    private fun requestTerminalClose(cancel: Boolean) {
        if (!terminalLatched.compareAndSet(expect = false, update = true)) return
        coordinator.lockInteractions()
        intents.trySend(LifecycleIntent.Close(cancel))
    }

    private suspend fun replace(source: VideoSourcePath, editor: VideoClipEditor) {
        if (terminalLatched.value) return
        val current = active
        if (current != null) {
            coordinator.lockInteractions()
            teardown(current, PreviewReleaseReason.SourceReplacement)
        }
        if (terminalLatched.value) return
        startFresh(source, editor)
    }

    private fun startFresh(source: VideoSourcePath, editor: VideoClipEditor) {
        if (terminalLatched.value) return
        val generation = PreviewGeneration(generationValue + 1)
        if (terminalLatched.value) return
        val port = portFactory.create()
        if (terminalLatched.value) {
            portFactory.dispose(port)
            return
        }
        generationValue = generation.value
        val lifecycle = ActiveLifecycle(generation, source, port)
        active = lifecycle
        backingActivePort.value = port
        coordinator.prepareFreshLifecycle()
        observe(lifecycle)
        if (terminalLatched.value) return
        presenter.start(source, editor)
    }

    private fun presenterStateChanged(
        generation: PreviewGeneration?,
        state: ClipEditorUiState,
    ) {
        val current = active ?: return
        if (current.generation != generation || terminalLatched.value) return
        if (state == ClipEditorUiState.Exporting) {
            coordinator.lockInteractions()
            return
        }
        val ready = state as? ClipEditorUiState.Ready ?: return
        if (active !== current) return
        val existing = coordinator.state.value.binding
        val revision = existing?.revision ?: PreviewRevision(1)
        val binding = PreviewBinding(
            generation = current.generation,
            revision = revision,
            source = current.source,
            metadata = ready.metadata,
            range = ready.range,
            sourcePosition = ready.range.start,
            playWhenReady = false,
        )
        if (terminalLatched.value) return
        coordinator.sync(current.port, binding)
    }

    private fun portEvent(generation: PreviewGeneration, event: PreviewEvent) {
        val current = active ?: return
        if (current.generation != generation || terminalLatched.value) return
        coordinator.onEvent(event)
    }

    private suspend fun close(cancel: Boolean) {
        teardown(active, PreviewReleaseReason.TerminalClose)
        if (cancel) runTerminalStage { presenter.cancelAfterClose() }
        backingClosed.value = true
        intents.close()
        attemptTerminalCompletion()
        scope.cancel()
    }

    private suspend fun teardown(
        lifecycle: ActiveLifecycle?,
        reason: PreviewReleaseReason,
    ) {
        if (lifecycle != null && active !== lifecycle) return
        coordinator.lockInteractions()
        if (lifecycle != null) {
            release(lifecycle, reason)
        }
        runTerminalStage { presenter.close() }
        cancelAndJoinEventJob(reason)
        backingActivePort.value = null
        if (lifecycle != null) {
            runTerminalStage { portFactory.dispose(lifecycle.port) }
        }
        if (lifecycle == null || active === lifecycle) active = null
        if (!activeClearPostcondition(active == null)) {
            recordTerminalFailure(IllegalStateException("Active lifecycle postcondition failed"))
        }
        runTerminalStage { clearCoordinatorAfterDisposal(coordinator) }
    }

    private suspend fun release(
        lifecycle: ActiveLifecycle,
        reason: PreviewReleaseReason,
    ) {
        val binding = coordinator.state.value.binding
        val revision = binding?.takeIf { it.generation == lifecycle.generation }?.revision ?: PreviewRevision(1)
        val acknowledgement = CompletableDeferred<Unit>()
        releaseWaiter = lifecycle.generation to acknowledgement
        var acknowledged = false
        try {
            lifecycle.port.dispatch(PreviewCommand.Release(lifecycle.generation))
            acknowledged = withTimeoutOrNull(releaseTimeout.inWholeMilliseconds) {
                acknowledgement.await()
                true
            } == true
        } catch (cause: Throwable) {
            recordTerminalFailure(cause)
        } finally {
            val outcome = if (acknowledged) {
                PreviewReleaseOutcome.Acknowledged
            } else {
                PreviewReleaseOutcome.TimedOut(PreviewReleaseDiagnostic.ReleaseTimeout)
            }
            backingReleaseAudit.value = PreviewReleaseAudit(lifecycle.generation, revision, outcome, reason)
            releaseWaiter = null
        }
    }

    private suspend fun cancelAndJoinEventJob(reason: PreviewReleaseReason) {
        val runningEventJob = eventJob
        eventJob = null
        if (runningEventJob != null) {
            runningEventJob.cancel(ExpectedLifecycleEventCancellation(reason))
            runningEventJob.join()
        }
    }

    private suspend fun runTerminalStage(stage: suspend () -> Unit) {
        try {
            stage()
        } catch (cause: Throwable) {
            recordTerminalFailure(cause)
        }
    }

    private fun recordTerminalFailure(cause: Throwable) {
        terminalFailure.compareAndSet(expect = null, update = cause)
    }

    private fun attemptTerminalCompletion() {
        if (terminalCompletionAttempted || !terminalLatched.value || terminalFailure.value != null) return
        if (active != null || backingActivePort.value != null || !backingClosed.value) return
        terminalCompletionAttempted = true
        try {
            terminalCompletion()
        } catch (_: Throwable) {
            // The host callback is an isolated one-shot notification, never a retry trigger.
        }
    }

    private fun observe(lifecycle: ActiveLifecycle) {
        val job = scope.launch {
            try {
                lifecycle.port.events.collect { event ->
                    if (event is PreviewEvent.Released) {
                        val waiter = releaseWaiter
                        if (waiter?.first == event.generation) waiter.second.complete(Unit)
                    } else {
                        intents.send(LifecycleIntent.PortEvent(lifecycle.generation, event))
                    }
                }
            } catch (cause: Throwable) {
                if (cause is ExpectedLifecycleEventCancellation) throw cause
                recordTerminalFailure(cause)
            }
        }
        eventJob = job
        job.invokeOnCompletion { cause ->
            if (cause != null && cause !is ExpectedLifecycleEventCancellation) {
                recordTerminalFailure(cause)
            }
        }
    }

    private data class ActiveLifecycle(
        val generation: PreviewGeneration,
        val source: VideoSourcePath,
        val port: PreviewPort,
    )

    private sealed interface LifecycleIntent {
        data class Replace(val source: VideoSourcePath, val editor: VideoClipEditor) : LifecycleIntent
        data class PresenterStateChanged(
            val generation: PreviewGeneration?,
            val state: ClipEditorUiState,
        ) : LifecycleIntent
        data class PortEvent(val generation: PreviewGeneration, val event: PreviewEvent) : LifecycleIntent
        data class Close(val cancel: Boolean) : LifecycleIntent
    }
}

private class ExpectedLifecycleEventCancellation(
    reason: PreviewReleaseReason,
) : CancellationException("Clip editor lifecycle event collector cancelled for $reason")
