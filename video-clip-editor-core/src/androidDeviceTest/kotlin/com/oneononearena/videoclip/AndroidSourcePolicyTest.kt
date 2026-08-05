package com.oneononearena.videoclip

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
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
        val root = File("/tmp/editor")
        assertEquals(ValidationCode.SOURCE_INSIDE_TEMP_ROOT, AndroidSourcePolicy.validate("/tmp/editor/session/source.mp4", root))
        assertEquals(null, AndroidSourcePolicy.validate("/tmp/editor-other/source.mp4", root))
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
}
