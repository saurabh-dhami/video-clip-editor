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
}
