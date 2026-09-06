package com.oneononearena.videoclip.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import kotlin.math.roundToInt
import kotlin.time.Duration

/** Shared Compose appearance. Does not change source, export, navigation or file ownership.
 * Host apps may choose [Light] or override individual colours with [copy].
 * Geometry is bounded when rendered; handles always retain at least 48dp touch targets.
 */
@Immutable
data class ClipEditorStyle(
    val backgroundColor: Color = Color(0xFF11181C),
    val controlsColor: Color = Color(0xFF1C252B),
    val previewBackgroundColor: Color = Color.Black,
    val textColor: Color = Color(0xFFF1F5F6),
    val secondaryTextColor: Color = Color(0xFFB2C0C8),
    val handleColor: Color = Color(0xFF60E3BE),
    val handleGripColor: Color = Color(0xFF083C2D),
    val selectionColor: Color = Color(0xFF60E3BE),
    val playheadColor: Color = Color.White,
    val outsideSelectionColor: Color = Color(0x99050C11),
    val thumbnailPlaceholderColor: Color = Color(0xFF35434B),
    val primaryButtonColor: Color = Color(0xFF60E3BE),
    val primaryButtonContentColor: Color = Color(0xFF083C2D),
    val secondaryButtonColor: Color = Color(0xFF2B3A43),
    val secondaryButtonContentColor: Color = Color(0xFFF1F5F6),
    val cornerRadius: Dp = 12.dp,
    val thumbnailHeight: Dp = 64.dp,
    val selectionBorderWidth: Dp = 2.dp,
    val typography: ClipEditorTypography = ClipEditorTypography(),
) {
    internal val safeCornerRadius get() = cornerRadius.bounded(0f, 24f, 12f)
    internal val safeThumbnailHeight get() = thumbnailHeight.bounded(48f, 96f, 64f)
    internal val safeSelectionBorderWidth get() = selectionBorderWidth.bounded(1f, 4f, 2f)

    companion object {
        val Light = ClipEditorStyle(
            backgroundColor = Color(0xFFF3F6F8), controlsColor = Color.White,
            textColor = Color(0xFF14232B), secondaryTextColor = Color(0xFF52636D),
            secondaryButtonColor = Color(0xFFE4ECEF), secondaryButtonContentColor = Color(0xFF14232B),
        )
    }
}

/** UI policy only; direct core session exports are not constrained by these options. */
@Immutable
data class ClipEditorOptions(
    val labels: ClipEditorLabels = ClipEditorLabels(),
    val showTimestamps: Boolean = true,
    val showSelectedDuration: Boolean = true,
    val showReset: Boolean = true,
    /** Null keeps the full source. Invalid values (<500ms or infinite) return InvalidRequest. */
    val maxSelectionDuration: Duration? = null,
)

/** Null roles inherit the host's MaterialTheme typography. Shared across Android and iOS. */
@Immutable
data class ClipEditorTypography(
    val title: TextStyle? = null,
    val body: TextStyle? = null,
    val button: TextStyle? = null,
    val label: TextStyle? = null,
    val timestamp: TextStyle? = null,
)

/** Host-localizable UI and accessibility labels. Diagnostic text may come from the engine. */
@Immutable
data class ClipEditorLabels(
    val title: String = "Trim video",
    val back: String = "Back",
    val useClip: String = "Use clip",
    val play: String = "Play",
    val pause: String = "Pause",
    val start: String = "Start",
    val end: String = "End",
    val playhead: String = "Playback position",
    val selectedDuration: String = "Selected clip",
    val reset: String = "Reset",
    val retry: String = "Retry",
    val loadingVideo: String = "Opening video…",
    val loadingFrames: String = "Loading thumbnails…",
    val exporting: String = "Creating clip…",
    val cancelled: String = "Cancelled",
    val previewUnavailable: String = "Preview unavailable",
    val completed: String = "Clip ready",
)

private fun Dp.bounded(min: Float, max: Float, fallback: Float): Dp =
    (value.takeIf { it.isFinite() } ?: fallback).coerceIn(min, max).dp

internal fun overviewSlotCount(widthDp: Float): Int =
    (widthDp / 48f).toInt().coerceIn(6, 10)

internal fun overviewFrameIndices(frameCount: Int, slots: Int): List<Int> {
    if (frameCount <= 0 || slots <= 0) return emptyList()
    if (slots == 1) return listOf(0)
    return List(slots) { (it.toDouble() * (frameCount - 1) / (slots - 1)).roundToInt() }
}

internal fun formatClipTime(value: Duration): String {
    val ms = value.inWholeMilliseconds.coerceAtLeast(0)
    val hours = ms / 3_600_000
    val minutes = ms / 60_000 % 60
    val seconds = ms / 1_000 % 60
    val prefix = if (hours > 0) hours.toString().padStart(2, '0') + ":" else ""
    return prefix + minutes.toString().padStart(2, '0') + ":" +
        seconds.toString().padStart(2, '0') + "." + (ms % 1_000).toString().padStart(3, '0')
}

internal fun formatRulerTime(value: Duration): String = formatClipTime(value).substringBeforeLast('.')
