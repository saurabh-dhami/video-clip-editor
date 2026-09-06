package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ClipEditorSelectionPolicyTest {
    @Test fun changingLimitKeepsSessionAndExportUsesCappedRangeExactlyOnce() = runTest {
        var opens = 0
        val exported = mutableListOf<ClipRange>()
        var callbacks = 0
        val presenter = ClipEditorPresenter(this, onResult = { callbacks++ })
        presenter.start(VideoSourcePath("/host.mp4"), object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
                opens++
                return OpenSessionResult.Open(object : ClipEditorSession {
                    override val metadata = VideoMetadata(120.seconds, 100, 100, false)
                    override fun frames(request: FrameStripRequest) = flow { emit(FrameStripEvent.Complete) }
                    override suspend fun createClip(range: ClipRange): ClipResult {
                        exported += range
                        return ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, false, "test result"))
                    }
                    override suspend fun close() = Unit
                })
            }
        })
        advanceUntilIdle()
        presenter.setMaximumSelectionDuration(60.seconds)
        presenter.beginRangeGesture()
        presenter.updateEndFromSelector(120.seconds)
        presenter.commitRangeGesture()
        presenter.createClip()
        presenter.createClip()
        advanceUntilIdle()
        assertEquals(listOf(ClipRange(60.seconds, 120.seconds)), exported)
        assertEquals(1, opens)
        assertEquals(1, callbacks)
        presenter.close()
    }

    @Test fun cappedWindowMovesOppositeBoundaryAndAllowsShorterClips() = runTest {
        val presenter = ClipEditorPresenter(this)
        presenter.setMaximumSelectionDuration(60.seconds)
        presenter.start(VideoSourcePath("/host.mp4"), editor())
        advanceUntilIdle()
        fun range() = assertIs<ClipEditorUiState.Ready>(presenter.state.value).let { it.provisionalRange ?: it.range }
        assertEquals(ClipRange(Duration.ZERO, 60.seconds), range())
        presenter.beginRangeGesture()
        presenter.updateEndFromSelector(70.seconds)
        assertEquals(ClipRange(10.seconds, 70.seconds), range())
        presenter.updateStartFromSelector(5.seconds)
        assertEquals(ClipRange(5.seconds, 65.seconds), range())
        presenter.updateEndFromSelector(30.seconds)
        presenter.commitRangeGesture()
        assertEquals(ClipRange(5.seconds, 30.seconds), range())
        presenter.resetRange()
        assertEquals(ClipRange(Duration.ZERO, 60.seconds), range())
        presenter.close()
    }

    @Test fun shortVideoAndNoLimitRemainValid() = runTest {
        val presenter = ClipEditorPresenter(this)
        presenter.setMaximumSelectionDuration(60.seconds)
        presenter.start(VideoSourcePath("/host.mp4"), editor(10.seconds))
        advanceUntilIdle()
        assertEquals(10.seconds, assertIs<ClipEditorUiState.Ready>(presenter.state.value).range.endExclusive)
        presenter.close()
        val uncapped = ClipEditorPresenter(this)
        uncapped.start(VideoSourcePath("/host.mp4"), editor())
        advanceUntilIdle()
        assertEquals(120.seconds, assertIs<ClipEditorUiState.Ready>(uncapped.state.value).range.endExclusive)
        uncapped.close()
    }

    @Test fun invalidLimitReturnsTypedResultWithoutOpeningSource() = runTest {
        var result: ClipResult? = null
        val presenter = ClipEditorPresenter(this, onResult = { result = it })
        presenter.setMaximumSelectionDuration(Duration.ZERO)
        presenter.start(VideoSourcePath("/host.mp4"), object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = error("Invalid configuration must not open source")
        })
        advanceUntilIdle()
        assertIs<ClipResult.InvalidRequest>(result)
    }

    @Test fun progressUsesEngineCountsAndDoesNotInventUnknownPercentage() = runTest {
        val complete = CompletableDeferred<Unit>()
        val presenter = ClipEditorPresenter(this)
        presenter.start(VideoSourcePath("/host.mp4"), editor(events = flow {
            emit(FrameStripEvent.Progress(3, 12))
            complete.await()
            emit(FrameStripEvent.Progress(1, 0))
        }))
        runCurrent()
        assertEquals(0.25f, presenter.thumbnailProgress.value)
        complete.complete(Unit)
        advanceUntilIdle()
        assertNull(presenter.thumbnailProgress.value)
        presenter.close()
    }

    private fun editor(duration: Duration = 120.seconds, events: kotlinx.coroutines.flow.Flow<FrameStripEvent> = flow { emit(FrameStripEvent.Complete) }) = object : VideoClipEditor {
        override suspend fun openSession(source: VideoSourcePath) = OpenSessionResult.Open(object : ClipEditorSession {
            override val metadata = VideoMetadata(duration, 100, 100, false)
            override fun frames(request: FrameStripRequest) = events
            override suspend fun createClip(range: ClipRange): ClipResult = error("Not exported")
            override suspend fun close() = Unit
        })
    }
}
