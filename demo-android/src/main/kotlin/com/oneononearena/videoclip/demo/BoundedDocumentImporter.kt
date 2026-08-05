package com.oneononearena.videoclip.demo

import java.io.File
import java.io.InputStream
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoSourcePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    fun importFrom(openInputStream: () -> InputStream?): DocumentImportResult = try {
        val input = openInputStream()
        if (input == null) {
            removePartialFiles()
            DocumentImportResult.Failed("Document provider returned no stream")
        } else {
            import(input)
        }
    } catch (error: Exception) {
        removePartialFiles()
        DocumentImportResult.Failed(error.message)
    }

    fun import(input: InputStream): DocumentImportResult {
        if (!inputRoot.exists() && !inputRoot.mkdirs()) return DocumentImportResult.Failed("Cannot create demo input directory")
        val partial = File(inputRoot, "random.partial")
        val target = File(inputRoot, "random.mp4")
        removePartialFiles()
        val result = try {
            var written = 0L
            var tooLarge = false
            input.use { source ->
                partial.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (written + count > maxBytes) {
                            tooLarge = true
                            break
                        }
                        sink.write(buffer, 0, count)
                        written += count
                    }
                }
            }
            // Same-directory rename is atomic on Android's app-private filesystem.
            if (tooLarge) DocumentImportResult.TooLarge
            else if (!partial.renameTo(target)) DocumentImportResult.Failed("Atomic import rename failed")
            else if (!target.isAbsolute || !target.isFile) DocumentImportResult.Failed("Imported file is invalid")
            else DocumentImportResult.Imported(target)
        } catch (error: Exception) {
            DocumentImportResult.Failed(error.message)
        }
        if (result !is DocumentImportResult.Imported) removePartialFiles()
        return result
    }

    private fun removePartialFiles() {
        File(inputRoot, "random.partial").delete()
        File(inputRoot, "random.mp4").delete()
    }

    internal companion object {
        const val MAX_INPUT_BYTES: Long = 512L * 1024L * 1024L
        private const val BUFFER_SIZE = 64 * 1024
    }
}

/** Demo-only bridge: exposes the actual session close required before deleting the imported source. */
internal class SessionTrackingEditor(private val delegate: VideoClipEditor) : VideoClipEditor {
    private val sessionMutex = Mutex()
    private var activeSession: ClipEditorSession? = null

    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = sessionMutex.withLock {
        delegate.openSession(source).also { opened ->
            activeSession = (opened as? OpenSessionResult.Open)?.session
        }
    }

    suspend fun closeActiveSession() = sessionMutex.withLock {
        activeSession?.close()
        activeSession = null
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
        return try {
            releasePlayer()
            if (clearOutput() == DemoClearResult.Failed) return DemoClearResult.Failed
            closeSession()
            if (!deleteSource()) return DemoClearResult.Failed
            clearUi()
            canReselect = true
            DemoClearResult.Cleared
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DemoClearResult.Failed
        }
    }
}
