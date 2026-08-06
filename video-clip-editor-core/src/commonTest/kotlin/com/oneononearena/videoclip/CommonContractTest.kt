package com.oneononearena.videoclip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CommonContractTest {
    @Test
    fun configurationUsesFrozenDefaults() {
        val configuration = VideoClipEditorConfiguration()

        assertEquals(500.milliseconds, configuration.minimumClipDuration)
        assertEquals(24, configuration.maximumFrameCount)
        assertEquals(160, configuration.maximumThumbnailDimensionPx)
    }

    @Test
    fun configurationRejectsValuesOutsideFrozenHardLimits() {
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(minimumClipDuration = 499.milliseconds)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(maximumFrameCount = 25)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(maximumThumbnailDimensionPx = 161)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(maximumFrameCount = 0)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(maximumThumbnailDimensionPx = 0)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(minimumClipDuration = 501.milliseconds)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(maximumFrameCount = 23)
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            VideoClipEditorConfiguration(maximumThumbnailDimensionPx = 159)
        }.exceptionOrNull())
    }

    @Test
    fun configurationAcceptsFrozenHardLimitsExactly() {
        VideoClipEditorConfiguration(
            minimumClipDuration = 500.milliseconds,
            maximumFrameCount = 24,
            maximumThumbnailDimensionPx = 160,
        )
    }

    @Test
    fun frameRequestValidationAcceptsOnlyOneThroughConfiguredMaximum() {
        val configuration = VideoClipEditorConfiguration()

        assertEquals(null, CommonValidation.frameRequest(FrameStripRequest(1), configuration))
        assertEquals(null, CommonValidation.frameRequest(FrameStripRequest(24), configuration))
        assertEquals(ValidationCode.INVALID_FRAME_REQUEST, CommonValidation.frameRequest(FrameStripRequest(0), configuration))
        assertEquals(ValidationCode.INVALID_FRAME_REQUEST, CommonValidation.frameRequest(FrameStripRequest(25), configuration))
    }

    @Test
    fun clipRangeValidationUsesMetadataAndFrozenMinimum() {
        val metadata = VideoMetadata(10.seconds, 1920, 1080, hasAudio = true)
        val configuration = VideoClipEditorConfiguration()

        assertEquals(null, CommonValidation.clipRange(ClipRange(1.seconds, 2.seconds), metadata, configuration))
        assertEquals(ValidationCode.RANGE_NEGATIVE, CommonValidation.clipRange(ClipRange((-1).seconds, 1.seconds), metadata, configuration))
        assertEquals(ValidationCode.RANGE_ORDER_INVALID, CommonValidation.clipRange(ClipRange(2.seconds, 2.seconds), metadata, configuration))
        assertEquals(ValidationCode.RANGE_BELOW_MINIMUM, CommonValidation.clipRange(ClipRange(1.seconds, 1499.milliseconds), metadata, configuration))
        assertEquals(ValidationCode.RANGE_EXCEEDS_DURATION, CommonValidation.clipRange(ClipRange(9.seconds, 11.seconds), metadata, configuration))
    }

    @Test
    fun sourcePathValidationRejectsRelativeAndTemporaryPaths() {
        assertEquals(
            ValidationCode.PATH_NOT_ABSOLUTE,
            CommonValidation.sourcePath(VideoSourcePath("input.mp4"), temporaryRoot = "/tmp/editor"),
        )
        assertEquals(
            ValidationCode.SOURCE_INSIDE_TEMP_ROOT,
            CommonValidation.sourcePath(VideoSourcePath("/tmp/editor/output.mp4"), temporaryRoot = "/tmp/editor"),
        )
        assertEquals(
            ValidationCode.SOURCE_INSIDE_TEMP_ROOT,
            CommonValidation.sourcePath(VideoSourcePath("/tmp/editor"), temporaryRoot = "/tmp/editor/"),
        )
        assertEquals(
            null,
            CommonValidation.sourcePath(VideoSourcePath("/storage/input.mp4"), temporaryRoot = "/tmp/editor"),
        )
    }

    @Test
    fun thumbnailCopiesInputAndReturnedBytes() {
        val source = byteArrayOf(1, 2, 3)
        val frame = ThumbnailFrame(1.seconds, 1.seconds, 160, 90, source)
        source[0] = 9
        val firstCopy = frame.copyEncodedJpeg()
        firstCopy[1] = 8

        assertContentEquals(byteArrayOf(1, 2, 3), frame.copyEncodedJpeg())
    }

    @Test
    fun thumbnailRejectsOversizeDimensionsAndData() {
        assertIs<IllegalArgumentException>(runCatching {
            ThumbnailFrame(1.seconds, 1.seconds, 161, 90, byteArrayOf())
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            ThumbnailFrame(1.seconds, 1.seconds, 160, 91, byteArrayOf())
        }.exceptionOrNull())
        assertIs<IllegalArgumentException>(runCatching {
            ThumbnailFrame(1.seconds, 1.seconds, 160, 90, ByteArray(64 * 1024 + 1))
        }.exceptionOrNull())
    }

    @Test
    fun stableResultCodesAreRepresentable() {
        assertEquals(
            listOf(
                "IOS_ENGINE_UNAVAILABLE", "UNSUPPORTED_CONTAINER", "UNSUPPORTED_VIDEO_CODEC",
                "UNSUPPORTED_AUDIO_CODEC", "DRM_PROTECTED", "HDR_UNSUPPORTED", "INPUT_TOO_LARGE",
                "INPUT_TOO_LONG", "DEVICE_ENCODER_UNAVAILABLE",
            ),
            UnsupportedCode.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "PATH_NOT_ABSOLUTE", "PATH_NOT_REGULAR_FILE", "SOURCE_INSIDE_TEMP_ROOT",
                "RANGE_NEGATIVE", "RANGE_ORDER_INVALID", "RANGE_BELOW_MINIMUM", "RANGE_EXCEEDS_DURATION",
                "INVALID_FRAME_REQUEST", "SESSION_CLOSED", "OPERATION_IN_PROGRESS",
            ),
            ValidationCode.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "METADATA_READ_FAILED", "FRAME_EXTRACTION_FAILED", "EXPORT_FAILED", "EXPORT_CANCELLED",
                "TEMP_CREATE_FAILED", "TEMP_RENAME_FAILED", "TEMP_DELETE_FAILED",
            ),
            FailureCode.entries.map { it.name },
        )
        assertEquals("Cleared", TempDeleteResult.Cleared::class.simpleName)
        assertEquals("AlreadyCleared", TempDeleteResult.AlreadyCleared::class.simpleName)
    }

    @Test
    fun resultVariantsRemainExhaustive() {
        val failure = VideoEditFailure(FailureCode.EXPORT_FAILED, retryable = false, diagnostic = null)
        val session = object : ClipEditorSession {
            override val metadata = VideoMetadata(1.seconds, 1, 1, hasAudio = false)
            override fun frames(request: FrameStripRequest) = kotlinx.coroutines.flow.emptyFlow<FrameStripEvent>()
            override suspend fun createClip(range: ClipRange): ClipResult = ClipResult.Failed(failure)
            override suspend fun close() = Unit
        }
        val lease = object : TemporaryClipLease {
            override val file = TemporaryVideoFile("/tmp/clip.mp4", "test")
            override suspend fun clearTemporaryFile() = TempDeleteResult.Cleared
        }
        val range = ClipRange(Duration.ZERO, 500.milliseconds)

        assertEquals("open", openVariant(OpenSessionResult.Open(session)))
        assertEquals("unsupported", openVariant(OpenSessionResult.Unsupported(UnsupportedCode.HDR_UNSUPPORTED, null)))
        assertEquals("invalid", openVariant(OpenSessionResult.InvalidRequest(ValidationCode.PATH_NOT_ABSOLUTE, null)))
        assertEquals("failed", openVariant(OpenSessionResult.Failed(failure)))

        assertEquals("frame", frameVariant(FrameStripEvent.Frame(ThumbnailFrame(Duration.ZERO, Duration.ZERO, 1, 1, byteArrayOf()))))
        assertEquals("progress", frameVariant(FrameStripEvent.Progress(1, 1)))
        assertEquals("complete", frameVariant(FrameStripEvent.Complete))
        assertEquals("invalid", frameVariant(FrameStripEvent.InvalidRequest(ValidationCode.INVALID_FRAME_REQUEST, null)))
        assertEquals("unsupported", frameVariant(FrameStripEvent.Unsupported(UnsupportedCode.HDR_UNSUPPORTED, null)))
        assertEquals("failed", frameVariant(FrameStripEvent.Failed(failure)))

        assertEquals("success", clipVariant(ClipResult.Success(lease, range)))
        assertEquals("unsupported", clipVariant(ClipResult.Unsupported(UnsupportedCode.HDR_UNSUPPORTED, null)))
        assertEquals("invalid", clipVariant(ClipResult.InvalidRequest(ValidationCode.RANGE_ORDER_INVALID, null)))
        assertEquals("failed", clipVariant(ClipResult.Failed(failure)))

        assertEquals("cleared", deleteVariant(TempDeleteResult.Cleared))
        assertEquals("alreadyCleared", deleteVariant(TempDeleteResult.AlreadyCleared))
        assertEquals("failed", deleteVariant(TempDeleteResult.Failed(failure)))
    }

    @Test
    fun interfaceOperationsRetainFrozenSignatures() {
        val openSession: suspend VideoClipEditor.(VideoSourcePath) -> OpenSessionResult = VideoClipEditor::openSession
        val frames: ClipEditorSession.(FrameStripRequest) -> kotlinx.coroutines.flow.Flow<FrameStripEvent> = ClipEditorSession::frames
        val createClip: suspend ClipEditorSession.(ClipRange) -> ClipResult = ClipEditorSession::createClip
        val close: suspend ClipEditorSession.() -> Unit = ClipEditorSession::close
        val clear: suspend TemporaryClipLease.() -> TempDeleteResult = TemporaryClipLease::clearTemporaryFile

        assertEquals(5, listOf(openSession, frames, createClip, close, clear).size)
    }

    private fun openVariant(result: OpenSessionResult): String = when (result) {
        is OpenSessionResult.Open -> "open"
        is OpenSessionResult.Unsupported -> "unsupported"
        is OpenSessionResult.InvalidRequest -> "invalid"
        is OpenSessionResult.Failed -> "failed"
    }

    private fun frameVariant(event: FrameStripEvent): String = when (event) {
        is FrameStripEvent.Frame -> "frame"
        is FrameStripEvent.Progress -> "progress"
        FrameStripEvent.Complete -> "complete"
        is FrameStripEvent.InvalidRequest -> "invalid"
        is FrameStripEvent.Unsupported -> "unsupported"
        is FrameStripEvent.Failed -> "failed"
    }

    private fun clipVariant(result: ClipResult): String = when (result) {
        is ClipResult.Success -> "success"
        is ClipResult.Unsupported -> "unsupported"
        is ClipResult.InvalidRequest -> "invalid"
        is ClipResult.Failed -> "failed"
    }

    private fun deleteVariant(result: TempDeleteResult): String = when (result) {
        TempDeleteResult.Cleared -> "cleared"
        TempDeleteResult.AlreadyCleared -> "alreadyCleared"
        is TempDeleteResult.Failed -> "failed"
    }
}
