package com.oneononearena.videoclip.internal.engine

import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ThumbnailFrame
import com.oneononearena.videoclip.UnsupportedCode
import com.oneononearena.videoclip.ValidationCode
import com.oneononearena.videoclip.VideoEditFailure
import com.oneononearena.videoclip.VideoMetadata
import kotlinx.coroutines.flow.Flow

internal data class EngineSource(val absolutePath: String)

internal enum class EngineVideoCodec { AVC, HEVC, OTHER }

internal enum class EngineAudioCodec { AAC, OTHER }

internal data class EngineStreamTopology(
    val videoCodec: EngineVideoCodec,
    val audioCodec: EngineAudioCodec?,
    val isHdr: Boolean,
)

internal sealed interface EngineProbeResult {
    data class Success(
        val metadata: VideoMetadata,
        val topology: EngineStreamTopology,
    ) : EngineProbeResult

    data class Unsupported(
        val code: UnsupportedCode,
        val diagnostic: String?,
    ) : EngineProbeResult

    data class Failed(val failure: VideoEditFailure) : EngineProbeResult
}

internal data class EngineFrameRequest(
    val frameCount: Int,
    val maximumDimensionPx: Int,
)

internal sealed interface EngineFrameEvent {
    data class Frame(val value: ThumbnailFrame) : EngineFrameEvent
    data class Progress(val emitted: Int, val total: Int) : EngineFrameEvent
    data object Complete : EngineFrameEvent
    data class InvalidRequest(val code: ValidationCode, val diagnostic: String?) : EngineFrameEvent
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : EngineFrameEvent
    data class Failed(val failure: VideoEditFailure) : EngineFrameEvent
}

internal data class EngineExportRequest(
    val source: EngineSource,
    val range: ClipRange,
    val outputPath: String,
)

internal sealed interface EngineExportResult {
    data object Success : EngineExportResult
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : EngineExportResult
    data class Failed(val failure: VideoEditFailure) : EngineExportResult
}

internal interface ClipMediaEngine {
    suspend fun probe(source: EngineSource): EngineProbeResult
    fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent>
    suspend fun export(request: EngineExportRequest): EngineExportResult
    suspend fun cancelActiveExport()
}
