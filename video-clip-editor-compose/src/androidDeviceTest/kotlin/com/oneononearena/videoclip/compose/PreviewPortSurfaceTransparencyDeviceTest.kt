package com.oneononearena.videoclip.compose

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import com.oneononearena.videoclip.createAndroidVideoClipEditor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreviewPortSurfaceTransparencyDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = PreviewFixtureFiles(context)

    @After
    fun cleanFixtureCopies() {
        fixtures.deleteCopies()
    }

    @Test
    fun resolver_acceptsDirectActualAndOneExactHop_butRejectsEveryInvalidDelegate() {
        val actual = onSurfaceMain { AndroidMedia3PreviewPort(context) }
        val oneHop = FixedSurfaceDelegate(actual)
        val nullDelegate = FixedSurfaceDelegate(null)
        val self = SelfSurfaceDelegate()
        val nested = FixedSurfaceDelegate(oneHop)
        val unrelated = SurfaceNoOpPort
        val unrelatedDelegate = FixedSurfaceDelegate(unrelated)
        val cycleA = MutableSurfaceDelegate()
        val cycleB = MutableSurfaceDelegate()
        cycleA.delegate = cycleB
        cycleB.delegate = cycleA

        try {
            assertSame(actual, resolveAndroidPreviewSurfacePort(actual))
            assertSame(actual, resolveAndroidPreviewSurfacePort(oneHop))
            assertEquals(null, resolveAndroidPreviewSurfacePort(nullDelegate))
            assertEquals(null, resolveAndroidPreviewSurfacePort(self))
            assertEquals(null, resolveAndroidPreviewSurfacePort(nested))
            assertEquals(null, resolveAndroidPreviewSurfacePort(cycleA))
            assertEquals(null, resolveAndroidPreviewSurfacePort(cycleB))
            assertEquals(null, resolveAndroidPreviewSurfacePort(unrelated))
            assertEquals(null, resolveAndroidPreviewSurfacePort(unrelatedDelegate))
        } finally {
            onSurfaceMain { actual.dispose() }
        }
    }

    @OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
    @Suppress("DEPRECATION")
    @Test
    fun invalidDelegatesNeverExposeAContentFrameSurfaceNode() {
        val oneHopToUnrelated = FixedSurfaceDelegate(SurfaceNoOpPort)
        val invalid = listOf<PreviewPort>(
            FixedSurfaceDelegate(null),
            SelfSurfaceDelegate(),
            FixedSurfaceDelegate(oneHopToUnrelated),
            SurfaceNoOpPort,
            oneHopToUnrelated,
        )
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val scenario = ActivityScenario.launch<Activity>(
            Intent.makeMainActivity(ComponentName(testContext, "androidx.activity.ComponentActivity")),
        )
        var composeView: ComposeView? = null

        try {
            runEmptyComposeUiTest {
                scenario.onActivity { activity ->
                    WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) {
                        composeView = ComposeView(activity).also { view ->
                            activity.setContentView(view)
                            view.setContent {
                                invalid.forEachIndexed { index, port ->
                                    PlatformPreviewSurface(
                                        port = port,
                                        modifier = Modifier.semantics {
                                            testTag = "invalid-surface-$index"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                waitForIdle()

                invalid.indices.forEach { index ->
                    assertTrue(
                        onAllNodesWithTag("invalid-surface-$index")
                            .fetchSemanticsNodes()
                            .isEmpty(),
                    )
                }
            }
        } finally {
            scenario.onActivity { composeView?.disposeComposition() }
            scenario.close()
        }
    }

    @OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
    @Suppress("DEPRECATION")
    @Test
    fun wrappedScreenPort_rendersRealContentFrame_andRecompositionKeepsLifecycleIdentity() {
        val sourceFile = fixtures.copyAvcFixture()
        val source = VideoSourcePath(sourceFile.absolutePath)
        val ledger = SurfaceEventLedger()
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val scenario = ActivityScenario.launch<Activity>(
            Intent.makeMainActivity(ComponentName(testContext, "androidx.activity.ComponentActivity")),
        )
        var composeView: ComposeView? = null
        var screenVisible by mutableStateOf(true)
        var unrelatedRecomposition by mutableIntStateOf(0)
        var observedRecomposition = -1
        var observedEditor: RecordingSurfaceVideoClipEditor? = null
        var observedFactory: RecordingSurfacePreviewPortFactory? = null

        try {
            runEmptyComposeUiTest {
                scenario.onActivity { activity ->
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) {
                        composeView = ComposeView(activity).also { view ->
                            activity.setContentView(view)
                            view.setContent {
                                val productionEditor = remember(activity) {
                                    createAndroidVideoClipEditor(activity)
                                }
                                val recordingEditor = remember(productionEditor, ledger) {
                                    RecordingSurfaceVideoClipEditor(productionEditor, ledger)
                                }
                                val realFactory = rememberPlatformPreviewPortFactory()
                                val recordingFactory = remember(realFactory, ledger) {
                                    RecordingSurfacePreviewPortFactory(realFactory, ledger)
                                }
                                val observedTick = unrelatedRecomposition
                                SideEffect {
                                    observedRecomposition = observedTick
                                    observedEditor = recordingEditor
                                    observedFactory = recordingFactory
                                }

                                if (screenVisible) {
                                    CompositionLocalProvider(
                                        LocalPreviewPortFactoryOverride provides recordingFactory,
                                    ) {
                                        ClipEditorScreen(
                                            source = source,
                                            editor = recordingEditor,
                                            onResult = {},
                                            onCancel = {},
                                        )
                                        recordingFactory.latestWrapper?.let { wrapper ->
                                            PlatformPreviewSurface(
                                                port = wrapper,
                                                modifier = Modifier.semantics {
                                                    testTag = "ig1-surface-probe"
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                waitUntil(timeoutMillis = 30_000) {
                    ledger.count("preview-event-Ready") == 1 &&
                        runCatching { onNodeWithTag("ig1-surface-probe").assertIsDisplayed() }.isSuccess
                }
                onNodeWithTag("ig1-surface-probe").assertIsDisplayed()

                val factoryBefore = requireNotNull(observedFactory)
                val editorBefore = requireNotNull(observedEditor)
                val wrapper = requireNotNull(factoryBefore.latestWrapper)
                val actual = requireNotNull(factoryBefore.latestActual)
                assertSame(actual, wrapper.surfacePort)
                assertSame(actual, resolveAndroidPreviewSurfacePort(wrapper))
                assertNotNull(actual.playerForSurface)
                assertEquals(1, factoryBefore.createCount)
                assertEquals(1, ledger.count("open-session"))
                assertEquals(1, ledger.count("frames-request-24"))

                assertThrows(IllegalStateException::class.java) {
                    factoryBefore.dispose(SurfaceNoOpPort)
                }
                assertEquals(0, factoryBefore.disposeCount)

                runOnIdle { unrelatedRecomposition++ }
                waitUntil(timeoutMillis = 5_000) { observedRecomposition == 1 }

                assertSame(factoryBefore, observedFactory)
                assertSame(editorBefore, observedEditor)
                assertSame(wrapper, factoryBefore.latestWrapper)
                assertEquals(1, factoryBefore.createCount)
                assertEquals(1, ledger.count("open-session"))
                assertEquals(0, ledger.count("preview-command-Release"))
                assertEquals(0, ledger.count("session-close-entry"))

                runOnIdle { screenVisible = false }
                waitUntil(timeoutMillis = 15_000) {
                    ledger.count("preview-dispose-complete") == 1
                }

                val snapshot = ledger.snapshot()
                val released = snapshot.single { it.name == "preview-event-Released" }.sequence
                val closeEntry = snapshot.single { it.name == "session-close-entry" }.sequence
                val disposeComplete = snapshot.single { it.name == "preview-dispose-complete" }.sequence
                assertTrue(released < closeEntry)
                assertTrue(closeEntry < disposeComplete)
                assertEquals(1, ledger.count("preview-command-Release"))
                assertEquals(1, ledger.count("session-close-entry"))
                assertEquals(1, ledger.count("session-close-complete"))
                assertEquals(1, factoryBefore.disposeCount)
            }
        } finally {
            scenario.onActivity { composeView?.disposeComposition() }
            scenario.close()
        }
    }
}

private data class SurfaceRecordedEvent(
    val sequence: Long,
    val name: String,
)

private class SurfaceEventLedger {
    private val events = mutableListOf<SurfaceRecordedEvent>()

    fun record(name: String) {
        synchronized(events) {
            events += SurfaceRecordedEvent(events.size.toLong() + 1L, name)
        }
    }

    fun count(name: String): Int = synchronized(events) { events.count { it.name == name } }

    fun snapshot(): List<SurfaceRecordedEvent> = synchronized(events) { events.toList() }
}

private class RecordingSurfaceVideoClipEditor(
    private val delegate: VideoClipEditor,
    private val ledger: SurfaceEventLedger,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        ledger.record("open-session")
        return when (val opened = delegate.openSession(source)) {
            is OpenSessionResult.Open -> OpenSessionResult.Open(
                RecordingSurfaceClipEditorSession(opened.session, ledger),
            )
            else -> opened
        }
    }
}

private class RecordingSurfaceClipEditorSession(
    private val delegate: ClipEditorSession,
    private val ledger: SurfaceEventLedger,
) : ClipEditorSession {
    override val metadata: VideoMetadata
        get() = delegate.metadata

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> {
        ledger.record("frames-request-${request.frameCount}")
        return delegate.frames(request).onEach { event ->
            ledger.record("frames-event-${event::class.simpleName}")
        }
    }

    override suspend fun createClip(range: ClipRange): ClipResult = delegate.createClip(range)

    override suspend fun close() {
        ledger.record("session-close-entry")
        delegate.close()
        ledger.record("session-close-complete")
    }
}

private class RecordingSurfacePreviewPortFactory(
    private val delegate: PreviewPortFactory,
    private val ledger: SurfaceEventLedger,
) : PreviewPortFactory {
    var latestWrapper: RecordingSurfacePreviewPort? by mutableStateOf(null)
        private set
    var latestActual: AndroidMedia3PreviewPort? = null
        private set
    var createCount: Int = 0
        private set
    var disposeCount: Int = 0
        private set

    override fun create(): PreviewPort {
        check(latestWrapper == null) { "Only one active recording wrapper is allowed" }
        val actual = delegate.create()
        check(actual is AndroidMedia3PreviewPort) { "Real Android factory must create AndroidMedia3PreviewPort" }
        val wrapper = RecordingSurfacePreviewPort(actual, ledger)
        latestActual = actual
        latestWrapper = wrapper
        createCount++
        ledger.record("preview-create")
        return wrapper
    }

    override fun dispose(port: PreviewPort) {
        val wrapper = latestWrapper
        val actual = latestActual
        check(port === wrapper) { "Dispose requires the exact known recording wrapper" }
        check(wrapper.surfacePort === actual) { "Recording wrapper must expose the exact created actual" }
        check(disposeCount == 0) { "Preview actual may be disposed only once" }
        ledger.record("preview-dispose-entry")
        delegate.dispose(checkNotNull(actual))
        disposeCount++
        ledger.record("preview-dispose-complete")
    }
}

private class RecordingSurfacePreviewPort(
    private val delegate: PreviewPort,
    private val ledger: SurfaceEventLedger,
) : PreviewPort, PreviewPortSurfaceDelegate {
    override val surfacePort: PreviewPort = delegate
    override val events: Flow<PreviewEvent> = delegate.events.onEach { event ->
        ledger.record("preview-event-${event::class.simpleName}")
    }

    override fun dispatch(command: PreviewCommand) {
        ledger.record("preview-command-${command::class.simpleName}")
        delegate.dispatch(command)
    }
}

private class FixedSurfaceDelegate(
    override val surfacePort: PreviewPort?,
) : PreviewPort, PreviewPortSurfaceDelegate {
    override val events: Flow<PreviewEvent> = emptyFlow()
    override fun dispatch(command: PreviewCommand) = Unit
}

private class SelfSurfaceDelegate : PreviewPort, PreviewPortSurfaceDelegate {
    override val surfacePort: PreviewPort
        get() = this
    override val events: Flow<PreviewEvent> = emptyFlow()
    override fun dispatch(command: PreviewCommand) = Unit
}

private class MutableSurfaceDelegate : PreviewPort, PreviewPortSurfaceDelegate {
    var delegate: PreviewPort? = null
    override val surfacePort: PreviewPort?
        get() = delegate
    override val events: Flow<PreviewEvent> = emptyFlow()
    override fun dispatch(command: PreviewCommand) = Unit
}

private data object SurfaceNoOpPort : PreviewPort {
    override val events: Flow<PreviewEvent> = emptyFlow()
    override fun dispatch(command: PreviewCommand) = Unit
}

private fun <T> onSurfaceMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
