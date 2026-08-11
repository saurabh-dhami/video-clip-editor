package com.oneononearena.videoclip.compose

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.v2.runEmptyComposeUiTest
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.TempDeleteResult
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import com.oneononearena.videoclip.createAndroidVideoClipEditor
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipEditorScreenIntegrationTest {
    @OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
    @Suppress("DEPRECATION")
    @Test
    fun editorFlow_clipsPreviewLoopsExportsThenReleasesBeforeClose() {
        val fixtures = PreviewFixtureFiles()
        val ledger = RecordedEventLedger()
        val result = AtomicReference<ClipResult?>()
        val source = fixtures.copyAvcFixture()
        val sourcePath = VideoSourcePath(source.absolutePath)
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val screenVisible = mutableStateOf(true)
        val unrelatedRecomposition = mutableIntStateOf(0)
        var observedTick = -1
        lateinit var observedEditor: RecordingVideoClipEditor
        lateinit var observedFactory: RecordingPreviewPortFactory
        val scenario = ActivityScenario.launch<Activity>(
            Intent.makeMainActivity(ComponentName(testContext, "androidx.activity.ComponentActivity")),
        )
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
                                val tick = unrelatedRecomposition.intValue
                                val productionEditor = remember(activity) {
                                    createAndroidVideoClipEditor(activity)
                                }
                                val recordingEditor = remember(productionEditor, ledger) {
                                    RecordingVideoClipEditor(productionEditor, ledger)
                                }
                                val realFactory = rememberPlatformPreviewPortFactory()
                                val recordingFactory = remember(realFactory, ledger) {
                                    RecordingPreviewPortFactory(realFactory, ledger)
                                }
                                SideEffect {
                                    observedTick = tick
                                    observedEditor = recordingEditor
                                    observedFactory = recordingFactory
                                }
                                if (screenVisible.value) {
                                    CompositionLocalProvider(
                                        LocalPreviewPortFactoryOverride provides recordingFactory,
                                    ) {
                                        ClipEditorScreen(
                                            source = sourcePath,
                                            editor = recordingEditor,
                                            onResult = result::set,
                                            onCancel = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                waitUntil(timeoutMillis = 60_000) {
                    val commands = ledger.previewCommands()
                    val initialBind = commands.filterIsInstance<PreviewCommand.Bind>().singleOrNull()
                    runCatching { onNodeWithTag("play-pause").assertIsEnabled() }.isSuccess &&
                        ledger.count(RecordedEditorEventKind.Frame) == 24 &&
                        ledger.count(RecordedEditorEventKind.FramesComplete) == 1 &&
                        initialBind != null &&
                        ledger.previewEvents().filterIsInstance<PreviewEvent.Ready>().any {
                            it.generation == initialBind.binding.generation &&
                                it.revision == initialBind.binding.revision
                        }
                }

                assertTrue(source.isFile)
                assertEquals(targetContext.cacheDir.canonicalFile, source.parentFile?.canonicalFile)
                assertEquals(1, ledger.count(RecordedEditorEventKind.OpenEntry))
                assertEquals(sourcePath, ledger.events(RecordedEditorEventKind.OpenEntry).single().source)
                assertEquals(1, ledger.count(RecordedEditorEventKind.OpenResult))
                val realSession = ledger.events(RecordedEditorEventKind.OpenResult).single().identity
                assertNotNull(realSession)
                val metadataEvents = ledger.events(RecordedEditorEventKind.Metadata)
                assertTrue(metadataEvents.isNotEmpty())
                assertTrue(metadataEvents.all { it.identity === realSession })
                val observedMetadata = metadataEvents.map { checkNotNull(it.metadata) }.distinct().single()
                assertTrue(observedMetadata.duration in 10_000.milliseconds..10_034.milliseconds)
                assertEquals(640, observedMetadata.displayWidthPx)
                assertEquals(360, observedMetadata.displayHeightPx)
                assertTrue(observedMetadata.hasAudio)
                assertEquals(1, ledger.count(RecordedEditorEventKind.FramesRequest))
                assertEquals(24, ledger.events(RecordedEditorEventKind.FramesRequest).single().frameRequest?.frameCount)
                val frames = ledger.events(RecordedEditorEventKind.Frame).map {
                    (it.frameEvent as FrameStripEvent.Frame).value
                }
                assertEquals(24, frames.size)
                assertTrue(frames.all { it.widthPx in 1..160 && it.heightPx in 1..90 })
                assertTrue(frames.all { it.copyEncodedJpeg().isNotEmpty() })
                assertEquals(1, ledger.count(RecordedEditorEventKind.FramesComplete))
                assertEquals(0, ledger.count(RecordedEditorEventKind.FrameTerminal))
                assertTrue(ledger.events(RecordedEditorEventKind.Frame).all { it.identity === realSession })

                val stableEditor = observedEditor
                val stableFactory = observedFactory
                val stableWrapper = checkNotNull(stableFactory.latestWrapper)
                val realPort = checkNotNull(stableFactory.latestActual)
                assertSame(realPort, stableWrapper.surfacePort)
                assertEquals(1, stableFactory.createCount)
                runOnIdle { unrelatedRecomposition.intValue++ }
                waitUntil(timeoutMillis = 5_000) { observedTick == 1 }
                assertSame(stableEditor, observedEditor)
                assertSame(stableFactory, observedFactory)
                assertSame(stableWrapper, stableFactory.latestWrapper)
                assertSame(realPort, stableFactory.latestActual)
                assertEquals(1, stableFactory.createCount)
                assertEquals(1, ledger.count(RecordedEditorEventKind.OpenEntry))
                assertEquals(0, ledger.count(RecordedEditorEventKind.SessionCloseEntry))
                assertEquals(0, ledger.count(RecordedEditorEventKind.PreviewDisposeEntry))
                assertTrue(ledger.previewEvents().none { it is PreviewEvent.Released })

                val contentWidthPx = 24f * 64f * targetContext.resources.displayMetrics.density
                onNodeWithTag("clip-start-handle").performTouchInput {
                    down(center)
                    moveBy(Offset(contentWidthPx * 0.8f, 0f))
                    up()
                }
                waitUntil(timeoutMillis = 30_000) {
                    val replace = ledger.previewCommands()
                        .filterIsInstance<PreviewCommand.ReplaceRange>()
                        .singleOrNull()
                    replace != null && ledger.previewEvents().filterIsInstance<PreviewEvent.Ready>().any {
                        it.generation == replace.binding.generation &&
                            it.revision == replace.binding.revision
                    }
                }
                val replace = ledger.previewCommands()
                    .filterIsInstance<PreviewCommand.ReplaceRange>()
                    .single()
                val committedRange = replace.binding.range
                assertTrue(committedRange.start >= 7.seconds)
                assertEquals(observedMetadata.duration, committedRange.endExclusive)
                assertTrue(committedRange.endExclusive - committedRange.start in 500.milliseconds..3.seconds)

                val commandsBeforePan = ledger.count(RecordedEditorEventKind.PreviewCommand)
                val playheadBeforePan = onNodeWithTag("clip-playhead").getUnclippedBoundsInRoot().left
                onNodeWithTag("clip-timeline").performTouchInput {
                    down(center)
                    moveBy(Offset(-180f, 0f))
                    up()
                }
                waitUntil(timeoutMillis = 15_000) {
                    kotlin.math.abs(
                        (
                            onNodeWithTag("clip-playhead").getUnclippedBoundsInRoot().left -
                                playheadBeforePan
                            ).value,
                    ) > 1f
                }
                assertEquals(commandsBeforePan, ledger.count(RecordedEditorEventKind.PreviewCommand))

                val seeksBefore = ledger.previewCommands().count { it is PreviewCommand.Seek }
                onNodeWithTag("clip-timeline").performTouchInput { click(center) }
                waitUntil(timeoutMillis = 15_000) {
                    ledger.previewCommands().count { it is PreviewCommand.Seek } == seeksBefore + 1
                }
                val seek = ledger.previewCommands().filterIsInstance<PreviewCommand.Seek>().last()
                assertTrue(seek.sourcePosition in committedRange.start..committedRange.endExclusive)
                waitUntil(timeoutMillis = 15_000) {
                    ledger.previewEvents().filterIsInstance<PreviewEvent.Position>().any {
                        it.generation == seek.generation &&
                            it.revision == seek.revision &&
                            it.sourcePosition in committedRange.start..committedRange.endExclusive
                    }
                }

                var clippingStartMs = -1L
                var clippingEndMs = -1L
                var repeatMode = Player.REPEAT_MODE_OFF
                runOnIdle {
                    val player = checkNotNull(realPort.playerForSurface)
                    val clipping = checkNotNull(player.currentMediaItem).clippingConfiguration
                    clippingStartMs = clipping.startPositionMs
                    clippingEndMs = clipping.endPositionMs
                    repeatMode = player.repeatMode
                }
                assertEquals(committedRange.start.inWholeMilliseconds, clippingStartMs)
                assertEquals(committedRange.endExclusive.inWholeMilliseconds, clippingEndMs)
                assertEquals(Player.REPEAT_MODE_ONE, repeatMode)

                onNodeWithTag("play-pause").assertIsEnabled().performTouchInput { click(center) }
                waitUntil(timeoutMillis = 15_000) {
                    ledger.previewCommands().filterIsInstance<PreviewCommand.SetPlayWhenReady>().any {
                        it.generation == replace.binding.generation &&
                            it.revision == replace.binding.revision &&
                            it.value
                    }
                }
                runCatching {
                    waitUntil(timeoutMillis = 45_000) {
                        val positions = ledger.previewEvents()
                            .filterIsInstance<PreviewEvent.Position>()
                            .filter {
                                it.generation == replace.binding.generation &&
                                    it.revision == replace.binding.revision &&
                                    it.isPlaying
                            }
                        val highIndex = positions.indexOfFirst {
                            it.sourcePosition >= committedRange.endExclusive - 400.milliseconds
                        }
                        highIndex >= 0 && positions.drop(highIndex + 1).any {
                            it.sourcePosition <= committedRange.start + 700.milliseconds
                        }
                    }
                }.getOrElse { failure ->
                    val positions = ledger.previewEvents()
                        .filterIsInstance<PreviewEvent.Position>()
                        .filter {
                            it.generation == replace.binding.generation &&
                                it.revision == replace.binding.revision
                        }
                    val recoverable = ledger.previewEvents()
                        .filterIsInstance<PreviewEvent.RecoverableFailure>()
                    throw AssertionError(
                        "Loop missing; range=$committedRange positions=" +
                            positions.joinToString { "${it.sourcePosition}:${it.isPlaying}" } +
                            " recoverable=$recoverable",
                        failure,
                    )
                }
                val activePositions = ledger.previewEvents()
                    .filterIsInstance<PreviewEvent.Position>()
                    .filter {
                        it.generation == replace.binding.generation &&
                            it.revision == replace.binding.revision
                    }
                assertTrue(activePositions.isNotEmpty())
                assertTrue(activePositions.all {
                    it.sourcePosition in committedRange.start..committedRange.endExclusive
                })

                onNodeWithTag("done").assertIsEnabled().performTouchInput { click(center) }
                waitUntil(timeoutMillis = 90_000) { result.get() != null }
                val success = result.get() as ClipResult.Success
                assertEquals(committedRange, success.sourceRange)
                assertEquals(1, ledger.count(RecordedEditorEventKind.CreateClipEntry))
                assertEquals(committedRange, ledger.events(RecordedEditorEventKind.CreateClipEntry).single().range)
                assertSame(realSession, ledger.events(RecordedEditorEventKind.CreateClipEntry).single().identity)
                assertEquals(1, ledger.count(RecordedEditorEventKind.CreateClipResult))
                assertSame(success, ledger.events(RecordedEditorEventKind.CreateClipResult).single().clipResult)
                assertTrue(success.output is RecordingTemporaryClipLease)
                val outputFile = File(success.output.file.absolutePath)
                assertTrue(outputFile.isFile)

                runOnIdle { screenVisible.value = false }
                waitUntil(timeoutMillis = 30_000) {
                    ledger.count(RecordedEditorEventKind.SessionCloseComplete) == 1 &&
                        ledger.count(RecordedEditorEventKind.PreviewDisposeComplete) == 1
                }
                val release = ledger.events(RecordedEditorEventKind.PreviewEvent).single {
                    it.previewEvent is PreviewEvent.Released
                }
                val closeEntry = ledger.events(RecordedEditorEventKind.SessionCloseEntry).single()
                val closeComplete = ledger.events(RecordedEditorEventKind.SessionCloseComplete).single()
                val disposeEntry = ledger.events(RecordedEditorEventKind.PreviewDisposeEntry).single()
                val disposeComplete = ledger.events(RecordedEditorEventKind.PreviewDisposeComplete).single()
                assertTrue(release.sequence < closeEntry.sequence)
                assertTrue(closeEntry.sequence < closeComplete.sequence)
                assertTrue(closeComplete.sequence < disposeEntry.sequence)
                assertTrue(disposeEntry.sequence < disposeComplete.sequence)
                assertEquals(replace.binding.generation, (release.previewEvent as PreviewEvent.Released).generation)
                assertSame(realSession, closeEntry.identity)
                assertSame(realPort, disposeEntry.identity)
                assertEquals(1, stableFactory.disposeCount)
                var playerAfterClose: Player? = null
                runOnIdle { playerAfterClose = realPort.playerForSurface }
                assertEquals(null, playerAfterClose)

                val firstClear = runBlocking { success.output.clearTemporaryFile() }
                assertEquals(TempDeleteResult.Cleared, firstClear)
                assertFalse(outputFile.exists())
                val secondClear = runBlocking { success.output.clearTemporaryFile() }
                assertEquals(TempDeleteResult.AlreadyCleared, secondClear)
                assertFalse(outputFile.exists())
                assertEquals(
                    listOf(TempDeleteResult.Cleared, TempDeleteResult.AlreadyCleared),
                    ledger.events(RecordedEditorEventKind.LeaseClear).map { it.clearResult },
                )
            }
        } finally {
            runCatching { scenario.onActivity { composeView?.disposeComposition() } }
            scenario.close()
            fixtures.deleteCopies()
            assertFalse(source.exists())
        }
    }
}

private fun diagnosticBinding(
    source: File,
    metadata: VideoMetadata,
    revision: Long,
    range: ClipRange,
    playWhenReady: Boolean = false,
) = PreviewBinding(
    generation = PreviewGeneration(1),
    revision = PreviewRevision(revision),
    source = VideoSourcePath(source.absolutePath),
    metadata = metadata,
    range = range,
    sourcePosition = range.start,
    playWhenReady = playWhenReady,
)

private data class DiagnosticWrap(
    val before: PreviewEvent.Position,
    val after: PreviewEvent.Position,
)

private class DiagnosticPreviewEventRecorder(port: PreviewPort) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val channel = Channel<PreviewEvent>(Channel.UNLIMITED)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            port.events.collect(channel::send)
        }
    }

    suspend fun awaitReady(revision: PreviewRevision) = withTimeout(15.seconds) {
        while (true) {
            val event = channel.receive()
            if (event is PreviewEvent.Ready && event.revision == revision) return@withTimeout event
        }
        error("Unreachable")
    }

    suspend fun awaitFirstWrap(
        revision: PreviewRevision,
        range: ClipRange,
    ): DiagnosticWrap = withTimeout(20.seconds) {
        var high: PreviewEvent.Position? = null
        while (true) {
            val event = channel.receive()
            if (event !is PreviewEvent.Position || event.revision != revision) continue
            if (event.sourcePosition >= range.endExclusive - 150.milliseconds && event.isPlaying) {
                high = event
                continue
            }
            val before = high
            if (before != null && event.sourcePosition <= range.start + 150.milliseconds) {
                return@withTimeout DiagnosticWrap(before, event)
            }
        }
        error("Unreachable")
    }

    suspend fun awaitPlayingNearStart(
        revision: PreviewRevision,
        range: ClipRange,
    ): PreviewEvent.Position? = withTimeoutOrNull(15.seconds) {
        while (true) {
            val event = channel.receive()
            if (
                event is PreviewEvent.Position &&
                event.revision == revision &&
                event.isPlaying &&
                event.sourcePosition <= range.start + 150.milliseconds
            ) {
                return@withTimeoutOrNull event
            }
        }
        error("Unreachable")
    }

    fun close() {
        scope.cancel()
    }
}

private data class DiagnosticPlayerState(
    val sequence: Long,
    val caseName: String,
    val callback: String,
    val playbackState: Int,
    val playWhenReady: Boolean,
    val isPlaying: Boolean,
    val currentPositionMs: Long,
    val durationMs: Long,
    val repeatMode: Int,
    val clippingStartMs: Long,
    val clippingEndMs: Long,
    val sourcePositionMs: Long,
    val oldPositionMs: Long? = null,
    val newPositionMs: Long? = null,
    val error: String? = null,
)

private class DiagnosticPlayerProbe(
    private val player: Player,
) : Player.Listener {
    private val states = mutableListOf<DiagnosticPlayerState>()
    var activeCase: String = "unassigned"

    override fun onPlaybackStateChanged(playbackState: Int) {
        record("playbackState:$playbackState")
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        record("playWhenReady:$playWhenReady:$reason")
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        record("isPlaying:$isPlaying")
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        record(
            callback = "discontinuity:$reason",
            oldPositionMs = oldPosition.positionMs,
            newPositionMs = newPosition.positionMs,
        )
    }

    override fun onPlayerError(error: PlaybackException) {
        record("error", error = error.errorCodeName)
    }

    fun snapshot(): List<DiagnosticPlayerState> = synchronized(states) { states.toList() }

    private fun record(
        callback: String,
        oldPositionMs: Long? = null,
        newPositionMs: Long? = null,
        error: String? = null,
    ) {
        val clipping = player.currentMediaItem?.clippingConfiguration
        val currentPositionMs = player.currentPosition
        val clippingStartMs = clipping?.startPositionMs ?: 0L
        val state = synchronized(states) {
            DiagnosticPlayerState(
                sequence = states.size.toLong() + 1L,
                caseName = activeCase,
                callback = callback,
                playbackState = player.playbackState,
                playWhenReady = player.playWhenReady,
                isPlaying = player.isPlaying,
                currentPositionMs = currentPositionMs,
                durationMs = player.duration,
                repeatMode = player.repeatMode,
                clippingStartMs = clippingStartMs,
                clippingEndMs = clipping?.endPositionMs ?: 0L,
                sourcePositionMs = clippingStartMs + currentPositionMs,
                oldPositionMs = oldPositionMs,
                newPositionMs = newPositionMs,
                error = error,
            ).also(states::add)
        }
        Log.i("IG1LoopDiagnostic", state.toString())
    }
}

private fun <T> onDiagnosticMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}

private fun RecordedEventLedger.previewCommands(): List<PreviewCommand> =
    events(RecordedEditorEventKind.PreviewCommand).map { checkNotNull(it.previewCommand) }

private fun RecordedEventLedger.previewEvents(): List<PreviewEvent> =
    events(RecordedEditorEventKind.PreviewEvent).map { checkNotNull(it.previewEvent) }
