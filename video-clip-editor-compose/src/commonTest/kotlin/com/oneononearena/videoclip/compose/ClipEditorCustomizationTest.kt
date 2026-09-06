package com.oneononearena.videoclip.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.Text
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import com.oneononearena.videoclip.*
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ClipEditorCustomizationTest {
    @OptIn(ExperimentalTestApi::class)
    @Test fun hostProgressSlotReceivesActualThumbnailFraction() = runComposeUiTest {
        val editor = object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath) = OpenSessionResult.Open(object : ClipEditorSession {
                override val metadata = VideoMetadata(15.seconds, 100, 100, false)
                override fun frames(request: FrameStripRequest) = flow {
                    emit(FrameStripEvent.Progress(3, 12))
                    awaitCancellation()
                }
                override suspend fun createClip(range: ClipRange): ClipResult = error("Not exported")
                override suspend fun close() = Unit
            })
        }
        setContent {
            ClipEditorScreen(VideoSourcePath("/host.mp4"), editor, {}, {},
                progressContent = { progress -> Text("${progress.stage}:${progress.fraction}") })
        }
        waitForIdle()
        onNodeWithText("LoadingThumbnails:0.25").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun defaultProgressHasAccessibleIndicatorAndHostButtonTypography() = runComposeUiTest {
        setContent {
            androidx.compose.foundation.layout.Column {
                ClipEditorProgressView(ClipEditorProgress(ClipEditorProgressStage.ExportingClip, null, "Finishing"), ClipEditorStyle())
                EditorButton("Host action", {}, ClipEditorStyle(typography = ClipEditorTypography(button = TextStyle(fontSize = 24.sp))))
            }
        }
        onNodeWithTag("clip-progress").assertIsDisplayed()
        onNodeWithText("Finishing").assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        onNodeWithText("Host action").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(24.sp, layouts.single().layoutInput.style.fontSize)
        assertEquals("00:05", formatRulerTime(5179.milliseconds))
    }
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun composeHarnessExecutesUiAssertions() {
        var executed = false
        runComposeUiTest { executed = true }
        assertTrue(executed, "UI test bodies must execute on this target; compilation alone is not UI proof")
    }

    @Test
    fun invalidHostDimensionsCannotCollapseControlsOrProduceInvalidLayout() {
        val style = ClipEditorStyle(thumbnailHeight = (-10).dp, cornerRadius = Dp.Unspecified, selectionBorderWidth = 100.dp)
        assertEquals(48.dp, style.safeThumbnailHeight)
        assertEquals(12.dp, style.safeCornerRadius)
        assertEquals(4.dp, style.safeSelectionBorderWidth)
    }

    @Test
    fun overviewSamplesAcrossEntireSourceWithoutSqueezingEveryFrame() {
        assertEquals(6, overviewSlotCount(320f))
        assertEquals(8, overviewSlotCount(400f))
        assertEquals(10, overviewSlotCount(1200f))
        assertEquals(listOf(0, 3, 7, 10, 13, 16, 20, 23), overviewFrameIndices(24, 8))
        assertEquals(emptyList(), overviewFrameIndices(0, 8))
        assertTrue(overviewFrameIndices(2, 8).all { it in 0..1 })
    }

    @Test
    fun timeLabelsRemainReadableAcrossMinuteAndHourBoundaries() {
        assertEquals("00:02.400", formatClipTime(2400.milliseconds))
        assertEquals("01:00.000", formatClipTime(60.seconds))
        assertEquals("01:01:01.001", formatClipTime(3661001.milliseconds))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun actualRenderedButtonUsesHostColourAndAction() = runComposeUiTest {
        var used = false
        setContent {
            EditorButton("Attach", { used = true }, ClipEditorStyle(primaryButtonColor = Color.Magenta),
                modifier = Modifier.size(120.dp, 48.dp), primary = true)
        }
        val button = onNodeWithText("Attach")
        val pixels = button.captureToImage().toPixelMap()
        assertEquals(Color.Magenta, pixels[pixels.width / 2, 4])
        button.performClick()
        assertTrue(used)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun hostLabelsAndOptionsUpdateWithoutReopeningTheSession() = runComposeUiTest {
        var opens = 0
        val options = mutableStateOf(ClipEditorOptions(labels = ClipEditorLabels(title = "Cut recording", useClip = "Attach")))
        val style = mutableStateOf(ClipEditorStyle())
        val editor = object : VideoClipEditor {
            override suspend fun openSession(source: VideoSourcePath): OpenSessionResult {
                opens++
                return OpenSessionResult.Open(object : ClipEditorSession {
                    override val metadata = VideoMetadata(15.seconds, 1080, 1920, true)
                    override fun frames(request: FrameStripRequest) = flowOf(FrameStripEvent.Complete)
                    override suspend fun createClip(range: ClipRange): ClipResult = error("Not exported by this test")
                    override suspend fun close() = Unit
                })
            }
        }
        setContent {
            ClipEditorScreen(
                source = VideoSourcePath("/customization.mp4"), editor = editor,
                onResult = {}, onCancel = {}, style = style.value, options = options.value,
                modifier = Modifier.size(360.dp, 720.dp),
            )
        }
        waitForIdle()
        onNodeWithText("Cut recording").assertIsDisplayed()
        onNodeWithText("Attach").assertIsDisplayed()
        onNodeWithTag("clip-start-time").assertIsDisplayed()
        val play = onNodeWithTag("play-pause").getUnclippedBoundsInRoot()
        val reset = onNodeWithTag("clip-reset").getUnclippedBoundsInRoot()
        assertTrue(reset.left - play.right >= 12.dp || reset.top >= play.bottom)
        runOnIdle {
            style.value = ClipEditorStyle(primaryButtonColor = Color.Magenta)
            options.value = options.value.copy(showTimestamps = false, showSelectedDuration = false, showReset = false)
        }
        waitForIdle()
        onNodeWithTag("clip-start-time").assertDoesNotExist()
        onNodeWithTag("clip-selected-duration").assertDoesNotExist()
        onNodeWithTag("clip-reset").assertDoesNotExist()
        assertEquals(1, opens)
    }
}
