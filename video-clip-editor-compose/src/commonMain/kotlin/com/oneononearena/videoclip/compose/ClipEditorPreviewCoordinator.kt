package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipRange
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ClipEditorPreviewState(
    val binding: PreviewBinding? = null,
    val playhead: Duration = Duration.ZERO,
    val ready: Boolean = false,
    val isPlaying: Boolean = false,
    val failure: String? = null,
    val closing: Boolean = false,
)

/** Scope-free reducer and preview command policy. Lifecycle ownership lives in [ClipEditorLifecycleOwner]. */
internal class ClipEditorPreviewCoordinator {
    private var port: PreviewPort? = null
    private val backingState = MutableStateFlow(ClipEditorPreviewState())
    val state: StateFlow<ClipEditorPreviewState> = backingState.asStateFlow()

    fun bind(port: PreviewPort, binding: PreviewBinding) {
        if (backingState.value.closing) return
        this.port = port
        backingState.value = ClipEditorPreviewState(
            binding = binding,
            playhead = binding.sourcePosition,
        )
        port.dispatch(PreviewCommand.Bind(binding))
    }

    fun sync(port: PreviewPort, binding: PreviewBinding) {
        val active = backingState.value.binding
        when {
            active == null -> bind(port, binding.copy(revision = PreviewRevision(1)))
            active.generation != binding.generation || active.source != binding.source -> Unit
            active.range != binding.range -> replaceRange(
                binding.copy(
                    generation = active.generation,
                    revision = PreviewRevision(active.revision.value + 1),
                ),
            )
        }
    }

    fun replaceRange(binding: PreviewBinding) {
        val current = backingState.value
        val active = current.binding ?: return
        if (current.closing || active.generation != binding.generation || active.revision.value >= binding.revision.value) return
        backingState.value = ClipEditorPreviewState(
            binding = binding,
            playhead = binding.sourcePosition.coerceIn(binding.range.start, binding.range.endExclusive),
        )
        port?.dispatch(PreviewCommand.ReplaceRange(binding))
    }

    fun togglePlayPause() {
        val current = backingState.value
        val binding = current.binding ?: return
        if (!current.ready || current.failure != null || current.closing) return
        if (current.isPlaying) {
            port?.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, false))
            return
        }
        val playhead = current.playhead.takeIf { it in binding.range.start..binding.range.endExclusive }
            ?: binding.range.start
        if (playhead != current.playhead) {
            port?.dispatch(PreviewCommand.Seek(binding.generation, binding.revision, playhead))
        }
        port?.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, true))
    }

    fun pause() = setPlaying(false)

    fun seekPaused(sourcePosition: Duration) {
        val current = backingState.value
        val binding = current.binding ?: return
        if (current.closing) return
        val bounded = sourcePosition.coerceIn(binding.range.start, binding.range.endExclusive)
        port?.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, false))
        port?.dispatch(PreviewCommand.Seek(binding.generation, binding.revision, bounded))
        backingState.value = current.copy(playhead = bounded, isPlaying = false)
    }

    fun constrainPlayhead(range: ClipRange) {
        val current = backingState.value
        if (current.closing || current.playhead in range.start..range.endExclusive) return
        seekPaused(current.playhead.coerceIn(range.start, range.endExclusive))
    }

    fun retry() {
        val current = backingState.value
        val binding = current.binding ?: return
        if (current.closing) return
        val retried = binding.copy(
            revision = PreviewRevision(binding.revision.value + 1),
            sourcePosition = binding.range.start,
            playWhenReady = false,
        )
        backingState.value = ClipEditorPreviewState(binding = retried, playhead = retried.range.start)
        port?.dispatch(PreviewCommand.Retry(retried))
    }

    fun onEvent(event: PreviewEvent) {
        val current = backingState.value
        val active = current.binding
        when (event) {
            is PreviewEvent.Released -> Unit
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
                backingState.value = current.copy(
                    ready = false,
                    isPlaying = false,
                    failure = event.diagnostic ?: "Preview unavailable",
                )
            }
        }
    }

    fun lockInteractions() {
        port = null
        backingState.value = backingState.value.copy(closing = true, ready = false, isPlaying = false)
    }

    fun prepareFreshLifecycle() {
        port = null
        backingState.value = ClipEditorPreviewState()
    }

    fun clearAfterDisposal() {
        port = null
        backingState.value = ClipEditorPreviewState(closing = true)
    }

    private fun setPlaying(value: Boolean) {
        val current = backingState.value
        val binding = current.binding ?: return
        if (!current.ready || current.closing) return
        port?.dispatch(PreviewCommand.SetPlayWhenReady(binding.generation, binding.revision, value))
    }

    private fun PreviewEvent.Ready.acceptedBy(active: PreviewBinding?): Boolean =
        active != null && generation == active.generation && revision == active.revision

    private fun PreviewEvent.Position.acceptedBy(active: PreviewBinding?): Boolean =
        active != null && generation == active.generation && revision == active.revision

    private fun PreviewEvent.RecoverableFailure.acceptedBy(active: PreviewBinding?): Boolean =
        active != null && generation == active.generation && revision == active.revision
}
