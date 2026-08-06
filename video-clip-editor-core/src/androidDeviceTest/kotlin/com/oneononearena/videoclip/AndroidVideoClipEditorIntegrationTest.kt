package com.oneononearena.videoclip

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineAudioCodec
import com.oneononearena.videoclip.internal.engine.EngineExportRequest
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import com.oneononearena.videoclip.internal.engine.EngineFrameEvent
import com.oneononearena.videoclip.internal.engine.EngineFrameRequest
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVideoClipEditorIntegrationTest {
    @Test
    fun factory_export_issues_then_clears_temporary_lease() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = copyFixture(context)
        try {
            val editor = createAndroidVideoClipEditor(context)
            val session = openSession(editor, source)
            val result = session.createClip(ClipRange(2_000.milliseconds, 7_000.milliseconds))
            assertTrue(result is ClipResult.Success)
            val success = result as ClipResult.Success

            assertTrue(success.output.file.absolutePath.startsWith(File(context.cacheDir, "video-clip-editor").absolutePath))
            assertTrue(File(success.output.file.absolutePath).isFile())
            assertEquals(TempDeleteResult.Cleared, success.output.clearTemporaryFile())
            assertFalse(File(success.output.file.absolutePath).exists())
            assertEquals(TempDeleteResult.AlreadyCleared, success.output.clearTemporaryFile())
            session.close()
        } finally {
            source.delete()
        }
    }

    @Test
    fun frames_called_after_close_emits_session_closed_terminal_event() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = copyFixture(context)
        try {
            val editor = createAndroidVideoClipEditor(context)
            val session = openSession(editor, source)
            session.close()

            assertEquals(
                listOf(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null)),
                session.frames(FrameStripRequest(1)).toList(),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun factory_rejects_sparse_input_larger_than_512_mib_before_media_probe() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "android-adapter-oversize-${System.nanoTime()}.mp4")
        try {
            RandomAccessFile(source, "rw").use { it.setLength(512L * 1024L * 1024L + 1L) }

            assertEquals(
                OpenSessionResult.Unsupported(UnsupportedCode.INPUT_TOO_LARGE, source.length().toString()),
                createAndroidVideoClipEditor(context).openSession(VideoSourcePath(source.absolutePath)),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun injected_engine_routes_probe_frames_export_and_close_cancellation() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "engine-injection-${System.nanoTime()}.mp4").apply { writeBytes(byteArrayOf(0)) }
        val engine = RecordingClipMediaEngine()
        try {
            val session = openSession(createAndroidVideoClipEditor(context, VideoClipEditorConfiguration(), engine), source)

            assertEquals(listOf(FrameStripEvent.Complete), session.frames(FrameStripRequest(1)).toList())
            assertTrue(session.createClip(ClipRange(0.milliseconds, 1_000.milliseconds)) is ClipResult.Success)
            session.close()

            assertEquals(1, engine.probeCalls)
            assertEquals(1, engine.framesCalls)
            assertEquals(1, engine.exportCalls)
            assertEquals(1, engine.cancelCalls)
        } finally {
            source.delete()
        }
    }

    private class RecordingClipMediaEngine : ClipMediaEngine {
        var probeCalls = 0
        var framesCalls = 0
        var exportCalls = 0
        var cancelCalls = 0

        override suspend fun probe(source: EngineSource): EngineProbeResult {
            probeCalls += 1
            return EngineProbeResult.Success(
                metadata = VideoMetadata(10_000.milliseconds, 640, 480, true),
                topology = EngineStreamTopology(EngineVideoCodec.AVC, EngineAudioCodec.AAC, false),
            )
        }

        override fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent> {
            framesCalls += 1
            return flowOf(EngineFrameEvent.Complete)
        }

        override suspend fun export(request: EngineExportRequest): EngineExportResult {
            exportCalls += 1
            File(request.outputPath).writeBytes(byteArrayOf(0))
            return EngineExportResult.Success
        }

        override suspend fun cancelActiveExport() {
            cancelCalls += 1
        }
    }

    private fun copyFixture(context: android.content.Context): File {
        val target = File(context.cacheDir, "android-adapter-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/avc-aac-10s-30fps.mp4").use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private suspend fun openSession(editor: VideoClipEditor, source: File): ClipEditorSession {
        val result = editor.openSession(VideoSourcePath(source.absolutePath))
        assertTrue("Expected open session, got $result", result is OpenSessionResult.Open)
        return (result as OpenSessionResult.Open).session
    }
}
