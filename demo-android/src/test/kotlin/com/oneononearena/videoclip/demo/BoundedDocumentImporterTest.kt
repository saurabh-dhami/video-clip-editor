package com.oneononearena.videoclip.demo

import java.io.ByteArrayInputStream
import java.io.File
import com.oneononearena.videoclip.ClipEditorSession
import com.oneononearena.videoclip.ClipRange
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.FrameStripEvent
import com.oneononearena.videoclip.FrameStripRequest
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoMetadata
import com.oneononearena.videoclip.VideoSourcePath
import kotlin.io.path.createTempDirectory
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlin.time.Duration.Companion.seconds

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
    fun `maps document provider exception to typed failure and removes partial`() {
        val root = createTempDirectory().toFile()
        val result = BoundedDocumentImporter(root).importFrom { throw SecurityException("permission revoked") }

        assertEquals(DocumentImportResult.Failed("permission revoked"), result)
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

    @Test
    fun `clear output exception is failed and never reported cleared`() = runBlocking {
        val coordinator = DemoCleanupCoordinator(
            releasePlayer = {}, clearOutput = { throw IllegalStateException("delete failed") },
            closeSession = {}, deleteSource = { true }, clearUi = {},
        )

        assertEquals(DemoClearResult.Failed, coordinator.clear())
        assertFalse(coordinator.canReselect)
    }

    @Test
    fun `cleanup awaits session close before deleting source`() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator(
            releasePlayer = { calls += "player" }, clearOutput = { DemoClearResult.Cleared },
            closeSession = { calls += "session-close" }, deleteSource = { calls += "source-delete"; true }, clearUi = {},
        )

        assertEquals(DemoClearResult.Cleared, coordinator.clear())
        assertEquals(listOf("player", "session-close", "source-delete"), calls)
    }

    @Test
    fun `session close waits for a racing open then closes the real session`() = runBlocking {
        val openStarted = CompletableDeferred<Unit>()
        val allowOpen = CompletableDeferred<Unit>()
        val session = FakeSession()
        val editor = SessionTrackingEditor(object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
                openStarted.complete(Unit)
                allowOpen.await()
                return OpenSessionResult.Open(session)
            }
        })

        val opening = async { editor.openSession(VideoSourcePath("/tmp/input.mp4")) }
        openStarted.await()
        val closing = async { editor.closeActiveSession() }
        assertFalse(closing.isCompleted)
        allowOpen.complete(Unit)
        opening.await()
        closing.await()

        assertTrue(session.closed)
    }

    private class FailingInputStream : java.io.InputStream() {
        override fun read(): Int = throw IllegalStateException("read failed")
    }

    private class FakeSession : ClipEditorSession {
        var closed = false
        override val metadata = VideoMetadata(1.seconds, 1, 1, false)
        override fun frames(request: FrameStripRequest) = emptyFlow<FrameStripEvent>()
        override suspend fun createClip(range: ClipRange): ClipResult = error("not used")
        override suspend fun close() { closed = true }
    }
}
