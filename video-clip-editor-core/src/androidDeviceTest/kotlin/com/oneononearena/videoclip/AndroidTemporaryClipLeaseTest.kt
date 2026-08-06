package com.oneononearena.videoclip

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidTemporaryClipLeaseTest {
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
            assertEquals(TempDeleteResult.AlreadyCleared, session.clearIssuedLease(foreign, "not-issued"))
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
    fun clears_an_issued_regular_file_and_reports_idempotent_result() {
        val target = File.createTempFile("video-editor-lease", ".mp4")
        try {
            assertEquals(TempDeleteResult.Cleared, AndroidLeaseDeletionPolicy.clear(target))
            assertFalse(target.exists())
            assertEquals(TempDeleteResult.AlreadyCleared, AndroidLeaseDeletionPolicy.clear(target))
        } finally {
            target.delete()
        }
    }

    @Test
    fun rejects_terminal_symbolic_link_without_deleting_its_target() {
        val target = File.createTempFile("video-editor-target", ".mp4")
        val link = File(target.parentFile, "video-editor-link-${System.nanoTime()}.mp4")
        try {
            Os.symlink(target.absolutePath, link.absolutePath)

            val result = AndroidLeaseDeletionPolicy.clear(link)

            assertTrue(result is TempDeleteResult.Failed)
            assertTrue(target.exists())
            assertTrue(link.exists())
        } finally {
            link.delete()
            target.delete()
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
}
