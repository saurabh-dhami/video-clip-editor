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
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal data class MediaProbe(val firstPresentationTimeUs: Long, val durationMs: Long, val audioVideoStartSkewMs: Long)
internal data class ExportedFixture(val file: File, val result: ExportResult)

internal suspend fun exportFixture(startMs: Long, endMs: Long): ExportedFixture = startExportFixture(startMs, endMs).awaitExport()

internal fun startExportFixture(startMs: Long, endMs: Long): ExportOperation {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val outputId = UUID.randomUUID().toString()
    val completed = CompletableDeferred<ExportedFixture>()
    lateinit var operation: ExportOperation
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val source = copyFixture(context, outputId)
        val partial = File(context.cacheDir, "$outputId.partial")
        val final = File(context.cacheDir, "$outputId.mp4")
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEnsureFileStartsOnVideoFrameEnabled(true)
            .build()
        operation = ExportOperation(transformer, source, partial, final, completed)
        transformer.addListener(object : Transformer.Listener {
            override fun onCompleted(composition: androidx.media3.transformer.Composition, result: ExportResult) {
                source.delete()
                if (partial.renameTo(final)) completed.complete(ExportedFixture(final, result))
                else { partial.delete(); completed.completeExceptionally(IllegalStateException("Could not atomically publish ${final.name}")) }
            }
            override fun onError(composition: androidx.media3.transformer.Composition, result: ExportResult, exception: ExportException) {
                source.delete()
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
    }
    return operation
}

internal class ExportOperation(
    private val transformer: Transformer,
    private val sourceFile: File,
    val partialFile: File,
    val finalFile: File,
    private val completed: CompletableDeferred<ExportedFixture>,
) {
    fun cancel() = InstrumentationRegistry.getInstrumentation().runOnMainSync { transformer.cancel() }
    suspend fun awaitTerminal(): Boolean = completed.isCompleted || runCatching { completed.await() }.isSuccess
    suspend fun awaitCancellationQuiescence() = withTimeout(CANCELLATION_QUIESCENCE_TIMEOUT_MS) {
        repeat(CANCELLATION_QUIESCENCE_POLLS) {
            delay(CANCELLATION_QUIESCENCE_POLL_MS)
            val progressState = onMain { transformer.getProgress(ProgressHolder()) }
            check(progressState == Transformer.PROGRESS_STATE_NOT_STARTED) { "Transformer still active after cancel: $progressState" }
        }
        sourceFile.delete()
        partialFile.delete()
        finalFile.delete()
    }
    suspend fun awaitExport(): ExportedFixture = completed.await()
}

internal suspend fun probe(file: File): MediaProbe = withContext(Dispatchers.IO) {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: error("Missing MP4 duration")
        val extractor = MediaExtractor()
        val trackMimes = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).associateWith { index -> extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            extractor.release()
        }
        val starts = trackMimes.mapValues { (track, _) -> firstSampleTimeUs(file, track) }
        val videoStart = starts.entries.firstOrNull { trackMimes.getValue(it.key).startsWith("video/") }?.value ?: error("Missing video track")
        val audioStart = starts.entries.firstOrNull { trackMimes.getValue(it.key).startsWith("audio/") }?.value ?: error("Missing audio track")
        MediaProbe(videoStart, durationMs, abs(videoStart - audioStart) / 1_000L)
    } finally {
        retriever.release()
    }
}

private fun firstSampleTimeUs(file: File, track: Int): Long {
    val extractor = MediaExtractor()
    try {
    extractor.setDataSource(file.absolutePath)
    extractor.selectTrack(track)
    check(extractor.readSampleData(ByteBuffer.allocate(1 shl 20), 0) >= 0) { "Track $track has no samples" }
        return extractor.sampleTime
    } finally {
        extractor.release()
    }
}

private fun copyFixture(context: Context, outputId: String): File {
    val destination = File(context.cacheDir, "$outputId-source.mp4")
    val digest = MessageDigest.getInstance("SHA-256")
    InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/avc-aac-10s-30fps.mp4").use { input ->
        destination.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                output.write(buffer, 0, count)
            }
        }
    }
    check(digest.digest().joinToString("") { "%02x".format(it) } == FIXTURE_SHA256) {
        "Fixture SHA-256 mismatch"
    }
    return destination
}

private suspend fun <T> onMain(block: () -> T): T {
    val result = AtomicReference<Result<T>>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync { result.set(runCatching(block)) }
    return checkNotNull(result.get()).getOrThrow()
}

private const val FIXTURE_SHA256 = "8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a"
private const val CANCELLATION_QUIESCENCE_POLL_MS = 100L
private const val CANCELLATION_QUIESCENCE_POLLS = 3
private const val CANCELLATION_QUIESCENCE_TIMEOUT_MS = 5_000L
