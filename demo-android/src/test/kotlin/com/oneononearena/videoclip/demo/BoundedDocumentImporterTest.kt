package com.oneononearena.videoclip.demo

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test

class BoundedDocumentImporterTest {
    @Test
    fun sameGenerationTerminalCompletionClearsLeaseOwnedInputThenUiAndAllowsReselect() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator()
        val generation = assertNotNull(coordinator.activateGeneration())

        val clearing = async {
            coordinator.clear(
                generation = generation,
                hideScreen = {
                    assertFalse(coordinator.canReselect)
                    calls += "hide-screen"
                },
                clearOutput = { calls += "lease-clear"; DemoClearResult.Cleared },
                deleteSource = { calls += "source-delete"; true },
                clearUi = { calls += "ui-clear" },
            )
        }
        yield()
        assertEquals(listOf("hide-screen"), calls)
        assertFalse(clearing.isCompleted)

        coordinator.onTerminalLifecycleComplete(generation)

        assertEquals(DemoClearResult.Cleared, clearing.await())
        assertEquals(
            listOf("hide-screen", "lease-clear", "source-delete", "ui-clear"),
            calls,
        )
        assertTrue(coordinator.canReselect)
        assertNotNull(coordinator.activateGeneration())
        Unit
    }

    @Test
    fun withheldTerminalCompletionRetainsLeaseOwnedInputUiAndBlocksReselect() = runBlocking {
        var leaseRetained = true
        var inputRetained = true
        var uiRetained = true
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator()
        val generation = assertNotNull(coordinator.activateGeneration())

        val clearing = async {
            coordinator.clear(
                generation = generation,
                hideScreen = { calls += "hide-screen" },
                clearOutput = { calls += "lease-clear"; leaseRetained = false; DemoClearResult.Cleared },
                deleteSource = { calls += "source-delete"; inputRetained = false; true },
                clearUi = { calls += "ui-clear"; uiRetained = false },
            )
        }
        yield()

        assertFalse(clearing.isCompleted)
        assertEquals(listOf("hide-screen"), calls)
        assertTrue(leaseRetained)
        assertTrue(inputRetained)
        assertTrue(uiRetained)
        assertFalse(coordinator.canReselect)
        assertNull(coordinator.activateGeneration())

        clearing.cancelAndJoin()
    }

    @Test
    fun staleGenerationTerminalCompletionNeverChangesCurrentState() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator()
        val staleGeneration = assertNotNull(coordinator.activateGeneration())
        val firstClear = async {
            coordinator.clear(
                generation = staleGeneration,
                hideScreen = {},
                clearOutput = { DemoClearResult.Cleared },
                deleteSource = { true },
                clearUi = {},
            )
        }
        yield()
        coordinator.onTerminalLifecycleComplete(staleGeneration)
        assertEquals(DemoClearResult.Cleared, firstClear.await())
        val currentGeneration = assertNotNull(coordinator.activateGeneration())

        val currentClear = async {
            coordinator.clear(
                generation = currentGeneration,
                hideScreen = { calls += "hide-current" },
                clearOutput = { calls += "lease-current"; DemoClearResult.Cleared },
                deleteSource = { calls += "source-current"; true },
                clearUi = { calls += "ui-current" },
            )
        }
        yield()
        coordinator.onTerminalLifecycleComplete(staleGeneration)
        yield()

        assertFalse(currentClear.isCompleted)
        assertEquals(listOf("hide-current"), calls)
        assertFalse(coordinator.canReselect)

        coordinator.onTerminalLifecycleComplete(currentGeneration)
        assertEquals(DemoClearResult.Cleared, currentClear.await())
        assertEquals(
            listOf("hide-current", "lease-current", "source-current", "ui-current"),
            calls,
        )
    }

    @Test
    fun terminalCompletionBeforeSealDoesNotAuthorizeDeletion() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator()
        val generation = assertNotNull(coordinator.activateGeneration())
        coordinator.onTerminalLifecycleComplete(generation)
        val clearing = async {
            coordinator.clear(
                generation = generation,
                hideScreen = { calls += "hide" },
                clearOutput = { calls += "lease"; DemoClearResult.Cleared },
                deleteSource = { calls += "source"; true },
                clearUi = { calls += "ui" },
            )
        }
        yield()

        assertFalse(clearing.isCompleted)
        assertEquals(listOf("hide"), calls)
        assertFalse(coordinator.canReselect)

        clearing.cancelAndJoin()
    }

    @Test
    fun `imports stream using an absolute regular final file`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root, maxBytes = 8).import(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val imported = assertIs<DocumentImportResult.Imported>(result).input.file
        assertTrue(imported.isAbsolute)
        assertTrue(imported.isFile)
        assertEquals(listOf(1, 2, 3), imported.readBytes().map { it.toInt() })
        assertFalse(File(root, "random.partial").exists())
    }

    @Test
    fun importIssuesCanonicalDemoOwnedInputAndPreservesProviderSource() {
        val workspace = createTempDirectory().toFile()
        val providerSource = File(workspace, "provider-source.mp4").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val inputRoot = File(workspace, "app/demo-inputs")

        val result = BoundedDocumentImporter(inputRoot, maxBytes = 8).importFrom(providerSource::inputStream)
        val ownedInput = assertIs<DocumentImportResult.Imported>(result).input

        assertEquals(inputRoot.canonicalFile, ownedInput.file.canonicalFile.parentFile)
        assertEquals(listOf(4, 5, 6), ownedInput.file.readBytes().map { it.toInt() })
        assertEquals(listOf(4, 5, 6), providerSource.readBytes().map { it.toInt() })
        assertTrue(ownedInput.delete())
        assertFalse(ownedInput.file.exists())
        assertTrue(providerSource.isFile)
        assertEquals(listOf(4, 5, 6), providerSource.readBytes().map { it.toInt() })
    }

    @Test
    fun `owned input refuses deletion when imported path resolves outside canonical root`() {
        val workspace = createTempDirectory().toFile()
        val providerSource = File(workspace, "provider-source.mp4").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val inputRoot = File(workspace, "app/demo-inputs")
        val ownedInput = assertIs<DocumentImportResult.Imported>(
            BoundedDocumentImporter(inputRoot, maxBytes = 8).import(ByteArrayInputStream(byteArrayOf(1))),
        ).input
        assertTrue(ownedInput.file.delete())
        Files.createSymbolicLink(ownedInput.file.toPath(), providerSource.toPath())

        assertFalse(ownedInput.delete())
        assertTrue(providerSource.isFile)
        assertEquals(listOf(7, 8, 9), providerSource.readBytes().map { it.toInt() })
    }

    @Test
    fun `rejects an unknown-size stream past hard cap and removes partial`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root, maxBytes = 3).import(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
        assertEquals(DocumentImportResult.TooLarge, result)
        assertFalse(File(root, "random.partial").exists())
        assertFalse(File(root, "random.mp4").exists())
    }

    @Test
    fun `maps document provider exception to typed failure and removes partial`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root).importFrom { throw SecurityException("permission revoked") }

        assertEquals(DocumentImportResult.Failed("permission revoked"), result)
        assertFalse(File(root, "random.partial").exists())
        assertFalse(File(root, "random.mp4").exists())
    }

    @Test
    fun `maps document provider null stream to typed failure and removes private copies`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root).importFrom { null }

        assertEquals(DocumentImportResult.Failed("Document provider returned no stream"), result)
        assertFalse(File(root, "random.partial").exists())
        assertFalse(File(root, "random.mp4").exists())
    }

    @Test
    fun `removes partial after stream terminal failure`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root).import(FailingInputStream())

        assertEquals(DocumentImportResult.Failed("read failed"), result)
        assertFalse(File(root, "random.partial").exists())
        assertFalse(File(root, "random.mp4").exists())
    }

    @Test
    fun clearFailureBlocksReselectUntilRetrySucceeds() = runBlocking {
        var attempts = 0
        val coordinator = DemoCleanupCoordinator()
        val generation = assertNotNull(coordinator.activateGeneration())
        val firstClear = async {
            coordinator.clear(
                generation = generation,
                hideScreen = {},
                clearOutput = { attempts++; DemoClearResult.Failed },
                deleteSource = { true },
                clearUi = {},
            )
        }
        yield()
        coordinator.onTerminalLifecycleComplete(generation)
        assertEquals(DemoClearResult.Failed, firstClear.await())
        assertFalse(coordinator.canReselect)
        assertNull(coordinator.activateGeneration())

        assertEquals(
            DemoClearResult.Cleared,
            coordinator.clear(
                generation = generation,
                hideScreen = {},
                clearOutput = { attempts++; DemoClearResult.Cleared },
                deleteSource = { true },
                clearUi = {},
            ),
        )
        assertEquals(2, attempts)
        assertTrue(coordinator.canReselect)
    }

    @Test
    fun hideLeaseInputFailuresStopBeforeNextStepAndBlockReselect() = runBlocking {
        val hideCalls = mutableListOf<String>()
        val hideCoordinator = DemoCleanupCoordinator()
        val hideGeneration = assertNotNull(hideCoordinator.activateGeneration())
        assertEquals(
            DemoClearResult.Failed,
            hideCoordinator.clear(
                generation = hideGeneration,
                hideScreen = { hideCalls += "hide"; error("hide failed") },
                clearOutput = { hideCalls += "lease"; DemoClearResult.Cleared },
                deleteSource = { hideCalls += "source"; true },
                clearUi = { hideCalls += "ui" },
            ),
        )
        assertEquals(listOf("hide"), hideCalls)
        assertFalse(hideCoordinator.canReselect)

        val leaseCalls = mutableListOf<String>()
        val leaseCoordinator = DemoCleanupCoordinator()
        val leaseGeneration = assertNotNull(leaseCoordinator.activateGeneration())
        val leaseClear = async {
            leaseCoordinator.clear(
                generation = leaseGeneration,
                hideScreen = { leaseCalls += "hide" },
                clearOutput = { leaseCalls += "lease"; DemoClearResult.Failed },
                deleteSource = { leaseCalls += "source"; true },
                clearUi = { leaseCalls += "ui" },
            )
        }
        yield()
        leaseCoordinator.onTerminalLifecycleComplete(leaseGeneration)
        assertEquals(DemoClearResult.Failed, leaseClear.await())
        assertEquals(listOf("hide", "lease"), leaseCalls)
        assertFalse(leaseCoordinator.canReselect)

        val inputCalls = mutableListOf<String>()
        val inputCoordinator = DemoCleanupCoordinator()
        val inputGeneration = assertNotNull(inputCoordinator.activateGeneration())
        val inputClear = async {
            inputCoordinator.clear(
                generation = inputGeneration,
                hideScreen = { inputCalls += "hide" },
                clearOutput = { inputCalls += "lease"; DemoClearResult.Cleared },
                deleteSource = { inputCalls += "source"; false },
                clearUi = { inputCalls += "ui" },
            )
        }
        yield()
        inputCoordinator.onTerminalLifecycleComplete(inputGeneration)
        assertEquals(DemoClearResult.Failed, inputClear.await())
        assertEquals(listOf("hide", "lease", "source"), inputCalls)
        assertFalse(inputCoordinator.canReselect)
    }

    @Test
    fun stageExceptionsReportFailureAndNeverRunLaterDestructiveSteps() = runBlocking {
        val leaseCalls = mutableListOf<String>()
        val leaseCoordinator = DemoCleanupCoordinator()
        val leaseGeneration = assertNotNull(leaseCoordinator.activateGeneration())
        val leaseClear = async {
            leaseCoordinator.clear(
                generation = leaseGeneration,
                hideScreen = { leaseCalls += "hide" },
                clearOutput = { leaseCalls += "lease"; error("lease failed") },
                deleteSource = { leaseCalls += "source"; true },
                clearUi = { leaseCalls += "ui" },
            )
        }
        yield()
        leaseCoordinator.onTerminalLifecycleComplete(leaseGeneration)
        assertEquals(DemoClearResult.Failed, leaseClear.await())
        assertEquals(listOf("hide", "lease"), leaseCalls)

        val inputCalls = mutableListOf<String>()
        val inputCoordinator = DemoCleanupCoordinator()
        val inputGeneration = assertNotNull(inputCoordinator.activateGeneration())
        val inputClear = async {
            inputCoordinator.clear(
                generation = inputGeneration,
                hideScreen = { inputCalls += "hide" },
                clearOutput = { inputCalls += "lease"; DemoClearResult.Cleared },
                deleteSource = { inputCalls += "source"; error("input failed") },
                clearUi = { inputCalls += "ui" },
            )
        }
        yield()
        inputCoordinator.onTerminalLifecycleComplete(inputGeneration)
        assertEquals(DemoClearResult.Failed, inputClear.await())
        assertEquals(listOf("hide", "lease", "source"), inputCalls)

        val uiCalls = mutableListOf<String>()
        val uiCoordinator = DemoCleanupCoordinator()
        val uiGeneration = assertNotNull(uiCoordinator.activateGeneration())
        val uiClear = async {
            uiCoordinator.clear(
                generation = uiGeneration,
                hideScreen = { uiCalls += "hide" },
                clearOutput = { uiCalls += "lease"; DemoClearResult.Cleared },
                deleteSource = { uiCalls += "source"; true },
                clearUi = { uiCalls += "ui"; error("ui failed") },
            )
        }
        yield()
        uiCoordinator.onTerminalLifecycleComplete(uiGeneration)
        assertEquals(DemoClearResult.Failed, uiClear.await())
        assertEquals(listOf("hide", "lease", "source", "ui"), uiCalls)
        assertFalse(uiCoordinator.canReselect)
    }

    @Test
    fun cleanupCancellationRethrowsAndPerformsNoLaterDestructiveStep() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator()
        val generation = assertNotNull(coordinator.activateGeneration())
        val clearing = async {
            coordinator.clear(
                generation = generation,
                hideScreen = { calls += "hide" },
                clearOutput = { calls += "lease"; throw CancellationException("cancelled") },
                deleteSource = { calls += "source"; true },
                clearUi = { calls += "ui" },
            )
        }
        yield()
        coordinator.onTerminalLifecycleComplete(generation)

        assertFailsWith<CancellationException> { clearing.await() }
        assertEquals(listOf("hide", "lease"), calls)
        assertFalse(coordinator.canReselect)
    }

    private class FailingInputStream : java.io.InputStream() {
        override fun read(): Int = throw IllegalStateException("read failed")
    }
}
