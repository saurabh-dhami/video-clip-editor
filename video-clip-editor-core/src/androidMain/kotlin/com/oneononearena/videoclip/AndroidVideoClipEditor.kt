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
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
) : VideoClipEditor {
    private val temporaryRoot = File(context.cacheDir, "video-clip-editor")

    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
        AndroidSourcePolicy.validate(source.value, temporaryRoot)?.let { return OpenSessionResult.InvalidRequest(it, null) }
        val sourceFile = AndroidSourcePolicy.canonicalFile(source.value)
            ?: return OpenSessionResult.InvalidRequest(ValidationCode.PATH_NOT_REGULAR_FILE, null)
        return when (val probe = probeSource(sourceFile)) {
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
            val video = formats.firstOrNull { it.mime().startsWith("video/") }
                ?: return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_CONTAINER, "No video track")
            if (video.mime() != MimeTypes.VIDEO_H264) return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, video.mime())
            if (video.isHdr()) return@withContext SourceProbeResult.Unsupported(UnsupportedCode.HDR_UNSUPPORTED, null)
            val audio = formats.firstOrNull { it.mime().startsWith("audio/") }
            if (audio != null && audio.mime() != MimeTypes.AUDIO_AAC) {
                return@withContext SourceProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_AUDIO_CODEC, audio.mime())
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext SourceProbeResult.Failed(metadataFailure("Missing duration"))
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
}

private class AndroidClipEditorSession(
    private val source: File,
    private val sessionRoot: File,
    override val metadata: VideoMetadata,
    private val configuration: VideoClipEditorConfiguration,
    private val context: Context,
) : ClipEditorSession {
    private val exportMutex = Mutex()
    private val frameEmissionMutex = Mutex()
    private val lifecycleLock = Any()
    private val issuedLeases = mutableMapOf<String, LeaseRecord>()
    @Volatile private var closed = false
    private var activeTransformer: Transformer? = null
    private var activeCancellation: (() -> Unit)? = null

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = flow {
        CommonValidation.frameRequest(request, configuration)?.let {
            emitIfOpen { emit(FrameStripEvent.InvalidRequest(it, null)) }
            return@flow
        }
        val frames = runCatching { extractFrames(request.frameCount) }.getOrElse {
            emitIfOpen { emit(FrameStripEvent.Failed(VideoEditFailure(FailureCode.FRAME_EXTRACTION_FAILED, true, it.message))) }
            return@flow
        }
        frames.forEachIndexed { index, frame ->
            if (!emitIfOpen { emit(FrameStripEvent.Frame(frame)) }) return@flow
            if (!emitIfOpen { emit(FrameStripEvent.Progress(index + 1, frames.size)) }) return@flow
        }
        emitIfOpen { emit(FrameStripEvent.Complete) }
    }

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
        AndroidLeaseDeletionPolicy.clear(target)
    }

    private suspend fun emitIfOpen(emitEvent: suspend () -> Unit): Boolean {
        val admitted = frameEmissionMutex.withLock { !closed }
        if (!admitted) return false
        emitEvent()
        return true
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

internal class AndroidTemporaryClipLease(
    private val target: File,
    opaqueId: String,
    private val onCleared: suspend () -> TempDeleteResult,
) : TemporaryClipLease {
    override val file = TemporaryVideoFile(target.absolutePath, opaqueId)
    private val clearMutex = Mutex()
    private var cleared = false

    override suspend fun clearTemporaryFile(): TempDeleteResult = clearMutex.withLock {
        if (cleared) TempDeleteResult.AlreadyCleared else onCleared().also { if (it is TempDeleteResult.Cleared) cleared = true }
    }
}

private data class LeaseRecord(val opaqueId: String)

/**
 * Android's public Os API has neither openat nor unlinkat. A descriptor can identify an issued
 * inode, but pathname removal after that check can still unlink a replacement inode. Keep the
 * issued output intact and return a typed retryable failure instead of risking that deletion.
 */
internal object AndroidLeaseDeletionPolicy {
    fun clear(target: File): TempDeleteResult = TempDeleteResult.Failed(
        VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, target.name),
    )
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
