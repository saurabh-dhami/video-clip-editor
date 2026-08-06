package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow

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
