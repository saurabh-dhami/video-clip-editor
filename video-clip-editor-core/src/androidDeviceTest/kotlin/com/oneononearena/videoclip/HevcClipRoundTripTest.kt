package com.oneononearena.videoclip

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HevcClipRoundTripTest {
    @Test
    fun productionFactoryClipsSdrHevcToOwnedH264AacMp4WithoutMutatingSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = copyFixture(context)
        val sourceHash = sha256(source)
        assertEquals("887363f6bcb9c270fb6e84b61177b65ae2db2a6fb65f132a94bac4a1d518dda5", sourceHash)
        val range = ClipRange(500.milliseconds, 2_500.milliseconds)
        try {
            val session = openSession(createAndroidVideoClipEditor(context), source)
            val frames = session.frames(FrameStripRequest(3)).toList()
            assertEquals(3, frames.filterIsInstance<FrameStripEvent.Frame>().size)
            assertTrue(frames.last() is FrameStripEvent.Complete)

            when (val result = session.createClip(range)) {
                is ClipResult.Success -> {
                    val output = File(result.output.file.absolutePath)
                    try {
                        val outputPath = output.absolutePath
                        val videoMime = trackMime(output, "video/")
                        val audioMime = trackMime(output, "audio/")
                        assertEquals(range, result.sourceRange)
                        assertTrue(outputPath.startsWith(File(context.cacheDir, "video-clip-editor").absolutePath))
                        assertTrue(output.isFile)
                        assertEquals("video/avc", videoMime)
                        assertEquals("audio/mp4a-latm", audioMime)
                        assertTrue(outputDurationMs(output) in 1_500L..2_500L)
                        assertEquals(sourceHash, sha256(source))
                        val cleanup = result.output.clearTemporaryFile()
                        val existsAfterCleanup = output.exists()
                        Log.i(
                            HEVC_EVIDENCE_TAG,
                            "HEVC_OUTPUT path=$outputPath videoMime=$videoMime audioMime=$audioMime " +
                                "cleanup=$cleanup existsAfterCleanup=$existsAfterCleanup",
                        )
                        assertEquals(TempDeleteResult.Cleared, cleanup)
                        assertFalse(existsAfterCleanup)
                        assertEquals(TempDeleteResult.AlreadyCleared, result.output.clearTemporaryFile())
                    } finally {
                        output.delete()
                    }
                }
                is ClipResult.Unsupported -> assertEquals(UnsupportedCode.DEVICE_ENCODER_UNAVAILABLE, result.code)
                else -> fail("Expected successful HEVC round trip or typed device capability result, got $result")
            }
            session.close()
        } finally {
            source.delete()
        }
    }

    private fun copyFixture(context: Context): File {
        val target = File(context.cacheDir, "hevc-round-trip-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("fixtures/aosp-cts-hevc-aac-480x360-10s.mp4")
            .use { input -> target.outputStream().use(input::copyTo) }
        return target
    }

    private suspend fun openSession(editor: VideoClipEditor, source: File): ClipEditorSession {
        val result = editor.openSession(VideoSourcePath(source.absolutePath))
        assertTrue("Expected open session, got $result", result is OpenSessionResult.Open)
        return (result as OpenSessionResult.Open).session
    }

    private fun trackMime(file: File, prefix: String): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map(extractor::getTrackFormat)
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
                ?.getString(MediaFormat.KEY_MIME)
        } finally {
            extractor.release()
        }
    }

    private fun outputDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            checkNotNull(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong())
        } finally {
            retriever.release()
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private companion object {
        const val HEVC_EVIDENCE_TAG = "VideoClipEditorHevc"
    }
}
