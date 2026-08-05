package com.oneononearena.videoclip

import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.jvm.JvmInline

@JvmInline
value class VideoSourcePath(val value: String)

data class ClipRange(
    val start: Duration,
    val endExclusive: Duration,
)

data class VideoClipEditorConfiguration(
    val minimumClipDuration: Duration = 500.milliseconds,
    val maximumFrameCount: Int = 24,
    val maximumThumbnailDimensionPx: Int = 160,
) {
    init {
        require(minimumClipDuration >= 500.milliseconds)
        require(maximumFrameCount in 1..24)
        require(maximumThumbnailDimensionPx in 1..160)
    }
}

data class FrameStripRequest(
    val frameCount: Int,
)

data class VideoMetadata(
    val duration: Duration,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val hasAudio: Boolean,
)

interface VideoClipEditor {
    suspend fun openSession(source: VideoSourcePath): OpenSessionResult
}

interface ClipEditorSession {
    val metadata: VideoMetadata

    fun frames(request: FrameStripRequest): Flow<FrameStripEvent>

    suspend fun createClip(range: ClipRange): ClipResult

    suspend fun close()
}

interface TemporaryClipLease {
    val file: TemporaryVideoFile

    suspend fun clearTemporaryFile(): TempDeleteResult
}

class ThumbnailFrame internal constructor(
    val requestedTime: Duration,
    val actualTime: Duration,
    val widthPx: Int,
    val heightPx: Int,
    encodedJpeg: ByteArray,
) {
    private val bytes: ByteArray

    init {
        require(widthPx in 1..MAX_WIDTH_PX)
        require(heightPx in 1..MAX_HEIGHT_PX)
        require(encodedJpeg.size <= MAX_ENCODED_JPEG_BYTES)
        bytes = encodedJpeg.copyOf()
    }

    fun copyEncodedJpeg(): ByteArray = bytes.copyOf()

    internal companion object {
        const val MAX_WIDTH_PX: Int = 160
        const val MAX_HEIGHT_PX: Int = 90
        const val MAX_ENCODED_JPEG_BYTES: Int = 64 * 1024
    }
}

sealed interface OpenSessionResult {
    data class Open(val session: ClipEditorSession) : OpenSessionResult
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : OpenSessionResult
    data class InvalidRequest(val code: ValidationCode, val diagnostic: String?) : OpenSessionResult
    data class Failed(val failure: VideoEditFailure) : OpenSessionResult
}

sealed interface FrameStripEvent {
    data class Frame(val value: ThumbnailFrame) : FrameStripEvent
    data class Progress(val emitted: Int, val total: Int) : FrameStripEvent
    data object Complete : FrameStripEvent
    data class InvalidRequest(val code: ValidationCode, val diagnostic: String?) : FrameStripEvent
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : FrameStripEvent
    data class Failed(val error: VideoEditFailure) : FrameStripEvent
}

sealed interface ClipResult {
    data class Success(
        val output: TemporaryClipLease,
        val sourceRange: ClipRange,
    ) : ClipResult

    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : ClipResult
    data class InvalidRequest(val code: ValidationCode, val diagnostic: String?) : ClipResult
    data class Failed(val failure: VideoEditFailure) : ClipResult
}

class TemporaryVideoFile internal constructor(
    val absolutePath: String,
    internal val opaqueId: String,
)

enum class UnsupportedCode {
    IOS_ENGINE_UNAVAILABLE,
    UNSUPPORTED_CONTAINER,
    UNSUPPORTED_VIDEO_CODEC,
    UNSUPPORTED_AUDIO_CODEC,
    DRM_PROTECTED,
    HDR_UNSUPPORTED,
    INPUT_TOO_LARGE,
    INPUT_TOO_LONG,
    DEVICE_ENCODER_UNAVAILABLE,
}

enum class ValidationCode {
    PATH_NOT_ABSOLUTE,
    PATH_NOT_REGULAR_FILE,
    SOURCE_INSIDE_TEMP_ROOT,
    RANGE_NEGATIVE,
    RANGE_ORDER_INVALID,
    RANGE_BELOW_MINIMUM,
    RANGE_EXCEEDS_DURATION,
    INVALID_FRAME_REQUEST,
    SESSION_CLOSED,
    OPERATION_IN_PROGRESS,
}

enum class FailureCode {
    METADATA_READ_FAILED,
    FRAME_EXTRACTION_FAILED,
    EXPORT_FAILED,
    EXPORT_CANCELLED,
    TEMP_CREATE_FAILED,
    TEMP_RENAME_FAILED,
    TEMP_DELETE_FAILED,
}

data class VideoEditFailure(
    val code: FailureCode,
    val retryable: Boolean,
    val diagnostic: String?,
)

sealed interface TempDeleteResult {
    data object Cleared : TempDeleteResult
    data object AlreadyCleared : TempDeleteResult
    data class Failed(val failure: VideoEditFailure) : TempDeleteResult
}

internal object CommonValidation {
    fun sourcePath(source: VideoSourcePath, temporaryRoot: String?): ValidationCode? = when {
        !source.value.startsWith('/') -> ValidationCode.PATH_NOT_ABSOLUTE
        temporaryRoot != null && source.value.isWithin(temporaryRoot) -> ValidationCode.SOURCE_INSIDE_TEMP_ROOT
        else -> null
    }

    fun frameRequest(
        request: FrameStripRequest,
        configuration: VideoClipEditorConfiguration,
    ): ValidationCode? = if (request.frameCount in 1..configuration.maximumFrameCount) {
        null
    } else {
        ValidationCode.INVALID_FRAME_REQUEST
    }

    fun clipRange(
        range: ClipRange,
        metadata: VideoMetadata,
        configuration: VideoClipEditorConfiguration,
    ): ValidationCode? = when {
        range.start.isNegative() || range.endExclusive.isNegative() -> ValidationCode.RANGE_NEGATIVE
        range.endExclusive <= range.start -> ValidationCode.RANGE_ORDER_INVALID
        range.endExclusive - range.start < configuration.minimumClipDuration -> ValidationCode.RANGE_BELOW_MINIMUM
        range.endExclusive > metadata.duration -> ValidationCode.RANGE_EXCEEDS_DURATION
        else -> null
    }

    private fun String.isWithin(root: String): Boolean {
        val normalizedRoot = root.trimEnd('/')
        return this == normalizedRoot || startsWith("$normalizedRoot/")
    }
}
