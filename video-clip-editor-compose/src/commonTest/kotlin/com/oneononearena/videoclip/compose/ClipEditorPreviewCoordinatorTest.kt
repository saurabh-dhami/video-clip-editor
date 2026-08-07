package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

class ClipEditorPreviewCoordinatorTest {
    @Test
    fun stalePositionDoesNotMutateCurrentBinding() {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator()
        coordinator.bind(port, binding(revision = 1, sourcePosition = 2.seconds))
        coordinator.replaceRange(binding(revision = 2, sourcePosition = 2.seconds))

        coordinator.onEvent(PreviewEvent.Position(PreviewGeneration(7), PreviewRevision(1), 8.seconds, true))

        assertEquals(2.seconds, coordinator.state.value.playhead)
    }

    @Test
    fun everyStaleRevisionEventIsIgnored() {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator()
        coordinator.bind(port, binding(revision = 1))
        coordinator.replaceRange(binding(revision = 2, sourcePosition = 3.seconds))

        coordinator.onEvent(PreviewEvent.Ready(PreviewGeneration(1), PreviewRevision(1)))
        coordinator.onEvent(PreviewEvent.Position(PreviewGeneration(7), PreviewRevision(1), 9.seconds, true))
        coordinator.onEvent(PreviewEvent.RecoverableFailure(PreviewGeneration(7), PreviewRevision(1), "stale"))
        coordinator.onEvent(PreviewEvent.Released(PreviewGeneration(99)))

        assertEquals(false, coordinator.state.value.ready)
        assertEquals(3.seconds, coordinator.state.value.playhead)
        assertEquals(null, coordinator.state.value.failure)
    }

    @Test
    fun matchingLivePositionMovesPlayheadWithinRange() {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator()
        coordinator.bind(port, binding())

        coordinator.onEvent(PreviewEvent.Position(PreviewGeneration(7), PreviewRevision(1), 3.seconds, true))

        assertEquals(3.seconds, coordinator.state.value.playhead)
        assertEquals(true, coordinator.state.value.isPlaying)
    }

    @Test
    fun playFromOutsideRangeSeeksSelectedStartAndWaitsForReady() {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator()
        coordinator.bind(port, binding(sourcePosition = 8.seconds))

        coordinator.togglePlayPause()
        assertEquals(emptyList(), port.commands.drop(1))

        coordinator.onEvent(PreviewEvent.Ready(PreviewGeneration(7), PreviewRevision(1)))
        coordinator.togglePlayPause()

        assertEquals(
            listOf(
                PreviewCommand.Seek(PreviewGeneration(7), PreviewRevision(1), 2.seconds),
                PreviewCommand.SetPlayWhenReady(PreviewGeneration(7), PreviewRevision(1), true),
            ),
            port.commands.drop(1),
        )
    }

    @Test
    fun lockedCoordinatorRejectsEveryPreviewMutation() {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator()
        coordinator.bind(port, binding())
        coordinator.onEvent(PreviewEvent.Ready(PreviewGeneration(7), PreviewRevision(1)))
        coordinator.lockInteractions()
        val commandsAtLock = port.commands.toList()

        coordinator.togglePlayPause()
        coordinator.pause()
        coordinator.seekPaused(3.seconds)
        coordinator.retry()
        coordinator.replaceRange(binding(revision = 2))

        assertEquals(commandsAtLock, port.commands)
    }

    private fun binding(
        revision: Long = 1,
        sourcePosition: kotlin.time.Duration = 2.seconds,
    ) = PreviewBinding(
        generation = PreviewGeneration(7),
        revision = PreviewRevision(revision),
        source = VideoSourcePath("/video.mp4"),
        metadata = VideoMetadata(10.seconds, 100, 100, false),
        range = ClipRange(2.seconds, 4.seconds),
        sourcePosition = sourcePosition,
        playWhenReady = false,
    )
}

private class CoordinatorRecordingPort : PreviewPort {
    override val events: Flow<PreviewEvent> = MutableSharedFlow()
    val commands = mutableListOf<PreviewCommand>()

    override fun dispatch(command: PreviewCommand) {
        commands += command
    }
}
