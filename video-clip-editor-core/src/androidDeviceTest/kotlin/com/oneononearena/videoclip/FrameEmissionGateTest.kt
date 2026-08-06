package com.oneononearena.videoclip

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class FrameEmissionGateTest {
    @Test
    fun external_close_waits_for_admitted_worker_to_finish_after_rendezvous_handoff() = runTest {
        val gate = FrameEmissionGate()
        val handoffCommitted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowWorkerToFinish = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        val collector = launch {
            trackedRendezvousEvents(gate, handoffCommitted, cleanupStarted, allowWorkerToFinish).collect {
                received += it
            }
        }

        handoffCommitted.await()
        assertEquals(listOf(1), received)
        val closer = async { gate.close() }
        cleanupStarted.await()

        assertFalse(closer.isCompleted)

        allowWorkerToFinish.complete(Unit)
        closer.await()
        collector.join()
    }

    @Test
    fun worker_can_close_gate_without_waiting_for_itself() = runTest {
        val gate = FrameEmissionGate()
        var completed = false

        val worker = launch {
            assertTrue(gate.register(coroutineContext[kotlinx.coroutines.Job]!!))
            gate.close()
            completed = true
        }

        worker.join()

        assertTrue(completed)
    }

    @Test
    fun collector_can_close_channel_backed_events_without_receiving_another_event() = runTest {
        val gate = FrameEmissionGate()
        val received = mutableListOf<Int>()

        withTimeout(1.seconds) {
            gateEvents(gate).collect { event ->
                received += event
                gate.close()
            }
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

    private fun trackedRendezvousEvents(
        gate: FrameEmissionGate,
        handoffCommitted: CompletableDeferred<Unit>,
        cleanupStarted: CompletableDeferred<Unit>,
        allowWorkerToFinish: CompletableDeferred<Unit>,
    ): Flow<Int> = callbackFlow {
        val worker = launch {
            assertTrue(gate.register(coroutineContext[kotlinx.coroutines.Job]!!))
            try {
                send(1)
                handoffCommitted.complete(Unit)
                awaitCancellation()
            } finally {
                cleanupStarted.complete(Unit)
                withContext(NonCancellable) { allowWorkerToFinish.await() }
            }
        }
        worker.invokeOnCompletion { close() }
        awaitClose { worker.cancel() }
    }.buffer(0)
}
