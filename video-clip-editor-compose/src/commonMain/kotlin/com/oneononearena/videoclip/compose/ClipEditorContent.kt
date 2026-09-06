package com.oneononearena.videoclip.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import kotlin.time.Duration

/** Stateless shared presentation. All media and lifecycle ownership stays in the screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClipEditorContent(
    ready: ClipEditorUiState.Ready,
    preview: ClipEditorPreviewState,
    style: ClipEditorStyle,
    options: ClipEditorOptions,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onTogglePlayback: () -> Unit,
    onReset: () -> Unit,
    onRangeGestureStart: () -> Unit,
    onRangeChange: (RangeBoundary, Duration) -> Unit,
    onRangeGestureEnd: () -> Unit,
    onRangeGestureCancel: () -> Unit,
    onSeek: (Duration) -> Unit,
    onPlayheadDragStart: () -> Unit,
    onRetry: () -> Unit,
    previewContent: @Composable () -> Unit,
) {
    val labels = options.labels
    val range = ready.provisionalRange ?: ready.range
    Column(Modifier.fillMaxSize().background(style.backgroundColor)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorButton(labels.back, onBack, style, Modifier.semantics { testTag = "back" })
            Text(labels.title, Modifier.weight(1f), color = style.textColor,
                style = style.typography.title ?: MaterialTheme.typography.titleMedium)
            EditorButton(labels.useClip, onDone, style, Modifier.semantics { testTag = "done" },
                primary = true, enabled = preview.failure == null && ready.provisionalRange == null)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val landscape = maxWidth > maxHeight && maxWidth >= 540.dp
            val controls: @Composable () -> Unit = {
                Column(Modifier.background(style.controlsColor).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    FlowRow(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (options.showTimestamps) Text(formatClipTime(preview.playhead),
                            Modifier.weight(1f).semantics { contentDescription = labels.playhead },
                            color = style.secondaryTextColor, style = style.typography.timestamp ?: MaterialTheme.typography.bodySmall)
                        EditorButton(if (preview.isPlaying) labels.pause else labels.play, onTogglePlayback, style,
                            Modifier.semantics { testTag = "play-pause" }, enabled = preview.ready && preview.failure == null)
                        if (options.showReset) EditorButton(labels.reset, onReset, style,
                            Modifier.semantics { testTag = "clip-reset" }, enabled = ready.provisionalRange == null)
                    }
                    if (options.showSelectedDuration) Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { testTag = "clip-selected-duration" },
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(labels.selectedDuration, Modifier.weight(1f), color = style.secondaryTextColor,
                            style = style.typography.label ?: MaterialTheme.typography.bodySmall)
                        Text(formatClipTime(range.endExclusive - range.start), color = style.secondaryTextColor,
                            style = style.typography.timestamp ?: MaterialTheme.typography.bodySmall)
                    }
                    ClipRangeSelector(
                        frames = ready.frames, metadata = ready.metadata, range = range,
                        playhead = clampPlayhead(preview.playhead, range), style = style, labels = labels,
                        onRangeGestureStart = onRangeGestureStart, onRangeChange = onRangeChange,
                        onRangeGestureEnd = onRangeGestureEnd, onRangeGestureCancel = onRangeGestureCancel,
                        onSeek = onSeek, onPlayheadDragStart = onPlayheadDragStart,
                    )
                    if (options.showTimestamps) Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf(Duration.ZERO, ready.metadata.duration / 2, ready.metadata.duration).forEach { time ->
                            Text(formatRulerTime(time), color = style.secondaryTextColor,
                                style = style.typography.timestamp ?: MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (options.showTimestamps) Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TimeLabel(labels.start, range.start, "clip-start-time", style, Modifier.weight(1f))
                        TimeLabel(labels.end, range.endExclusive, "clip-end-time", style, Modifier.weight(1f))
                    }
                }
            }
            val video: @Composable (Modifier) -> Unit = { modifier ->
                Box(modifier.background(style.previewBackgroundColor).semantics { testTag = "clip-preview" }) {
                    previewContent()
                    if (preview.failure != null) Column(
                        Modifier.align(Alignment.Center).background(style.controlsColor, RoundedCornerShape(style.safeCornerRadius)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(labels.previewUnavailable, color = style.textColor,
                            style = style.typography.body ?: MaterialTheme.typography.bodyMedium)
                        EditorButton(labels.retry, onRetry, style, Modifier.semantics { testTag = "retry-preview" })
                    }
                }
            }
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    video(Modifier.weight(1f).fillMaxHeight())
                    Box(Modifier.widthIn(max = 360.dp).fillMaxWidth(0.5f).align(Alignment.CenterVertically)) { controls() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    video(Modifier.weight(1f).fillMaxWidth())
                    controls()
                }
            }
        }
    }
}

@Composable
internal fun EditorButton(
    label: String,
    onClick: () -> Unit,
    style: ClipEditorStyle,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val container = if (primary) style.primaryButtonColor else style.secondaryButtonColor
    val content = if (primary) style.primaryButtonContentColor else style.secondaryButtonContentColor
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(style.safeCornerRadius),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content,
            disabledContainerColor = container.copy(alpha = 0.35f), disabledContentColor = content.copy(alpha = 0.6f)),
    ) { Text(label, style = style.typography.button ?: MaterialTheme.typography.labelLarge) }
}

@Composable
private fun TimeLabel(label: String, time: Duration, tag: String, style: ClipEditorStyle, modifier: Modifier) {
    Column(modifier.border(1.dp, style.secondaryButtonColor, RoundedCornerShape(style.safeCornerRadius))
        .padding(horizontal = 12.dp, vertical = 6.dp).semantics(mergeDescendants = true) { testTag = tag }) {
        Text(label, color = style.secondaryTextColor, style = style.typography.label ?: MaterialTheme.typography.bodySmall)
        Text(formatClipTime(time), color = style.textColor, style = style.typography.timestamp ?: MaterialTheme.typography.bodySmall)
    }
}
