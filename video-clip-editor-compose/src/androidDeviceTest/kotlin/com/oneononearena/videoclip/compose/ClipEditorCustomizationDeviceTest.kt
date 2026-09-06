package com.oneononearena.videoclip.compose

import android.content.Intent
import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.WindowRecomposerFactory
import androidx.compose.ui.platform.WindowRecomposerPolicy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runEmptyComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.oneononearena.videoclip.VideoSourcePath
import com.oneononearena.videoclip.createAndroidVideoClipEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Runs in the test APK: never drives the demo picker or reads host media. */
class ClipEditorCustomizationDeviceTest {
    // Catches ignored host colours, theme-triggered session resets, disconnected Reset,
    // gaps/scrolling in the overview, and controls falling outside landscape bounds.
    @OptIn(ExperimentalTestApi::class, InternalComposeUiApi::class)
    @Test
    fun customizedEditorPreservesRangeAndResetsAcrossResponsiveLayouts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtures = PreviewFixtureFiles(context)
        val source = fixtures.copyAvcFixture()
        val style = mutableStateOf(ClipEditorStyle())
        val visible = mutableStateOf(true)
        val closed = AtomicBoolean(false)
        val scenario = ActivityScenario.launch<Activity>(
            Intent.makeMainActivity(ComponentName(context, "androidx.activity.ComponentActivity")),
        )
        var view: ComposeView? = null
        try {
            runEmptyComposeUiTest {
                scenario.onActivity { activity ->
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    // Keep window chrome outside pixel assertions. The test capture API
                    // otherwise samples ActionBar/system-bar pixels at Compose coordinates.
                    activity.actionBar?.hide()
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    WindowRecomposerPolicy.withFactory(WindowRecomposerFactory.LifecycleAware) {
                        view = ComposeView(activity).also { content ->
                            activity.setContentView(content)
                            content.setContent {
                                val editor = remember(activity) { createAndroidVideoClipEditor(activity) }
                                if (visible.value) ClipEditorScreen(
                                    source = VideoSourcePath(source.absolutePath), editor = editor,
                                    style = style.value,
                                    options = ClipEditorOptions(labels = ClipEditorLabels(useClip = "Attach clip")),
                                    onResult = {}, onCancel = {},
                                    onTerminalLifecycleComplete = { closed.set(true) },
                                )
                            }
                        }
                    }
                }
                waitUntil(timeoutMillis = 15_000) {
                    runCatching { onNodeWithTag("play-pause").assertIsEnabled() }.isSuccess
                }
                onNodeWithText("Attach clip").assertIsDisplayed()
                val initialEnd = onNodeWithTag("clip-end-handle").getUnclippedBoundsInRoot().left
                val portraitPreview = onNodeWithTag("clip-preview").getUnclippedBoundsInRoot()
                assertTrue("Preview must dominate portrait layout", portraitPreview.bottom - portraitPreview.top > 300.dp)

                onNodeWithTag("clip-end-handle").performTouchInput {
                    down(center); moveBy(Offset(-160f, 0f)); up()
                }
                waitForIdle()
                val trimmedEnd = onNodeWithTag("clip-end-handle").getUnclippedBoundsInRoot().left
                assertTrue(trimmedEnd < initialEnd)

                // Sample real rendered surfaces, not configuration getters.
                for (palette in listOf(
                    ClipEditorStyle(primaryButtonColor = Color(0xFF90CAF9), handleColor = Color(0xFF90CAF9)),
                    ClipEditorStyle.Light,
                )) {
                    runOnIdle { style.value = palette }
                    waitForIdle()
                    val buttonImage = onNodeWithTag("done").captureToImage()
                    context.openFileOutput("customization-button.png", 0).use {
                        buttonImage.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    context.openFileOutput("customization-screen.png", 0).use {
                        onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    val pixels = buttonImage.toPixelMap()
                    assertEquals(palette.primaryButtonColor, pixels[pixels.width / 2, pixels.height / 4])
                    assertEquals(trimmedEnd, onNodeWithTag("clip-end-handle").getUnclippedBoundsInRoot().left)
                    onNodeWithTag("play-pause").assertIsEnabled()
                }

                onNodeWithTag("clip-reset").performClick()
                waitForIdle()
                assertEquals(initialEnd, onNodeWithTag("clip-end-handle").getUnclippedBoundsInRoot().left)
                onNodeWithTag("clip-start-time").assertTextContains("00:00.000")
                onNodeWithTag("clip-start-handle").performTouchInput {
                    down(center); moveBy(Offset(80f, 0f)); up()
                }
                waitForIdle()
                assertTrue(onNodeWithTag("clip-start-handle").getUnclippedBoundsInRoot().left > 16.dp)
                onNodeWithTag("clip-reset").performClick()
                waitForIdle()

                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitUntil(timeoutMillis = 15_000) {
                    val preview = onNodeWithTag("clip-preview").getUnclippedBoundsInRoot()
                    val strip = onNodeWithTag("clip-timeline").getUnclippedBoundsInRoot()
                    strip.left >= preview.right
                }
                onNodeWithTag("done").assertIsDisplayed()
                onNodeWithTag("clip-reset").assertIsDisplayed()
                onNodeWithTag("clip-start-time").assertIsDisplayed()
                onNodeWithTag("clip-end-time").assertIsDisplayed()
                val timeline = onNodeWithTag("clip-timeline").getUnclippedBoundsInRoot()
                val first = onNodeWithTag("clip-thumbnail-0").getUnclippedBoundsInRoot()
                val second = onNodeWithTag("clip-thumbnail-1").getUnclippedBoundsInRoot()
                assertEquals(first.right, second.left)
                assertEquals(timeline.left, first.left)
                onNodeWithTag("clip-timeline").assert(hasScrollAction().not())
                runOnIdle { visible.value = false }
                waitUntil(timeoutMillis = 15_000) { closed.get() }
            }
        } finally {
            scenario.onActivity { view?.disposeComposition() }
            scenario.close()
            fixtures.deleteCopies()
        }
    }
}
