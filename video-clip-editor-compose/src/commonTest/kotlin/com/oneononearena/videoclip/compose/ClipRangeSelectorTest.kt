package com.oneononearena.videoclip.compose

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
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
    fun bareTrackDrag_doesNotSeek() = runComposeUiTest {
        var sought = (-1).milliseconds

        setContent {
            ClipRangeSelector(
                frames = emptyList(),
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

    @Test
    fun viewportTimeMapping_isInverseAtZeroHalfAndMaximumScroll() {
        val duration = 10_000.milliseconds
        val contentWidth = 2_400f

        listOf(0f, 1_000f, 2_000f).forEach { scrollPx ->
            val content = sourceTimeToContentPx(7_500.milliseconds, duration, contentWidth)

            assertEquals(
                7_500.milliseconds,
                viewportPxToSourceTime(
                    viewportPx = content - scrollPx,
                    scrollPx = scrollPx,
                    viewportWidthPx = 400f,
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
