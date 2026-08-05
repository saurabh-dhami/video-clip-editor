package com.oneononearena.videoclip.feasibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class Media3TrimFeasibilityTest {
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

        assertTrue(operation.awaitTerminal())
        assertFalse(operation.partialFile.exists())
        assertFalse(operation.finalFile.exists())
    }
}
