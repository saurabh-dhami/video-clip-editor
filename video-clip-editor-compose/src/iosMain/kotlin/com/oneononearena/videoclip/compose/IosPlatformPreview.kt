package com.oneononearena.videoclip.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

@Composable
internal actual fun rememberPlatformPreviewPort(): PreviewPort = remember { IosUnavailablePreviewPort() }

@Composable
internal actual fun rememberPlatformPreviewPortFactory(): PreviewPortFactory = remember {
    object : PreviewPortFactory {
        override fun create(): PreviewPort = IosUnavailablePreviewPort()
        override fun dispose(port: PreviewPort) = Unit
    }
}

@Composable
internal actual fun PlatformPreviewSurface(
    port: PreviewPort,
    modifier: Modifier,
) {
    Box(modifier)
}

private class IosUnavailablePreviewPort : PreviewPort {
    private val mutableEvents = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = 8)
    private var terminal = false

    override val events: Flow<PreviewEvent> = mutableEvents

    override fun dispatch(command: PreviewCommand) {
        if (terminal) return
        when (command) {
            is PreviewCommand.Bind -> unavailable(command.binding)
            is PreviewCommand.ReplaceRange -> unavailable(command.binding)
            is PreviewCommand.Retry -> unavailable(command.binding)
            is PreviewCommand.Release -> {
                terminal = true
                mutableEvents.tryEmit(PreviewEvent.Released(command.generation))
            }
            is PreviewCommand.Seek,
            is PreviewCommand.SetPlayWhenReady,
            -> Unit
        }
    }

    private fun unavailable(binding: PreviewBinding) {
        mutableEvents.tryEmit(
            PreviewEvent.RecoverableFailure(
                generation = binding.generation,
                revision = binding.revision,
                diagnostic = IOS_PREVIEW_UNAVAILABLE,
            ),
        )
    }

    private companion object {
        const val IOS_PREVIEW_UNAVAILABLE = "Preview unavailable on iOS"
    }
}
