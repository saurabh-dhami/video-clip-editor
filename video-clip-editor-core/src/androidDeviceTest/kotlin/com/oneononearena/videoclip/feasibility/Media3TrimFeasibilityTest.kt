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
    fun trim_has_no_edit_list_and_is_time_aligned() = runTest {
        val result = exportFixture(startMs = 2_000, endMs = 7_000)

        assertFalse(Mp4BoxScanner(result.file).contains("edts") || Mp4BoxScanner(result.file).contains("elst"))
        assertEquals(0L, probe(result.file).firstPresentationTimeUs)
        assertTrue(abs(probe(result.file).durationMs - 5_000) <= 50)
        assertTrue(probe(result.file).audioVideoStartSkewMs <= 50)
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
