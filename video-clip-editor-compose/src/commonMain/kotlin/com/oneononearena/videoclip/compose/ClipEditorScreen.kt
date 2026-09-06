package com.oneononearena.videoclip.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
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
    ClipEditorScreenImpl(source, editor, onResult, onCancel, modifier, {})
}

@Composable
fun ClipEditorScreen(
    source: VideoSourcePath,
    editor: VideoClipEditor,
    onResult: (ClipResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onTerminalLifecycleComplete: () -> Unit,
) {
    ClipEditorScreenImpl(
        source,
        editor,
        onResult,
        onCancel,
        modifier,
        onTerminalLifecycleComplete,
    )
}

/** Customizable editor. Existing overloads retain their original source and binary signatures.
 * Changing [style] or [options] updates presentation without reopening the source session.
 */
@Composable
fun ClipEditorScreen(
    source: VideoSourcePath,
    editor: VideoClipEditor,
    onResult: (ClipResult) -> Unit,
    onCancel: () -> Unit,
    style: ClipEditorStyle,
    options: ClipEditorOptions = ClipEditorOptions(),
    modifier: Modifier = Modifier,
    onTerminalLifecycleComplete: () -> Unit = {},
) {
    ClipEditorScreenImpl(source, editor, onResult, onCancel, modifier, onTerminalLifecycleComplete, style, options)
}

@Composable
fun ClipEditorScreen(
    source: VideoSourcePath,
    editor: VideoClipEditor,
    onResult: (ClipResult) -> Unit,
    onCancel: () -> Unit,
    progressContent: @Composable (ClipEditorProgress) -> Unit,
    style: ClipEditorStyle = ClipEditorStyle(),
    options: ClipEditorOptions = ClipEditorOptions(),
    modifier: Modifier = Modifier,
    onTerminalLifecycleComplete: () -> Unit = {},
) {
    ClipEditorScreenImpl(source, editor, onResult, onCancel, modifier, onTerminalLifecycleComplete, style, options, progressContent)
}

@Composable
private fun ClipEditorScreenImpl(
    source: VideoSourcePath,
    editor: VideoClipEditor,
    onResult: (ClipResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
    onTerminalLifecycleComplete: () -> Unit,
    style: ClipEditorStyle = ClipEditorStyle(),
    options: ClipEditorOptions = ClipEditorOptions(),
    progressContent: (@Composable (ClipEditorProgress) -> Unit)? = null,
) {
    val platformPreviewPortFactory = rememberPlatformPreviewPortFactory()
    val previewPortFactory = LocalPreviewPortFactoryOverride.current ?: platformPreviewPortFactory
    val lifecycle = remember(previewPortFactory) { ClipEditorLifecycleOwner(previewPortFactory) }
    val result by rememberUpdatedState(onResult)
    val cancel by rememberUpdatedState(onCancel)
    val terminalCompletion by rememberUpdatedState(onTerminalLifecycleComplete)
    SideEffect { lifecycle.updateCallbacks(result, cancel, terminalCompletion) }
    SideEffect { lifecycle.presenter.setMaximumSelectionDuration(options.maxSelectionDuration) }
    val state by lifecycle.presenter.state.collectAsState()
    val fraction by lifecycle.presenter.thumbnailProgress.collectAsState()
    LaunchedEffect(source, editor, lifecycle) {
        lifecycle.requestReplace(source, editor)
    }
    DisposableEffect(lifecycle) {
        onDispose {
            lifecycle.requestClose()
        }
    }

    val labels = options.labels
    val showProgress: @Composable (ClipEditorProgressStage, Float?, String) -> Unit = { stage, amount, message ->
        val progress = ClipEditorProgress(stage, amount, message)
        if (progressContent != null) progressContent(progress) else ClipEditorProgressView(progress, style)
    }
    CompositionLocalProvider(LocalContentColor provides style.textColor) {
    Column(modifier.fillMaxSize().background(style.backgroundColor)) {
        when (val current = state) {
            ClipEditorUiState.LoadingMetadata -> showProgress(ClipEditorProgressStage.OpeningVideo, null, labels.loadingVideo)
            ClipEditorUiState.LoadingFrames -> showProgress(ClipEditorProgressStage.LoadingThumbnails, fraction, labels.loadingFrames)
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
                    style = style,
                    options = options,
                )
            }
            ClipEditorUiState.Exporting -> showProgress(ClipEditorProgressStage.ExportingClip, null, labels.exporting)
            is ClipEditorUiState.Retry -> {
                Text(current.message, style = style.typography.body ?: MaterialTheme.typography.bodyMedium)
                EditorButton(
                    style = style,
                    onClick = { lifecycle.requestReplace(source, editor) },
                    modifier = Modifier.semantics { testTag = "retry" },
                    label = labels.retry,
                )
            }
            is ClipEditorUiState.Terminal -> Text(if (current.success) labels.completed else current.message,
                style = style.typography.body ?: MaterialTheme.typography.bodyMedium)
            ClipEditorUiState.Cancelled -> Text(labels.cancelled, style = style.typography.body ?: MaterialTheme.typography.bodyMedium)
        }
        if (state !is ClipEditorUiState.Ready && state !is ClipEditorUiState.Terminal && state != ClipEditorUiState.Cancelled) {
            EditorButton(
                style = style,
                onClick = lifecycle::requestCancel,
                enabled = state != ClipEditorUiState.Exporting,
                modifier = Modifier.semantics { testTag = "back" },
                label = labels.back,
            )
        }
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
    style: ClipEditorStyle,
    options: ClipEditorOptions,
) {
    val visualRange = ready.provisionalRange ?: ready.range
    LaunchedEffect(visualRange) { coordinator.constrainPlayhead(visualRange) }
    ClipEditorContent(
        ready, preview, style, options,
        onBack = onBack,
        onDone = { coordinator.pause(); presenter.createClip() },
        onTogglePlayback = coordinator::togglePlayPause,
        onReset = {
            coordinator.pause()
            presenter.resetRange()
        },
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
        onRetry = coordinator::retry,
        previewContent = { activePort?.let { PlatformPreviewSurface(it, Modifier.fillMaxSize()) } },
    )
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
    data class Terminal(val message: String, val success: Boolean = false) : ClipEditorUiState
    data object Cancelled : ClipEditorUiState
}

internal class ClipEditorPresenter(
    private val scope: CoroutineScope,
    onResult: (ClipResult) -> Unit = {},
    onCancel: () -> Unit = {},
    private val previewPort: PreviewPort? = null,
    private val onExportTransition: () -> Unit = {},
    private val onUnexpectedOperationFailure: (Throwable) -> Unit = {},
) {
    private val backingState = MutableStateFlow<ClipEditorUiState>(ClipEditorUiState.LoadingMetadata)
    val state: StateFlow<ClipEditorUiState> = backingState.asStateFlow()
    private val backingThumbnailProgress = MutableStateFlow<Float?>(null)
    val thumbnailProgress: StateFlow<Float?> = backingThumbnailProgress.asStateFlow()
    private var maximumSelectionDuration: Duration? = null
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

    fun setMaximumSelectionDuration(value: Duration?) {
        if (maximumSelectionDuration == value || backingState.value == ClipEditorUiState.Exporting) return
        maximumSelectionDuration = value
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        if (!validSelectionLimit()) {
            invalidSelectionLimit()
            return
        }
        val end = minOf(ready.range.endExclusive, ready.range.start + (value ?: ready.metadata.duration))
        cancelRangeGesture()
        beginRangeGesture()
        backingState.value = ready.copy(provisionalRange = ready.range.copy(endExclusive = end))
        commitRangeGesture()
    }

    private fun validSelectionLimit() = maximumSelectionDuration?.let { it.isFinite() && it >= minimumRange } ?: true
    private fun invalidSelectionLimit() = finish(ClipResult.InvalidRequest(
        com.oneononearena.videoclip.ValidationCode.RANGE_BELOW_MINIMUM,
        "Maximum selection duration must be finite and at least 500ms",
    ))

    fun resetRange() {
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        cancelRangeGesture()
        beginRangeGesture()
        backingState.value = ready.copy(provisionalRange = ClipRange(Duration.ZERO,
            minOf(ready.metadata.duration, maximumSelectionDuration ?: ready.metadata.duration)))
        commitRangeGesture()
    }

    fun start(source: VideoSourcePath, editor: VideoClipEditor) {
        operation?.cancel(ExpectedPresenterOperationCancellation())
        this.source = source
        this.editor = editor
        resultSent = false
        cancelSent = false
        rangeGestureInProgress = false
        backingThumbnailProgress.value = null
        if (!validSelectionLimit()) {
            invalidSelectionLimit()
            return
        }
        previewGeneration = PreviewGeneration(previewGeneration.value + 1)
        previewRevision = PreviewRevision(0)
        backingState.value = ClipEditorUiState.LoadingMetadata
        operation = launchTrackedOperation {
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
            backingThumbnailProgress.value = null
            val frames = mutableListOf<ThumbnailFrame>()
            var framesTerminal = false
            var retryWithFewerFrames = false
            opened.frames(FrameStripRequest(requestedFrameCount)).collect { event ->
                if (framesTerminal || session !== opened || !currentCoroutineContext().isActive) return@collect
                when (event) {
                    is FrameStripEvent.Frame -> frames += event.value
                    is FrameStripEvent.Progress -> backingThumbnailProgress.value =
                        if (event.total > 0 && event.emitted >= 0) (event.emitted.toFloat() / event.total).coerceIn(0f, 1f) else null
                    FrameStripEvent.Complete -> {
                        framesTerminal = true
                        if (!validSelectionLimit()) {
                            invalidSelectionLimit()
                        } else if (opened.metadata.duration < minimumRange) {
                            finish(ClipResult.InvalidRequest(com.oneononearena.videoclip.ValidationCode.RANGE_BELOW_MINIMUM, "Video shorter than 500ms"))
                        } else {
                            val ready = ClipEditorUiState.Ready(opened.metadata, frames.toList(), ClipRange(Duration.ZERO,
                                minOf(opened.metadata.duration, maximumSelectionDuration ?: opened.metadata.duration)))
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

    fun updateStart(value: Duration) = updateStartFromSelector(value)
    fun updateEnd(value: Duration) = updateEndFromSelector(value)
    fun beginRangeGesture() {
        if (rangeGestureInProgress) return
        val ready = backingState.value as? ClipEditorUiState.Ready ?: return
        rangeGestureInProgress = true
        backingState.value = ready.copy(provisionalRange = ready.range)
        previewPort?.dispatch(PreviewCommand.SetPlayWhenReady(previewGeneration, previewRevision, false))
    }
    fun updateStartFromSelector(value: Duration) = updateRange { range, duration ->
        val start = clampRangeBoundary(value, range.endExclusive, duration, RangeBoundary.Start)
        range.copy(start = start, endExclusive = minOf(range.endExclusive, start + (maximumSelectionDuration ?: duration)))
    }
    fun updateEndFromSelector(value: Duration) = updateRange { range, duration ->
        val end = clampRangeBoundary(value, range.start, duration, RangeBoundary.End)
        range.copy(start = maxOf(range.start, end - (maximumSelectionDuration ?: duration)), endExclusive = end)
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
        operation = launchTrackedOperation {
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
            runningOperation?.cancel(ExpectedPresenterOperationCancellation())
            runningOperation?.join()
        }
        sessionMutex.withLock {
            val opened = session
            session = null
            opened?.close()
        }
    }
    private fun launchTrackedOperation(block: suspend CoroutineScope.() -> Unit): Job {
        val launched = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cause: Throwable) {
                if (cause is ExpectedPresenterOperationCancellation) throw cause
                onUnexpectedOperationFailure(cause)
            }
        }
        launched.invokeOnCompletion { cause ->
            if (cause != null && cause !is ExpectedPresenterOperationCancellation) {
                onUnexpectedOperationFailure(cause)
            }
        }
        launched.start()
        return launched
    }
    private fun finishFailure(failure: VideoEditFailure) {
        if (failure.retryable) backingState.value = ClipEditorUiState.Retry(failure.diagnostic ?: failure.code.name)
        else finish(ClipResult.Failed(failure))
    }
    private fun finish(result: ClipResult) {
        if (resultSent) return
        resultSent = true
        val message = when (result) {
            is ClipResult.Success -> ""
            is ClipResult.Unsupported -> result.diagnostic ?: result.code.name
            is ClipResult.InvalidRequest -> result.diagnostic ?: result.code.name
            is ClipResult.Failed -> result.failure.diagnostic ?: result.failure.code.name
        }
        backingState.value = ClipEditorUiState.Terminal(message, result is ClipResult.Success)
        onResult(result)
    }
}

private class ExpectedPresenterOperationCancellation :
    CancellationException("Clip editor presenter operation cancelled by lifecycle owner")
