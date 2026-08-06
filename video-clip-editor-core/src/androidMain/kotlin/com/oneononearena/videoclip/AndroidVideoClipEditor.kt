package com.oneononearena.videoclip

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

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
    private val temporaryRoot = File(context.cacheDir, "video-clip-editor")

    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        AndroidSourcePolicy.validate(source.value, temporaryRoot)?.let { return OpenSessionResult.InvalidRequest(it, null) }
        val sourceFile = AndroidSourcePolicy.canonicalFile(source.value)
            ?: return OpenSessionResult.InvalidRequest(ValidationCode.PATH_NOT_REGULAR_FILE, null)
        return when (val probe = engine?.probe(EngineSource(sourceFile.absolutePath))?.toSourceProbeResult() ?: probeSource(sourceFile)) {
            is SourceProbeResult.Unsupported -> OpenSessionResult.Unsupported(probe.code, probe.diagnostic)
            is SourceProbeResult.Failed -> OpenSessionResult.Failed(probe.failure)
            is SourceProbeResult.Success -> {
                val root = File(temporaryRoot, UUID.randomUUID().toString())
                if (!root.mkdirs()) return OpenSessionResult.Failed(tempCreateFailure(root))
                OpenSessionResult.Open(AndroidClipEditorSession(sourceFile, root, probe.metadata, configuration, context))
            }
        }
    }

    private suspend fun probeSource(file: File): SourceProbeResult = withContext(Dispatchers.IO) {
        if (file.length() > MAXIMUM_INPUT_BYTES) {
            return@withContext SourceProbeResult.Unsupported(UnsupportedCode.INPUT_TOO_LARGE, file.length().toString())
        }
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            retriever.setDataSource(file.absolutePath)
            if (Build.VERSION.SDK_INT >= 26 && extractor.drmInitData != null) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.DRM_PROTECTED, null)
            }
            val containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (containerMime != "video/mp4" && containerMime != "application/mp4") {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_CONTAINER, containerMime)
            }
            val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
            val videoTracks = formats.filter { it.mime().startsWith("video/") }
            if (videoTracks.size != 1) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, "Expected exactly one video track")
            }
            val video = videoTracks.single()
            if (video.mime() != MimeTypes.VIDEO_H264) return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, video.mime())
            if (video.isHdr()) return@withContext SourceProbeResult.Unsupported(UnsupportedCode.HDR_UNSUPPORTED, null)
            val audioTracks = formats.filter { it.mime().startsWith("audio/") }
            if (audioTracks.size > 1) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_AUDIO_CODEC, "Expected at most one audio track")
            }
            if (formats.any { !it.mime().startsWith("video/") && !it.mime().startsWith("audio/") }) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_CONTAINER, "Unsupported auxiliary track")
            }
            val audio = audioTracks.singleOrNull()
            if (audio != null && audio.mime() != MimeTypes.AUDIO_AAC) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_AUDIO_CODEC, audio.mime())
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext SourceProbeResult.Failed(metadataFailure("Missing duration"))
            if (durationMs > MAXIMUM_INPUT_DURATION_MS) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.INPUT_TOO_LONG, durationMs.toString())
            }
            val encodedWidth = video.getInteger(MediaFormat.KEY_WIDTH)
            val encodedHeight = video.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = if (rotation == 90 || rotation == 270) encodedHeight else encodedWidth
            val height = if (rotation == 90 || rotation == 270) encodedWidth else encodedHeight
            SourceProbeResult.Success(VideoMetadata(durationMs.milliseconds, width, height, audio != null))
        } catch (error: Exception) {
            SourceProbeResult.Failed(metadataFailure(error.message))
        } finally {
            retriever.release()
            extractor.release()
        }
    }

    private companion object {
        const val MAXIMUM_INPUT_BYTES: Long = 512L * 1024L * 1024L
        const val MAXIMUM_INPUT_DURATION_MS: Long = 300_000L
    }
}

private fun EngineProbeResult.toSourceProbeResult(): SourceProbeResult = when (this) {
    is EngineProbeResult.Success -> SourceProbeResult.Success(metadata)
    is EngineProbeResult.Unsupported -> SourceProbeResult.Unsupported(code, diagnostic)
    is EngineProbeResult.Failed -> SourceProbeResult.Failed(failure)
}

private class AndroidClipEditorSession(
    private val source: File,
    private val sessionRoot: File,
    override val metadata: VideoMetadata,
    private val configuration: VideoClipEditorConfiguration,
    private val context: Context,
) : ClipEditorSession {
    private val exportMutex = Mutex()
    private val frameEmissionGate = FrameEmissionGate()
    private val lifecycleLock = Any()
    private val issuedLeases = mutableMapOf<String, LeaseRecord>()
    @Volatile private var closed = false
    private var activeTransformer: Transformer? = null
    private var activeCancellation: (() -> Unit)? = null

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = callbackFlow {
        val worker = launch {
            if (closed || !frameEmissionGate.register(coroutineContext[Job]!!)) {
                send(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null))
                return@launch
            }
            CommonValidation.frameRequest(request, configuration)?.let {
                send(FrameStripEvent.InvalidRequest(it, null))
                return@launch
            }
            val frames = runCatching { extractFrames(request.frameCount) }.getOrElse {
                send(FrameStripEvent.Failed(VideoEditFailure(FailureCode.FRAME_EXTRACTION_FAILED, true, it.message)))
                return@launch
            }
            frames.forEachIndexed { index, frame ->
                send(FrameStripEvent.Frame(frame))
                send(FrameStripEvent.Progress(index + 1, frames.size))
            }
            send(FrameStripEvent.Complete)
        }
        worker.invokeOnCompletion { close() }
        awaitClose { worker.cancel() }
    }.buffer(0)

    override suspend fun createClip(range: ClipRange): ClipResult {
        if (closed) return ClipResult.InvalidRequest(ValidationCode.SESSION_CLOSED, null)
        CommonValidation.clipRange(range, metadata, configuration)?.let { return ClipResult.InvalidRequest(it, null) }
        if (!exportMutex.tryLock()) return ClipResult.InvalidRequest(ValidationCode.OPERATION_IN_PROGRESS, null)
        try {
            if (closed) return ClipResult.InvalidRequest(ValidationCode.SESSION_CLOSED, null)
            val outputId = UUID.randomUUID().toString()
            val partial = File(sessionRoot, "$outputId.partial")
            val final = File(sessionRoot, "$outputId.mp4")
            return try {
                export(range, partial)
                if (!partial.renameTo(final)) {
                    partial.delete()
                    ClipResult.Failed(VideoEditFailure(FailureCode.TEMP_RENAME_FAILED, true, final.name))
                } else {
                    val opaqueId = UUID.randomUUID().toString()
                    val record = LeaseRecord(opaqueId)
                    synchronized(lifecycleLock) {
                        if (closed) {
                            final.delete()
                            return ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, "Session closed"))
                        }
                        issuedLeases[final.absolutePath] = record
                    }
                    ClipResult.Success(AndroidTemporaryClipLease(final, opaqueId) { clearIssuedLease(final, opaqueId) }, range)
                }
            } catch (unavailable: DeviceEncoderUnavailableException) {
                partial.delete()
                ClipResult.Unsupported(UnsupportedCode.DEVICE_ENCODER_UNAVAILABLE, unavailable.message)
            } catch (cancelled: CancellationException) {
                partial.delete()
                ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, cancelled.message))
            } catch (error: Exception) {
                partial.delete()
                ClipResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, true, error.message))
            }
        } finally {
            exportMutex.unlock()
        }
    }

    override suspend fun close() {
        val active = synchronized(lifecycleLock) {
            closed = true
            activeTransformer to activeCancellation
        }
        frameEmissionGate.close()
        AndroidMainLooperDispatcher.run {
            runCatching { active.first?.cancel() }
            runCatching { active.second?.invoke() }
        }
        exportMutex.lock()
        try {
            synchronized(lifecycleLock) {
                sessionRoot.listFiles()?.forEach { child ->
                    if (child.absolutePath !in issuedLeases) child.delete()
                }
                if (sessionRoot.listFiles().isNullOrEmpty()) sessionRoot.delete()
            }
        } finally {
            exportMutex.unlock()
        }
    }

    private suspend fun clearIssuedLease(target: File, opaqueId: String): TempDeleteResult = synchronized(lifecycleLock) {
        val record = issuedLeases[target.absolutePath]
        if (record?.opaqueId != opaqueId) return@synchronized TempDeleteResult.AlreadyCleared
        AndroidLeaseDeletionPolicy.clear(target).also { result ->
            if (result is TempDeleteResult.Cleared || result is TempDeleteResult.AlreadyCleared) {
                issuedLeases.remove(target.absolutePath)
                if (sessionRoot.listFiles().isNullOrEmpty()) sessionRoot.delete()
            }
        }
    }

    private suspend fun extractFrames(count: Int): List<ThumbnailFrame> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(source.absolutePath)
            extractor.setDataSource(source.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).mime().startsWith("video/") }
                ?: throw IOException("No video track")
            extractor.selectTrack(videoTrack)
            (0 until count).map { index ->
                val requested = (metadata.duration.inWholeMilliseconds * index / count).milliseconds
                extractor.seekTo(requested.inWholeMicroseconds, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val actual = extractor.sampleTime.coerceAtLeast(0).milliseconds / 1_000
                val bitmap = retriever.getFrameAtTime(actual.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IOException("No frame at ${requested.inWholeMilliseconds}ms")
                bitmap.useAsThumbnail(requested, actual, configuration.maximumThumbnailDimensionPx)
            }
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    private suspend fun export(range: ClipRange, partial: File): ExportResult = suspendCancellableCoroutine { continuation ->
        AndroidMainLooperDispatcher.post {
            var cleanup: (() -> Unit)? = null
            try {
                if (!continuation.isActive) return@post
                val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEnsureFileStartsOnVideoFrameEnabled(true)
                .build()
                val clearActive = {
                    synchronized(lifecycleLock) {
                        if (activeTransformer === transformer) {
                            activeTransformer = null
                            activeCancellation = null
                        }
                    }
                }
                cleanup = clearActive
                val cancel = {
                    try {
                        transformer.cancel()
                    } finally {
                        clearActive()
                        if (continuation.isActive) continuation.resumeWith(Result.failure(CancellationException("Export cancelled")))
                    }
                }
                synchronized(lifecycleLock) {
                    if (closed) {
                        continuation.resumeWith(Result.failure(CancellationException("Session closed")))
                        return@post
                    }
                    activeTransformer = transformer
                    activeCancellation = cancel
                }
                transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                clearActive()
                if (continuation.isActive) continuation.resume(result)
            }

            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                clearActive()
                val error: Throwable = if (exception.errorCode == ExportException.ERROR_CODE_ENCODER_INIT_FAILED || exception.errorCode == ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED) DeviceEncoderUnavailableException(exception) else exception
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
                })
                continuation.invokeOnCancellation { AndroidMainLooperDispatcher.post { runCatching { cancel() }; partial.delete() } }
                transformer.start(
            MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.start.inWholeMilliseconds)
                    .setEndPositionMs(range.endExclusive.inWholeMilliseconds)
                    .build(),
            ).build(),
            partial.absolutePath,
                )
            } catch (error: Throwable) {
                cleanup?.invoke()
                partial.delete()
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
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

private data class LeaseRecord(val opaqueId: String)

/**
 * Deletes only an issued regular-file entry. `lstat` inspects the terminal entry without following
 * links; `remove` then removes that entry without traversing a target. Both APIs are available on
 * Android API 21+, unlike `File.toPath`.
 */
internal object AndroidLeaseDeletionPolicy {
    fun clear(target: File): TempDeleteResult {
        return try {
            when (Os.lstat(target.absolutePath).st_mode and OsConstants.S_IFMT) {
                OsConstants.S_IFLNK -> deleteFailure(target, "Refusing symbolic link")
                OsConstants.S_IFREG -> removeRegularFile(target)
                else -> deleteFailure(target, "Refusing non-regular file")
            }
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) TempDeleteResult.AlreadyCleared else deleteFailure(target, error.message)
        } catch (error: SecurityException) {
            deleteFailure(target, error.message)
        }
    }

    private fun removeRegularFile(target: File): TempDeleteResult = try {
        Os.remove(target.absolutePath)
        TempDeleteResult.Cleared
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) TempDeleteResult.AlreadyCleared else deleteFailure(target, error.message)
    } catch (error: SecurityException) {
        deleteFailure(target, error.message)
    }
}

private class DeviceEncoderUnavailableException(cause: ExportException) : Exception(cause.message, cause)

private sealed interface SourceProbeResult {
    data class Success(val metadata: VideoMetadata) : SourceProbeResult
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : SourceProbeResult
    data class Failed(val failure: VideoEditFailure) : SourceProbeResult
}

private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()

private fun MediaFormat.isHdr(): Boolean = containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
    getInteger(MediaFormat.KEY_COLOR_TRANSFER) in setOf(6, 7)

private fun Bitmap.useAsThumbnail(requested: kotlin.time.Duration, actual: kotlin.time.Duration, maxDimension: Int): ThumbnailFrame {
    val scale = min(1f, min(min(maxDimension, ThumbnailFrame.MAX_WIDTH_PX).toFloat() / width, ThumbnailFrame.MAX_HEIGHT_PX.toFloat() / height))
    val targetWidth = max(1, (width * scale).toInt())
    val targetHeight = max(1, (height * scale).toInt())
    val scaled = if (targetWidth == width && targetHeight == height) this else Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    return try {
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
        ThumbnailFrame(requested, actual, targetWidth, targetHeight, output.toByteArray())
    } finally {
        if (scaled !== this) scaled.recycle()
        recycle()
    }
}

private fun tempCreateFailure(root: File) = VideoEditFailure(FailureCode.TEMP_CREATE_FAILED, true, root.absolutePath)
private fun metadataFailure(diagnostic: String?) = VideoEditFailure(FailureCode.METADATA_READ_FAILED, true, diagnostic)
private fun deleteFailure(target: File, diagnostic: String?) = TempDeleteResult.Failed(
    VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, diagnostic ?: target.name),
)
