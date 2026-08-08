package com.oneononearena.videoclip.demo

import java.io.File
import java.io.InputStream
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoSourcePath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface DemoOwnedInput {
    val file: File

    fun delete(): Boolean
}

private class ImportedDemoOwnedInput(
    override val file: File,
    private val canonicalInputRoot: File,
) : DemoOwnedInput {
    override fun delete(): Boolean = try {
        val canonicalFile = file.canonicalFile
        canonicalFile.isFile &&
            canonicalFile.parentFile == canonicalInputRoot &&
            canonicalFile.delete()
    } catch (_: Exception) {
        false
    }
}

internal sealed interface DocumentImportResult {
    data class Imported(val input: DemoOwnedInput) : DocumentImportResult
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
            else {
                val canonicalRoot = inputRoot.canonicalFile
                val canonicalTarget = target.canonicalFile
                if (canonicalTarget.parentFile != canonicalRoot) {
                    DocumentImportResult.Failed("Imported file escaped demo input directory")
                } else {
                    DocumentImportResult.Imported(ImportedDemoOwnedInput(target, canonicalRoot))
                }
            }
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

/** Demo-only bridge: observes the screen-owned session close without exposing raw close authority. */
internal class SessionTrackingEditor(private val delegate: VideoClipEditor) : VideoClipEditor {
    private val sessionMutex = Mutex()
    private var activeSessionClosed: CompletableDeferred<Unit>? = null

    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult = sessionMutex.withLock {
        activeSessionClosed = null
        when (val opened = delegate.openSession(source)) {
            is OpenSessionResult.Open -> {
                val closeSignal = CompletableDeferred<Unit>()
                activeSessionClosed = closeSignal
                OpenSessionResult.Open(ForwardingClipEditorSession(opened.session, closeSignal))
            }
            else -> opened
        }
    }

    suspend fun awaitActiveSessionClosed() {
        sessionMutex.withLock { activeSessionClosed }?.await()
    }
}

private class ForwardingClipEditorSession(
    private val delegate: ClipEditorSession,
    private val closeSignal: CompletableDeferred<Unit>,
) : ClipEditorSession by delegate {
    private val closeMutex = Mutex()
    private var closeStarted = false

    override suspend fun close() {
        val ownsClose = closeMutex.withLock {
            if (closeStarted) {
                false
            } else {
                closeStarted = true
                true
            }
        }
        if (!ownsClose) {
            closeSignal.await()
            return
        }
        try {
            delegate.close()
            closeSignal.complete(Unit)
        } catch (error: Throwable) {
            closeSignal.completeExceptionally(error)
            throw error
        }
    }
}

internal enum class DemoClearResult { Cleared, Failed }

/** Lifecycle owner. A failed lease deletion leaves UI/source intact and disables reselect until retry. */
internal class DemoCleanupCoordinator(
    private val hideAndAwaitSessionClose: suspend () -> Unit,
    private val clearOutput: suspend () -> DemoClearResult,
    private val deleteSource: suspend () -> Boolean,
    private val clearUi: suspend () -> Unit,
) {
    var canReselect: Boolean = true
        private set

    suspend fun clear(): DemoClearResult {
        canReselect = false
        return try {
            hideAndAwaitSessionClose()
            if (clearOutput() == DemoClearResult.Failed) return DemoClearResult.Failed
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
