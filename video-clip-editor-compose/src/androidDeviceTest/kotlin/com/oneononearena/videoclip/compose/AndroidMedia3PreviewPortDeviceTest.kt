package com.oneononearena.videoclip.compose

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMedia3PreviewPortDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fixtures = PreviewFixtureFiles(context)

    @After
    fun cleanFixtureCopies() {
        fixtures.deleteCopies()
    }

    @Test
    fun bindingUsesExactSourceClipAndOnePeriodLoop() = runBlocking {
        val source = fixtures.copyAvcFixture()
        val port = onMain { AndroidMedia3PreviewPort(context) }
        val recorder = EventRecorder(port)

        try {
            onMain {
                port.dispatch(PreviewCommand.Bind(binding(source, revision = 1, startSeconds = 2, endSeconds = 4)))
            }
            recorder.await { it is PreviewEvent.Ready && it.revision == PreviewRevision(1) }

            val player = onMain { checkNotNull(port.playerForSurface) }
            val item = onMain { checkNotNull(player.currentMediaItem) }
            assertEquals(2_000L, item.clippingConfiguration.startPositionMs)
            assertEquals(4_000L, item.clippingConfiguration.endPositionMs)
            assertEquals(Player.REPEAT_MODE_ONE, onMain { player.repeatMode })
            assertFalse(onMain { player.playWhenReady })
            assertTrue(onMain { player.currentPosition } in 0L..250L)
        } finally {
            onMain { port.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
            recorder.close()
        }
    }

    @Test
    fun obsoleteReadyDoesNotReachEventsWhenLatestRangeIsPending() = runBlocking {
        val source = fixtures.copyAvcFixture()
        val port = onMain { AndroidMedia3PreviewPort(context) }
        val recorder = EventRecorder(port)

        try {
            onMain {
                port.dispatch(PreviewCommand.Bind(binding(source, revision = 1, startSeconds = 0, endSeconds = 8)))
                port.dispatch(PreviewCommand.ReplaceRange(binding(source, revision = 2, startSeconds = 2, endSeconds = 4)))
            }

            recorder.await { it is PreviewEvent.Ready && it.revision == PreviewRevision(2) }
            assertFalse(recorder.snapshot().any { it is PreviewEvent.Ready && it.revision == PreviewRevision(1) })
            assertEquals("1:2", onMain { port.playerForSurface?.currentMediaItem?.mediaId })
        } finally {
            onMain { port.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
            recorder.close()
        }
    }

    @Test
    fun staleRetryCannotReplaceNewerReadyBindingOrPlaybackIntent() = runBlocking {
        val source = fixtures.copyAvcFixture()
        val port = onMain { AndroidMedia3PreviewPort(context) }
        val recorder = EventRecorder(port)

        try {
            onMain {
                port.dispatch(
                    PreviewCommand.Bind(
                        binding(source, revision = 2, startSeconds = 2, endSeconds = 4),
                    ),
                )
            }
            recorder.await { it == PreviewEvent.Ready(PreviewGeneration(1), PreviewRevision(2)) }
            val player = onMain { checkNotNull(port.playerForSurface) }

            onMain {
                port.dispatch(
                    PreviewCommand.Retry(
                        binding(
                            source = source,
                            revision = 1,
                            startSeconds = 0,
                            endSeconds = 8,
                            playWhenReady = true,
                        ),
                    ),
                )
            }
            delay(2.seconds)

            val item = onMain { checkNotNull(player.currentMediaItem) }
            assertEquals("1:2", item.mediaId)
            assertEquals(2_000L, item.clippingConfiguration.startPositionMs)
            assertEquals(4_000L, item.clippingConfiguration.endPositionMs)
            assertFalse(
                recorder.snapshot().any {
                    it == PreviewEvent.Ready(PreviewGeneration(1), PreviewRevision(1))
                },
            )
            assertFalse(onMain { player.playWhenReady })
        } finally {
            onMain { port.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
            recorder.close()
        }
    }

    @Test
    fun sourcePositionEventsStayInsideSelectedRange() = runBlocking {
        val source = fixtures.copyAvcFixture()
        val port = onMain { AndroidMedia3PreviewPort(context) }
        val recorder = EventRecorder(port)

        try {
            onMain { port.dispatch(PreviewCommand.Bind(binding(source, revision = 3, startSeconds = 2, endSeconds = 4))) }
            recorder.await { it is PreviewEvent.Ready && it.revision == PreviewRevision(3) }
            onMain {
                port.dispatch(
                    PreviewCommand.Seek(
                        generation = PreviewGeneration(1),
                        revision = PreviewRevision(3),
                        sourcePosition = 20.seconds,
                    ),
                )
            }
            recorder.await { it is PreviewEvent.Position && it.revision == PreviewRevision(3) }
            val positions = recorder.snapshot().filterIsInstance<PreviewEvent.Position>()
            assertTrue(positions.isNotEmpty())
            assertTrue(positions.all { it.sourcePosition >= 2.seconds && it.sourcePosition <= 4.seconds })
        } finally {
            onMain { port.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
            recorder.close()
        }
    }

    @Test
    fun playerFailureEmitsOnlyBoundedSafeDiagnostic() = runBlocking {
        val missing = File(context.cacheDir, "missing-preview-${System.nanoTime()}.mp4")
        val port = onMain { AndroidMedia3PreviewPort(context) }
        val recorder = EventRecorder(port)

        try {
            onMain { port.dispatch(PreviewCommand.Bind(binding(missing, revision = 4, startSeconds = 0, endSeconds = 2))) }
            val failure = recorder.await { it is PreviewEvent.RecoverableFailure } as PreviewEvent.RecoverableFailure
            assertNotNull(failure.diagnostic)
            val diagnostic = requireNotNull(failure.diagnostic)
            assertTrue(diagnostic.length <= 160)
            assertFalse(diagnostic.contains(missing.absolutePath))
            assertFalse(diagnostic.contains('\n'))
            assertFalse(diagnostic.contains("Exception"))
        } finally {
            onMain { port.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
            recorder.close()
        }
    }

    @Test
    fun repeatedReleaseEmitsOneAcknowledgement() = runBlocking {
        val port = onMain { AndroidMedia3PreviewPort(context) }
        val recorder = EventRecorder(port)

        try {
            onMain {
                port.dispatch(PreviewCommand.Release(PreviewGeneration(9)))
                port.dispatch(PreviewCommand.Release(PreviewGeneration(9)))
            }

            recorder.await { it == PreviewEvent.Released(PreviewGeneration(9)) }
            assertEquals(1, recorder.snapshot().count { it == PreviewEvent.Released(PreviewGeneration(9)) })
        } finally {
            recorder.close()
        }
    }

    @Test
    fun releaseIsTerminalAndReplacementRequiresAFreshActualPort() = runBlocking {
        val source = fixtures.copyAvcFixture()
        val old = onMain { AndroidMedia3PreviewPort(context) }
        val oldRecorder = EventRecorder(old)
        try {
            onMain { old.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
            oldRecorder.await { it == PreviewEvent.Released(PreviewGeneration(1)) }
            onMain { old.dispatch(PreviewCommand.Bind(binding(source, revision = 1, startSeconds = 0, endSeconds = 2))) }
            delay(250)
            assertEquals(null, onMain { old.playerForSurface })
            assertFalse(oldRecorder.snapshot().any { it is PreviewEvent.Ready })

            val fresh = onMain { AndroidMedia3PreviewPort(context) }
            val freshRecorder = EventRecorder(fresh)
            try {
                onMain { fresh.dispatch(PreviewCommand.Bind(binding(source, revision = 1, startSeconds = 0, endSeconds = 2))) }
                freshRecorder.await { it is PreviewEvent.Ready }
            } finally {
                onMain { fresh.dispatch(PreviewCommand.Release(PreviewGeneration(1))) }
                freshRecorder.close()
            }
        } finally {
            oldRecorder.close()
        }
    }

    private fun binding(
        source: File,
        revision: Long,
        startSeconds: Int,
        endSeconds: Int,
        playWhenReady: Boolean = false,
    ) = PreviewBinding(
        generation = PreviewGeneration(1),
        revision = PreviewRevision(revision),
        source = VideoSourcePath(source.absolutePath),
        metadata = VideoMetadata(10.seconds, 320, 240, true),
        range = ClipRange(startSeconds.seconds, endSeconds.seconds),
        sourcePosition = startSeconds.seconds,
        playWhenReady = playWhenReady,
    )
}

private class EventRecorder(port: PreviewPort) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val channel = Channel<PreviewEvent>(Channel.UNLIMITED)
    private val events = mutableListOf<PreviewEvent>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            port.events.collect { event ->
                synchronized(events) { events += event }
                channel.send(event)
            }
        }
    }

    suspend fun await(predicate: (PreviewEvent) -> Boolean): PreviewEvent = withTimeout(15.seconds) {
        while (true) {
            val event = channel.receive()
            if (predicate(event)) return@withTimeout event
        }
        error("Unreachable")
    }

    fun snapshot(): List<PreviewEvent> = synchronized(events) { events.toList() }

    fun close() {
        scope.cancel()
    }
}

private fun <T> onMain(block: () -> T): T {
    var result: Result<T>? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
