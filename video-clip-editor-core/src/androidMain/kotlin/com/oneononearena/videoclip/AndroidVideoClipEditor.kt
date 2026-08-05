package com.oneononearena.videoclip

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
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
            val width = video.getInteger(MediaFormat.KEY_WIDTH)
            val height = video.getInteger(MediaFormat.KEY_HEIGHT)
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
    private val stateMutex = Mutex()
    private val issuedLeases = mutableSetOf<String>()
    @Volatile private var closed = false
    @Volatile private var activeTransformer: Transformer? = null

    override fun frames(request: FrameStripRequest): Flow<FrameStripEvent> = flow {
        if (closed) {
            emit(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null))
            return@flow
        }
        CommonValidation.frameRequest(request, configuration)?.let {
            emit(FrameStripEvent.InvalidRequest(it, null))
            return@flow
        }
        val frames = runCatching { extractFrames(request.frameCount) }.getOrElse {
            emit(FrameStripEvent.Failed(VideoEditFailure(FailureCode.FRAME_EXTRACTION_FAILED, true, it.message)))
            return@flow
        }
        frames.forEachIndexed { index, frame ->
            if (closed) {
                emit(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null))
                return@flow
            }
            emit(FrameStripEvent.Frame(frame))
            emit(FrameStripEvent.Progress(index + 1, frames.size))
        }
        emit(FrameStripEvent.Complete)
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
                    stateMutex.lock()
                    try { issuedLeases += final.name } finally { stateMutex.unlock() }
                    ClipResult.Success(AndroidTemporaryClipLease(final) { clearIssuedLease(final.name) }, range)
                }
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
        closed = true
        activeTransformer?.cancel()
        stateMutex.lock()
        try {
            sessionRoot.listFiles()?.forEach { child ->
                if (child.name !in issuedLeases) child.delete()
            }
            if (sessionRoot.listFiles().isNullOrEmpty()) sessionRoot.delete()
        } finally {
            stateMutex.unlock()
        }
    }

    private suspend fun clearIssuedLease(name: String) {
        stateMutex.lock()
        try { issuedLeases.remove(name) } finally { stateMutex.unlock() }
    }

    private suspend fun extractFrames(count: Int): List<ThumbnailFrame> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            (0 until count).map { index ->
                val requested = (metadata.duration.inWholeMilliseconds * index / count).milliseconds
                val bitmap = retriever.getFrameAtTime(requested.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IOException("No frame at ${requested.inWholeMilliseconds}ms")
                bitmap.useAsThumbnail(requested, configuration.maximumThumbnailDimensionPx)
            }
        } finally {
            retriever.release()
        }
    }

    private suspend fun export(range: ClipRange, partial: File): ExportResult = suspendCancellableCoroutine { continuation ->
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEnsureFileStartsOnVideoFrameEnabled(true)
            .build()
        activeTransformer = transformer
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: Composition, result: ExportResult) {
                activeTransformer = null
                if (continuation.isActive) continuation.resume(result)
            }

            override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                activeTransformer = null
                if (continuation.isActive) continuation.resumeWith(Result.failure(exception))
            }
        })
        continuation.invokeOnCancellation { transformer.cancel(); partial.delete() }
        transformer.start(
            MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.start.inWholeMilliseconds)
                    .setEndPositionMs(range.endExclusive.inWholeMilliseconds)
                    .build(),
            ).build(),
            partial.absolutePath,
        )
    }
}

private class AndroidTemporaryClipLease(
    private val target: File,
    private val onCleared: suspend () -> Unit,
) : TemporaryClipLease {
    override val file = TemporaryVideoFile(target.absolutePath, UUID.randomUUID().toString())
    private var cleared = false

    override suspend fun clearTemporaryFile(): TempDeleteResult {
        if (cleared) return TempDeleteResult.AlreadyCleared
        return try {
            if (target.exists() && !target.delete()) {
                TempDeleteResult.Failed(VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, target.name))
            } else {
                cleared = true
                onCleared()
                TempDeleteResult.Cleared
            }
        } catch (error: SecurityException) {
            TempDeleteResult.Failed(VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, error.message))
        }
    }
}

private sealed interface SourceProbeResult {
    data class Success(val metadata: VideoMetadata) : SourceProbeResult
    data class Unsupported(val code: UnsupportedCode, val diagnostic: String?) : SourceProbeResult
    data class Failed(val failure: VideoEditFailure) : SourceProbeResult
}

private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()

private fun MediaFormat.isHdr(): Boolean = containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
    getInteger(MediaFormat.KEY_COLOR_TRANSFER) in setOf(6, 7)

private fun Bitmap.useAsThumbnail(requested: kotlin.time.Duration, maxDimension: Int): ThumbnailFrame {
    val scale = min(1f, min(min(maxDimension, ThumbnailFrame.MAX_WIDTH_PX).toFloat() / width, ThumbnailFrame.MAX_HEIGHT_PX.toFloat() / height))
    val targetWidth = max(1, (width * scale).toInt())
    val targetHeight = max(1, (height * scale).toInt())
    val scaled = if (targetWidth == width && targetHeight == height) this else Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
    return try {
        val output = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 82, output)
        ThumbnailFrame(requested, requested, targetWidth, targetHeight, output.toByteArray())
    } finally {
        if (scaled !== this) scaled.recycle()
        recycle()
    }
}

private fun tempCreateFailure(root: File) = VideoEditFailure(FailureCode.TEMP_CREATE_FAILED, true, root.absolutePath)
private fun metadataFailure(diagnostic: String?) = VideoEditFailure(FailureCode.METADATA_READ_FAILED, true, diagnostic)
