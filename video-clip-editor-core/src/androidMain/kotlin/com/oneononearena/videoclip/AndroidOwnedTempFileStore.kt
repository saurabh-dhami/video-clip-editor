package com.oneononearena.videoclip

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Owns only files created below the library's private cache directory. Callers can receive an
 * opaque lease, but cannot nominate an arbitrary path for deletion.
 */
internal class AndroidOwnedTempFileStore(context: Context) {
    internal val root = File(context.cacheDir, ROOT_DIRECTORY_NAME)

    private val lock = Any()
    private val sessions = mutableMapOf<String, SessionState>()

    fun createSession(): AndroidOwnedTempSession = synchronized(lock) {
        if (!root.isDirectory && !root.mkdirs()) throw TempStoreCreateException(root)
        val sessionRoot = File(root, UUID.randomUUID().toString())
        if (!sessionRoot.mkdir()) throw TempStoreCreateException(sessionRoot)
        val state = SessionState(sessionRoot)
        sessions[sessionRoot.absolutePath] = state
        AndroidOwnedTempSession(this, state)
    }

    private fun createDestination(state: SessionState): AndroidOwnedExportDestination = synchronized(lock) {
        check(!state.closed) { "Temporary session already closed" }
        val id = UUID.randomUUID().toString()
        val destination = AndroidOwnedExportDestination(
            partial = File(state.root, "$id.partial"),
            final = File(state.root, "$id.mp4"),
        )
        state.pending[destination.partial.absolutePath] = destination
        destination
    }

    private fun publish(state: SessionState, destination: AndroidOwnedExportDestination): AndroidTemporaryClipLease = synchronized(lock) {
        check(state.pending.remove(destination.partial.absolutePath) === destination) {
            "Temporary export destination was not issued by this session"
        }
        if (destination.final.exists() || !destination.partial.renameTo(destination.final)) {
            destination.partial.delete()
            throw TempStoreRenameException(destination.final)
        }
        val opaqueId = UUID.randomUUID().toString()
        state.issued[destination.final.absolutePath] = opaqueId
        AndroidTemporaryClipLease(destination.final, opaqueId) {
            clearIssuedLease(state, destination.final, opaqueId)
        }
    }

    private fun discard(state: SessionState, destination: AndroidOwnedExportDestination) = synchronized(lock) {
        if (state.pending.remove(destination.partial.absolutePath) === destination) {
            destination.partial.delete()
        }
        deleteSessionIfEmpty(state)
    }

    private fun clearIssuedLease(state: SessionState, target: File, opaqueId: String): TempDeleteResult = synchronized(lock) {
        if (state.issued[target.absolutePath] != opaqueId) return@synchronized TempDeleteResult.AlreadyCleared
        AndroidLeaseDeletionPolicy.clear(target).also { result ->
            if (result is TempDeleteResult.Cleared || result is TempDeleteResult.AlreadyCleared) {
                state.issued.remove(target.absolutePath)
                deleteSessionIfEmpty(state)
            }
        }
    }

    private fun close(state: SessionState) = synchronized(lock) {
        if (state.closed) return@synchronized
        state.closed = true
        state.pending.values.forEach { it.partial.delete() }
        state.pending.clear()
        state.root.listFiles()?.forEach { child ->
            if (child.absolutePath !in state.issued) child.delete()
        }
        deleteSessionIfEmpty(state)
    }

    private fun deleteSessionIfEmpty(state: SessionState) {
        if (state.issued.isEmpty() && state.pending.isEmpty() && state.root.listFiles().isNullOrEmpty()) {
            state.root.delete()
            sessions.remove(state.root.absolutePath)
        }
    }

    internal class AndroidOwnedTempSession internal constructor(
        private val store: AndroidOwnedTempFileStore,
        private val state: SessionState,
    ) {
        fun createDestination(): AndroidOwnedExportDestination = store.createDestination(state)

        fun publish(destination: AndroidOwnedExportDestination): AndroidTemporaryClipLease = store.publish(state, destination)

        fun discard(destination: AndroidOwnedExportDestination) = store.discard(state, destination)

        fun clearIssuedLease(target: File, opaqueId: String): TempDeleteResult =
            store.clearIssuedLease(state, target, opaqueId)

        fun close() = store.close(state)
    }

    internal class SessionState(
        val root: File,
        val pending: MutableMap<String, AndroidOwnedExportDestination> = mutableMapOf(),
        val issued: MutableMap<String, String> = mutableMapOf(),
        var closed: Boolean = false,
    )

    private companion object {
        const val ROOT_DIRECTORY_NAME = "video-clip-editor"
    }
}

internal data class AndroidOwnedExportDestination(
    val partial: File,
    internal val final: File,
)

internal class TempStoreCreateException(val target: File) : Exception(target.absolutePath)

internal class TempStoreRenameException(val target: File) : Exception(target.absolutePath)
