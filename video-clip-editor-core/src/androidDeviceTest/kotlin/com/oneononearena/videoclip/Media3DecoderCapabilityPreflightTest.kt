package com.oneononearena.videoclip

import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Media3DecoderCapabilityPreflightTest {
    @Test
    fun unavailableAvcDecoder_returnsUnsupportedBeforeSessionOrExportStarts() = runTest {
        val capability = RecordingDecoderCapability(available = false)
        val result = openWithFixture("avc-aac-10s-30fps.mp4", capability)

        assertEquals(
            OpenSessionResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, MimeTypes.VIDEO_H264),
            result,
        )
        assertEquals(listOf(MimeTypes.VIDEO_H264), capability.queriedMimes)
        assertFalse(result is OpenSessionResult.Open)
    }

    @Test
    fun unavailableHevcDecoder_returnsUnsupportedBeforeSessionOrExportStarts() = runTest {
        val capability = RecordingDecoderCapability(available = false)
        val result = openWithFixture("aosp-cts-hevc-aac-480x360-10s.mp4", capability)

        assertEquals(
            OpenSessionResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, MimeTypes.VIDEO_H265),
            result,
        )
        assertEquals(listOf(MimeTypes.VIDEO_H265), capability.queriedMimes)
        assertFalse(result is OpenSessionResult.Open)
    }

    @Test
    fun availableAvcDecoder_keepsExistingOpenSessionPath() = runTest {
        val capability = RecordingDecoderCapability(available = true)
        val result = openWithFixture("avc-aac-10s-30fps.mp4", capability)

        assertEquals(listOf(MimeTypes.VIDEO_H264), capability.queriedMimes)
        assertTrue("Expected open session, got $result", result is OpenSessionResult.Open)
        (result as OpenSessionResult.Open).session.close()
    }

    private suspend fun openWithFixture(
        assetName: String,
        capability: AndroidDecoderCapability,
    ): OpenSessionResult {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "decoder-preflight-${System.nanoTime()}.mp4")
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$assetName").use { input ->
                source.outputStream().use(input::copyTo)
            }
            return createAndroidVideoClipEditor(
                context,
                VideoClipEditorConfiguration(),
                capability,
            ).openSession(VideoSourcePath(source.absolutePath))
        } finally {
            source.delete()
        }
    }

    private class RecordingDecoderCapability(
        private val available: Boolean,
    ) : AndroidDecoderCapability {
        val queriedMimes = mutableListOf<String>()

        override fun isAvailable(mimeType: String): Boolean {
            queriedMimes += mimeType
            return available
        }
    }
}
