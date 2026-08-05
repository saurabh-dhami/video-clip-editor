package com.oneononearena.videoclip.feasibility

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class MediaProbe(val firstPresentationTimeUs: Long, val durationMs: Long, val audioVideoStartSkewMs: Long)
internal data class ExportedFixture(val file: File, val result: ExportResult)

internal suspend fun exportFixture(startMs: Long, endMs: Long): ExportedFixture = startExportFixture(startMs, endMs).awaitExport()

internal fun startExportFixture(startMs: Long, endMs: Long): ExportOperation {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val source = copyFixture(context)
    val outputId = UUID.randomUUID().toString()
    val partial = File(context.cacheDir, "$outputId.partial")
    val final = File(context.cacheDir, "$outputId.mp4")
    val completed = CompletableDeferred<ExportedFixture>()
    val transformer = Transformer.Builder(context)
        .setVideoMimeType(MimeTypes.VIDEO_H264)
        .setAudioMimeType(MimeTypes.AUDIO_AAC)
        .setEnsureFileStartsOnVideoFrameEnabled(true)
        .build()
    transformer.addListener(object : Transformer.Listener {
        override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
            if (partial.renameTo(final)) completed.complete(ExportedFixture(final, result))
            else { partial.delete(); completed.completeExceptionally(IllegalStateException("Could not atomically publish ${final.name}")) }
        }
        override fun onError(composition: androidx.media3.transformer.Composition, result: ExportResult, exception: ExportException) {
            partial.delete()
            completed.completeExceptionally(exception)
        }
    })
    transformer.start(
        MediaItem.Builder().setUri(Uri.fromFile(source)).setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build(),
        ).build(),
        partial.absolutePath,
    )
    return ExportOperation(transformer, partial, final, completed)
}

internal class ExportOperation(
    private val transformer: Transformer,
    val partialFile: File,
    val finalFile: File,
    private val completed: CompletableDeferred<ExportedFixture>,
) {
    fun cancel() { transformer.cancel(); partialFile.delete(); finalFile.delete(); completed.cancel() }
    suspend fun awaitTerminal(): Boolean = completed.isCompleted || runCatching { completed.await() }.isSuccess
    suspend fun awaitExport(): ExportedFixture = completed.await()
}

internal suspend fun probe(file: File): MediaProbe = withContext(Dispatchers.IO) {
    MediaMetadataRetriever().use { retriever ->
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: error("Missing MP4 duration")
        val extractor = MediaExtractor()
        val starts = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).associate { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty() to firstSampleTimeUs(extractor, index) }
        } finally {
            extractor.release()
        }
        val videoStart = starts.entries.firstOrNull { it.key.startsWith("video/") }?.value ?: error("Missing video track")
        val audioStart = starts.entries.firstOrNull { it.key.startsWith("audio/") }?.value ?: error("Missing audio track")
        MediaProbe(videoStart, durationMs, abs(videoStart - audioStart) / 1_000L)
    }
}

private fun firstSampleTimeUs(extractor: MediaExtractor, track: Int): Long {
    extractor.selectTrack(track)
    check(extractor.readSampleData(ByteBuffer.allocate(1 shl 20), 0) >= 0) { "Track $track has no samples" }
    return extractor.sampleTime
}

private fun copyFixture(context: Context): File {
    val destination = File(context.cacheDir, "avc-aac-10s-30fps.mp4")
    if (!destination.exists()) InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/avc-aac-10s-30fps.mp4")
        .use { input -> destination.outputStream().use(input::copyTo) }
    return destination
}
