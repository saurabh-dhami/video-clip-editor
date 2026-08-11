package com.oneononearena.videoclip.compose

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.cancel
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.VideoMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClipRangeSelectorTest {
    @Test
    fun thumbnailSlotsReuseNearestFrameInsteadOfLeavingVisualGaps() {
        assertEquals(
            listOf("first", "first", "third", "third"),
            fillMissingThumbnailSlots(listOf("first", null, "third", null), slotCount = 4),
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun selector_exposesVisibleSelectionAnd48DpHandles_andTimelineTapSeeks() = runComposeUiTest {
        var sought = (-1).milliseconds

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(2.seconds, 8.seconds),
                playhead = 2.seconds,
                modifier = Modifier.width(400.dp),
                onSeek = { sought = it },
            )
        }

        onNodeWithTag("clip-selected-range").assertIsDisplayed()
        onNodeWithTag("clip-start-handle").assertTouchWidthIsEqualTo(48.dp)
        onNodeWithTag("clip-end-handle").assertTouchWidthIsEqualTo(48.dp)
        onNodeWithTag("clip-timeline").performTouchInput { click(center) }
        waitForIdle()

        assertEquals(5.seconds, sought)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timelineFitsEveryThumbnailSlotWithoutHorizontalScrolling() = runComposeUiTest {
        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                frameSlots = 4,
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(Duration.ZERO, 10.seconds),
                playhead = Duration.ZERO,
                modifier = Modifier.width(400.dp),
            )
        }

        onAllNodes(hasScrollAction()).assertCountEquals(0)
        repeat(4) { index ->
            onNodeWithTag("clip-thumbnail-$index").assertWidthIsEqualTo(100.dp)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun bareTrackDrag_doesNotScrollOrSeek() = runComposeUiTest {
        var sought = (-1).milliseconds

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                frameSlots = 24,
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(Duration.ZERO, 10.seconds),
                playhead = Duration.ZERO,
                modifier = Modifier.width(400.dp),
                onSeek = { sought = it },
            )
        }

        onNodeWithTag("clip-timeline").performTouchInput {
            down(center)
            moveBy(Offset(-160f, 0f))
            up()
        }
        waitForIdle()

        assertEquals((-1).milliseconds, sought)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun timelineTapMapsTheFixedViewportAcrossTheFullVideo() = runComposeUiTest {
        var sought = (-1).milliseconds

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                frameSlots = 24,
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(Duration.ZERO, 10.seconds),
                playhead = Duration.ZERO,
                modifier = Modifier.width(400.dp),
                onSeek = { sought = it },
            )
        }

        onNodeWithTag("clip-timeline").performTouchInput {
            down(center)
            moveBy(Offset(-160f, 0f))
            up()
        }
        onNodeWithTag("clip-timeline").performTouchInput { click(center) }
        waitForIdle()

        assertEquals(5.seconds, sought)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun playheadDrag_pausesAndSeeksThroughRecordingPreviewPort() = runComposeUiTest {
        val port = SelectorRecordingPreviewPort()

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(2.seconds, 8.seconds),
                playhead = 4.seconds,
                modifier = Modifier.width(400.dp),
                onPlayheadDragStart = {
                    port.dispatch(PreviewCommand.SetPlayWhenReady(PreviewGeneration(1), PreviewRevision(1), false))
                },
                onSeek = {
                    port.dispatch(PreviewCommand.Seek(PreviewGeneration(1), PreviewRevision(1), it))
                },
            )
        }

        onNodeWithTag("clip-playhead").performTouchInput {
            down(center)
            moveBy(Offset(40f, 0f))
            up()
        }
        waitForIdle()

        assertEquals(false, port.commands.filterIsInstance<PreviewCommand.SetPlayWhenReady>().single().value)
        assertTrue(port.commands.filterIsInstance<PreviewCommand.Seek>().single().sourcePosition > 4.seconds)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun playheadAtTimelineEnd_remainsVisibleAndDraggable() = runComposeUiTest {
        var sought = 10.seconds

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(Duration.ZERO, 10.seconds),
                playhead = 10.seconds,
                modifier = Modifier.width(400.dp),
                onSeek = { sought = it },
            )
        }

        val timelineBounds = onNodeWithTag("clip-timeline").getUnclippedBoundsInRoot()
        val playheadBounds = onNodeWithTag("clip-playhead").getUnclippedBoundsInRoot()
        assertTrue(playheadBounds.left >= timelineBounds.left)
        assertTrue(playheadBounds.right <= timelineBounds.right)

        onNodeWithTag("clip-playhead").performTouchInput {
            down(center)
            moveBy(Offset(-40f, 0f))
            up()
        }
        waitForIdle()

        assertTrue(sought < 10.seconds)
        assertTrue(sought > 8.seconds)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun endHandlePointerDrag_keepsCanonicalRangeUntilRelease_andEmitsOneReplacement() = runComposeUiTest {
        val port = SelectorRecordingPreviewPort()
        val metadata = VideoMetadata(10.seconds, 100, 100, false)
        var canonical by mutableStateOf(ClipRange(2.seconds, 8.seconds))
        var provisional by mutableStateOf<ClipRange?>(null)
        var canonicalObservedDuringDrag: ClipRange? = null

        setContent {
            val visual = provisional ?: canonical
            ClipRangeSelector(
                frames = emptyList(),
                metadata = metadata,
                range = visual,
                playhead = visual.start,
                modifier = Modifier.width(400.dp),
                onRangeGestureStart = { provisional = canonical },
                onRangeChange = { boundary, value ->
                    val current = provisional ?: canonical
                    provisional = when (boundary) {
                        RangeBoundary.End -> current.copy(endExclusive = clampRangeBoundary(value, current.start, metadata.duration, boundary))
                        RangeBoundary.Start -> current.copy(start = clampRangeBoundary(value, current.endExclusive, metadata.duration, boundary))
                    }
                    canonicalObservedDuringDrag = canonical
                },
                onRangeGestureEnd = {
                    canonical = requireNotNull(provisional)
                    provisional = null
                    port.dispatch(PreviewCommand.ReplaceRange(testBinding(canonical, metadata)))
                },
                onRangeGestureCancel = { provisional = null },
            )
        }

        onNodeWithTag("clip-end-handle").performTouchInput {
            down(center)
            moveBy(Offset(-40f, 0f))
            up()
        }
        waitForIdle()

        assertEquals(8.seconds, canonicalObservedDuringDrag?.endExclusive)
        assertTrue(canonical.endExclusive < 8.seconds)
        assertEquals(1, port.commands.filterIsInstance<PreviewCommand.ReplaceRange>().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun startHandlePointerDrag_keepsCanonicalRangeUntilRelease_andEmitsOneReplacement() = runComposeUiTest {
        val port = SelectorRecordingPreviewPort()
        val metadata = VideoMetadata(10.seconds, 100, 100, false)
        var canonical by mutableStateOf(ClipRange(2.seconds, 8.seconds))
        var provisional by mutableStateOf<ClipRange?>(null)
        var canonicalObservedDuringDrag: ClipRange? = null

        setContent {
            val visual = provisional ?: canonical
            ClipRangeSelector(
                frames = emptyList(),
                metadata = metadata,
                range = visual,
                playhead = visual.start,
                modifier = Modifier.width(400.dp),
                onRangeGestureStart = { provisional = canonical },
                onRangeChange = { boundary, value ->
                    val current = provisional ?: canonical
                    provisional = when (boundary) {
                        RangeBoundary.Start -> current.copy(start = clampRangeBoundary(value, current.endExclusive, metadata.duration, boundary))
                        RangeBoundary.End -> current.copy(endExclusive = clampRangeBoundary(value, current.start, metadata.duration, boundary))
                    }
                    canonicalObservedDuringDrag = canonical
                },
                onRangeGestureEnd = {
                    canonical = requireNotNull(provisional)
                    provisional = null
                    port.dispatch(PreviewCommand.ReplaceRange(testBinding(canonical, metadata)))
                },
                onRangeGestureCancel = { provisional = null },
            )
        }

        onNodeWithTag("clip-start-handle").performTouchInput {
            down(Offset(2f, center.y))
            moveBy(Offset(40f, 0f))
            up()
        }
        waitForIdle()

        assertEquals(2.seconds, canonicalObservedDuringDrag?.start)
        assertTrue(canonical.start > 2.seconds)
        assertEquals(1, port.commands.filterIsInstance<PreviewCommand.ReplaceRange>().size)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun startHandleAtTimelineBoundary_remainsDraggable() = runComposeUiTest {
        var started = false
        var ended = false
        var changedTo = Duration.ZERO

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
                frameSlots = 24,
                metadata = VideoMetadata(10.seconds, 100, 100, false),
                range = ClipRange(Duration.ZERO, 10.seconds),
                playhead = Duration.ZERO,
                modifier = Modifier.width(400.dp),
                onRangeGestureStart = { started = true },
                onRangeChange = { boundary, value ->
                    if (boundary == RangeBoundary.Start) changedTo = value
                },
                onRangeGestureEnd = { ended = true },
            )
        }

        onNodeWithTag("clip-start-handle").performTouchInput {
            down(center)
            moveBy(Offset(320f, 0f))
            up()
        }
        waitForIdle()

        assertTrue(started)
        assertTrue(ended)
        assertTrue(changedTo >= 7.seconds)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun cancelledHandlePointerGesture_discardsProvisionalRangeWithoutReplacement() = runComposeUiTest {
        val port = SelectorRecordingPreviewPort()
        val metadata = VideoMetadata(10.seconds, 100, 100, false)
        var canonical by mutableStateOf(ClipRange(2.seconds, 8.seconds))
        var provisional by mutableStateOf<ClipRange?>(null)
        var cancelled = false
        var cancelBoundary: (() -> Unit)? = null

        setContent {
            val visual = provisional ?: canonical
            ClipRangeSelector(
                frames = emptyList(),
                metadata = metadata,
                range = visual,
                playhead = visual.start,
                modifier = Modifier.width(400.dp),
                onRangeGestureStart = { provisional = canonical },
                onRangeChange = { boundary, value ->
                    val current = provisional ?: canonical
                    provisional = current.copy(endExclusive = clampRangeBoundary(value, current.start, metadata.duration, boundary))
                },
                onRangeGestureEnd = { port.dispatch(PreviewCommand.ReplaceRange(testBinding(requireNotNull(provisional), metadata))) },
                onRangeGestureCancel = ({
                    cancelled = true
                    provisional = null
                }).also { cancelBoundary = it },
            )
        }

        onNodeWithTag("clip-end-handle").performTouchInput {
            down(center)
            moveBy(Offset(-40f, 0f), delayMillis = 200)
            cancel()
        }
        waitForIdle()

        assertTrue(provisional != null)
        requireNotNull(cancelBoundary).invoke()

        assertTrue(cancelled)
        assertEquals(ClipRange(2.seconds, 8.seconds), canonical)
        assertEquals(null, provisional)
        assertEquals(0, port.commands.filterIsInstance<PreviewCommand.ReplaceRange>().size)
    }

    @Test
    fun fixedTimelineTimeMapping_isInverseAcrossTheFullWidth() {
        val duration = 10_000.milliseconds
        val contentWidth = 400f

        listOf(0.milliseconds, 2_500.milliseconds, 7_500.milliseconds, 10_000.milliseconds).forEach { sourceTime ->
            val content = sourceTimeToContentPx(sourceTime, duration, contentWidth)

            assertEquals(
                sourceTime,
                contentPxToSourceTime(
                    contentPx = content,
                    contentWidthPx = contentWidth,
                    duration = duration,
                ),
            )
        }
    }

    @Test
    fun endHandle_cannotCrossStartOrBreakMinimumRange() {
        assertEquals(
            1_500.milliseconds,
            clampRangeBoundary(100.milliseconds, 1.seconds, 10.seconds, RangeBoundary.End),
        )
    }
}

private class SelectorRecordingPreviewPort : PreviewPort {
    override val events = kotlinx.coroutines.flow.emptyFlow<PreviewEvent>()
    val commands = mutableListOf<PreviewCommand>()

    override fun dispatch(command: PreviewCommand) {
        commands += command
    }
}

private fun testBinding(range: ClipRange, metadata: VideoMetadata) = PreviewBinding(
    generation = PreviewGeneration(1),
    revision = PreviewRevision(1),
    source = com.oneononearena.videoclip.VideoSourcePath("/video.mp4"),
    metadata = metadata,
    range = range,
    sourcePosition = range.start,
    playWhenReady = false,
)
