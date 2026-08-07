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
import androidx.compose.foundation.layout.widthIn
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
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
    val platformPreviewPortFactory = rememberPlatformPreviewPortFactory()
    val previewPortFactory = LocalPreviewPortFactoryOverride.current ?: platformPreviewPortFactory
    val lifecycle = remember(previewPortFactory) { ClipEditorLifecycleOwner(previewPortFactory) }
    val result by rememberUpdatedState(onResult)
    val cancel by rememberUpdatedState(onCancel)
    SideEffect { lifecycle.updateCallbacks(result, cancel) }
    val state by lifecycle.presenter.state.collectAsState()
    LaunchedEffect(source, editor, lifecycle) {
        lifecycle.requestReplace(source, editor)
    }
    DisposableEffect(lifecycle) {
        onDispose {
            lifecycle.requestClose()
        }
    }

    Column(modifier.padding(16.dp)) {
        when (val current = state) {
            ClipEditorUiState.LoadingMetadata -> Text("Loading metadata")
            ClipEditorUiState.LoadingFrames -> Text("Loading frames")
            is ClipEditorUiState.Ready -> {
                val preview by lifecycle.coordinator.state.collectAsState()
                val activePort by lifecycle.activePort.collectAsState()
                EditorControls(
                    ready = current,
                    presenter = lifecycle.presenter,
                    coordinator = lifecycle.coordinator,
                    preview = preview,
                    activePort = activePort,
                    onBack = lifecycle::requestCancel,
                )
            }
            ClipEditorUiState.Exporting -> Text("Creating clip")
            is ClipEditorUiState.Retry -> {
                Text(current.message)
                Button(
                    onClick = { lifecycle.requestReplace(source, editor) },
                    modifier = Modifier.semantics { testTag = "retry" },
                ) { Text("Retry") }
            }
            is ClipEditorUiState.Terminal -> Text(current.message)
            ClipEditorUiState.Cancelled -> Text("Cancelled")
        }
        if (state !is ClipEditorUiState.Ready && state !is ClipEditorUiState.Terminal && state != ClipEditorUiState.Cancelled) {
            Button(
                onClick = lifecycle::requestCancel,
                enabled = state != ClipEditorUiState.Exporting,
                modifier = Modifier.semantics { testTag = "back" },
            ) { Text("Back") }
        }
    }
}

@Composable
private fun EditorControls(
    ready: ClipEditorUiState.Ready,
    presenter: ClipEditorPresenter,
    coordinator: ClipEditorPreviewCoordinator,
    preview: ClipEditorPreviewState,
    activePort: PreviewPort?,
    onBack: () -> Unit,
) {
    val visualRange = ready.provisionalRange ?: ready.range
    Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black)) {
        activePort?.let { PlatformPreviewSurface(port = it, modifier = Modifier.fillMaxWidth()) }
    }
    Text(visualRange.start.toString(), Modifier.semantics { testTag = "clip-start-time" })
    Text(visualRange.endExclusive.toString(), Modifier.semantics { testTag = "clip-end-time" })
    LaunchedEffect(visualRange) { coordinator.constrainPlayhead(visualRange) }
    ClipRangeSelector(
        frames = ready.frames,
        metadata = ready.metadata,
        range = visualRange,
        playhead = clampPlayhead(preview.playhead, visualRange),
        onRangeGestureStart = { coordinator.pause(); presenter.beginRangeGesture() },
        onRangeChange = { boundary, value ->
            when (boundary) {
                RangeBoundary.Start -> presenter.updateStartFromSelector(value)
                RangeBoundary.End -> presenter.updateEndFromSelector(value)
            }
        },
        onRangeGestureEnd = presenter::commitRangeGesture,
        onRangeGestureCancel = presenter::cancelRangeGesture,
        onSeek = coordinator::seekPaused,
        onPlayheadDragStart = coordinator::pause,
    )
    Row(Modifier.fillMaxWidth()) {
        Button(
            onClick = onBack,
            modifier = Modifier.semantics { testTag = "back" },
        ) { Text("Back") }
        Button(
            onClick = coordinator::togglePlayPause,
            enabled = preview.ready && preview.failure == null,
            modifier = Modifier.semantics { testTag = "play-pause" },
        ) { Text(if (preview.isPlaying) "Pause" else "Play") }
        Button(
            onClick = { coordinator.pause(); presenter.createClip() },
            enabled = preview.failure == null && ready.provisionalRange == null,
            modifier = Modifier.semantics { testTag = "done" },
        ) { Text("Done") }
    }
    if (preview.failure != null) {
        Text(preview.failure)
        Button(coordinator::retry, Modifier.semantics { testTag = "retry-preview" }) { Text("Retry") }
    }
}

internal expect fun decodeJpegForRender(bytes: ByteArray): ImageBitmap?

internal sealed interface ClipEditorUiState {
    data object LoadingMetadata : ClipEditorUiState
    data object LoadingFrames : ClipEditorUiState
    data class Ready(
        val metadata: VideoMetadata,
        val frames: List<ThumbnailFrame>,
        val range: ClipRange,
        val provisionalRange: ClipRange? = null,
    ) : ClipEditorUiState
    data object Exporting : ClipEditorUiState
    data class Retry(val message: String) : ClipEditorUiState
    data class Terminal(val message: String) : ClipEditorUiState
    data object Cancelled : ClipEditorUiState
}

internal class ClipEditorPresenter(
    private val scope: CoroutineScope,
    onResult: (ClipResult) -> Unit = {},
    onCancel: () -> Unit = {},
    private val previewPort: PreviewPort? = null,
    private val onExportTransition: () -> Unit = {},
) {
    private val backingState = MutableStateFlow<ClipEditorUiState>(ClipEditorUiState.LoadingMetadata)
    val state: StateFlow<ClipEditorUiState> = backingState.asStateFlow()
    private var source: VideoSourcePath? = null
    private var editor: VideoClipEditor? = null
    private var session: ClipEditorSession? = null
    private var operation: Job? = null
    private var resultSent = false
    private var cancelSent = false
    private var previewGeneration = PreviewGeneration(0)
    private var previewRevision = PreviewRevision(0)
    private var rangeGestureInProgress = false
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
        cancelSent = false
        previewGeneration = PreviewGeneration(previewGeneration.value + 1)
        previewRevision = PreviewRevision(0)
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
        for (requestedFrameCount in frameCount downTo 1) {
            if (session !== opened || !currentCoroutineContext().isActive) return
            backingState.value = ClipEditorUiState.LoadingFrames
            val frames = mutableListOf<ThumbnailFrame>()
            var framesTerminal = false
            var retryWithFewerFrames = false
            opened.frames(FrameStripRequest(requestedFrameCount)).collect { event ->
                if (framesTerminal || session !== opened || !currentCoroutineContext().isActive) return@collect
                when (event) {
                    is FrameStripEvent.Frame -> frames += event.value
                    is FrameStripEvent.Progress -> Unit
                    FrameStripEvent.Complete -> {
                        framesTerminal = true
                        if (opened.metadata.duration < minimumRange) {
                            finish(ClipResult.InvalidRequest(com.oneononearena.videoclip.ValidationCode.RANGE_BELOW_MINIMUM, "Video shorter than 500ms"))
                        } else {
                            val ready = ClipEditorUiState.Ready(opened.metadata, frames.toList(), ClipRange(Duration.ZERO, opened.metadata.duration))
                            backingState.value = ready
                            previewPort?.dispatch(PreviewCommand.Bind(previewBinding(ready)))
                        }
                    }
                    is FrameStripEvent.Failed -> {
                        framesTerminal = true
                        finishFailure(event.error)
                    }
                    is FrameStripEvent.InvalidRequest -> {
                        framesTerminal = true
                        if (event.code == com.oneononearena.videoclip.ValidationCode.INVALID_FRAME_REQUEST && requestedFrameCount > 1) {
                            retryWithFewerFrames = true
                        } else {
                            finish(ClipResult.InvalidRequest(event.code, event.diagnostic))
                        }
                    }
                    is FrameStripEvent.Unsupported -> {
                        framesTerminal = true
                        finish(ClipResult.Unsupported(event.code, event.diagnostic))
                    }
                }
            }
            if (!retryWithFewerFrames) return
        }
    }

    fun updateStart(value: Duration) = updateRange { range, duration -> range.copy(start = value.coerceIn(Duration.ZERO, range.endExclusive - minimumRange)) }
    fun updateEnd(value: Duration) = updateRange { range, duration -> range.copy(endExclusive = value.coerceIn(range.start + minimumRange, duration)) }
    fun beginRangeGesture() {
        if (rangeGestureInProgress) return
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        rangeGestureInProgress = true
        backingState.value = ready.copy(provisionalRange = ready.range)
        previewPort?.dispatch(PreviewCommand.SetPlayWhenReady(previewGeneration, previewRevision, false))
    }
    fun updateStartFromSelector(value: Duration) = updateRange { range, duration ->
        range.copy(start = clampRangeBoundary(value, range.endExclusive, duration, RangeBoundary.Start))
    }
    fun updateEndFromSelector(value: Duration) = updateRange { range, duration ->
        range.copy(endExclusive = clampRangeBoundary(value, range.start, duration, RangeBoundary.End))
    }
    fun pausePreview() {
        if (backingState.value !is ClipEditorUiState.Ready) return
        previewPort?.dispatch(PreviewCommand.SetPlayWhenReady(previewGeneration, previewRevision, false))
    }
    fun seekFromSelector(value: Duration) {
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        previewPort?.dispatch(PreviewCommand.Seek(previewGeneration, previewRevision, clampPlayhead(value, ready.range)))
    }
    fun commitRangeGesture() {
        if (!rangeGestureInProgress) return
        rangeGestureInProgress = false
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        val committedRange = ready.provisionalRange ?: return
        previewRevision = PreviewRevision(previewRevision.value + 1)
        val committed = ready.copy(range = committedRange, provisionalRange = null)
        backingState.value = committed
        previewPort?.dispatch(PreviewCommand.ReplaceRange(previewBinding(committed)))
    }
    fun cancelRangeGesture() {
        if (!rangeGestureInProgress) return
        rangeGestureInProgress = false
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        backingState.value = ready.copy(provisionalRange = null)
    }
    private fun updateRange(transform: (ClipRange, Duration) -> ClipRange) {
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        if (rangeGestureInProgress) {
            val provisional = ready.provisionalRange ?: ready.range
            backingState.value = ready.copy(provisionalRange = transform(provisional, ready.metadata.duration))
        } else {
            backingState.value = ready.copy(range = transform(ready.range, ready.metadata.duration))
        }
    }
    private fun previewBinding(ready: ClipEditorUiState.Ready): PreviewBinding = PreviewBinding(
        generation = previewGeneration,
        revision = previewRevision,
        source = requireNotNull(source),
        metadata = ready.metadata,
        range = ready.range,
        sourcePosition = ready.range.start,
        playWhenReady = false,
    )

    fun createClip() {
        if (rangeGestureInProgress) return
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        val opened = session ?: return
        onExportTransition()
        backingState.value = ClipEditorUiState.Exporting
        operation = scope.launch {
            when (val result = opened.createClip(ready.range)) {
                is ClipResult.Failed -> finishFailure(result.failure)
                else -> finish(result)
            }
        }
    }

    fun retry() {
        if (backingState.value == ClipEditorUiState.Exporting) return
        source?.let { s -> editor?.let { e -> start(s, e) } }
    }
    fun cancel() {
        if (backingState.value == ClipEditorUiState.Exporting) return
        if (cancelSent) return
        cancelSent = true
        scope.launch { close() }
        backingState.value = ClipEditorUiState.Cancelled
        onCancel()
    }
    fun cancelAfterClose() {
        if (cancelSent) return
        cancelSent = true
        backingState.value = ClipEditorUiState.Cancelled
        onCancel()
    }
    suspend fun close() {
        val runningOperation = operation
        operation = null
        if (runningOperation != currentCoroutineContext()[Job]) {
            runningOperation?.cancelAndJoin()
        }
        sessionMutex.withLock {
            session?.close()
            session = null
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
