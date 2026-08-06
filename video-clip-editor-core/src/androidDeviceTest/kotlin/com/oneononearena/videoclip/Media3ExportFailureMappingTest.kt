package com.oneononearena.videoclip

import androidx.media3.transformer.ExportException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Media3ExportFailureMappingTest {
    @Test
    fun hevcDecoderInitializationFailure_isReportedAsTypedExportFailure() {
        assertEquals(
            EngineExportResult.Failed(
                VideoEditFailure(FailureCode.EXPORT_FAILED, retryable = true, diagnostic = "HEVC decoder init"),
            ),
            media3ExportFailure(ExportException.ERROR_CODE_DECODER_INIT_FAILED, "HEVC decoder init"),
        )
    }

    @Test
    fun decodingFormatUnsupported_isReportedAsTypedExportFailure() {
        assertEquals(
            EngineExportResult.Failed(
                VideoEditFailure(FailureCode.EXPORT_FAILED, retryable = true, diagnostic = "decoder format"),
            ),
            media3ExportFailure(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, "decoder format"),
        )
    }

    @Test
    fun encoderInitializationFailure_isReportedAsEncoderUnavailable() {
        assertEquals(
            EngineExportResult.Unsupported(UnsupportedCode.DEVICE_ENCODER_UNAVAILABLE, "encoder init"),
            media3ExportFailure(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, "encoder init"),
        )
    }

    @Test
    fun encodingFormatUnsupported_isReportedAsTypedExportFailure() {
        assertEquals(
            EngineExportResult.Failed(
                VideoEditFailure(FailureCode.EXPORT_FAILED, retryable = true, diagnostic = "encoder format"),
            ),
            media3ExportFailure(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, "encoder format"),
        )
    }
}
