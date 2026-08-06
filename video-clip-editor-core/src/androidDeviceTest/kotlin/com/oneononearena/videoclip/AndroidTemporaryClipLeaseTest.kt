package com.oneononearena.videoclip

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineExportRequest
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import com.oneononearena.videoclip.internal.engine.EngineFrameEvent
import com.oneononearena.videoclip.internal.engine.EngineFrameRequest
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec
import java.io.File
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTemporaryClipLeaseTest {
    @Test
    fun canonicalization_failure_at_session_creation_maps_to_typed_open_failure() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "video-editor-canonical-source-${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(1))
        }
        val store = AndroidOwnedTempFileStore(
            context,
            canonicalize = { throw IOException("canonicalization denied") },
        )
        try {
            val result = createAndroidVideoClipEditor(
                context,
                VideoClipEditorConfiguration(),
                ProbeOnlyEngine,
                store,
            ).openSession(VideoSourcePath(source.absolutePath))

            assertEquals(FailureCode.TEMP_CREATE_FAILED, (result as OpenSessionResult.Failed).failure.code)
        } finally {
            source.delete()
        }
    }

    @Test
    fun post_rename_identity_capture_failure_removes_only_the_new_library_output() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context, postRenameIdentityCapture = { _, _, _, _ -> null })
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        try {
            try {
                session.publish(destination)
                fail("Expected identity capture failure")
            } catch (_: TempStoreRenameException) {
                assertFalse(destination.partial.exists())
                assertFalse(destination.final.exists())
            }
        } finally {
            destination.partial.delete()
            destination.final.delete()
            session.close()
        }
    }

    @Test
    fun post_rename_identity_capture_never_removes_a_replacement_file() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val replacement = byteArrayOf(9, 9)
        val store = AndroidOwnedTempFileStore(
            context,
            postRenameIdentityCapture = { published, _, _, _ ->
                assertTrue(published.delete())
                published.writeBytes(replacement)
                null
            },
        )
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        try {
            try {
                session.publish(destination)
                fail("Expected identity capture failure")
            } catch (_: TempStoreRenameException) {
                assertTrue(destination.final.exists())
                assertTrue(destination.final.readBytes().contentEquals(replacement))
            }
        } finally {
            destination.partial.delete()
            destination.final.delete()
            session.close()
        }
    }

    @Test
    fun issued_store_lease_clears_only_its_owned_output_and_is_idempotent() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context)
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        val lease = session.publish(destination)
        val foreign = File(context.cacheDir, "video-editor-foreign-${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(2))
        }

        try {
            assertTrue(lease.file.absolutePath.startsWith(File(context.cacheDir, "video-clip-editor").absolutePath))
            assertEquals(TempDeleteResult.Cleared, lease.clearTemporaryFile())
            assertFalse(File(lease.file.absolutePath).exists())
            assertEquals(TempDeleteResult.AlreadyCleared, lease.clearTemporaryFile())
            assertTrue(session.clearIssuedLease(foreign, "not-issued") is TempDeleteResult.Failed)
            assertTrue(foreign.exists())
        } finally {
            foreign.delete()
            session.close()
        }
    }

    @Test
    fun issued_store_lease_refuses_terminal_symbolic_link_without_deleting_foreign_target() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context)
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        val lease = session.publish(destination)
        val target = File(context.cacheDir, "video-editor-target-${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(2))
        }
        val output = File(lease.file.absolutePath)

        try {
            assertTrue(output.delete())
            Os.symlink(target.absolutePath, output.absolutePath)

            assertTrue(lease.clearTemporaryFile() is TempDeleteResult.Failed)
            assertTrue(target.exists())
            assertTrue(output.exists())
        } finally {
            output.delete()
            target.delete()
            session.close()
        }
    }

    @Test
    fun issued_store_lease_remains_clearable_after_the_session_closes() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context)
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        val lease = session.publish(destination)

        session.close()

        assertEquals(TempDeleteResult.Cleared, lease.clearTemporaryFile())
        assertFalse(File(lease.file.absolutePath).exists())
    }

    @Test
    fun unissued_source_host_copy_foreign_file_and_directory_are_refused_without_deletion() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context)
        val session = store.createSession()
        val source = File(context.cacheDir, "video-editor-source-${System.nanoTime()}.mp4").apply { writeBytes(byteArrayOf(1)) }
        val hostCopy = File(context.filesDir, "video-editor-host-copy-${System.nanoTime()}.mp4").apply { writeBytes(byteArrayOf(2)) }
        val foreign = File.createTempFile("video-editor-foreign", ".mp4")
        val directory = File(context.cacheDir, "video-editor-directory-${System.nanoTime()}").apply { mkdir() }
        try {
            listOf(source, hostCopy, foreign, directory).forEach { target ->
                assertTrue(session.clearIssuedLease(target, "unissued") is TempDeleteResult.Failed)
                assertTrue(target.exists())
            }
        } finally {
            source.delete()
            hostCopy.delete()
            foreign.delete()
            directory.delete()
            session.close()
        }
    }

    @Test
    fun issued_store_lease_refuses_same_path_regular_file_replacement_without_deleting_replacement() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context)
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        val lease = session.publish(destination)
        val output = File(lease.file.absolutePath)
        try {
            assertTrue(output.delete())
            output.writeBytes(byteArrayOf(9, 9))

            assertTrue(lease.clearTemporaryFile() is TempDeleteResult.Failed)
            assertTrue(output.exists())
            assertEquals(2, output.length())
        } finally {
            output.delete()
            session.close()
        }
    }

    @Test
    fun issued_store_lease_refuses_same_path_directory_without_deleting_directory() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = AndroidOwnedTempFileStore(context)
        val session = store.createSession()
        val destination = session.createDestination()
        destination.partial.writeBytes(byteArrayOf(1))
        val lease = session.publish(destination)
        val output = File(lease.file.absolutePath)
        try {
            assertTrue(output.delete())
            assertTrue(output.mkdir())

            assertTrue(lease.clearTemporaryFile() is TempDeleteResult.Failed)
            assertTrue(output.isDirectory)
        } finally {
            output.delete()
            session.close()
        }
    }

    @Test
    fun failed_clear_does_not_make_concurrent_clear_report_already_cleared() = runTest {
        val firstClearEntered = CompletableDeferred<Unit>()
        val allowFirstClearToFail = CompletableDeferred<Unit>()
        var attempts = 0
        val lease = AndroidTemporaryClipLease(File("unused"), "lease-id") {
            attempts += 1
            if (attempts == 1) {
                firstClearEntered.complete(Unit)
                allowFirstClearToFail.await()
                TempDeleteResult.Failed(VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, null))
            } else {
                TempDeleteResult.Cleared
            }
        }

        val first = async { lease.clearTemporaryFile() }
        firstClearEntered.await()
        val second = async { lease.clearTemporaryFile() }
        yield()
        allowFirstClearToFail.complete(Unit)

        assertEquals(TempDeleteResult.Failed(VideoEditFailure(FailureCode.TEMP_DELETE_FAILED, true, null)), first.await())
        assertEquals(TempDeleteResult.Cleared, second.await())
        assertEquals(2, attempts)
    }

    private data object ProbeOnlyEngine : ClipMediaEngine {
        override suspend fun probe(source: EngineSource): EngineProbeResult = EngineProbeResult.Success(
            metadata = VideoMetadata(10_000.milliseconds, 640, 480, false),
            topology = EngineStreamTopology(EngineVideoCodec.AVC, null, false),
        )

        override fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent> = emptyFlow()

        override suspend fun export(request: EngineExportRequest): EngineExportResult = EngineExportResult.Failed(
            VideoEditFailure(FailureCode.EXPORT_FAILED, true, null),
        )

        override suspend fun cancelActiveExport() = Unit
    }
}
