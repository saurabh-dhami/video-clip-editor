package com.oneononearena.videoclip.compose

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneononearena.videoclip.OpenSessionResult
import com.oneononearena.videoclip.UnsupportedCode
import com.oneononearena.videoclip.VideoClipEditor
import com.oneononearena.videoclip.VideoSourcePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipEditorScreenLifecycleTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun terminalCompletionUsesLatestCallbackExactlyOnceOnMainDispatcher() = runComposeUiTest {
        val source = VideoSourcePath("/screen-lifecycle.mp4")
        val factory = ScreenLifecyclePortFactory()
        val callbackVersion = mutableStateOf(0)
        val visible = mutableStateOf(true)
        var oldCallbacks = 0
        var latestCallbacks = 0
        var callbackOnMain = false

        setContent {
            CompositionLocalProvider(LocalPreviewPortFactoryOverride provides factory) {
                if (visible.value) {
                    val version = callbackVersion.value
                    ClipEditorScreen(
                        source = source,
                        editor = UnsupportedEditor,
                        onResult = {},
                        onCancel = {},
                        onTerminalLifecycleComplete = {
                            callbackOnMain = Looper.myLooper() === Looper.getMainLooper()
                            if (version == 0) oldCallbacks++ else latestCallbacks++
                        },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { callbackVersion.value = 1 }
        waitForIdle()
        runOnIdle { visible.value = false }
        waitUntil("terminal callback") { latestCallbacks == 1 }

        assertEquals(0, oldCallbacks)
        assertEquals(1, latestCallbacks)
        assertTrue(callbackOnMain)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sourceReplacementReleasesPriorPortButEmitsZeroTerminalCompletion() = runComposeUiTest {
        val source = mutableStateOf(VideoSourcePath("/source-a.mp4"))
        val visible = mutableStateOf(true)
        val factory = ScreenLifecyclePortFactory()
        var completions = 0

        setContent {
            CompositionLocalProvider(LocalPreviewPortFactoryOverride provides factory) {
                if (visible.value) {
                    ClipEditorScreen(
                        source = source.value,
                        editor = UnsupportedEditor,
                        onResult = {},
                        onCancel = {},
                        onTerminalLifecycleComplete = { completions++ },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { source.value = VideoSourcePath("/source-b.mp4") }
        waitUntil("source replacement disposal") { factory.disposeCount == 1 }
        assertEquals(0, completions)

        runOnIdle { visible.value = false }
        waitUntil("terminal callback") { completions == 1 }
        assertEquals(1, completions)
        assertEquals(2, factory.disposeCount)
    }
}

private object UnsupportedEditor : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult =
        OpenSessionResult.Unsupported(UnsupportedCode.UNSUPPORTED_VIDEO_CODEC, null)
}

private class ScreenLifecyclePortFactory : PreviewPortFactory {
    var disposeCount = 0
    override fun create(): PreviewPort = ScreenLifecyclePort()
    override fun dispose(port: PreviewPort) {
        disposeCount++
    }
}

private class ScreenLifecyclePort : PreviewPort {
    private val mutableEvents = MutableSharedFlow<PreviewEvent>(extraBufferCapacity = 4)
    override val events: Flow<PreviewEvent> = mutableEvents

    override fun dispatch(command: PreviewCommand) {
        if (command is PreviewCommand.Release) {
            mutableEvents.tryEmit(PreviewEvent.Released(command.generation))
        }
    }
}
