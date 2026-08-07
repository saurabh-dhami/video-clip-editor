package com.oneononearena.videoclip.compose

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.v2.runEmptyComposeUiTest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoEditFailure
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipEditorLifecycleDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = PreviewFixtureFiles(context)

    @After
    fun cleanFixtureCopies() {
        fixtures.deleteCopies()
    }

    @Test
    fun actualOwnerReleasesClosesDisposesThenCreatesDistinctReadyPort() {
        runBlocking {
            val firstSource = fixtures.copyAvcFixture()
            val secondSource = fixtures.copyAvcFixture()
            val calls = mutableListOf<String>()
            val factory = RecordingActualPortFactory(context, calls)
            lateinit var owner: ClipEditorLifecycleOwner
            val firstSession = DeviceSession("g1", calls) { owner.releaseAudit.value }
            val secondSession = DeviceSession("g2", calls) { owner.releaseAudit.value }
            owner = onLifecycleMain { ClipEditorLifecycleOwner(factory, Dispatchers.Main.immediate) }

            onLifecycleMain {
                owner.requestReplace(VideoSourcePath(firstSource.absolutePath), DeviceEditor(firstSession))
            }
            awaitCondition { owner.coordinator.state.value.ready }
            val oldActual = factory.actuals.single()
            calls.clear()

            onLifecycleMain {
                owner.requestReplace(VideoSourcePath(secondSource.absolutePath), DeviceEditor(secondSession))
            }
            awaitCondition {
                owner.coordinator.state.value.binding?.generation == PreviewGeneration(2) &&
                    owner.coordinator.state.value.ready
            }

            assertEquals(
                listOf(
                    "Release:g1:r1",
                    "Released:g1",
                    "audit:g1:r1:Acknowledged:SourceReplacement",
                    "session-close:g1",
                    "port-dispose:g1",
                    "port-create:g2",
                    "session-start:g2",
                    "Bind:g2:r1",
                ),
                calls,
            )
            assertEquals(2, factory.actuals.size)
            assertNotSame(oldActual, factory.actuals.last())
            assertTrue(factory.wrappers.first().commandsAfterRelease.isEmpty())

            onLifecycleMain { owner.requestClose() }
            awaitCondition { owner.closed.value }
        }
    }

    @OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
    @Suppress("DEPRECATION")
    @Test
    fun screenShowsLivePlayheadGestureAndLocksControlsDuringExport() {
        val source = fixtures.copyAvcFixture()
        val export = CompletableDeferred<ClipResult>()
        val session = HoldingDeviceSession(export)
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val activityIntent = Intent.makeMainActivity(
            ComponentName(testContext, "androidx.activity.ComponentActivity"),
        )
        val scenario = ActivityScenario.launch<Activity>(activityIntent)
        var composeView: ComposeView? = null

        try {
            runEmptyComposeUiTest {
                scenario.onActivity { activity ->
                    activity.window.addFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                    )
                    WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) {
                        composeView = ComposeView(activity).also { view ->
                            activity.setContentView(view)
                            view.setContent {
                                ClipEditorScreen(
                                    source = VideoSourcePath(source.absolutePath),
                                    editor = DeviceEditor(session),
                                    onResult = {},
                                    onCancel = {},
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    runCatching { onNodeWithTag("play-pause").assertIsEnabled() }.isSuccess
                }
                onNodeWithTag("play-pause").assertIsEnabled()
                val initialPlayhead = onNodeWithTag("clip-playhead").getUnclippedBoundsInRoot().left

                onNodeWithTag("clip-timeline").performTouchInput {
                    click(center)
                }
                waitUntil(timeoutMillis = 15_000) {
                    onNodeWithTag("clip-playhead").getUnclippedBoundsInRoot().left > initialPlayhead
                }

                onNodeWithTag("clip-end-handle").performTouchInput {
                    down(center)
                    moveBy(Offset(-80f, 0f))
                    up()
                }
                waitForIdle()
                onNodeWithTag("done").assertIsEnabled().performTouchInput { click(center) }
                waitUntil(timeoutMillis = 15_000) { session.createCalls == 1 }

                onNodeWithTag("back").assertIsNotEnabled()
                assertTrue(onAllNodesWithTag("play-pause").fetchSemanticsNodes().isEmpty())
                assertTrue(onAllNodesWithTag("done").fetchSemanticsNodes().isEmpty())
                assertTrue(requireNotNull(session.createdRange).endExclusive < 10.seconds)

                export.complete(
                    ClipResult.Failed(
                        VideoEditFailure(com.oneononearena.videoclip.FailureCode.EXPORT_FAILED, false, null),
                    ),
                )
                waitForIdle()
            }
        } finally {
            scenario.onActivity { composeView?.disposeComposition() }
            scenario.close()
        }
    }

}

private class RecordingActualPortFactory(
    private val context: Context,
    private val calls: MutableList<String>,
) : PreviewPortFactory {
    val actuals = mutableListOf<AndroidMedia3PreviewPort>()
    val wrappers = mutableListOf<RecordingActualPort>()

    override fun create(): PreviewPort {
        val label = "g${actuals.size + 1}"
        calls += "port-create:$label"
        val actual = AndroidMedia3PreviewPort(context)
        val wrapper = RecordingActualPort(label, actual, calls)
        actuals += actual
        wrappers += wrapper
        return wrapper
    }

    override fun dispose(port: PreviewPort) {
        val wrapper = port as RecordingActualPort
        calls += "port-dispose:${wrapper.label}"
        wrapper.actual.dispose()
    }
}

private class RecordingActualPort(
    val label: String,
    val actual: AndroidMedia3PreviewPort,
    private val calls: MutableList<String>,
) : PreviewPort {
    private var terminal = false
    val commandsAfterRelease = mutableListOf<PreviewCommand>()
    override val events: Flow<PreviewEvent> = actual.events.onEach { event ->
        if (event is PreviewEvent.Released) calls += "Released:g${event.generation.value}"
    }

    override fun dispatch(command: PreviewCommand) {
        if (terminal) {
            commandsAfterRelease += command
            return
        }
        when (command) {
            is PreviewCommand.Bind -> calls += "Bind:$label:r${command.binding.revision.value}"
            is PreviewCommand.Release -> {
                calls += "Release:$label:r1"
                terminal = true
            }
            else -> Unit
        }
        actual.dispatch(command)
    }
}

private class DeviceEditor(
    private val session: ClipEditorSession,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = OpenSessionResult.Open(session)
}

private class DeviceSession(
    private val label: String,
    private val calls: MutableList<String>,
    private val audit: () -> PreviewReleaseAudit?,
) : ClipEditorSession {
    override val metadata = VideoMetadata(10.seconds, 320, 240, true)
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> {
        calls += "session-start:$label"
        return flowOf(FrameStripEvent.Complete)
    }

    override suspend fun createClip(range: ClipRange): ClipResult = error("Not used")

    override suspend fun close() {
        val current = requireNotNull(audit())
        calls += when (val outcome = current.outcome) {
            PreviewReleaseOutcome.Acknowledged ->
                "audit:g${current.generation.value}:r${current.revision.value}:Acknowledged:${current.reason}"
            is PreviewReleaseOutcome.TimedOut ->
                "audit:g${current.generation.value}:r${current.revision.value}:TimedOut(${outcome.diagnostic}):${current.reason}"
        }
        calls += "session-close:$label"
    }
}

private class HoldingDeviceSession(
    private val export: CompletableDeferred<ClipResult>,
) : ClipEditorSession {
    override val metadata = VideoMetadata(10.seconds, 320, 240, true)
    var createCalls = 0
    var createdRange: ClipRange? = null
    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = flowOf(FrameStripEvent.Complete)
    override suspend fun createClip(range: ClipRange): ClipResult {
        createCalls++
        createdRange = range
        return export.await()
    }
    override suspend fun close() = Unit
}

private suspend fun awaitCondition(predicate: () -> Boolean) {
    withTimeout(15.seconds) {
        while (!predicate()) delay(25)
    }
}

private fun <T> onLifecycleMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
