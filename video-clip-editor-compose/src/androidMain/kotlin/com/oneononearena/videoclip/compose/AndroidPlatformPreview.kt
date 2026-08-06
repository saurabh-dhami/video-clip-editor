package com.oneononearena.videoclip.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.ui.compose.ContentFrame

@Composable
internal actual fun rememberPlatformPreviewPort(): PreviewPort {
    val context = LocalContext.current.applicationContext
    val port = remember(context) { AndroidMedia3PreviewPort(context) }
    DisposableEffect(port) {
        onDispose { port.dispose() }
    }
    return port
}

@Composable
internal actual fun PlatformPreviewSurface(
    port: PreviewPort,
    modifier: Modifier,
) {
    val androidPort = port as? AndroidMedia3PreviewPort ?: return
    val player = androidPort.playerForSurface ?: return
    ContentFrame(
        player = player,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}
