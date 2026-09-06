package com.oneononearena.videoclip.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp

enum class ClipEditorProgressStage { OpeningVideo, LoadingThumbnails, ExportingClip }

/** Fraction is null when the engine has no reliable percentage. This is not export completion. */
@Immutable
data class ClipEditorProgress(val stage: ClipEditorProgressStage, val fraction: Float?, val message: String)

@Composable
internal fun ClipEditorProgressView(progress: ClipEditorProgress, style: ClipEditorStyle) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(progress.message, color = style.textColor, style = style.typography.body ?: MaterialTheme.typography.bodyMedium)
        val modifier = Modifier.fillMaxWidth().semantics { testTag = "clip-progress" }
        val fraction = progress.fraction?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        if (fraction == null) LinearProgressIndicator(modifier = modifier, color = style.primaryButtonColor, trackColor = style.secondaryButtonColor)
        else LinearProgressIndicator(progress = { fraction }, modifier = modifier, color = style.primaryButtonColor, trackColor = style.secondaryButtonColor)
    }
}
