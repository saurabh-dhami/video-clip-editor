package com.oneononearena.videoclip.internal.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ClipMediaContractsTest {
    @Test
    fun hevcTopologyIsRepresentableWithoutPlatformTypes() {
        val topology = EngineStreamTopology(
            videoCodec = EngineVideoCodec.HEVC,
            audioCodec = EngineAudioCodec.AAC,
            isHdr = false,
        )

        assertEquals(EngineVideoCodec.HEVC, topology.videoCodec)
    }
}
