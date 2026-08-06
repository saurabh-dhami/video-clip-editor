package com.oneononearena.videoclip

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidVideoClipEditorIntegrationTest {
    @Test
    fun factory_export_issues_then_clears_temporary_lease() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = copyFixture(context)
        try {
            val editor = createAndroidVideoClipEditor(context)
            val session = openSession(editor, source)
            val result = session.createClip(ClipRange(2_000.milliseconds, 7_000.milliseconds))
            assertTrue(result is ClipResult.Success)
            val success = result as ClipResult.Success

            assertTrue(success.output.file.absolutePath.startsWith(File(context.cacheDir, "video-clip-editor").absolutePath))
            assertTrue(File(success.output.file.absolutePath).isFile())
            assertEquals(TempDeleteResult.Cleared, success.output.clearTemporaryFile())
            assertFalse(File(success.output.file.absolutePath).exists())
            assertEquals(TempDeleteResult.AlreadyCleared, success.output.clearTemporaryFile())
            session.close()
        } finally {
            source.delete()
        }
    }

    @Test
    fun frames_called_after_close_emits_session_closed_terminal_event() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = copyFixture(context)
        try {
            val editor = createAndroidVideoClipEditor(context)
            val session = openSession(editor, source)
            session.close()

            assertEquals(
                listOf(FrameStripEvent.InvalidRequest(ValidationCode.SESSION_CLOSED, null)),
                session.frames(FrameStripRequest(1)).toList(),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun factory_rejects_sparse_input_larger_than_512_mib_before_media_probe() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "android-adapter-oversize-${System.nanoTime()}.mp4")
        try {
            RandomAccessFile(source, "rw").use { it.setLength(512L * 1024L * 1024L + 1L) }

            assertEquals(
                OpenSessionResult.Unsupported(UnsupportedCode.INPUT_TOO_LARGE, source.length().toString()),
                createAndroidVideoClipEditor(context).openSession(VideoSourcePath(source.absolutePath)),
            )
        } finally {
            source.delete()
        }
    }

    private fun copyFixture(context: android.content.Context): File {
        val target = File(context.cacheDir, "android-adapter-${System.nanoTime()}.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/avc-aac-10s-30fps.mp4").use { input ->
            target.outputStream().use(input::copyTo)
        }
        return target
    }

    private suspend fun openSession(editor: VideoClipEditor, source: File): ClipEditorSession {
        val result = editor.openSession(VideoSourcePath(source.absolutePath))
        assertTrue("Expected open session, got $result", result is OpenSessionResult.Open)
        return (result as OpenSessionResult.Open).session
    }
}
