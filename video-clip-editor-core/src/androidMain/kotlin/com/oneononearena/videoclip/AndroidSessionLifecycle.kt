package com.oneononearena.videoclip

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes session shutdown while providing a fast frame-admission fence. */
internal class AndroidSessionLifecycle {
    private val closeMutex = Mutex()

    @Volatile
    private var closed = false

    suspend fun close(block: suspend () -> Unit) {
        closeMutex.withLock {
            if (closed) return
            closed = true
            block()
        }
    }

    fun isClosed(): Boolean = closed
}
