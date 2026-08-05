package com.oneononearena.videoclip

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameEmissionGateTest {
    @Test
    fun close_cancels_an_emission_admitted_before_its_event_starts() = runTest {
        val gate = FrameEmissionGate()
        val eventStarted = CompletableDeferred<Unit>()

        val emission = launch(start = CoroutineStart.UNDISPATCHED) {
            assertFalse(gate.emitIfOpen { eventStarted.complete(Unit) })
        }

        gate.close()
        emission.join()

        assertFalse(eventStarted.isCompleted)
    }
}
