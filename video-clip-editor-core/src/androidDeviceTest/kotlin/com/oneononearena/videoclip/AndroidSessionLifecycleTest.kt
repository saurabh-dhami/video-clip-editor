package com.oneononearena.videoclip

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineExportRequest
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import com.oneononearena.videoclip.internal.engine.EngineFrameEvent
import com.oneononearena.videoclip.internal.engine.EngineFrameRequest
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSessionLifecycleTest {
    @Test
    fun framesAfterCloseEmitsSessionClosed() = runTest {
        val fixture = sessionWithFakeEngine(flowOf(EngineFrameEvent.Complete))
        try {
            fixture.session.close()

            assertEquals(
                listOf(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null)),
                fixture.session.frames(FrameStripRequest(1)).toList(),
            )
        } finally {
            fixture.source.delete()
        }
    }

    @Test
    fun collectorCloseDoesNotDeadlockFrameEmission() = runTest {
        val fixture = sessionWithFakeEngine(
            flowOf(EngineFrameEvent.Frame(ThumbnailFrame(0.milliseconds, 0.milliseconds, 1, 1, byteArrayOf()))),
        )
        try {
            withTimeout(1.seconds) {
                fixture.session.frames(FrameStripRequest(1)).collect { event ->
                    if (event is FrameStripEvent.Frame) fixture.session.close()
                }
            }
        } finally {
            fixture.source.delete()
        }
    }

    @Test
    fun repeatedCloseRunsResourceShutdownOnce() = runTest {
        val lifecycle = AndroidSessionLifecycle()
        var shutdownCalls = 0

        lifecycle.close { shutdownCalls += 1 }
        lifecycle.close { shutdownCalls += 1 }

        assertTrue(lifecycle.isClosed())
        assertEquals(1, shutdownCalls)
    }

    private suspend fun sessionWithFakeEngine(frameEvents: Flow<EngineFrameEvent>): SessionFixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "session-lifecycle-${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(0))
        }
        val editor = createAndroidVideoClipEditor(
            context,
            VideoClipEditorConfiguration(),
            FakeClipMediaEngine(frameEvents),
        )
        val open = editor.openSession(VideoSourcePath(source.absolutePath))
        assertTrue("Expected open session, got $open", open is OpenSessionResult.Open)
        return SessionFixture((open as OpenSessionResult.Open).session, source)
    }

    private data class SessionFixture(
        val session: ClipEditorSession,
        val source: File,
    )

    private class FakeClipMediaEngine(
        private val frameEvents: Flow<EngineFrameEvent>,
    ) : ClipMediaEngine {
        override suspend fun probe(source: EngineSource): EngineProbeResult = EngineProbeResult.Success(
            metadata = VideoMetadata(10_000.milliseconds, 640, 480, false),
            topology = EngineStreamTopology(EngineVideoCodec.AVC, null, false),
        )

        override fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent> = frameEvents

        override suspend fun export(request: EngineExportRequest): EngineExportResult = EngineExportResult.Success

        override suspend fun cancelActiveExport() = Unit
    }
}
