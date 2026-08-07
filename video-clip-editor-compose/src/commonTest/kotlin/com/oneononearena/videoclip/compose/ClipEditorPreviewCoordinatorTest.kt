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
    fun everyStaleRevisionEventIsIgnored() = runTest {
        val port = CoordinatorRecordingPort()
        val coordinator = ClipEditorPreviewCoordinator(this, port)
        testScheduler.runCurrent()
        coordinator.bind(binding(revision = 1))
        coordinator.replaceRange(binding(revision = 2, sourcePosition = 3.seconds))

        port.emit(PreviewEvent.Ready(PreviewGeneration(1), PreviewRevision(1)))
        port.emit(PreviewEvent.Position(PreviewGeneration(7), PreviewRevision(1), 9.seconds, true))
        port.emit(PreviewEvent.RecoverableFailure(PreviewGeneration(7), PreviewRevision(1), "stale"))
        port.emit(PreviewEvent.Released(PreviewGeneration(99)))
        testScheduler.runCurrent()

        assertEquals(false, coordinator.state.value.ready)
        assertEquals(3.seconds, coordinator.state.value.playhead)
        assertEquals(null, coordinator.state.value.failure)
        coordinator.dispose()
    }

    @Test
    fun replacementReleasesBeforeOldSessionClosesAndNewBindingStarts() = runTest {
        val calls = mutableListOf<String>()
        val port = CoordinatorRecordingPort { generation ->
            calls += "released-$generation"
            emit(PreviewEvent.Released(generation))
        }
        val fresh = CoordinatorRecordingPort()
        val factory = object : PreviewPortFactory {
            var created = false
            override fun create(): PreviewPort = if (!created) {
                created = true
                port
            } else {
                fresh
            }
            override fun dispose(port: PreviewPort) = Unit
        }
        val coordinator = ClipEditorPreviewCoordinator(this, portFactory = factory)
        testScheduler.runCurrent()
        coordinator.bind(binding())

        coordinator.replaceSourceThen { calls += "close-session" }
        coordinator.bind(binding(source = VideoSourcePath("/new.mp4")))

        assertEquals(
            listOf("released-PreviewGeneration(value=1)", "close-session"),
            calls,
        )
        assertEquals(PreviewGeneration(2), coordinator.state.value.binding?.generation)
        coordinator.dispose()
    }

    @Test
    fun terminalPortIsDisposedAfterAcknowledgedReleaseAndSessionCloseBeforeFreshPortBinds() = runTest {
        val calls = mutableListOf<String>()
        val old = CoordinatorRecordingPort { generation ->
            calls += "released"
            emit(PreviewEvent.Released(generation))
        }
        val fresh = CoordinatorRecordingPort()
        val factory = object : PreviewPortFactory {
            var creates = 0
            override fun create(): PreviewPort = if (creates++ == 0) old else fresh
            override fun dispose(port: PreviewPort) { calls += "dispose-${if (port === old) "old" else "fresh"}" }
        }
        val coordinator = ClipEditorPreviewCoordinator(this, portFactory = factory)
        testScheduler.runCurrent()

        coordinator.bind(binding())
        coordinator.replaceSourceThen { calls += "close-session" }
        coordinator.bind(binding(source = VideoSourcePath("/new.mp4")))

        assertEquals(listOf("released", "close-session", "dispose-old"), calls)
        assertEquals(2, factory.creates)
        assertEquals(PreviewCommand.Bind::class, fresh.commands.single()::class)
        coordinator.dispose()
    }

    @Test
    fun timeoutFallbackIsRecordedBeforeSessionClose() = runTest {
        val calls = mutableListOf<String>()
        val coordinator = ClipEditorPreviewCoordinator(this, CoordinatorRecordingPort())
        testScheduler.runCurrent()
        coordinator.bind(binding())

        coordinator.closeThen { calls += "session" }

        assertEquals(PreviewReleaseFence.Timeout(PreviewGeneration(1)), coordinator.state.value.releaseFence)
        assertEquals(listOf("session"), calls)
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
    fun closeThenIsExactOnce() = runTest {
        var closes = 0
        val port = CoordinatorRecordingPort { emit(PreviewEvent.Released(it)) }
        val coordinator = ClipEditorPreviewCoordinator(this, port)
        testScheduler.runCurrent()
        coordinator.bind(binding())

        coordinator.closeThen { closes++ }
        coordinator.closeThen { closes++ }

        assertEquals(1, closes)
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

        port.emit(PreviewEvent.Ready(PreviewGeneration(1), PreviewRevision(1)))
        testScheduler.runCurrent()
        coordinator.togglePlayPause()

        assertEquals(
            listOf(
                PreviewCommand.Seek(PreviewGeneration(1), PreviewRevision(1), 2.seconds),
                PreviewCommand.SetPlayWhenReady(PreviewGeneration(1), PreviewRevision(1), true),
            ),
            port.commands.drop(1),
        )
        coordinator.dispose()
    }

    private fun binding(
        revision: Long = 1,
        sourcePosition: kotlin.time.Duration = 2.seconds,
        source: VideoSourcePath = VideoSourcePath("/video.mp4"),
    ) = PreviewBinding(
        generation = PreviewGeneration(7),
        revision = PreviewRevision(revision),
        source = source,
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
