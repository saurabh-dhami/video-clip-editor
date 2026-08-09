package com.oneononearena.videoclip.demo

import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

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

internal enum class DemoClearResult { Cleared, Failed }

/** Generation fence. Destructive cleanup starts only after the screen's matching terminal callback. */
internal class DemoCleanupCoordinator {
    private data class ActiveGeneration(
        val id: Long,
        val terminalCompletion: CompletableDeferred<Unit> = CompletableDeferred(),
        var sealed: Boolean = false,
    )

    private var nextGeneration = 0L
    private var activeGeneration: ActiveGeneration? = null

    var canReselect: Boolean = true
        private set

    fun activateGeneration(): Long? {
        if (!canReselect || activeGeneration != null) return null
        val generation = ++nextGeneration
        activeGeneration = ActiveGeneration(generation)
        return generation
    }

    fun acceptsUpdates(generation: Long): Boolean {
        val active = activeGeneration
        return active?.id == generation && !active.sealed
    }

    fun sealGeneration(generation: Long): Boolean {
        val active = activeGeneration
        if (active?.id != generation) return false
        active.sealed = true
        canReselect = false
        return true
    }

    fun onTerminalLifecycleComplete(generation: Long) {
        val active = activeGeneration
        if (active?.id == generation && active.sealed) {
            active.terminalCompletion.complete(Unit)
        }
    }

    suspend fun clear(
        generation: Long,
        hideScreen: suspend () -> Unit,
        clearOutput: suspend () -> DemoClearResult,
        deleteSource: suspend () -> Boolean,
        clearUi: suspend () -> Unit,
    ): DemoClearResult {
        val active = activeGeneration
        if (active?.id != generation || !sealGeneration(generation)) return DemoClearResult.Failed
        return try {
            hideScreen()
            active.terminalCompletion.await()
            if (clearOutput() == DemoClearResult.Failed) return DemoClearResult.Failed
            if (!deleteSource()) return DemoClearResult.Failed
            clearUi()
            activeGeneration = null
            canReselect = true
            DemoClearResult.Cleared
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DemoClearResult.Failed
        }
    }
}
