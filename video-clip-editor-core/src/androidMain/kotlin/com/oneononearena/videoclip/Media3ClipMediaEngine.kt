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
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineAudioCodec
import com.oneononearena.videoclip.internal.engine.EngineExportRequest
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import com.oneononearena.videoclip.internal.engine.EngineFrameEvent
import com.oneononearena.videoclip.internal.engine.EngineFrameRequest
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Android-only production engine. The shared API sees only [ClipMediaEngine] result types. */
internal class Media3ClipMediaEngine(
    private val context: Context,
) : ClipMediaEngine {
    private val activeExportLock = Any()
    private var activeCancellation: (() -> Unit)? = null

    override suspend fun probe(source: EngineSource): EngineProbeResult = withContext(Dispatchers.IO) {
        val file = File(source.absolutePath)
        if (file.length() > MAXIMUM_INPUT_BYTES) {
            return@withContext EngineProbeResult.Unsupported(UnsupportedCode.INPUT_TOO_LARGE, file.length().toString())
        }
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            extractor.setDataSource(file.absolutePath)
            retriever.setDataSource(file.absolutePath)
            if (Build.VERSION.SDK_INT >= 26 && extractor.drmInitData != null) {
                return@withContext EngineProbeResult.Unsupported(UnsupportedCode.DRM_PROTECTED, null)
            }
            val containerMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            if (containerMime != "video/mp4" && containerMime != "application/mp4") {
                return@withContext EngineProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_CONTAINER, containerMime)
            }
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val videoTracks = formats.filter { it.mime().startsWith("video/") }
            if (videoTracks.size != 1) {
                return@withContext EngineProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, "Expected exactly one video track")
            }
            val video = videoTracks.single()
            val audioTracks = formats.filter { it.mime().startsWith("audio/") }
            if (audioTracks.size > 1) {
                return@withContext EngineProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_AUDIO_CODEC, "Expected at most one audio track")
            }
            if (formats.any { !it.mime().startsWith("video/") && !it.mime().startsWith("audio/") }) {
                return@withContext EngineProbeResult.Unsupported(UnsupportedCode.UNSUPPORTED_CONTAINER, "Unsupported auxiliary track")
            }
            val audio = audioTracks.singleOrNull()
            val topology = EngineStreamTopology(
                videoCodec = video.toEngineVideoCodec(),
                audioCodec = audio?.toEngineAudioCodec(),
                isHdr = video.isHdr(),
            )
            AndroidSourceTopologyPolicy.validate(topology)?.let { code ->
                val diagnostic = when (code) {
                    UnsupportedCode.UNSUPPORTED_VIDEO_CODEC -> video.mime()
                    UnsupportedCode.UNSUPPORTED_AUDIO_CODEC -> audio?.mime()
                    else -> null
                }
                return@withContext EngineProbeResult.Unsupported(code, diagnostic)
            }
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext EngineProbeResult.Failed(metadataFailure("Missing duration"))
            if (durationMs > MAXIMUM_INPUT_DURATION_MS) {
                return@withContext EngineProbeResult.Unsupported(UnsupportedCode.INPUT_TOO_LONG, durationMs.toString())
            }
            val encodedWidth = video.getInteger(MediaFormat.KEY_WIDTH)
            val encodedHeight = video.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = if (rotation == 90 || rotation == 270) encodedHeight else encodedWidth
            val height = if (rotation == 90 || rotation == 270) encodedWidth else encodedHeight
            EngineProbeResult.Success(
                metadata = VideoMetadata(durationMs.milliseconds, width, height, audio != null),
                topology = topology,
            )
        } catch (error: Exception) {
            EngineProbeResult.Failed(metadataFailure(error.message))
        } finally {
            retriever.release()
            extractor.release()
        }
    }

    override fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent> = flow {
        val frames = runCatching { extractFrames(source, request) }.getOrElse {
            emit(EngineFrameEvent.Failed(VideoEditFailure(FailureCode.FRAME_EXTRACTION_FAILED, true, it.message)))
            return@flow
        }
        frames.forEachIndexed { index, frame ->
            emit(EngineFrameEvent.Frame(frame))
            emit(EngineFrameEvent.Progress(index + 1, frames.size))
        }
        emit(EngineFrameEvent.Complete)
    }

    override suspend fun export(request: EngineExportRequest): EngineExportResult = try {
        awaitExport(request)
    } catch (cancelled: CancellationException) {
        EngineExportResult.Failed(VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, cancelled.message))
    } catch (error: Exception) {
        EngineExportResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, true, error.message))
    }

    override suspend fun cancelActiveExport() {
        val cancel = synchronized(activeExportLock) { activeCancellation }
        if (cancel != null) AndroidMainLooperDispatcher.run(cancel)
    }

    private suspend fun extractFrames(source: EngineSource, request: EngineFrameRequest): List<ThumbnailFrame> = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        try {
            retriever.setDataSource(source.absolutePath)
            extractor.setDataSource(source.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: throw IOException("Missing duration")
            val videoTrack = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).mime().startsWith("video/")
            } ?: throw IOException("No video track")
            extractor.selectTrack(videoTrack)
            (0 until request.frameCount).map { index ->
                val requested = (durationMs * index / request.frameCount).milliseconds
                extractor.seekTo(requested.inWholeMicroseconds, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val actual = extractor.sampleTime.coerceAtLeast(0).milliseconds / 1_000
                val bitmap = retriever.getFrameAtTime(actual.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IOException("No frame at ${requested.inWholeMilliseconds}ms")
                bitmap.useAsThumbnail(requested, actual, request.maximumDimensionPx)
            }
        } finally {
            extractor.release()
            retriever.release()
        }
    }

    private suspend fun awaitExport(request: EngineExportRequest): EngineExportResult = suspendCancellableCoroutine { continuation ->
        AndroidMainLooperDispatcher.post {
            try {
                if (!continuation.isActive) return@post
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .setEnsureFileStartsOnVideoFrameEnabled(true)
                    .build()
                val clearActive = {
                    synchronized(activeExportLock) {
                        if (activeCancellation != null) activeCancellation = null
                    }
                }
                val cancel = {
                    try {
                        transformer.cancel()
                    } finally {
                        clearActive()
                        if (continuation.isActive) {
                            continuation.resume(
                                EngineExportResult.Failed(
                                    VideoEditFailure(FailureCode.EXPORT_CANCELLED, true, "Export cancelled"),
                                ),
                            )
                        }
                    }
                }
                synchronized(activeExportLock) {
                    activeCancellation = cancel
                }
                transformer.addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        clearActive()
                        if (continuation.isActive) continuation.resume(EngineExportResult.Success)
                    }

                    override fun onError(composition: Composition, result: ExportResult, exception: ExportException) {
                        clearActive()
                        val engineResult = when (exception.errorCode) {
                            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                            ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                            -> EngineExportResult.Unsupported(UnsupportedCode.DEVICE_ENCODER_UNAVAILABLE, exception.message)
                            else -> EngineExportResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, true, exception.message))
                        }
                        if (continuation.isActive) continuation.resume(engineResult)
                    }
                })
                continuation.invokeOnCancellation {
                    AndroidMainLooperDispatcher.post { runCatching(cancel) }
                }
                transformer.start(
                    MediaItem.Builder()
                        .setUri(Uri.fromFile(File(request.source.absolutePath)))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(request.range.start.inWholeMilliseconds)
                                .setEndPositionMs(request.range.endExclusive.inWholeMilliseconds)
                                .build(),
                        )
                        .build(),
                    request.outputPath,
                )
            } catch (error: Throwable) {
                synchronized(activeExportLock) { activeCancellation = null }
                if (continuation.isActive) {
                    continuation.resume(
                        EngineExportResult.Failed(VideoEditFailure(FailureCode.EXPORT_FAILED, true, error.message)),
                    )
                }
            }
        }
    }

    private companion object {
        const val MAXIMUM_INPUT_BYTES: Long = 512L * 1024L * 1024L
        const val MAXIMUM_INPUT_DURATION_MS: Long = 300_000L
    }
}

private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()

internal fun MediaFormat.toEngineVideoCodec(): EngineVideoCodec = when (mime()) {
    MimeTypes.VIDEO_H264 -> EngineVideoCodec.AVC
    MimeTypes.VIDEO_H265 -> EngineVideoCodec.HEVC
    else -> EngineVideoCodec.OTHER
}

internal fun MediaFormat.toEngineAudioCodec(): EngineAudioCodec = when (mime()) {
    MimeTypes.AUDIO_AAC -> EngineAudioCodec.AAC
    else -> EngineAudioCodec.OTHER
}

private fun MediaFormat.isHdr(): Boolean = containsKey(MediaFormat.KEY_COLOR_TRANSFER) &&
    getInteger(MediaFormat.KEY_COLOR_TRANSFER) in setOf(6, 7)

private fun Bitmap.useAsThumbnail(
    requested: kotlin.time.Duration,
    actual: kotlin.time.Duration,
    maxDimension: Int,
): ThumbnailFrame {
    val scale = min(
        1f,
        min(
            min(maxDimension, ThumbnailFrame.MAX_WIDTH_PX).toFloat() / width,
            ThumbnailFrame.MAX_HEIGHT_PX.toFloat() / height,
        ),
    )
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

private fun metadataFailure(diagnostic: String?) = VideoEditFailure(FailureCode.METADATA_READ_FAILED, true, diagnostic)
