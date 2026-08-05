package com.oneononearena.videoclip.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.TempDeleteResult
import com.oneononearena.videoclip.TemporaryClipLease
import com.oneononearena.videoclip.VideoSourcePath
import com.oneononearena.videoclip.compose.ClipEditorScreen
import com.oneononearena.videoclip.createAndroidVideoClipEditor
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { DemoApp(this) } }
    }
}

@Composable
private fun DemoApp(activity: DemoActivity) {
    val scope = rememberCoroutineScope()
    val editor = remember(activity) { createAndroidVideoClipEditor(activity) }
    var source by remember { mutableStateOf<File?>(null) }
    var output by remember { mutableStateOf<TemporaryClipLease?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editorVisible by remember { mutableStateOf(true) }
    var cleanupBlocked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || cleanupBlocked) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                activity.contentResolver.openInputStream(uri)?.use { input ->
                    BoundedDocumentImporter(File(activity.filesDir, "demo-inputs")).import(input)
                } ?: DocumentImportResult.Failed("Document provider returned no stream")
            }
            when (result) {
                is DocumentImportResult.Imported -> {
                    source = result.file
                    output = null
                    message = "Input: ${result.file.absolutePath}"
                    editorVisible = true
                }
                DocumentImportResult.TooLarge -> message = "Input rejected: exceeds 512 MiB"
                is DocumentImportResult.Failed -> message = "Import failed: ${result.message ?: "unknown error"}"
            }
        }
    }

    fun select() = launcher.launch(arrayOf("video/mp4"))
    fun clear() {
        scope.launch {
            cleanupBlocked = true
            // No demo playback dependency: releasePlayer is intentionally a no-op.
            val coordinator = DemoCleanupCoordinator(
                releasePlayer = {},
                clearOutput = {
                    when (val result = output?.let { lease -> runCatching { lease.clearTemporaryFile() }.getOrNull() }) {
                        null, TempDeleteResult.Cleared, TempDeleteResult.AlreadyCleared -> DemoClearResult.Cleared
                        is TempDeleteResult.Failed -> DemoClearResult.Failed
                    }
                },
                // Removing the screen invokes ClipEditorScreen's presenter close before its resources are disposed.
                closeSession = { editorVisible = false },
                deleteSource = { source?.delete() ?: true },
                clearUi = { source = null; output = null; message = null },
            )
            when (coordinator.clear()) {
                DemoClearResult.Cleared -> cleanupBlocked = false
                DemoClearResult.Failed -> message = "Temporary output clear failed. Retry Clear temp before reselecting."
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Button(onClick = ::select, enabled = source == null && !cleanupBlocked) { Text("Select MP4") }
        if (source != null || cleanupBlocked) Button(onClick = ::clear) { Text("Clear temp") }
        message?.let { Text(it) }
        source?.let { imported ->
            if (editorVisible) ClipEditorScreen(
                source = VideoSourcePath(imported.absolutePath),
                editor = editor,
                onResult = { result ->
                    when (result) {
                        is ClipResult.Success -> {
                            output = result.output
                            message = "Output: ${result.output.file.absolutePath}\nRange: ${result.sourceRange}"
                        }
                        is ClipResult.Unsupported -> message = "Unsupported: ${result.code} ${result.diagnostic.orEmpty()}"
                        is ClipResult.InvalidRequest -> message = "Invalid: ${result.code} ${result.diagnostic.orEmpty()}"
                        is ClipResult.Failed -> message = "Failed: ${result.failure.code} ${result.failure.diagnostic.orEmpty()}"
                    }
                },
                onCancel = { message = "Editor cancelled" },
            )
        }
    }
}
