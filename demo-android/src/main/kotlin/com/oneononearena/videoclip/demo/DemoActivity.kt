package com.oneononearena.videoclip.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.TempDeleteResult
import com.oneononearena.videoclip.TemporaryClipLease
import com.oneononearena.videoclip.VideoSourcePath
import com.oneononearena.videoclip.compose.ClipEditorScreen
import com.oneononearena.videoclip.compose.ClipEditorStyle
import com.oneononearena.videoclip.compose.ClipEditorOptions
import com.oneononearena.videoclip.compose.ClipEditorLabels
import com.oneononearena.videoclip.createAndroidVideoClipEditor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

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
    val cleanupCoordinator = remember { DemoCleanupCoordinator() }
    var source by remember { mutableStateOf<DemoOwnedInput?>(null) }
    var sourceGeneration by remember { mutableStateOf<Long?>(null) }
    var output by remember { mutableStateOf<TemporaryClipLease?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var editorVisible by remember { mutableStateOf(true) }
    var cleanupBlocked by remember { mutableStateOf(false) }
    var operationInProgress by remember { mutableStateOf(false) }
    var pickerOpen by remember { mutableStateOf(false) }
    var editorFinished by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf("Default") }
    val editorStyle = when (theme) {
        "Light" -> ClipEditorStyle.Light
        "Host blue" -> ClipEditorStyle(
            primaryButtonColor = Color(0xFF90CAF9), primaryButtonContentColor = Color(0xFF082B46),
            handleColor = Color(0xFF90CAF9), selectionColor = Color(0xFF90CAF9), handleGripColor = Color(0xFF082B46),
        )
        else -> ClipEditorStyle()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (!pickerOpen || cleanupBlocked) return@rememberLauncherForActivityResult
        pickerOpen = false
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            operationInProgress = true
            val result = try {
                withContext(Dispatchers.IO) {
                    BoundedDocumentImporter(File(activity.filesDir, "demo-inputs")).importFrom {
                        activity.contentResolver.openInputStream(uri)
                    }
                }
            } finally {
                operationInProgress = false
            }
            when (result) {
                is DocumentImportResult.Imported -> {
                    val generation = cleanupCoordinator.activateGeneration()
                    if (generation == null) {
                        withContext(Dispatchers.IO) { result.input.delete() }
                        message = "Import failed: cleanup is still pending"
                    } else {
                        source = result.input
                        sourceGeneration = generation
                        output = null
                        message = "Input: ${result.input.file.absolutePath}"
                        editorVisible = true
                        editorFinished = false
                    }
                }
                DocumentImportResult.TooLarge -> message = "Input rejected: exceeds 512 MiB"
                is DocumentImportResult.Failed -> message = "Import failed: ${result.message ?: "unknown error"}"
            }
        }
    }

    fun select() {
        if (!operationInProgress && !pickerOpen && !cleanupBlocked) {
            pickerOpen = true
            launcher.launch(arrayOf("video/mp4"))
        }
    }
    fun clear() {
        if (operationInProgress) return
        val generation = sourceGeneration ?: return
        val ownedInput = source ?: return
        if (!cleanupCoordinator.sealGeneration(generation)) return
        operationInProgress = true
        cleanupBlocked = true
        editorVisible = false
        val issuedLease = output
        scope.launch {
            try {
                val result = cleanupCoordinator.clear(
                    generation = generation,
                    hideScreen = {},
                    clearOutput = {
                        val lease = issuedLease ?: return@clear DemoClearResult.Cleared
                        try {
                            when (lease.clearTemporaryFile()) {
                                TempDeleteResult.Cleared, TempDeleteResult.AlreadyCleared -> DemoClearResult.Cleared
                                is TempDeleteResult.Failed -> DemoClearResult.Failed
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            DemoClearResult.Failed
                        }
                    },
                    deleteSource = { withContext(Dispatchers.IO) { ownedInput.delete() } },
                    clearUi = {
                        source = null
                        sourceGeneration = null
                        output = null
                        message = null
                    },
                )
                when (result) {
                    DemoClearResult.Cleared -> cleanupBlocked = false
                    DemoClearResult.Failed -> message = "Temporary cleanup failed. Retry Clear temp before reselecting."
                }
            } finally {
                operationInProgress = false
            }
        }
    }

    Column(Modifier.fillMaxSize().background(editorStyle.backgroundColor).safeDrawingPadding()) {
        // Keep the editor mounted through result handling; Clear still seals the generation
        // before disposal, then awaits its terminal callback before deleting owned files.
        if (source == null || editorFinished || !editorVisible) Column(Modifier.padding(16.dp)) {
            if (source == null) {
                Text("Editor theme", color = editorStyle.textColor)
                Row {
                    listOf("Default", "Host blue", "Light").forEach { choice ->
                        Button(onClick = { theme = choice }, enabled = theme != choice) { Text(choice) }
                    }
                }
            }
            Button(onClick = ::select, enabled = source == null && !cleanupBlocked && !operationInProgress && !pickerOpen) { Text("Select MP4") }
            if (source != null || cleanupBlocked) Button(onClick = ::clear, enabled = !operationInProgress && !pickerOpen) { Text("Clear temp") }
            message?.let { Text(it, color = editorStyle.textColor) }
        }
        source?.let { imported ->
            val generation = sourceGeneration
            if (editorVisible && generation != null) ClipEditorScreen(
                source = VideoSourcePath(imported.file.absolutePath),
                editor = editor,
                style = editorStyle,
                options = ClipEditorOptions(
                    labels = ClipEditorLabels(useClip = if (theme == "Host blue") "Attach clip" else "Use clip"),
                    maxSelectionDuration = 60.seconds,
                ),
                onResult = { result ->
                    if (!cleanupCoordinator.acceptsUpdates(generation)) return@ClipEditorScreen
                    editorFinished = true
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
                onCancel = {
                    cleanupCoordinator.onScreenCancel(generation, ::clear)
                },
                onTerminalLifecycleComplete = {
                    cleanupCoordinator.onTerminalLifecycleComplete(generation)
                },
            )
        }
    }
}
