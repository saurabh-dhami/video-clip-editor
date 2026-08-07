package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal data class ClipEditorPreviewState(
    val binding: PreviewBinding? = null,
    val playhead: Duration = Duration.ZERO,
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val failure: String? = null,
    val closing: Boolean = false,
    val releaseFence: PreviewReleaseFence? = null,
)

internal sealed interface PreviewReleaseFence {
    data class Awaiting(val generation: PreviewGeneration) : PreviewReleaseFence
    data class Acknowledged(val generation: PreviewGeneration) : PreviewReleaseFence
    data class Timeout(val generation: PreviewGeneration) : PreviewReleaseFence
}

/** Common ownership boundary for native preview. Session/export stay in [ClipEditorPresenter]. */
internal class ClipEditorPreviewCoordinator(
    private val scope: CoroutineScope,
    private val port: PreviewPort,
) {
    val surfacePort: PreviewPort = port
    private val backingState = MutableStateFlow(ClipEditorPreviewState())
    val state: StateFlow<ClipEditorPreviewState> = backingState.asStateFlow()
    private var released: Pair<PreviewGeneration, kotlinx.coroutines.CompletableDeferred<Unit>>? = null
    private var nextGeneration = 0L
    private val closeMutex = Mutex()
    private val eventJob: kotlinx.coroutines.Job

    init {
        eventJob = scope.launch {
            port.events.collect(::onEvent)
        }
    }

    fun bind(binding: PreviewBinding) {
        if (backingState.value.closing) return
        val previous = backingState.value.binding
        if (previous != null) {
            if (previous.source != binding.source) return
            if (previous == binding) return
        }
        val lifecycleBinding = binding.copy(generation = PreviewGeneration(++nextGeneration))
        backingState.value = ClipEditorPreviewState(binding = lifecycleBinding, playhead = lifecycleBinding.sourcePosition)
        port.dispatch(PreviewCommand.Bind(lifecycleBinding))
    }

    fun sync(source: PreviewBinding) {
        val active = backingState.value.binding
        when {
            active == null -> bind(source)
            active.source != source.source -> Unit
            active.range != source.range -> replaceRange(source.copy(revision = PreviewRevision(active.revision.value + 1)))
        }
    }

    fun replaceRange(binding: PreviewBinding) {
        if (backingState.value.closing) return
        val active = backingState.value.binding ?: return bind(binding)
        if (active.revision.value >= binding.revision.value) return
        val replacement = binding.copy(generation = active.generation)
        backingState.value = ClipEditorPreviewState(binding = replacement, playhead = replacement.sourcePosition)
        port.dispatch(PreviewCommand.ReplaceRange(replacement))
    }

    fun togglePlayPause() {
        val current = backingState.value
        val binding = current.binding ?: return
        if (!current.ready || current.failure != null || current.closing) return
        if (current.isPlaying) {
            port.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, false))
            return
        }
        val playhead = current.playhead.takeIf { it in binding.range.start..binding.range.endExclusive }
            ?: binding.range.start
        if (playhead != current.playhead) {
            port.dispatch(PreviewCommand.Seek(binding.generation, binding.revision, playhead))
        }
        port.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, true))
    }

    fun pause() = setPlaying(false)

    fun seekPaused(sourcePosition: Duration) {
        val binding = backingState.value.binding ?: return
        if (backingState.value.closing) return
        val bounded = sourcePosition.coerceIn(binding.range.start, binding.range.endExclusive)
        port.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, false))
        port.dispatch(PreviewCommand.Seek(binding.generation, binding.revision, bounded))
        backingState.value = backingState.value.copy(playhead = bounded, isPlaying = false)
    }

    fun constrainPlayhead(range: ClipRange) {
        val current = backingState.value
        if (current.closing || current.playhead in range.start..range.endExclusive) return
        seekPaused(current.playhead.coerceIn(range.start, range.endExclusive))
    }

    fun retry() {
        val binding = backingState.value.binding ?: return
        if (backingState.value.closing) return
        val retried = binding.copy(revision = PreviewRevision(binding.revision.value + 1), playWhenReady = false)
        backingState.value = ClipEditorPreviewState(binding = retried, playhead = retried.range.start)
        port.dispatch(PreviewCommand.Retry(retried))
    }

    suspend fun closeThen(closeSession: suspend () -> Unit) {
        closeMutex.withLock {
            if (backingState.value.closing) return
            backingState.value = backingState.value.copy(closing = true, isPlaying = false)
            awaitRelease()
            closeSession()
        }
    }

    /** Release current platform binding before its owning core session can be closed. */
    suspend fun replaceSourceThen(closePreviousSession: suspend () -> Unit) {
        closeMutex.withLock {
            if (backingState.value.closing) return
            awaitRelease()
            closePreviousSession()
            backingState.value = ClipEditorPreviewState(releaseFence = backingState.value.releaseFence)
        }
    }

    fun dispose() = eventJob.cancel()

    private fun setPlaying(value: Boolean) {
        val binding = backingState.value.binding ?: return
        if (!backingState.value.ready || backingState.value.closing) return
        port.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, value))
    }

    private fun onEvent(event: PreviewEvent) {
        val current = backingState.value
        val active = current.binding
        when (event) {
            is PreviewEvent.Released -> {
                val acknowledgement = released
                if (acknowledgement?.first == event.generation) acknowledgement.second.complete(Unit)
            }
            is PreviewEvent.Ready -> if (event.acceptedBy(active)) {
                backingState.value = current.copy(ready = true, failure = null)
            }
            is PreviewEvent.Position -> if (event.acceptedBy(active)) {
                val range = active?.range ?: return
                backingState.value = current.copy(
                    playhead = event.sourcePosition.coerceIn(range.start, range.endExclusive),
                    isPlaying = event.isPlaying,
                )
            }
            is PreviewEvent.RecoverableFailure -> if (event.acceptedBy(active)) {
                backingState.value = current.copy(ready = false, isPlaying = false, failure = event.diagnostic ?: "Preview unavailable")
            }
        }
    }

    private fun PreviewEvent.Ready.acceptedBy(active: PreviewBinding?): Boolean =
        active != null && generation == active.generation && revision == active.revision

    private fun PreviewEvent.Position.acceptedBy(active: PreviewBinding?): Boolean =
        active != null && generation == active.generation && revision == active.revision

    private fun PreviewEvent.RecoverableFailure.acceptedBy(active: PreviewBinding?): Boolean =
        active != null && generation == active.generation && revision == active.revision

    private suspend fun awaitRelease() {
        val binding = backingState.value.binding ?: return
        val acknowledgement = kotlinx.coroutines.CompletableDeferred<Unit>()
        released = binding.generation to acknowledgement
        backingState.value = backingState.value.copy(releaseFence = PreviewReleaseFence.Awaiting(binding.generation))
        port.dispatch(PreviewCommand.Release(binding.generation))
        val releasedInTime = withTimeoutOrNull(releaseTimeout) { acknowledgement.await(); true } == true
        backingState.value = backingState.value.copy(
            releaseFence = if (releasedInTime) {
                PreviewReleaseFence.Acknowledged(binding.generation)
            } else {
                PreviewReleaseFence.Timeout(binding.generation)
            },
        )
        released = null
    }

    private companion object {
        val releaseTimeout = 100.milliseconds
    }
}
