package com.oneononearena.videoclip.compose

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewPortSurfaceDelegateTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun previewFactoryOverride_isNullByDefaultAndScopedToItsProvider() = runComposeUiTest {
        val override = NoOpPreviewPortFactory()
        var outside: PreviewPortFactory? = override
        var inside: PreviewPortFactory? = null

        setContent {
            val outsideCurrent = LocalPreviewPortFactoryOverride.current
            SideEffect { outside = outsideCurrent }
            CompositionLocalProvider(LocalPreviewPortFactoryOverride provides override) {
                val insideCurrent = LocalPreviewPortFactoryOverride.current
                SideEffect { inside = insideCurrent }
            }
        }
        waitForIdle()

        assertNull(outside)
        assertSame(override, inside)
    }

    @Test
    fun recordingDelegate_recordsReleasedBeforeLifecycleAcknowledgesAndClosesSession() = runTest {
        val calls = mutableListOf<String>()
        val delegate = ReleasingPreviewPort()
        val wrapper = object : PreviewPort, PreviewPortSurfaceDelegate {
            override val surfacePort: PreviewPort = delegate
            override val events: Flow<PreviewEvent> = delegate.events.onEach { event ->
                if (event is PreviewEvent.Released) calls += "recorded-released"
            }

            override fun dispatch(command: PreviewCommand) = delegate.dispatch(command)
        }
        val session = ClosingSession(calls)
        val owner = ClipEditorLifecycleOwner(
            portFactory = object : PreviewPortFactory {
                override fun create(): PreviewPort = wrapper
                override fun dispose(port: PreviewPort) {
                    assertSame(wrapper, port)
                    calls += "disposed"
                }
            },
            dispatcher = StandardTestDispatcher(testScheduler),
        )

        owner.requestReplace(VideoSourcePath("/fixture.mp4"), SessionEditor(session))
        advanceUntilIdle()
        owner.requestClose()
        advanceUntilIdle()

        assertEquals(
            listOf("recorded-released", "session-close", "disposed"),
            calls,
        )
    }
}

private class NoOpPreviewPortFactory : PreviewPortFactory {
    override fun create(): PreviewPort = NoOpPreviewPort
    override fun dispose(port: PreviewPort) = Unit
}

private data object NoOpPreviewPort : PreviewPort {
    override val events: Flow<PreviewEvent> = emptyFlow()
    override fun dispatch(command: PreviewCommand) = Unit
}

private class ReleasingPreviewPort : PreviewPort {
    private val mutableEvents = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = 1)
    override val events: Flow<PreviewEvent> = mutableEvents

    override fun dispatch(command: PreviewCommand) {
        if (command is PreviewCommand.Release) {
            mutableEvents.tryEmit(PreviewEvent.Released(command.generation))
        }
    }
}

private class SessionEditor(
    private val session: ClipEditorSession,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult =
        OpenSessionResult.Open(session)
}

private class ClosingSession(
    private val calls: MutableList<String>,
) : ClipEditorSession {
    override val metadata = VideoMetadata(10.seconds, 320, 240, true)
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> =
        flowOf(FrameStripEvent.Complete)

    override suspend fun createClip(range: ClipRange): ClipResult = error("Not used")

    override suspend fun close() {
        calls += "session-close"
    }
}
