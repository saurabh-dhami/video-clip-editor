package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest

class ClipEditorPreviewCoordinatorTest {
    @Test
    fun stalePositionDoesNotMutateCurrentBinding() = runTest {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator(this, port)
        testScheduler.runCurrent()
        coordinator.bind(binding(revision = 1, sourcePosition = 2.seconds))
        coordinator.replaceRange(binding(revision = 2, sourcePosition = 2.seconds))

        port.emit(PreviewEvent.Position(PreviewGeneration(7), PreviewRevision(1), 8.seconds, true))

        assertEquals(2.seconds, coordinator.state.value.playhead)
        coordinator.dispose()
    }

    @Test
    fun releasedAcknowledgementPrecedesSessionClose() = runTest {
        val calls = mutableListOf<String>()
        val port = CoordinatorRecordingPort { generation ->
            calls += "released"
            emit(PreviewEvent.Released(generation))
        }
        val coordinator = ClipEditorPreviewCoordinator(this, port)
        testScheduler.runCurrent()
        coordinator.bind(binding())

        coordinator.closeThen { calls += "session" }

        assertEquals(listOf("released", "session"), calls)
        coordinator.dispose()
    }

    @Test
    fun playFromOutsideRangeSeeksSelectedStartAndWaitsForReady() = runTest {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator(this, port)
        testScheduler.runCurrent()
        coordinator.bind(binding(sourcePosition = 8.seconds))

        coordinator.togglePlayPause()
        assertEquals(emptyList(), port.commands.drop(1))

        port.emit(PreviewEvent.Ready(PreviewGeneration(7), PreviewRevision(1)))
        testScheduler.runCurrent()
        coordinator.togglePlayPause()

        assertEquals(
            listOf(
                PreviewCommand.Seek(PreviewGeneration(7), PreviewRevision(1), 2.seconds),
                PreviewCommand.SetPlayWhenReady(PreviewGeneration(7), PreviewRevision(1), true),
            ),
            port.commands.drop(1),
        )
        coordinator.dispose()
    }

    private fun binding(revision: Long = 1, sourcePosition: kotlin.time.Duration = 2.seconds) = PreviewBinding(
        generation = PreviewGeneration(7),
        revision = PreviewRevision(revision),
        source = VideoSourcePath("/video.mp4"),
        metadata = VideoMetadata(10.seconds, 100, 100, false),
        range = ClipRange(2.seconds, 4.seconds),
        sourcePosition = sourcePosition,
        playWhenReady = false,
    )
}

private class CoordinatorRecordingPort(
    private val onRelease: suspend CoordinatorRecordingPort.(PreviewGeneration) -> Unit = {},
) : PreviewPort {
    private val mutableEvents = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = 8)
    override val events: Flow<PreviewEvent> = mutableEvents
    val commands = mutableListOf<PreviewCommand>()

    override fun dispatch(command: PreviewCommand) {
        commands += command
        if (command is PreviewCommand.Release) {
            kotlinx.coroutines.runBlocking { onRelease(command.generation) }
        }
    }

    suspend fun emit(event: PreviewEvent) {
        mutableEvents.emit(event)
    }
}
