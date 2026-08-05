package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.TemporaryClipLease
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoEditFailure
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest

class ClipEditorPresenterTest {
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

}

private class FakeEditor(private val session: ClipEditorSession) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = OpenSessionResult.Open(session)
}

private class FakeSession(private val events: Flow<FrameStripEvent>) : ClipEditorSession {
    override val metadata = VideoMetadata(10_000.milliseconds, 100, 100, false)
    var createCalls = 0
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = events
    override suspend fun createClip(range: ClipRange): ClipResult {
        createCalls++
        return ClipResult.Failed(VideoEditFailure(com.oneononearena.videoclip.FailureCode.EXPORT_FAILED, false, null))
    }
    override suspend fun close() = Unit
}
