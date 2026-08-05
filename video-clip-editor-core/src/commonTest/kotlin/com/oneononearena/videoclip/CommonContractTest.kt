package com.oneononearena.videoclip

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
}
