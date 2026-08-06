package com.oneononearena.videoclip

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
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
