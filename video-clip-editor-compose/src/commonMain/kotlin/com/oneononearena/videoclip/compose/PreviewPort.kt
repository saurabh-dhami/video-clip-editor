package com.oneononearena.videoclip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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

internal val LocalPreviewPortFactoryOverride =
    staticCompositionLocalOf<PreviewPortFactory?> { null }

internal interface PreviewPortSurfaceDelegate {
    val surfacePort: PreviewPort?
}

/** Internal ownership seam: Android Release is terminal, so each source needs a fresh port. */
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

@Composable
internal expect fun rememberPlatformPreviewPortFactory(): PreviewPortFactory

@Composable
internal expect fun rememberPlatformPreviewPort(): PreviewPort

@Composable
internal expect fun PlatformPreviewSurface(
    port: PreviewPort,
    modifier: Modifier = Modifier,
)
