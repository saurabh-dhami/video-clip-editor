package com.oneononearena.videoclip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.ui.compose.ContentFrame

@Composable
internal actual fun rememberPlatformPreviewPort(): PreviewPort {
    val context = LocalContext.current.applicationContext
    val port = remember(context) { AndroidMedia3PreviewPort(context) }
    return port
}

@Composable
internal actual fun rememberPlatformPreviewPortFactory(): PreviewPortFactory {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        object : PreviewPortFactory {
            override fun create(): PreviewPort = AndroidMedia3PreviewPort(context)
            override fun dispose(port: PreviewPort) {
                (port as? AndroidMedia3PreviewPort)?.dispose()
            }
        }
    }
}

@Composable
internal actual fun PlatformPreviewSurface(
    port: PreviewPort,
    modifier: Modifier,
) {
    val androidPort = resolveAndroidPreviewSurfacePort(port) ?: return
    val player = androidPort.playerForSurface ?: return
    ContentFrame(
        player = player,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

internal fun resolveAndroidPreviewSurfacePort(port: PreviewPort): AndroidMedia3PreviewPort? {
    if (port !is PreviewPortSurfaceDelegate) return port as? AndroidMedia3PreviewPort
    val candidate = port.surfacePort ?: return null
    if (candidate === port || candidate is PreviewPortSurfaceDelegate) return null
    return candidate as? AndroidMedia3PreviewPort
}
