package com.oneononearena.videoclip

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.oneononearena.videoclip.internal.engine.EngineAudioCodec
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSourceTopologyPolicyTest {
    @Test
    fun acceptsSdrHevcWithAac() {
        assertNull(
            AndroidSourceTopologyPolicy.validate(
                EngineStreamTopology(EngineVideoCodec.HEVC, EngineAudioCodec.AAC, isHdr = false),
            ),
        )
    }

    @Test
    fun acceptsSdrAvcWithoutAudio() {
        assertNull(
            AndroidSourceTopologyPolicy.validate(
                EngineStreamTopology(EngineVideoCodec.AVC, audioCodec = null, isHdr = false),
            ),
        )
    }

    @Test
    fun rejectsHdrBeforeCodecSelection() {
        assertEquals(
            UnsupportedCode.HDR_UNSUPPORTED,
            AndroidSourceTopologyPolicy.validate(
                EngineStreamTopology(EngineVideoCodec.HEVC, EngineAudioCodec.AAC, isHdr = true),
            ),
        )
    }

    @Test
    fun rejectsUnsupportedVideoCodec() {
        assertEquals(
            UnsupportedCode.UNSUPPORTED_VIDEO_CODEC,
            AndroidSourceTopologyPolicy.validate(
                EngineStreamTopology(EngineVideoCodec.OTHER, EngineAudioCodec.AAC, isHdr = false),
            ),
        )
    }

    @Test
    fun rejectsUnsupportedAudioCodec() {
        assertEquals(
            UnsupportedCode.UNSUPPORTED_AUDIO_CODEC,
            AndroidSourceTopologyPolicy.validate(
                EngineStreamTopology(EngineVideoCodec.HEVC, EngineAudioCodec.OTHER, isHdr = false),
            ),
        )
    }
}
