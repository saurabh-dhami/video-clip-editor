package com.oneononearena.videoclip.compose

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class AndroidMedia3PreviewPort(
    context: Context,
) : PreviewPort {
    private val applicationContext = context.applicationContext
    private val mutableEvents = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var appliedBinding: PreviewBinding? = null
    private var pendingBinding: PreviewBinding? = null
    private var isPreparing = false
    private var readyRevision: PreviewRevision? = null
    private var ticker: Job? = null
    private var terminal = false

    internal var playerForSurface: Player? by mutableStateOf(null)
        private set

    override val events: Flow<PreviewEvent> = mutableEvents

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            requireMainThread()
            if (terminal || playbackState != Player.STATE_READY) return
            val binding = appliedBinding ?: return
            if (!isCurrentItem(binding)) return

            val pending = pendingBinding
            if (pending != null && pending.isLaterThan(binding)) {
                pendingBinding = null
                applyBinding(pending)
                return
            }

            isPreparing = false
            readyRevision = binding.revision
            mutableEvents.tryEmit(PreviewEvent.Ready(binding.generation, binding.revision))
            player?.playWhenReady = binding.playWhenReady
            emitPosition(binding)
            updateTicker()
        }

        override fun onPlayerError(error: PlaybackException) {
            requireMainThread()
            if (terminal) return
            val binding = appliedBinding ?: return
            if (!isCurrentItem(binding)) return

            val pending = pendingBinding
            if (pending != null && pending.isLaterThan(binding)) {
                pendingBinding = null
                releaseNativePlayer()
                applyBinding(pending)
                return
            }

            isPreparing = false
            readyRevision = null
            mutableEvents.tryEmit(
                PreviewEvent.RecoverableFailure(
                    generation = binding.generation,
                    revision = binding.revision,
                    diagnostic = error.errorCodeName.take(MAX_DIAGNOSTIC_LENGTH),
                ),
            )
            releaseNativePlayer()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            requireMainThread()
            if (terminal) return
            appliedBinding?.takeIf(::isCurrentItem)?.let(::emitPosition)
            updateTicker()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            requireMainThread()
            if (terminal) return
            appliedBinding?.takeIf(::isCurrentItem)?.let(::emitPosition)
        }
    }

    init {
        requireMainThread()
    }

    override fun dispatch(command: PreviewCommand) {
        requireMainThread()
        if (terminal) return

        when (command) {
            is PreviewCommand.Bind -> acceptBinding(command.binding)
            is PreviewCommand.ReplaceRange -> acceptBinding(command.binding)
            is PreviewCommand.Retry -> acceptBinding(command.binding)
            is PreviewCommand.Seek -> seek(command)
            is PreviewCommand.SetPlayWhenReady -> setPlayWhenReady(command)
            is PreviewCommand.Release -> release(command.generation)
        }
    }

    internal fun dispose() {
        requireMainThread()
        if (terminal) return
        terminal = true
        pendingBinding = null
        appliedBinding = null
        readyRevision = null
        releaseNativePlayer()
        scope.cancel()
    }

    private fun acceptBinding(binding: PreviewBinding) {
        if (!binding.isLaterThan(appliedBinding) && binding != appliedBinding) return

        if (isPreparing) {
            val latest = pendingBinding
            if (latest == null || binding.isLaterThan(latest)) {
                pendingBinding = binding
            }
            player?.playWhenReady = false
            updateTicker()
            return
        }

        applyBinding(binding)
    }

    private fun applyBinding(binding: PreviewBinding) {
        val player = ensurePlayer()
        val clipPosition = (binding.sourcePosition - binding.range.start)
            .coerceIn(Duration.ZERO, binding.range.endExclusive - binding.range.start)
        val item = MediaItem.Builder()
            .setMediaId(binding.mediaId())
            .setUri(Uri.fromFile(File(binding.source.value)))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(binding.range.start.inWholeMilliseconds)
                    .setEndPositionMs(binding.range.endExclusive.inWholeMilliseconds)
                    .build(),
            )
            .build()

        ticker?.cancel()
        ticker = null
        player.playWhenReady = false
        player.repeatMode = Player.REPEAT_MODE_ONE
        appliedBinding = binding
        pendingBinding = null
        readyRevision = null
        isPreparing = true
        player.setMediaItem(item, clipPosition.inWholeMilliseconds)
        player.prepare()
    }

    private fun seek(command: PreviewCommand.Seek) {
        val binding = matchingReadyBinding(command.generation, command.revision) ?: return
        val bounded = command.sourcePosition.coerceIn(binding.range.start, binding.range.endExclusive)
        player?.seekTo((bounded - binding.range.start).inWholeMilliseconds)
        emitPosition(binding)
    }

    private fun setPlayWhenReady(command: PreviewCommand.SetPlayWhenReady) {
        val binding = matchingReadyBinding(command.generation, command.revision) ?: return
        val bindingWithIntent = binding.copy(playWhenReady = command.value)
        appliedBinding = bindingWithIntent
        player?.playWhenReady = command.value
        emitPosition(bindingWithIntent)
        updateTicker()
    }

    private fun matchingReadyBinding(
        generation: PreviewGeneration,
        revision: PreviewRevision,
    ): PreviewBinding? = appliedBinding?.takeIf { binding ->
        binding.generation == generation &&
            binding.revision == revision &&
            readyRevision == revision &&
            isCurrentItem(binding)
    }

    private fun release(generation: PreviewGeneration) {
        terminal = true
        pendingBinding = null
        appliedBinding = null
        readyRevision = null
        releaseNativePlayer()
        scope.cancel()
        mutableEvents.tryEmit(PreviewEvent.Released(generation))
    }

    private fun ensurePlayer(): ExoPlayer {
        requireMainThread()
        player?.let { return it }
        return ExoPlayer.Builder(applicationContext).build().also { created ->
            created.repeatMode = Player.REPEAT_MODE_ONE
            created.addListener(listener)
            player = created
            playerForSurface = created
        }
    }

    private fun releaseNativePlayer() {
        requireMainThread()
        ticker?.cancel()
        ticker = null
        player?.let { current ->
            current.removeListener(listener)
            current.clearVideoSurface()
            current.clearMediaItems()
            current.release()
        }
        player = null
        playerForSurface = null
    }

    private fun updateTicker() {
        ticker?.cancel()
        ticker = null
        val player = player ?: return
        val binding = appliedBinding ?: return
        if (!player.isPlaying || !isCurrentItem(binding)) return
        ticker = scope.launch {
            while (isActive && player.isPlaying && isCurrentItem(binding)) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                emitPosition(binding)
            }
        }
    }

    private fun emitPosition(binding: PreviewBinding) {
        val current = player ?: return
        if (terminal || !isCurrentItem(binding)) return
        val sourcePosition = (binding.range.start + current.currentPosition.milliseconds)
            .coerceIn(binding.range.start, binding.range.endExclusive)
        mutableEvents.tryEmit(
            PreviewEvent.Position(
                generation = binding.generation,
                revision = binding.revision,
                sourcePosition = sourcePosition,
                isPlaying = current.isPlaying,
            ),
        )
    }

    private fun isCurrentItem(binding: PreviewBinding): Boolean =
        player?.currentMediaItem?.mediaId == binding.mediaId()

    private fun requireMainThread() {
        check(Looper.myLooper() === Looper.getMainLooper()) {
            "Android preview must run on the main thread"
        }
    }

    private fun PreviewBinding.mediaId(): String = "${generation.value}:${revision.value}"

    private fun PreviewBinding.isLaterThan(other: PreviewBinding?): Boolean = when {
        other == null -> true
        generation.value != other.generation.value -> generation.value > other.generation.value
        else -> revision.value > other.revision.value
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 64
        const val MAX_DIAGNOSTIC_LENGTH = 160
        const val POSITION_UPDATE_INTERVAL_MS = 100L
    }
}
