package com.oneononearena.videoclip.compose

import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.TempDeleteResult
import com.oneononearena.videoclip.TemporaryClipLease
import com.oneononearena.videoclip.TemporaryVideoFile
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

internal enum class RecordedEditorEventKind {
    OpenEntry,
    OpenResult,
    Metadata,
    FramesRequest,
    Frame,
    FrameProgress,
    FramesComplete,
    FrameTerminal,
    PreviewCreate,
    PreviewCommand,
    PreviewEvent,
    CreateClipEntry,
    CreateClipResult,
    SessionCloseEntry,
    SessionCloseComplete,
    PreviewDisposeEntry,
    PreviewDisposeComplete,
    LeaseClear,
}

internal data class RecordedEditorEvent(
    val sequence: Long,
    val kind: RecordedEditorEventKind,
    val source: VideoSourcePath? = null,
    val metadata: VideoMetadata? = null,
    val frameRequest: FrameStripRequest? = null,
    val frameEvent: FrameStripEvent? = null,
    val previewCommand: PreviewCommand? = null,
    val previewEvent: PreviewEvent? = null,
    val range: ClipRange? = null,
    val clipResult: ClipResult? = null,
    val clearResult: TempDeleteResult? = null,
    val identity: Any? = null,
)

internal class RecordedEventLedger {
    private val recordedEvents = mutableListOf<RecordedEditorEvent>()

    fun record(
        kind: RecordedEditorEventKind,
        source: VideoSourcePath? = null,
        metadata: VideoMetadata? = null,
        frameRequest: FrameStripRequest? = null,
        frameEvent: FrameStripEvent? = null,
        previewCommand: PreviewCommand? = null,
        previewEvent: PreviewEvent? = null,
        range: ClipRange? = null,
        clipResult: ClipResult? = null,
        clearResult: TempDeleteResult? = null,
        identity: Any? = null,
    ): RecordedEditorEvent = synchronized(recordedEvents) {
        RecordedEditorEvent(
            sequence = recordedEvents.size.toLong() + 1L,
            kind = kind,
            source = source,
            metadata = metadata,
            frameRequest = frameRequest,
            frameEvent = frameEvent,
            previewCommand = previewCommand,
            previewEvent = previewEvent,
            range = range,
            clipResult = clipResult,
            clearResult = clearResult,
            identity = identity,
        ).also(recordedEvents::add)
    }

    fun snapshot(): List<RecordedEditorEvent> = synchronized(recordedEvents) {
        recordedEvents.toList()
    }

    fun events(kind: RecordedEditorEventKind): List<RecordedEditorEvent> =
        snapshot().filter { it.kind == kind }

    fun count(kind: RecordedEditorEventKind): Int = events(kind).size
}

/** Test-only recorder. Every operation delegates to the exact production editor/session. */
internal class RecordingVideoClipEditor(
    private val delegate: VideoClipEditor,
    private val ledger: RecordedEventLedger,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        ledger.record(RecordedEditorEventKind.OpenEntry, source = source, identity = delegate)
        return when (val result = delegate.openSession(source)) {
            is OpenSessionResult.Open -> {
                val recordingSession = RecordingClipEditorSession(result.session, ledger)
                ledger.record(
                    RecordedEditorEventKind.OpenResult,
                    source = source,
                    identity = result.session,
                )
                OpenSessionResult.Open(recordingSession)
            }
            else -> {
                ledger.record(RecordedEditorEventKind.OpenResult, source = source, identity = result)
                result
            }
        }
    }
}

internal class RecordingClipEditorSession(
    private val delegate: ClipEditorSession,
    private val ledger: RecordedEventLedger,
) : ClipEditorSession {
    override val metadata: VideoMetadata
        get() = delegate.metadata.also { value ->
            ledger.record(
                RecordedEditorEventKind.Metadata,
                metadata = value,
                identity = delegate,
            )
        }

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> {
        ledger.record(
            RecordedEditorEventKind.FramesRequest,
            frameRequest = request,
            identity = delegate,
        )
        return delegate.frames(request).onEach { event ->
            ledger.record(
                kind = when (event) {
                    is FrameStripEvent.Frame -> RecordedEditorEventKind.Frame
                    is FrameStripEvent.Progress -> RecordedEditorEventKind.FrameProgress
                    FrameStripEvent.Complete -> RecordedEditorEventKind.FramesComplete
                    else -> RecordedEditorEventKind.FrameTerminal
                },
                frameEvent = event,
                identity = delegate,
            )
        }
    }

    override suspend fun createClip(range: ClipRange): ClipResult {
        ledger.record(
            RecordedEditorEventKind.CreateClipEntry,
            range = range,
            identity = delegate,
        )
        val productionResult = delegate.createClip(range)
        val observedResult = when (productionResult) {
            is ClipResult.Success -> productionResult.copy(
                output = RecordingTemporaryClipLease(productionResult.output, ledger),
            )
            else -> productionResult
        }
        ledger.record(
            RecordedEditorEventKind.CreateClipResult,
            range = range,
            clipResult = observedResult,
            identity = productionResult,
        )
        return observedResult
    }

    override suspend fun close() {
        ledger.record(RecordedEditorEventKind.SessionCloseEntry, identity = delegate)
        delegate.close()
        ledger.record(RecordedEditorEventKind.SessionCloseComplete, identity = delegate)
    }
}

internal class RecordingTemporaryClipLease(
    internal val delegate: TemporaryClipLease,
    private val ledger: RecordedEventLedger,
) : TemporaryClipLease {
    override val file: TemporaryVideoFile
        get() = delegate.file

    override suspend fun clearTemporaryFile(): TempDeleteResult =
        delegate.clearTemporaryFile().also { result ->
            ledger.record(
                RecordedEditorEventKind.LeaseClear,
                clearResult = result,
                identity = delegate,
            )
        }
}

internal class RecordingPreviewPortFactory(
    private val delegate: PreviewPortFactory,
    private val ledger: RecordedEventLedger,
) : PreviewPortFactory {
    internal var latestWrapper: RecordingPreviewPort? = null
        private set
    internal var latestActual: AndroidMedia3PreviewPort? = null
        private set
    internal var createCount: Int = 0
        private set
    internal var disposeCount: Int = 0
        private set

    override fun create(): PreviewPort {
        check(latestWrapper == null) { "Only one recording wrapper may be active" }
        val actual = delegate.create()
        check(actual is AndroidMedia3PreviewPort) { "Expected real Android preview port" }
        val wrapper = RecordingPreviewPort(actual, ledger)
        latestActual = actual
        latestWrapper = wrapper
        createCount++
        ledger.record(RecordedEditorEventKind.PreviewCreate, identity = actual)
        return wrapper
    }

    override fun dispose(port: PreviewPort) {
        val wrapper = latestWrapper
        val actual = latestActual
        check(port === wrapper) { "Dispose requires the exact known wrapper" }
        check(wrapper.surfacePort === actual) { "Wrapper must expose the exact actual" }
        check(disposeCount == 0) { "Preview actual already disposed" }
        ledger.record(RecordedEditorEventKind.PreviewDisposeEntry, identity = actual)
        delegate.dispose(checkNotNull(actual))
        disposeCount++
        ledger.record(RecordedEditorEventKind.PreviewDisposeComplete, identity = actual)
    }
}

internal class RecordingPreviewPort(
    private val delegate: PreviewPort,
    private val ledger: RecordedEventLedger,
) : PreviewPort, PreviewPortSurfaceDelegate {
    override val surfacePort: PreviewPort = delegate
    override val events: Flow<PreviewEvent> = delegate.events.onEach { event ->
        ledger.record(
            RecordedEditorEventKind.PreviewEvent,
            previewEvent = event,
            identity = delegate,
        )
    }

    override fun dispatch(command: PreviewCommand) {
        ledger.record(
            RecordedEditorEventKind.PreviewCommand,
            previewCommand = command,
            identity = delegate,
        )
        delegate.dispatch(command)
    }
}
