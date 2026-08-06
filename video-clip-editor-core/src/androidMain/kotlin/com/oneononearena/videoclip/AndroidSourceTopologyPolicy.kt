package com.oneononearena.videoclip

import com.oneononearena.videoclip.internal.engine.EngineAudioCodec
import com.oneononearena.videoclip.internal.engine.EngineStreamTopology
import com.oneononearena.videoclip.internal.engine.EngineVideoCodec

internal object AndroidSourceTopologyPolicy {
    fun validate(topology: EngineStreamTopology): UnsupportedCode? = when {
        topology.isHdr -> UnsupportedCode.HDR_UNSUPPORTED
        topology.videoCodec != EngineVideoCodec.AVC && topology.videoCodec != EngineVideoCodec.HEVC ->
            UnsupportedCode.UNSUPPORTED_VIDEO_CODEC
        topology.audioCodec != null && topology.audioCodec != EngineAudioCodec.AAC ->
            UnsupportedCode.UNSUPPORTED_AUDIO_CODEC
        else -> null
    }
}
