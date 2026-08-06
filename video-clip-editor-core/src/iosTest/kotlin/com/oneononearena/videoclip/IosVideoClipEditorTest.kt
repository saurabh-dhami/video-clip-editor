package com.oneononearena.videoclip

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class IosVideoClipEditorTest {
    @Test
    fun platformFactoryReturnsTypedUnavailableResult() = runBlocking {
        val result = createIosVideoClipEditor().openSession(VideoSourcePath("/private/video.mp4"))

        assertIs<OpenSessionResult.Unsupported>(result)
        assertEquals(UnsupportedCode.IOS_ENGINE_UNAVAILABLE, result.code)
    }

    @Test
    fun legacyFacadeReturnsItsFrozenTypedUnavailableResult() {
        var result: IosOpenSessionResult? = null

        IosClipEditorFactory.create().openSession("/private/video.mp4") { result = it }

        val unavailable = assertIs<IosOpenSessionResult.IosEngineUnavailable>(result)
        assertEquals(IosOpenSessionCode.IOS_ENGINE_UNAVAILABLE, unavailable.code)
    }
}
