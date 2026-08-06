package com.oneononearena.videoclip

import android.media.MediaFormat
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneononearena.videoclip.internal.engine.ClipMediaEngine
import com.oneononearena.videoclip.internal.engine.EngineAudioCodec
import com.oneononearena.videoclip.internal.engine.EngineExportRequest
import com.oneononearena.videoclip.internal.engine.EngineExportResult
import com.oneononearena.videoclip.internal.engine.EngineFrameEvent
import com.oneononearena.videoclip.internal.engine.EngineFrameRequest
import com.oneononearena.videoclip.internal.engine.EngineProbeResult
import com.oneononearena.videoclip.internal.engine.EngineSource
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSourcePolicyTest {
    @Test
    fun classifies_non_absolute_path_before_filesystem_access() {
        assertEquals(ValidationCode.PATH_NOT_ABSOLUTE, AndroidSourcePolicy.validate("relative.mp4", File("/tmp/editor")))
    }

    @Test
    fun rejects_source_under_temporary_root_including_sibling_like_names() {
        val root = File.createTempFile("video-editor-root", "").apply { delete(); mkdir() }
        val sibling = File(root.parentFile, "${root.name}-sibling.mp4")
        try {
            check(sibling.createNewFile())
            assertEquals(ValidationCode.SOURCE_INSIDE_TEMP_ROOT, AndroidSourcePolicy.validate("${root.absolutePath}/session/source.mp4", root))
            assertEquals(null, AndroidSourcePolicy.validate(sibling.absolutePath, root))
        } finally {
            sibling.delete()
            root.delete()
        }
    }

    @Test
    fun rejects_absent_and_directory_sources() {
        val root = File.createTempFile("video-editor-root", "").apply { delete(); mkdir() }
        val directory = File.createTempFile("video-editor-source", "").apply { delete(); mkdir() }
        try {
            assertEquals(ValidationCode.PATH_NOT_REGULAR_FILE, AndroidSourcePolicy.validate("${directory.parent}/missing.mp4", root))
            assertEquals(ValidationCode.PATH_NOT_REGULAR_FILE, AndroidSourcePolicy.validate(directory.absolutePath, root))
        } finally {
            directory.delete()
            root.delete()
        }
    }

    @Test
    fun mapsHevcAndAacMimeValuesToAnAdmittedTopology() {
        val video = MediaFormat.createVideoFormat(MimeTypes.VIDEO_H265, 640, 480)
        val audio = MediaFormat.createAudioFormat(MimeTypes.AUDIO_AAC, 48_000, 2)

        assertNull(
            AndroidSourceTopologyPolicy.validate(
                EngineStreamTopology(video.toEngineVideoCodec(), audio.toEngineAudioCodec(), isHdr = false),
            ),
        )
    }

    @Test
    fun injectedHdrSourceReturnsTypedUnsupportedResult() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "source-policy-${System.nanoTime()}.mp4").apply {
            writeBytes(byteArrayOf(0))
        }
        try {
            val editor = createAndroidVideoClipEditor(
                context,
                VideoClipEditorConfiguration(),
                TopologyProbeEngine(EngineStreamTopology(EngineVideoCodec.HEVC, EngineAudioCodec.AAC, isHdr = true)),
            )

            assertEquals(
                OpenSessionResult.Unsupported(UnsupportedCode.HDR_UNSUPPORTED, null),
                editor.openSession(VideoSourcePath(source.absolutePath)),
            )
        } finally {
            source.delete()
        }
    }

    private class TopologyProbeEngine(
        private val topology: EngineStreamTopology,
    ) : ClipMediaEngine {
        override suspend fun probe(source: EngineSource): EngineProbeResult = EngineProbeResult.Success(
            metadata = VideoMetadata(10_000.milliseconds, 640, 480, topology.audioCodec != null),
            topology = topology,
        )

        override fun frames(source: EngineSource, request: EngineFrameRequest): Flow<EngineFrameEvent> = emptyFlow()

        override suspend fun export(request: EngineExportRequest): EngineExportResult = EngineExportResult.Success

        override suspend fun cancelActiveExport() = Unit
    }
}
