package com.oneononearena.videoclip

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineExportRequest
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import com.oneononearena.videoclip.internal.engine.EngineFrameEvent
import com.oneononearena.videoclip.internal.engine.EngineFrameRequest
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

public fun createAndroidVideoClipEditor(
    context: Context,
    configuration: VideoClipEditorConfiguration = VideoClipEditorConfiguration(),
): VideoClipEditor = AndroidVideoClipEditor(context.applicationContext, configuration)

internal fun createAndroidVideoClipEditor(
    context: Context,
    configuration: VideoClipEditorConfiguration,
    engine: ClipMediaEngine,
): VideoClipEditor = AndroidVideoClipEditor(context.applicationContext, configuration, engine)

internal object AndroidSourcePolicy {
    fun validate(path: String, temporaryRoot: File): ValidationCode? {
        val source = File(path)
        if (!source.isAbsolute) return ValidationCode.PATH_NOT_ABSOLUTE
        val canonicalRoot = canonicalOrNull(temporaryRoot) ?: return ValidationCode.PATH_NOT_REGULAR_FILE
        val canonicalSource = canonicalOrNull(source) ?: return ValidationCode.PATH_NOT_REGULAR_FILE
        if (canonicalSource == canonicalRoot || canonicalSource.path.startsWith("${canonicalRoot.path}/")) {
            return ValidationCode.SOURCE_INSIDE_TEMP_ROOT
        }
        return if (canonicalSource.isFile && canonicalSource.canRead()) null else ValidationCode.PATH_NOT_REGULAR_FILE
    }

    fun canonicalFile(path: String): File? = canonicalOrNull(File(path))

    private fun canonicalOrNull(file: File): File? = runCatching { file.canonicalFile }.getOrNull()
}

/** Media3 Transformer binds listener and operation state to this Looper. */
internal object AndroidMainLooperDispatcher {
    private val handler = Handler(Looper.getMainLooper())

    suspend fun <T> run(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        handler.post {
            if (continuation.isActive) continuation.resumeWith(runCatching(block))
        }
    }

    fun post(block: () -> Unit) {
        handler.post(block)
    }
}

private class AndroidVideoClipEditor(
    private val context: Context,
    private val configuration: VideoClipEditorConfiguration,
    private val engine: ClipMediaEngine? = null,
) : VideoClipEditor {
    private val temporaryStore = AndroidOwnedTempFileStore(context)

    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        AndroidSourcePolicy.validate(source.value, temporaryStore.root)?.let { return OpenSessionResult.InvalidRequest(it, null) }
        val sourceFile = AndroidSourcePolicy.canonicalFile(source.value)
            ?: return OpenSessionResult.InvalidRequest(ValidationCode.PATH_NOT_REGULAR_FILE, null)
        val sessionEngine = engine ?: Media3ClipMediaEngine(context)
        return when (val probe = sessionEngine.probe(EngineSource(sourceFile.absolutePath)).toSourceProbeResult()) {
            is SourceProbeResult.Unsupported -> OpenSessionResult.Unsupported(probe.code, probe.diagnostic)
            is SourceProbeResult.Failed -> OpenSessionResult.Failed(probe.failure)
            is SourceProbeResult.Success -> {
                val temporarySession = try {
                    temporaryStore.createSession()
                } catch (error: TempStoreCreateException) {
                    return OpenSessionResult.Failed(tempCreateFailure(error.target))
                }
                OpenSessionResult.Open(
                    AndroidClipEditorSession(
                        EngineSource(sourceFile.absolutePath),
                        temporarySession,
                        probe.metadata,
                        configuration,
                        sessionEngine,
                    ),
                )
            }
        }
    }
}

private fun EngineProbeResult.toSourceProbeResult(): SourceProbeResult = when (this) {
    is EngineProbeResult.Success -> AndroidSourceTopologyPolicy.validate(topology)?.let { code ->
        SourceProbeResult.Unsupported(code, null)
    } ?: SourceProbeResult.Success(metadata)
    is EngineProbeResult.Unsupported -> SourceProbeResult.Unsupported(code, diagnostic)
    is EngineProbeResult.Failed -> SourceProbeResult.Failed(failure)
}

private class AndroidClipEditorSession(
    private val engineSource: EngineSource,
    private val temporarySession: AndroidOwnedTempFileStore.AndroidOwnedTempSession,
    override val metadata: VideoMetadata,
    private val configuration: VideoClipEditorConfiguration,
    private val engine: ClipMediaEngine,
) : ClipEditorSession {
    private val exportMutex = Mutex()
    private val frameEmissionGate = FrameEmissionGate()
    private val lifecycle = AndroidSessionLifecycle()

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = callbackFlow {
        val worker = launch {
            if (lifecycle.isClosed() || !frameEmissionGate.register(coroutineContext[Job]!!)) {
                send(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null))
                return@launch
            }
            CommonValidation.frameRequest(request, configuration)?.let {
                send(FrameStripEvent.InvalidRequest(it, null))
                return@launch
            }
            engine.frames(
                engineSource,
                EngineFrameRequest(request.frameCount, configuration.maximumThumbnailDimensionPx),
            ).collect { event ->
                send(event.toFrameStripEvent())
            }
        }
        worker.invokeOnCompletion { close() }
        awaitClose { worker.cancel() }
    }.buffer(0)

    override suspend fun createClip(range: ClipRange): ClipResult {
        if (lifecycle.isClosed()) return ClipResult.InvalidRequest(ValidationCode.SESSION_CLOSED, null)
        CommonValidation.clipRange(range, metadata, configuration)?.let { return ClipResult.InvalidRequest(it, null) }
        if (!exportMutex.tryLock()) return ClipResult.InvalidRequest(ValidationCode.OPERATION_IN_PROGRESS, null)
        try {
            if (lifecycle.isClosed()) return ClipResult.InvalidRequest(ValidationCode.SESSION_CLOSED, null)
            val destination = try {
                temporarySession.createDestination()
            } catch (error: IllegalStateException) {
                return ClipResult.InvalidRequest(ValidationCode.SESSION_CLOSED, null)
            }
            return try {
                when (val result = engine.export(EngineExportRequest(engineSource, range, destination.partial.absolutePath))) {
                    EngineExportResult.Success -> {
                        if (lifecycle.isClosed()) {
                            temporarySession.discard(destination)
                            ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, "Session closed"))
                        } else {
                            val lease = temporarySession.publish(destination)
                            if (lifecycle.isClosed()) {
                                lease.clearTemporaryFile()
                                ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, "Session closed"))
                            } else {
                                ClipResult.Success(lease, range)
                            }
                        }
                    }
                    is EngineExportResult.Unsupported -> {
                        temporarySession.discard(destination)
                        ClipResult.Unsupported(result.code, result.diagnostic)
                    }
                    is EngineExportResult.Failed -> {
                        temporarySession.discard(destination)
                        ClipResult.Failed(result.failure)
                    }
                }
            } catch (rename: TempStoreRenameException) {
                ClipResult.Failed(VideoEditFailure(FailureCode.TEMP_RENAME_FAILED, true, rename.target.name))
            } catch (cancelled: CancellationException) {
                temporarySession.discard(destination)
                ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, cancelled.message))
            } catch (error: Exception) {
                temporarySession.discard(destination)
                ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, true, error.message))
            }
        } finally {
            exportMutex.unlock()
        }
    }

    override suspend fun close() = lifecycle.close {
        runCatching { engine.cancelActiveExport() }
        frameEmissionGate.close()
        exportMutex.lock()
        try {
            temporarySession.close()
        } finally {
            exportMutex.unlock()
        }
    }
}

/**
 * Tracks frame workers that send into their own channel-backed Flow.
 *
 * `close()` cancels and joins workers, except itself when called by a worker. A rendezvous channel
 * keeps event delivery serialized, and joining prevents any later send after close returns.
 */
internal class FrameEmissionGate {
    private val lock = Any()
    private val activeWorkers = mutableSetOf<Job>()
    private var closed = false

    fun register(worker: Job): Boolean {
        val admitted = synchronized(lock) {
            if (closed) false else {
                activeWorkers += worker
                true
            }
        }
        if (admitted) {
            worker.invokeOnCompletion {
                synchronized(lock) { activeWorkers -= worker }
            }
        }
        return admitted
    }

    suspend fun close() {
        val caller = currentCoroutineContext()[Job]
        val workers = synchronized(lock) {
            closed = true
            activeWorkers.toList()
        }
        workers.forEach { it.cancel() }
        workers.filterNot { it === caller }.joinAll()
    }
}

internal class AndroidTemporaryClipLease(
    private val target: File,
    opaqueId: String,
    private val onCleared: suspend () -> TempDeleteResult,
) : TemporaryClipLease {
    override val file = TemporaryVideoFile(target.absolutePath, opaqueId)
    private val clearMutex = Mutex()
    private var cleared = false

    override suspend fun clearTemporaryFile(): TempDeleteResult = clearMutex.withLock {
        if (cleared) TempDeleteResult.AlreadyCleared else onCleared().also {
            if (it is TempDeleteResult.Cleared || it is TempDeleteResult.AlreadyCleared) cleared = true
        }
    }
}

/**
 * Issues and deletes only a verified lease identity. `lstat` never follows the terminal entry;
 * its device/inode pair must still equal the values recorded at publication before `remove` runs.
 */
internal object AndroidLeaseDeletionPolicy {
    fun captureIdentity(
        target: File,
        opaqueId: String,
        libraryRoot: File,
        sessionParent: File,
    ): IssuedLeaseIdentity? {
        if (!matchesLocation(target, libraryRoot.canonicalPathOrNull(), sessionParent.canonicalPathOrNull(), target.name)) {
            return null
        }
        return try {
            val stat = Os.lstat(target.absolutePath)
            if ((stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG) {
                null
            } else {
                IssuedLeaseIdentity(
                    opaqueId = opaqueId,
                    libraryRootCanonicalPath = libraryRoot.canonicalPath,
                    sessionParentCanonicalPath = sessionParent.canonicalPath,
                    finalBasename = target.name,
                    device = stat.st_dev,
                    inode = stat.st_ino,
                    size = stat.st_size,
                )
            }
        } catch (error: ErrnoException) {
            null
        } catch (error: SecurityException) {
            null
        }
    }

    fun clearVerified(target: File, identity: IssuedLeaseIdentity): TempDeleteResult {
        if (!matchesLocation(
                target,
                identity.libraryRootCanonicalPath,
                identity.sessionParentCanonicalPath,
                identity.finalBasename,
            )
        ) {
            return refused(target, "Lease containment mismatch")
        }
        return try {
            val stat = Os.lstat(target.absolutePath)
            when {
                (stat.st_mode and OsConstants.S_IFMT) != OsConstants.S_IFREG ->
                    refused(target, "Refusing non-regular terminal entry")
                stat.st_dev != identity.device || stat.st_ino != identity.inode || stat.st_size != identity.size ->
                    refused(target, "Lease identity changed")
                else -> {
                    Os.remove(target.absolutePath)
                    TempDeleteResult.Cleared
                }
            }
        } catch (error: ErrnoException) {
            refused(target, error.message)
        } catch (error: SecurityException) {
            refused(target, error.message)
        }
    }

    private fun matchesLocation(
        target: File,
        libraryRootCanonicalPath: String?,
        sessionParentCanonicalPath: String?,
        finalBasename: String,
    ): Boolean {
        val parentPath = target.parentFile?.canonicalPathOrNull() ?: return false
        if (target.name != finalBasename || parentPath != sessionParentCanonicalPath) return false
        val sessionParent = File(sessionParentCanonicalPath)
        return sessionParent.parentFile?.canonicalPathOrNull() == libraryRootCanonicalPath
    }

    private fun File.canonicalPathOrNull(): String? = runCatching { canonicalPath }.getOrNull()

    private fun refused(target: File, diagnostic: String?) = TempDeleteResult.Failed(
        VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, diagnostic ?: target.name),
    )
}

private sealed interface SourceProbeResult {
    data class Success(val metadata: VideoMetadata) : SourceProbeResult
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : SourceProbeResult
    data class Failed(val failure: VideoEditFailure) : SourceProbeResult
}

private fun EngineFrameEvent.toFrameStripEvent(): FrameStripEvent = when (this) {
    is EngineFrameEvent.Frame -> FrameStripEvent.Frame(value)
    is EngineFrameEvent.Progress -> FrameStripEvent.Progress(emitted, total)
    EngineFrameEvent.Complete -> FrameStripEvent.Complete
    is EngineFrameEvent.InvalidRequest -> FrameStripEvent.InvalidRequest(code, diagnostic)
    is EngineFrameEvent.Unsupported -> FrameStripEvent.Unsupported(code, diagnostic)
    is EngineFrameEvent.Failed -> FrameStripEvent.Failed(failure)
}

private fun tempCreateFailure(root: File) = VideoEditFailure(FailureCode.TEMP_CREATE_FAILED, true, root.absolutePath)
