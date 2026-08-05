package com.oneononearena.videoclip

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameEmissionGateTest {
    @Test
    fun collector_can_close_channel_backed_events_without_receiving_another_event() = runTest {
        val gate = FrameEmissionGate()
        val received = mutableListOf<Int>()

        gateEvents(gate).collect { event ->
            received += event
            gate.close()
        }

        assertEquals(listOf(1), received)
    }

    private fun gateEvents(gate: FrameEmissionGate): Flow<Int> = callbackFlow {
        val worker = launch {
            if (!gate.register(coroutineContext[kotlinx.coroutines.Job]!!)) return@launch
            send(1)
            send(2)
        }
        worker.invokeOnCompletion { close() }
        awaitClose { worker.cancel() }
    }.buffer(0)
}
