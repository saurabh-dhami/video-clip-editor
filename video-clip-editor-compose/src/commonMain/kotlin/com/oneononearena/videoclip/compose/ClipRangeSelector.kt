package com.oneononearena.videoclip.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ThumbnailFrame
import com.oneononearena.videoclip.VideoMetadata
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal enum class RangeBoundary { Start, End }

internal fun sourceTimeToContentPx(sourceTime: Duration, duration: Duration, contentWidthPx: Float): Float {
    if (duration <= Duration.ZERO || contentWidthPx <= 0f) return 0f
    return (sourceTime.inWholeMilliseconds.toFloat() / duration.inWholeMilliseconds * contentWidthPx)
        .coerceIn(0f, contentWidthPx)
}

internal fun viewportPxToSourceTime(
    viewportPx: Float,
    scrollPx: Float,
    viewportWidthPx: Float,
    contentWidthPx: Float,
    duration: Duration,
): Duration {
    if (viewportWidthPx <= 0f || contentWidthPx <= 0f || duration <= Duration.ZERO) return Duration.ZERO
    val contentPx = (viewportPx + scrollPx).coerceIn(0f, contentWidthPx)
    return (contentPx / contentWidthPx * duration.inWholeMilliseconds).roundToInt().milliseconds
}

internal fun clampRangeBoundary(
    requested: Duration,
    fixedOtherBoundary: Duration,
    duration: Duration,
    boundary: RangeBoundary,
): Duration = when (boundary) {
    RangeBoundary.Start -> requested.coerceIn(Duration.ZERO, fixedOtherBoundary - 500.milliseconds)
    RangeBoundary.End -> requested.coerceIn(fixedOtherBoundary + 500.milliseconds, duration)
}

internal fun clampPlayhead(value: Duration, range: ClipRange): Duration =
    value.coerceIn(range.start, range.endExclusive)

@Composable
internal fun ClipRangeSelector(
    frames: List<ThumbnailFrame>,
    metadata: VideoMetadata,
    range: ClipRange,
    playhead: Duration,
    modifier: Modifier = Modifier,
    onRangeGestureStart: () -> Unit = {},
    onRangeChange: (RangeBoundary, Duration) -> Unit = { _, _ -> },
    onRangeGestureEnd: () -> Unit = {},
    onRangeGestureCancel: () -> Unit = {},
    onSeek: (Duration) -> Unit = {},
    onPlayheadDragStart: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val frameWidth = 64.dp
    val frameHeight = 48.dp
    val handleTarget = 48.dp
    val handleTargetPx = with(density) { handleTarget.toPx() }
    var viewportSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val contentWidthPx = with(density) { (frames.size * frameWidth.toPx()).coerceAtLeast(viewportSize.width.toFloat()) }
    val duration = metadata.duration
    val sourceTimeAt: (Float) -> Duration = { viewportPx ->
        viewportPxToSourceTime(viewportPx, scrollState.value.toFloat(), viewportSize.width.toFloat(), contentWidthPx, duration)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(frameHeight)
            .semantics { testTag = "clip-timeline" }
            .onSizeChanged { viewportSize = it }
            .pointerInput(scrollState.value, viewportSize, contentWidthPx, duration) {
                detectTapGestures { offset -> onSeek(clampPlayhead(sourceTimeAt(offset.x), range)) }
            },
    ) {
        Row(Modifier.horizontalScroll(scrollState)) {
            Box(Modifier.width(with(density) { contentWidthPx.toDp() }).height(frameHeight)) {
                Row {
                    frames.forEach { frame ->
                        decodeJpegForRender(frame.copyEncodedJpeg())?.let { bitmap ->
                            Image(bitmap, null, Modifier.size(frameWidth, frameHeight))
                        } ?: Box(Modifier.size(frameWidth, frameHeight).background(Color.DarkGray))
                    }
                }
                val startPx = sourceTimeToContentPx(range.start, duration, contentWidthPx)
                val endPx = sourceTimeToContentPx(range.endExclusive, duration, contentWidthPx)
                Box(Modifier.width(with(density) { startPx.toDp() }).height(frameHeight).background(Color.Black.copy(alpha = 0.55f)))
                Box(Modifier.offset { IntOffset(endPx.roundToInt(), 0) }.fillMaxWidth().height(frameHeight).background(Color.Black.copy(alpha = 0.55f)))
                Box(
                    Modifier
                        .offset { IntOffset(startPx.roundToInt(), 0) }
                        .width(with(density) { (endPx - startPx).coerceAtLeast(0f).toDp() })
                        .height(frameHeight)
                        .border(2.dp, Color.Yellow)
                        .semantics { testTag = "clip-selected-range" },
                )
                SelectorHandle("clip-start-handle", startPx, handleTargetPx, onRangeGestureStart, onRangeGestureEnd, onRangeGestureCancel) { x -> onRangeChange(RangeBoundary.Start, sourceTimeToContentPxToDuration(x, duration, contentWidthPx)) }
                SelectorHandle("clip-end-handle", endPx, handleTargetPx, onRangeGestureStart, onRangeGestureEnd, onRangeGestureCancel) { x -> onRangeChange(RangeBoundary.End, sourceTimeToContentPxToDuration(x, duration, contentWidthPx)) }
                Playhead(
                    positionPx = sourceTimeToContentPx(playhead, duration, contentWidthPx),
                    duration = duration,
                    contentWidthPx = contentWidthPx,
                    onDragStart = onPlayheadDragStart,
                    onSeek = onSeek,
                )
            }
        }
    }
}

@Composable
private fun Playhead(
    positionPx: Float,
    duration: Duration,
    contentWidthPx: Float,
    onDragStart: () -> Unit,
    onSeek: (Duration) -> Unit,
) {
    var position by remember(positionPx) { mutableFloatStateOf(positionPx) }
    Box(
        Modifier
            .offset { IntOffset(position.roundToInt(), 0) }
            .width(12.dp)
            .height(48.dp)
            .semantics { testTag = "clip-playhead" }
            .pointerInput(duration, contentWidthPx) {
                detectDragGestures(onDragStart = { onDragStart() }) { change, drag ->
                    change.consume()
                    position = (position + drag.x).coerceIn(0f, contentWidthPx)
                    onSeek(sourceTimeToContentPxToDuration(position, duration, contentWidthPx))
                }
            },
    ) { Box(Modifier.align(Alignment.Center).width(2.dp).height(48.dp).background(Color.Red)) }
}

private fun sourceTimeToContentPxToDuration(contentPx: Float, duration: Duration, contentWidthPx: Float): Duration =
    viewportPxToSourceTime(contentPx, 0f, 1f, contentWidthPx, duration)

@Composable
private fun SelectorHandle(
    tag: String,
    positionPx: Float,
    targetWidthPx: Float,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPosition: (Float) -> Unit,
) {
    var position by remember(tag, positionPx) { mutableFloatStateOf(positionPx) }
    Box(
        Modifier
            .offset { IntOffset((position - targetWidthPx / 2f).roundToInt(), 0) }
            .size(with(LocalDensity.current) { targetWidthPx.toDp() }, 48.dp)
            .semantics { testTag = tag }
            .pointerInput(tag) {
                detectDragGestures(onDragStart = { onDragStart() }, onDragEnd = onDragEnd, onDragCancel = onDragCancel) { change, drag ->
                    change.consume()
                    position += drag.x
                    onPosition(position)
                }
            },
    ) { Box(Modifier.align(Alignment.Center).width(12.dp).height(32.dp).background(Color.White)) }
}
