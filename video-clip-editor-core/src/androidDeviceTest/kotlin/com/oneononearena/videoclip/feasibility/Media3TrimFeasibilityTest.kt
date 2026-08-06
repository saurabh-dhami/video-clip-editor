package com.oneononearena.videoclip.feasibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Media3TrimFeasibilityTest {
    @Test
    fun scanner_finds_child_inside_nonzero_offset_64bit_size_container() {
        val file = File.createTempFile("largesize", ".mp4")
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.writeInt(8)
                output.writeBytes("ftyp")
                output.writeInt(1)
                output.writeBytes("moov")
                output.writeLong(24)
                output.writeInt(8)
                output.writeBytes("edts")
            }

            assertTrue(Mp4BoxScanner(file).contains("edts"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun scanner_rejects_64bit_size_smaller_than_its_header() {
        val file = File.createTempFile("largesize-invalid", ".mp4")
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.writeInt(1)
                output.writeBytes("moov")
                output.writeLong(8)
            }

            assertFalse(Mp4BoxScanner(file).contains("edts"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun scanner_reads_empty_and_zero_based_edit_list_media_times() {
        val file = File.createTempFile("edit-list", ".mp4")
        try {
            RandomAccessFile(file, "rw").use { output ->
                output.writeInt(56)
                output.writeBytes("moov")
                output.writeInt(48)
                output.writeBytes("edts")
                output.writeInt(40)
                output.writeBytes("elst")
                output.writeInt(0) // version and flags
                output.writeInt(2)
                output.writeInt(50)
                output.writeInt(-1)
                output.writeInt(0x0001_0000)
                output.writeInt(5_000)
                output.writeInt(0)
                output.writeInt(0x0001_0000)
            }

            assertEquals(listOf(-1L, 0L), Mp4BoxScanner(file).editListMediaTimes())
        } finally {
            file.delete()
        }
    }

    @Test
    fun trim_has_no_edit_list_and_is_time_aligned() = runTest {
        val result = exportFixture(startMs = 2_000, endMs = 7_000)
        val hasEdts = Mp4BoxScanner(result.file).contains("edts")
        val hasElst = Mp4BoxScanner(result.file).contains("elst")
        val editListMediaTimes = Mp4BoxScanner(result.file).editListMediaTimes()
        val mediaProbe = probe(result.file)

        assertTrue(
            "Trim must not use an edit list to skip source media: edts=$hasEdts, elst=$hasElst, mediaTimes=$editListMediaTimes, probe=$mediaProbe",
            editListMediaTimes.all { it == -1L || it == 0L },
        )
        assertEquals(0L, mediaProbe.firstPresentationTimeUs)
        assertTrue(abs(mediaProbe.durationMs - 5_000) <= 50)
        assertTrue(mediaProbe.audioVideoStartSkewMs <= 50)
    }

    @Test
    fun cancellation_removes_partial_file() = runTest {
        val operation = startExportFixture(startMs = 2_000, endMs = 7_000)

        operation.cancel()

        operation.awaitCancellationQuiescence()
        assertFalse(operation.partialFile.exists())
        assertFalse(operation.finalFile.exists())
    }
}
