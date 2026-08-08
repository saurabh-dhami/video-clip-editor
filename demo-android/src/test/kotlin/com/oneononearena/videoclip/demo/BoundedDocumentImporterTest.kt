package com.oneononearena.videoclip.demo

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.yield
import kotlin.time.Duration.Companion.seconds

class BoundedDocumentImporterTest {
    @Test
    fun demoClear_waitsForScreenOwnedSessionCloseBeforeDeletingImportedSource() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = { calls += "hide-await-close" },
            clearOutput = { calls += "lease-clear"; DemoClearResult.Cleared },
            deleteSource = { calls += "source-delete"; true },
            clearUi = { calls += "ui-clear" },
        )

        assertEquals(DemoClearResult.Cleared, coordinator.clear())
        assertEquals(
            listOf("hide-await-close", "lease-clear", "source-delete", "ui-clear"),
            calls,
        )
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
    fun `clear failure blocks reselect until retry succeeds`() = runBlocking {
        var attempts = 0
        val coordinator = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = {},
            clearOutput = { attempts++; if (attempts == 1) DemoClearResult.Failed else DemoClearResult.Cleared },
            deleteSource = { true }, clearUi = {},
        )
        assertEquals(DemoClearResult.Failed, coordinator.clear())
        assertFalse(coordinator.canReselect)
        assertEquals(DemoClearResult.Cleared, coordinator.clear())
        assertTrue(coordinator.canReselect)
    }

    @Test
    fun `clear output exception is failed and never reported cleared`() = runBlocking {
        val coordinator = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = {}, clearOutput = { throw IllegalStateException("delete failed") },
            deleteSource = { true }, clearUi = {},
        )

        assertEquals(DemoClearResult.Failed, coordinator.clear())
        assertFalse(coordinator.canReselect)
    }

    @Test
    fun closeLeaseInputFailuresStopBeforeNextStepAndBlockReselect() = runBlocking {
        val closeFailureCalls = mutableListOf<String>()
        val closeFailure = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = { closeFailureCalls += "hide-close"; error("close failed") },
            clearOutput = { closeFailureCalls += "lease"; DemoClearResult.Cleared },
            deleteSource = { closeFailureCalls += "source"; true },
            clearUi = { closeFailureCalls += "ui" },
        )
        assertEquals(DemoClearResult.Failed, closeFailure.clear())
        assertEquals(listOf("hide-close"), closeFailureCalls)
        assertFalse(closeFailure.canReselect)

        val leaseFailureCalls = mutableListOf<String>()
        val leaseFailure = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = { leaseFailureCalls += "hide-close" },
            clearOutput = { leaseFailureCalls += "lease"; DemoClearResult.Failed },
            deleteSource = { leaseFailureCalls += "source"; true },
            clearUi = { leaseFailureCalls += "ui" },
        )
        assertEquals(DemoClearResult.Failed, leaseFailure.clear())
        assertEquals(listOf("hide-close", "lease"), leaseFailureCalls)
        assertFalse(leaseFailure.canReselect)

        val inputFailureCalls = mutableListOf<String>()
        val inputFailure = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = { inputFailureCalls += "hide-close" },
            clearOutput = { inputFailureCalls += "lease"; DemoClearResult.Cleared },
            deleteSource = { inputFailureCalls += "source"; false },
            clearUi = { inputFailureCalls += "ui" },
        )
        assertEquals(DemoClearResult.Failed, inputFailure.clear())
        assertEquals(listOf("hide-close", "lease", "source"), inputFailureCalls)
        assertFalse(inputFailure.canReselect)
    }

    @Test
    fun `cleanup cancellation rethrows and performs no later step`() = runBlocking {
        val calls = mutableListOf<String>()
        val coordinator = DemoCleanupCoordinator(
            hideAndAwaitSessionClose = { calls += "hide-close"; throw CancellationException("cancelled") },
            clearOutput = { calls += "lease"; DemoClearResult.Cleared },
            deleteSource = { calls += "source"; true },
            clearUi = { calls += "ui" },
        )

        assertFailsWith<CancellationException> { coordinator.clear() }
        assertEquals(listOf("hide-close"), calls)
        assertFalse(coordinator.canReselect)
    }

    @Test
    fun forwardingSessionSignalsOnlyAfterDelegateCloseAndHostNeverClosesRawSession() = runBlocking {
        val closeStarted = CompletableDeferred<Unit>()
        val allowClose = CompletableDeferred<Unit>()
        val session = FakeSession {
            closeStarted.complete(Unit)
            allowClose.await()
        }
        val editor = SessionTrackingEditor(object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath) = OpenSessionResult.Open(session)
        })
        val opened = assertIs<OpenSessionResult.Open>(editor.openSession(VideoSourcePath("/tmp/input.mp4")))

        val awaitingClose = async { editor.awaitActiveSessionClosed() }
        yield()
        assertFalse(awaitingClose.isCompleted)
        assertEquals(0, session.closeCalls)

        val screenClose = async { opened.session.close() }
        closeStarted.await()
        assertFalse(awaitingClose.isCompleted)
        allowClose.complete(Unit)
        screenClose.await()
        awaitingClose.await()
        opened.session.close()

        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `await active session close waits for a racing open and screen-owned close`() = runBlocking {
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
        val awaitingClose = async { editor.awaitActiveSessionClosed() }
        assertFalse(awaitingClose.isCompleted)
        allowOpen.complete(Unit)
        val opened = assertIs<OpenSessionResult.Open>(opening.await())
        yield()
        assertFalse(awaitingClose.isCompleted)
        opened.session.close()
        awaitingClose.await()

        assertEquals(1, session.closeCalls)
    }

    @Test
    fun `delegate close failure completes active close signal exceptionally`() = runBlocking {
        val session = FakeSession { error("close failed") }
        val editor = SessionTrackingEditor(object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath) = OpenSessionResult.Open(session)
        })
        val opened = assertIs<OpenSessionResult.Open>(editor.openSession(VideoSourcePath("/tmp/input.mp4")))

        assertEquals("close failed", assertFailsWith<IllegalStateException> { opened.session.close() }.message)
        assertEquals("close failed", assertFailsWith<IllegalStateException> { editor.awaitActiveSessionClosed() }.message)
        assertEquals(1, session.closeCalls)
    }

    private class FailingInputStream : java.io.InputStream() {
        override fun read(): Int = throw IllegalStateException("read failed")
    }

    private class FakeSession(
        private val onClose: suspend () -> Unit = {},
    ) : ClipEditorSession {
        var closeCalls = 0
        override val metadata = VideoMetadata(1.seconds, 1, 1, false)
        override fun frames(request: FrameStripRequest) = emptyFlow<FrameStripEvent>()
        override suspend fun createClip(range: ClipRange): ClipResult = error("not used")
        override suspend fun close() {
            closeCalls += 1
            onClose()
        }
    }
}
