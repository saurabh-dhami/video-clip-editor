package com.oneononearena.videoclip.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClipRangeSelectorTest {
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
