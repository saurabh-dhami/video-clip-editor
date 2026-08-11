package com.oneononearena.videoclip.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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

internal fun contentPxToSourceTime(
    contentPx: Float,
    contentWidthPx: Float,
    duration: Duration,
): Duration {
    if (contentWidthPx <= 0f || duration <= Duration.ZERO) return Duration.ZERO
    val boundedPx = contentPx.coerceIn(0f, contentWidthPx)
    return (boundedPx / contentWidthPx * duration.inWholeMilliseconds).roundToInt().milliseconds
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

internal fun <T : Any> fillMissingThumbnailSlots(values: List<T?>, slotCount: Int): List<T?> {
    var nearest = values.firstOrNull { it != null }
    return List(slotCount.coerceAtLeast(0)) { index ->
        values.getOrNull(index)?.also { nearest = it } ?: nearest
    }
}

@Composable
internal fun ClipRangeSelector(
    frames: List<ThumbnailFrame>,
    frameSlots: Int = frames.size,
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
    val density = LocalDensity.current
    val frameHeight = 48.dp
    val handleTarget = 48.dp
    val handleTargetPx = with(density) { handleTarget.toPx() }
    var viewportSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val contentWidthPx = viewportSize.width.toFloat()
    val slotCount = frameSlots.coerceAtLeast(frames.size).coerceAtLeast(1)
    val decodedFrames = remember(frames) {
        frames.map { frame -> decodeJpegForRender(frame.copyEncodedJpeg()) }
    }
    val slotBitmaps = fillMissingThumbnailSlots(decodedFrames, slotCount)
    val duration = metadata.duration
    val sourceTimeAt: (Float) -> Duration = { viewportPx ->
        contentPxToSourceTime(viewportPx, contentWidthPx, duration)
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(frameHeight)
            .semantics { testTag = "clip-timeline" }
            .onSizeChanged { viewportSize = it }
            .pointerInput(viewportSize, contentWidthPx, duration) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val up = waitForUpOrCancellation()
                    if (up != null && (up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                        onSeek(clampPlayhead(sourceTimeAt(up.position.x), range))
                    }
                }
            },
    ) {
        Row(Modifier.fillMaxSize()) {
            repeat(slotCount) { index ->
                val slotModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { testTag = "clip-thumbnail-$index" }
                val bitmap = slotBitmaps[index]
                if (bitmap != null) {
                    Image(bitmap, null, slotModifier, contentScale = ContentScale.Crop)
                } else {
                    Box(slotModifier.background(Color.DarkGray))
                }
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
        SelectorHandle("clip-start-handle", startPx, contentWidthPx, handleTargetPx, onRangeGestureStart, onRangeGestureEnd, onRangeGestureCancel) { x -> onRangeChange(RangeBoundary.Start, sourceTimeToContentPxToDuration(x, duration, contentWidthPx)) }
        SelectorHandle("clip-end-handle", endPx, contentWidthPx, handleTargetPx, onRangeGestureStart, onRangeGestureEnd, onRangeGestureCancel) { x -> onRangeChange(RangeBoundary.End, sourceTimeToContentPxToDuration(x, duration, contentWidthPx)) }
        Playhead(
            positionPx = sourceTimeToContentPx(playhead, duration, contentWidthPx),
            duration = duration,
            contentWidthPx = contentWidthPx,
            onDragStart = onPlayheadDragStart,
            onSeek = onSeek,
        )
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
    val density = LocalDensity.current
    val targetWidthPx = with(density) { 12.dp.toPx() }
    val markerWidthPx = with(density) { 2.dp.toPx() }
    val targetLeft = (position - targetWidthPx / 2f)
        .coerceIn(0f, (contentWidthPx - targetWidthPx).coerceAtLeast(0f))
    val markerLeft = (position - targetLeft - markerWidthPx / 2f)
        .coerceIn(0f, (targetWidthPx - markerWidthPx).coerceAtLeast(0f))
    Box(
        Modifier
            .offset { IntOffset(targetLeft.roundToInt(), 0) }
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
    ) {
        Box(
            Modifier
                .offset { IntOffset(markerLeft.roundToInt(), 0) }
                .width(2.dp)
                .height(48.dp)
                .background(Color.Red),
        )
    }
}

private fun sourceTimeToContentPxToDuration(contentPx: Float, duration: Duration, contentWidthPx: Float): Duration =
    contentPxToSourceTime(contentPx, contentWidthPx, duration)

@Composable
private fun SelectorHandle(
    tag: String,
    positionPx: Float,
    contentWidthPx: Float,
    targetWidthPx: Float,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPosition: (Float) -> Unit,
) {
    var position by remember(tag, positionPx) { mutableFloatStateOf(positionPx) }
    val targetLeft = (position - targetWidthPx / 2f)
        .coerceIn(0f, (contentWidthPx - targetWidthPx).coerceAtLeast(0f))
    val markerWidthPx = with(LocalDensity.current) { 12.dp.toPx() }
    val markerLeft = (position - targetLeft - markerWidthPx / 2f)
        .coerceIn(0f, (targetWidthPx - markerWidthPx).coerceAtLeast(0f))
    Box(
        Modifier
            .offset { IntOffset(targetLeft.roundToInt(), 0) }
            .size(with(LocalDensity.current) { targetWidthPx.toDp() }, 48.dp)
            .semantics { testTag = tag }
            .pointerInput(tag) {
                detectDragGestures(onDragStart = { onDragStart() }, onDragEnd = onDragEnd, onDragCancel = onDragCancel) { change, drag ->
                    change.consume()
                    position = (position + drag.x).coerceIn(0f, contentWidthPx)
                    onPosition(position)
                }
            },
    ) {
        Box(
            Modifier
                .offset { IntOffset(markerLeft.roundToInt(), 0) }
                .align(Alignment.CenterStart)
                .width(12.dp)
                .height(32.dp)
                .background(Color.White),
        )
    }
}
