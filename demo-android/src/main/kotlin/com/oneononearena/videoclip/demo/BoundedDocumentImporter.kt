package com.oneononearena.videoclip.demo

import java.io.File
import java.io.InputStream

internal sealed interface DocumentImportResult {
    data class Imported(val file: File) : DocumentImportResult
    data object TooLarge : DocumentImportResult
    data class Failed(val message: String?) : DocumentImportResult
}

/** Demo boundary: a URI stream becomes one app-private, absolute regular file before core sees it. */
internal class BoundedDocumentImporter(
    private val inputRoot: File,
    private val maxBytes: Long = MAX_INPUT_BYTES,
) {
    fun import(input: InputStream): DocumentImportResult {
        if (!inputRoot.exists() && !inputRoot.mkdirs()) return DocumentImportResult.Failed("Cannot create demo input directory")
        val partial = File(inputRoot, "random.partial")
        val target = File(inputRoot, "random.mp4")
        partial.delete()
        target.delete()
        return try {
            var written = 0L
            input.use { source ->
                partial.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (written + count > maxBytes) return DocumentImportResult.TooLarge
                        sink.write(buffer, 0, count)
                        written += count
                    }
                }
            }
            // Same-directory rename is atomic on Android's app-private filesystem.
            if (!partial.renameTo(target)) DocumentImportResult.Failed("Atomic import rename failed")
            else if (!target.isAbsolute || !target.isFile) DocumentImportResult.Failed("Imported file is invalid")
            else DocumentImportResult.Imported(target)
        } catch (error: Exception) {
            DocumentImportResult.Failed(error.message)
        } finally {
            if (!target.isFile) partial.delete()
        }
    }

    internal companion object {
        const val MAX_INPUT_BYTES: Long = 512L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
    }
}

internal enum class DemoClearResult { Cleared, Failed }

/** Lifecycle owner. A failed lease deletion leaves UI/source intact and disables reselect until retry. */
internal class DemoCleanupCoordinator(
    private val releasePlayer: suspend () -> Unit,
    private val clearOutput: suspend () -> DemoClearResult,
    private val closeSession: suspend () -> Unit,
    private val deleteSource: suspend () -> Boolean,
    private val clearUi: suspend () -> Unit,
) {
    var canReselect: Boolean = true
        private set

    suspend fun clear(): DemoClearResult {
        canReselect = false
        releasePlayer()
        if (clearOutput() == DemoClearResult.Failed) return DemoClearResult.Failed
        closeSession()
        if (!deleteSource()) return DemoClearResult.Failed
        clearUi()
        canReselect = true
        return DemoClearResult.Cleared
    }
}
