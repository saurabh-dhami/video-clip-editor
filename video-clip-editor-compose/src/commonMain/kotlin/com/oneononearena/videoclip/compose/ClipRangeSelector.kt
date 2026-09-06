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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    frameSlots: Int = 0,
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
    style: ClipEditorStyle = ClipEditorStyle(),
    labels: ClipEditorLabels = ClipEditorLabels(),
) {
    val density = LocalDensity.current
    val frameHeight = style.safeThumbnailHeight
    val handleTarget = 48.dp
    val handleTargetPx = with(density) { handleTarget.toPx() }
    var viewportSize by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }
    val contentWidthPx = viewportSize.width.toFloat()
    val slotCount = if (frameSlots > 0) frameSlots else overviewSlotCount(with(density) { contentWidthPx.toDp().value })
    val selectedFrames = remember(frames, slotCount) {
        overviewFrameIndices(frames.size, slotCount).map { frames[it] }
    }
    val decodedFrames by produceState<List<ImageBitmap?>>(emptyList(), selectedFrames) {
        value = withContext(Dispatchers.Default) {
            selectedFrames.map { frame -> decodeJpegForRender(frame.copyEncodedJpeg()) }
        }
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
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(style.safeCornerRadius))) {
            repeat(slotCount) { index ->
                val slotModifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .semantics { testTag = "clip-thumbnail-$index" }
                val bitmap = slotBitmaps[index]
                if (bitmap != null) {
                    Image(bitmap, null, slotModifier, contentScale = ContentScale.Crop)
                } else {
                    Box(slotModifier.background(style.thumbnailPlaceholderColor))
                }
            }
        }
        val startPx = sourceTimeToContentPx(range.start, duration, contentWidthPx)
        val endPx = sourceTimeToContentPx(range.endExclusive, duration, contentWidthPx)
        Box(Modifier.width(with(density) { startPx.toDp() }).height(frameHeight).background(style.outsideSelectionColor))
        Box(Modifier.offset { IntOffset(endPx.roundToInt(), 0) }
            .width(with(density) { (contentWidthPx - endPx).coerceAtLeast(0f).toDp() })
            .height(frameHeight).background(style.outsideSelectionColor))
        Box(
            Modifier
                .offset { IntOffset(startPx.roundToInt(), 0) }
                .width(with(density) { (endPx - startPx).coerceAtLeast(0f).toDp() })
                .height(frameHeight)
                .border(style.safeSelectionBorderWidth, style.selectionColor, RoundedCornerShape(style.safeCornerRadius))
                .semantics { testTag = "clip-selected-range" },
        )
        SelectorHandle("clip-start-handle", startPx, contentWidthPx, handleTargetPx, style, labels.start,
            formatClipTime(range.start), onRangeGestureStart, onRangeGestureEnd, onRangeGestureCancel) { x ->
            onRangeChange(RangeBoundary.Start, sourceTimeToContentPxToDuration(x, duration, contentWidthPx))
        }
        SelectorHandle("clip-end-handle", endPx, contentWidthPx, handleTargetPx, style, labels.end,
            formatClipTime(range.endExclusive), onRangeGestureStart, onRangeGestureEnd, onRangeGestureCancel) { x ->
            onRangeChange(RangeBoundary.End, sourceTimeToContentPxToDuration(x, duration, contentWidthPx))
        }
        Playhead(
            positionPx = sourceTimeToContentPx(playhead, duration, contentWidthPx),
            duration = duration,
            contentWidthPx = contentWidthPx,
            onDragStart = onPlayheadDragStart,
            onSeek = onSeek,
            style = style,
            label = labels.playhead,
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
    style: ClipEditorStyle,
    label: String,
) {
    var dragPosition by remember { mutableFloatStateOf(positionPx) }
    var dragging by remember { mutableStateOf(false) }
    val latestPosition by rememberUpdatedState(positionPx)
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestSeek by rememberUpdatedState(onSeek)
    val position = if (dragging) dragPosition else positionPx
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
            .height(style.safeThumbnailHeight)
            .semantics {
                testTag = "clip-playhead"
                contentDescription = label
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..contentWidthPx)
                setProgress { value ->
                    onDragStart()
                    onSeek(contentPxToSourceTime(value, contentWidthPx, duration))
                    true
                }
            }
            .pointerInput(duration, contentWidthPx) {
                detectDragGestures(
                    onDragStart = {
                        dragPosition = latestPosition
                        dragging = true
                        latestDragStart()
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, drag ->
                    change.consume()
                    dragPosition = (dragPosition + drag.x).coerceIn(0f, contentWidthPx)
                    latestSeek(sourceTimeToContentPxToDuration(dragPosition, duration, contentWidthPx))
                }
            },
    ) {
        Box(
            Modifier
                .offset { IntOffset(markerLeft.roundToInt(), 0) }
                .width(2.dp)
                .height(style.safeThumbnailHeight)
                .background(style.playheadColor),
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
    style: ClipEditorStyle,
    label: String,
    timeLabel: String,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onPosition: (Float) -> Unit,
) {
    var dragPosition by remember(tag) { mutableFloatStateOf(positionPx) }
    var dragging by remember(tag) { mutableStateOf(false) }
    val latestPosition by rememberUpdatedState(positionPx)
    val latestDragStart by rememberUpdatedState(onDragStart)
    val latestDragEnd by rememberUpdatedState(onDragEnd)
    val latestDragCancel by rememberUpdatedState(onDragCancel)
    val latestOnPosition by rememberUpdatedState(onPosition)
    val position = if (dragging) dragPosition else positionPx
    val targetLeft = (position - targetWidthPx / 2f)
        .coerceIn(0f, (contentWidthPx - targetWidthPx).coerceAtLeast(0f))
    val markerWidthPx = with(LocalDensity.current) { 12.dp.toPx() }
    val markerLeft = (position - targetLeft - markerWidthPx / 2f)
        .coerceIn(0f, (targetWidthPx - markerWidthPx).coerceAtLeast(0f))
    Box(
        Modifier
            .offset { IntOffset(targetLeft.roundToInt(), 0) }
            .size(with(LocalDensity.current) { targetWidthPx.toDp() }, style.safeThumbnailHeight)
            .semantics {
                testTag = tag
                contentDescription = label
                stateDescription = timeLabel
                progressBarRangeInfo = ProgressBarRangeInfo(position, 0f..contentWidthPx)
                setProgress { value ->
                    onDragStart()
                    onPosition(value.coerceIn(0f, contentWidthPx))
                    onDragEnd()
                    true
                }
            }
            .pointerInput(tag, contentWidthPx) {
                detectDragGestures(
                    onDragStart = {
                        dragPosition = latestPosition
                        dragging = true
                        latestDragStart()
                    },
                    onDragEnd = { dragging = false; latestDragEnd() },
                    onDragCancel = { dragging = false; latestDragCancel() },
                ) { change, drag ->
                    change.consume()
                    dragPosition = (dragPosition + drag.x).coerceIn(0f, contentWidthPx)
                    latestOnPosition(dragPosition)
                }
            },
    ) {
        Box(
            Modifier
                .offset { IntOffset(markerLeft.roundToInt(), 0) }
                .align(Alignment.CenterStart)
                .width(12.dp)
                .height(style.safeThumbnailHeight)
                .background(style.handleColor, RoundedCornerShape(style.safeCornerRadius)),
            contentAlignment = Alignment.Center,
        ) {
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(2.dp)) {
                repeat(2) { Box(Modifier.width(1.dp).height(16.dp).background(style.handleGripColor)) }
            }
        }
    }
}
