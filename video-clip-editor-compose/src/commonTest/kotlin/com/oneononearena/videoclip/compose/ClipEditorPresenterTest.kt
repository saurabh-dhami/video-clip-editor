package com.oneononearena.videoclip.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.FailureCode
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.TemporaryClipLease
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoEditFailure
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class ClipEditorPresenterTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun disposingClipEditorScreenClosesOpenedSession() = runComposeUiTest {
        val session = FakeSession(flow { emit(FrameStripEvent.Complete) })
        var visible by mutableStateOf(true)

        setContent {
            if (visible) {
                ClipEditorScreen(
                    source = VideoSourcePath("/video.mp4"),
                    editor = FakeEditor(session),
                    onResult = {},
                    onCancel = {},
                )
            }
        }
        waitForIdle()

        runOnIdle { visible = false }

        waitUntil("ClipEditorScreen disposal must close its session") { session.closed.isCompleted }
        assertTrue(session.closed.isCompleted)
    }

    @Test
    fun `frames become ready only after complete`() = runTest {
        val presenter = ClipEditorPresenter(this, {})

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(FakeSession(flow {
            emit(FrameStripEvent.Complete)
        })))
        testScheduler.advanceUntilIdle()

        val ready = assertIs<ClipEditorUiState.Ready>(presenter.state.value)
        assertEquals(emptyList(), ready.frames)
    }

    @Test
    fun `frame collection falls back to the configured lower maximum`() = runTest {
        val session = LowerFrameLimitSession(maximumFrameCount = 3)
        val presenter = ClipEditorPresenter(this, {})

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(session))
        testScheduler.advanceUntilIdle()

        assertIs<ClipEditorUiState.Ready>(presenter.state.value)
        assertEquals((24 downTo 3).toList(), session.requests)
    }

    @Test
    fun `range handles retain at least 500 milliseconds`() = runTest {
        val presenter = ClipEditorPresenter(this, {})
        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(FakeSession(flow { emit(FrameStripEvent.Complete) })))
        testScheduler.advanceUntilIdle()

        presenter.updateStart(9_900.milliseconds)
        presenter.updateEnd(100.milliseconds)

        val ready = assertIs<ClipEditorUiState.Ready>(presenter.state.value)
        assertEquals(9_500.milliseconds, ready.range.start)
        assertEquals(10_000.milliseconds, ready.range.endExclusive)
    }

    @Test
    fun `timeline mapping clamps positions at both track edges`() {
        assertEquals(0.milliseconds, toDuration(-4f, 100, 10_000))
        assertEquals(10_000.milliseconds, toDuration(104f, 100, 10_000))
        assertEquals(0f, toPosition((-1).milliseconds, 10_000, 100))
        assertEquals(100f, toPosition(12_000.milliseconds, 10_000, 100))
    }

    @Test
    fun `timeline mapping keeps 48dp edge targets fully inside track`() {
        val edgeInsetPx = 24f

        assertEquals(0.milliseconds, toDuration(24f, 100, 10_000, edgeInsetPx))
        assertEquals(10_000.milliseconds, toDuration(76f, 100, 10_000, edgeInsetPx))
        assertEquals(24f, toPosition(0.milliseconds, 10_000, 100, edgeInsetPx))
        assertEquals(76f, toPosition(10_000.milliseconds, 10_000, 100, edgeInsetPx))
    }

    @Test
    fun `fractional density target stays within full measured track bounds`() {
        val targetWidthPx = 53 // 48dp rounded at density 1.1
        val trackWidthPx = 200
        val edgeInsetPx = targetWidthPx / 2f
        val startPosition = toPosition(0.milliseconds, 10_000, trackWidthPx, edgeInsetPx)
        val endPosition = toPosition(10_000.milliseconds, 10_000, trackWidthPx, edgeInsetPx)

        assertEquals(0, timelineHandleOffsetPx(startPosition, targetWidthPx))
        assertEquals(trackWidthPx - targetWidthPx, timelineHandleOffsetPx(endPosition, targetWidthPx))
        assertEquals(0.milliseconds, toDuration(startPosition, trackWidthPx, 10_000, edgeInsetPx))
        assertEquals(10_000.milliseconds, toDuration(endPosition, trackWidthPx, 10_000, edgeInsetPx))
    }

    @Test
    fun `retry closes current session before opening next session`() = runTest {
        val first = FakeSession(flow { emit(FrameStripEvent.Complete) })
        val second = FakeSession(flow { emit(FrameStripEvent.Complete) })
        val editor = SequentialFakeEditor(first, second)
        val presenter = ClipEditorPresenter(this, {})

        presenter.start(VideoSourcePath("/video.mp4"), editor)
        testScheduler.advanceUntilIdle()
        presenter.retry()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("open-0", "close-0", "open-1"), editor.events)
        assertEquals(1, first.closeCalls)
    }

    @Test
    fun `create submission and result callback happen once`() = runTest {
        var callbacks = 0
        val session = FakeSession(flow { emit(FrameStripEvent.Complete) })
        val presenter = ClipEditorPresenter(this, { callbacks++ })
        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(session))
        testScheduler.advanceUntilIdle()

        presenter.createClip()
        presenter.createClip()
        testScheduler.advanceUntilIdle()

        assertEquals(1, session.createCalls)
        assertEquals(1, callbacks)
        assertIs<ClipEditorUiState.Terminal>(presenter.state.value)
    }

    @Test
    fun `complete after failed frame strip cannot replace terminal failure`() = runTest {
        val presenter = ClipEditorPresenter(this, {})
        val failure = VideoEditFailure(FailureCode.FRAME_EXTRACTION_FAILED, false, "bad frame")

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(FakeSession(flow {
            emit(FrameStripEvent.Failed(failure))
            emit(FrameStripEvent.Complete)
        })))
        testScheduler.advanceUntilIdle()

        assertIs<ClipEditorUiState.Terminal>(presenter.state.value)
    }

    @Test
    fun `complete after invalid frame strip cannot replace terminal result`() = runTest {
        val presenter = ClipEditorPresenter(this, {})

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(FakeSession(flow {
            emit(FrameStripEvent.InvalidRequest(com.oneononearena.videoclip.ValidationCode.INVALID_FRAME_REQUEST, "bad request"))
            emit(FrameStripEvent.Complete)
        })))
        testScheduler.advanceUntilIdle()

        assertIs<ClipEditorUiState.Terminal>(presenter.state.value)
    }

    @Test
    fun `complete after unsupported frame strip cannot replace terminal result`() = runTest {
        val presenter = ClipEditorPresenter(this, {})

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(FakeSession(flow {
            emit(FrameStripEvent.Unsupported(com.oneononearena.videoclip.UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, "unsupported"))
            emit(FrameStripEvent.Complete)
        })))
        testScheduler.advanceUntilIdle()

        assertIs<ClipEditorUiState.Terminal>(presenter.state.value)
    }

    @Test
    fun `updated result callback receives terminal result`() = runTest {
        var stale = 0
        var fresh = 0
        val presenter = ClipEditorPresenter(this, { stale++ })
        presenter.updateCallbacks(onResult = { fresh++ }, onCancel = {})

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(FakeSession(flow {
            emit(FrameStripEvent.InvalidRequest(com.oneononearena.videoclip.ValidationCode.RANGE_BELOW_MINIMUM, "bad request"))
        })))
        testScheduler.advanceUntilIdle()

        assertEquals(0, stale)
        assertEquals(1, fresh)
    }

    @Test
    fun `cancel callback is delivered for each started session`() = runTest {
        var callbacks = 0
        val presenter = ClipEditorPresenter(this, onCancel = { callbacks++ })
        val editor = SequentialFakeEditor(
            FakeSession(flow { emit(FrameStripEvent.Complete) }),
            FakeSession(flow { emit(FrameStripEvent.Complete) }),
        )

        presenter.start(VideoSourcePath("/first.mp4"), editor)
        testScheduler.advanceUntilIdle()
        presenter.cancel()
        testScheduler.advanceUntilIdle()
        presenter.start(VideoSourcePath("/second.mp4"), editor)
        testScheduler.advanceUntilIdle()
        presenter.cancel()
        testScheduler.advanceUntilIdle()

        assertEquals(2, callbacks)
    }

    @Test
    fun `close closes session after presenter scope is cancelled`() = runTest {
        val presenterScope = CoroutineScope(coroutineContext + Job())
        val session = FakeSession(flow { emit(FrameStripEvent.Complete) })
        val presenter = ClipEditorPresenter(presenterScope, {})

        presenter.start(VideoSourcePath("/video.mp4"), FakeEditor(session))
        testScheduler.advanceUntilIdle()
        presenterScope.cancel()

        presenter.close()

        assertEquals(1, session.closeCalls)
    }

}

private class FakeEditor(private val session: ClipEditorSession) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = OpenSessionResult.Open(session)
}

private class FakeSession(private val events: Flow<FrameStripEvent>) : ClipEditorSession {
    override val metadata = VideoMetadata(10_000.milliseconds, 100, 100, false)
    var createCalls = 0
    var closeCalls = 0
    val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = events
    override suspend fun createClip(range: ClipRange): ClipResult {
        createCalls++
        return ClipResult.Failed(VideoEditFailure(com.oneononearena.videoclip.FailureCode.EXPORT_FAILED, false, null))
    }
    override suspend fun close() {
        closeCalls++
        closed.complete(Unit)
    }
}

private class LowerFrameLimitSession(
    private val maximumFrameCount: Int,
) : ClipEditorSession {
    override val metadata = VideoMetadata(10_000.milliseconds, 100, 100, false)
    val requests = mutableListOf<Int>()

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = flow {
        requests += request.frameCount
        if (request.frameCount > maximumFrameCount) {
            emit(FrameStripEvent.InvalidRequest(com.oneononearena.videoclip.ValidationCode.INVALID_FRAME_REQUEST, null))
        } else {
            emit(FrameStripEvent.Complete)
        }
    }

    override suspend fun createClip(range: ClipRange): ClipResult =
        ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, false, null))

    override suspend fun close() = Unit
}

private class SequentialFakeEditor(vararg private val sessions: FakeSession) : VideoClipEditor {
    val events = mutableListOf<String>()
    private var index = 0

    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        events += "open-$index"
        val session = sessions[index]
        val openIndex = index++
        return OpenSessionResult.Open(object : ClipEditorSession by session {
            override suspend fun close() {
                events += "close-$openIndex"
                session.close()
            }
        })
    }
}
