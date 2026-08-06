package com.oneononearena.videoclip.compose

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class PreviewFixtureFiles(
    private val targetContext: Context = InstrumentationRegistry.getInstrumentation().targetContext,
) {
    private val copies = mutableListOf<File>()

    fun copyAvcFixture(): File {
        val destination = File(targetContext.cacheDir, "preview-${UUID.randomUUID()}-source.mp4")
        val digest = MessageDigest.getInstance("SHA-256")
        InstrumentationRegistry.getInstrumentation().context.assets
            .open("fixtures/avc-aac-10s-30fps.mp4")
            .use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
        check(digest.digest().joinToString("") { "%02x".format(it) } == FIXTURE_SHA256) {
            destination.delete()
            "Fixture SHA-256 mismatch"
        }
        copies += destination
        return destination
    }

    fun deleteCopies() {
        copies.forEach { it.delete() }
        copies.clear()
    }

    private companion object {
        const val FIXTURE_SHA256 = "8c2c8ac4cb6ca54b1fed4f688f7c64b466e3afe3727ffac76ab4ebb33eee465a"
    }
}
