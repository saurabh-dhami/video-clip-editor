package com.oneononearena.videoclip.demo

import java.io.ByteArrayInputStream
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class BoundedDocumentImporterTest {
    @Test
    fun `imports stream using an absolute regular final file`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root, maxBytes = 8).import(ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        val imported = assertIs<DocumentImportResult.Imported>(result).file
        assertTrue(imported.isAbsolute)
        assertTrue(imported.isFile)
        assertEquals(listOf(1, 2, 3), imported.readBytes().map { it.toInt() })
        assertFalse(File(root, "random.partial").exists())
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
    fun `cleanup order releases player then output then session then source then UI`() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator(
            releasePlayer = { calls += "player" }, clearOutput = { calls += "output"; DemoClearResult.Cleared },
            closeSession = { calls += "session" }, deleteSource = { calls += "source"; true }, clearUi = { calls += "ui" },
        )
        assertEquals(DemoClearResult.Cleared, coordinator.clear())
        assertEquals(listOf("player", "output", "session", "source", "ui"), calls)
    }

    @Test
    fun `clear failure blocks reselect until retry succeeds`() = runBlocking {
        var attempts = 0
        val coordinator = DemoCleanupCoordinator(
            releasePlayer = {}, clearOutput = { attempts++; if (attempts == 1) DemoClearResult.Failed else DemoClearResult.Cleared },
            closeSession = {}, deleteSource = { true }, clearUi = {},
        )
        assertEquals(DemoClearResult.Failed, coordinator.clear())
        assertFalse(coordinator.canReselect)
        assertEquals(DemoClearResult.Cleared, coordinator.clear())
        assertTrue(coordinator.canReselect)
    }
}
