package com.oneononearena.videoclip.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.ThumbnailFrame
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoEditFailure
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val minimumRange = 500.milliseconds
private const val frameCount = 24

@Composable
fun ClipEditorScreen(
    source: VideoSourcePath,
    editor: VideoClipEditor,
    onResult: (ClipResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val cancel by rememberUpdatedState(onCancel)
    val presenter = remember { ClipEditorPresenter(scope) }
    SideEffect { presenter.updateCallbacks(result, cancel) }
    val state by presenter.state.collectAsState()
    LaunchedEffect(source, editor, presenter) { presenter.start(source, editor) }
    DisposableEffect(presenter) { onDispose { presenter.close() } }

    Column(modifier.padding(16.dp)) {
        when (val current = state) {
            ClipEditorUiState.LoadingMetadata -> Text("Loading metadata")
            ClipEditorUiState.LoadingFrames -> Text("Loading frames")
            is ClipEditorUiState.Ready -> EditorControls(current, presenter)
            ClipEditorUiState.Exporting -> Text("Creating clip")
            is ClipEditorUiState.Retry -> {
                Text(current.message)
                Button(presenter::retry, Modifier.semantics { testTag = "retry" }) { Text("Retry") }
            }
            is ClipEditorUiState.Terminal -> Text(current.message)
            ClipEditorUiState.Cancelled -> Text("Cancelled")
        }
        if (state !is ClipEditorUiState.Terminal && state != ClipEditorUiState.Cancelled) {
            Button(presenter::cancel, Modifier.semantics { testTag = "cancel" }) { Text("Cancel") }
        }
    }
}

@Composable
private fun EditorControls(ready: ClipEditorUiState.Ready, presenter: ClipEditorPresenter) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val durationMs = ready.metadata.duration.inWholeMilliseconds.coerceAtLeast(1)
    Box(Modifier.fillMaxWidth().height(48.dp).background(Color.DarkGray).onSizeChanged { size = it }) {
        DragHandle("clip-start-handle", toPosition(ready.range.start, durationMs, size.width), size) { x ->
            presenter.updateStart(toDuration(x, size.width, durationMs))
        }
        DragHandle("clip-end-handle", toPosition(ready.range.endExclusive, durationMs, size.width), size) { x ->
            presenter.updateEnd(toDuration(x, size.width, durationMs))
        }
    }
    Row {
        ready.frames.forEach { frame ->
            decodeJpegForRender(frame.copyEncodedJpeg())?.let { bitmap ->
                Image(bitmap, null, Modifier.size(48.dp, 32.dp))
            }
        }
    }
    Button(presenter::createClip, Modifier.semantics { testTag = "create-clip" }) { Text("Create clip") }
}

@Composable
private fun DragHandle(label: String, initialPosition: Float, size: IntSize, onPosition: (Float) -> Unit) {
    val density = LocalDensity.current
    val hitTargetWidthPx = with(density) { 48.dp.toPx() }
    var position by remember(label, initialPosition) { mutableFloatStateOf(initialPosition) }
    Box(
        Modifier
            .offset { IntOffset((position - hitTargetWidthPx / 2f).roundToInt(), 0) }
            .size(48.dp)
            .semantics { testTag = label }
            .pointerInput(label, size) {
                detectDragGestures { _, drag ->
                    position = (position + drag.x).coerceIn(0f, size.width.toFloat())
                    onPosition(position)
                }
            },
    ) {
        Box(Modifier.align(Alignment.Center).width(12.dp).height(32.dp).background(Color.White))
    }
}

internal expect fun decodeJpegForRender(bytes: ByteArray): ImageBitmap?

internal fun toDuration(position: Float, trackWidthPx: Int, durationMs: Long): Duration {
    val width = trackWidthPx.toFloat()
    if (width <= 0f) return Duration.ZERO
    return ((position.coerceIn(0f, width) / width) * durationMs).roundToLong().milliseconds
}

internal fun toPosition(duration: Duration, durationMs: Long, trackWidthPx: Int): Float {
    val width = trackWidthPx.coerceAtLeast(0).toFloat()
    if (durationMs <= 0L) return 0f
    return (duration.inWholeMilliseconds.toFloat() / durationMs * width).coerceIn(0f, width)
}

internal sealed interface ClipEditorUiState {
    data object LoadingMetadata : ClipEditorUiState
    data object LoadingFrames : ClipEditorUiState
    data class Ready(val metadata: VideoMetadata, val frames: List<ThumbnailFrame>, val range: ClipRange) : ClipEditorUiState
    data object Exporting : ClipEditorUiState
    data class Retry(val message: String) : ClipEditorUiState
    data class Terminal(val message: String) : ClipEditorUiState
    data object Cancelled : ClipEditorUiState
}

internal class ClipEditorPresenter(
    private val scope: CoroutineScope,
    onResult: (ClipResult) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    private val backingState = MutableStateFlow<ClipEditorUiState>(ClipEditorUiState.LoadingMetadata)
    val state: StateFlow<ClipEditorUiState> = backingState.asStateFlow()
    private var source: VideoSourcePath? = null
    private var editor: VideoClipEditor? = null
    private var session: ClipEditorSession? = null
    private var operation: Job? = null
    private var resultSent = false
    private var cancelSent = false
    private var onResult = onResult
    private var onCancel = onCancel
    private val sessionMutex = Mutex()

    fun updateCallbacks(onResult: (ClipResult) -> Unit, onCancel: () -> Unit) {
        this.onResult = onResult
        this.onCancel = onCancel
    }

    fun start(source: VideoSourcePath, editor: VideoClipEditor) {
        operation?.cancel()
        this.source = source
        this.editor = editor
        resultSent = false
        backingState.value = ClipEditorUiState.LoadingMetadata
        operation = scope.launch {
            val openedSession = sessionMutex.withLock {
                session?.close()
                session = null
                when (val opened = editor.openSession(source)) {
                    is OpenSessionResult.Open -> {
                        if (!currentCoroutineContext().isActive) {
                            opened.session.close()
                            null
                        } else {
                            session = opened.session
                            opened.session
                        }
                    }
                    is OpenSessionResult.Failed -> {
                        finishFailure(opened.failure)
                        null
                    }
                    is OpenSessionResult.Unsupported -> {
                        finish(ClipResult.Unsupported(opened.code, opened.diagnostic))
                        null
                    }
                    is OpenSessionResult.InvalidRequest -> {
                        finish(ClipResult.InvalidRequest(opened.code, opened.diagnostic))
                        null
                    }
                }
            }
            if (openedSession != null) collectFrames(openedSession)
        }
    }

    private suspend fun collectFrames(opened: ClipEditorSession) {
        if (session !== opened || !currentCoroutineContext().isActive) return
        backingState.value = ClipEditorUiState.LoadingFrames
        val frames = mutableListOf<ThumbnailFrame>()
        var framesTerminal = false
        opened.frames(FrameStripRequest(frameCount)).collect { event ->
            if (framesTerminal || session !== opened || !currentCoroutineContext().isActive) return@collect
            when (event) {
                is FrameStripEvent.Frame -> frames += event.value
                is FrameStripEvent.Progress -> Unit
                FrameStripEvent.Complete -> {
                    framesTerminal = true
                    if (opened.metadata.duration < minimumRange) {
                        finish(ClipResult.InvalidRequest(com.oneononearena.videoclip.ValidationCode.RANGE_BELOW_MINIMUM, "Video shorter than 500ms"))
                    } else {
                        backingState.value = ClipEditorUiState.Ready(opened.metadata, frames.toList(), ClipRange(Duration.ZERO, opened.metadata.duration))
                    }
                }
                is FrameStripEvent.Failed -> {
                    framesTerminal = true
                    finishFailure(event.error)
                }
                is FrameStripEvent.InvalidRequest -> {
                    framesTerminal = true
                    finish(ClipResult.InvalidRequest(event.code, event.diagnostic))
                }
                is FrameStripEvent.Unsupported -> {
                    framesTerminal = true
                    finish(ClipResult.Unsupported(event.code, event.diagnostic))
                }
            }
        }
    }

    fun updateStart(value: Duration) = updateRange { range, duration -> range.copy(start = value.coerceIn(Duration.ZERO, range.endExclusive - minimumRange)) }
    fun updateEnd(value: Duration) = updateRange { range, duration -> range.copy(endExclusive = value.coerceIn(range.start + minimumRange, duration)) }
    private fun updateRange(transform: (ClipRange, Duration) -> ClipRange) {
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        backingState.value = ready.copy(range = transform(ready.range, ready.metadata.duration))
    }

    fun createClip() {
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        val opened = session ?: return
        backingState.value = ClipEditorUiState.Exporting
        operation = scope.launch {
            when (val result = opened.createClip(ready.range)) {
                is ClipResult.Failed -> finishFailure(result.failure)
                else -> finish(result)
            }
        }
    }

    fun retry() { source?.let { s -> editor?.let { e -> start(s, e) } } }
    fun cancel() {
        if (cancelSent) return
        cancelSent = true
        close()
        backingState.value = ClipEditorUiState.Cancelled
        onCancel()
    }
    fun close() {
        operation?.cancel()
        operation = null
        scope.launch {
            sessionMutex.withLock {
                session?.close()
                session = null
            }
        }
    }
    private fun finishFailure(failure: VideoEditFailure) {
        if (failure.retryable) backingState.value = ClipEditorUiState.Retry(failure.diagnostic ?: failure.code.name)
        else finish(ClipResult.Failed(failure))
    }
    private fun finish(result: ClipResult) {
        if (resultSent) return
        resultSent = true
        backingState.value = ClipEditorUiState.Terminal(result.toString())
        onResult(result)
    }
}
