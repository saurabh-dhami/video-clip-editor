# Video Clip Editor

A focused Kotlin Multiplatform library for clipping a single local MP4 video. Android is functional in version `0.1.0`; iOS exposes the same common contract and currently returns typed unsupported results.

## Features

- Local MP4 input through an absolute file path.
- H.264 (AVC) and H.265 (HEVC) input on supported Android devices.
- Video preview, frame thumbnails, scrubbing, and draggable start/end handles.
- Temporary MP4 output owned by the library.
- Typed validation, unsupported, and failure results.
- Shared Compose Multiplatform clip-editor screen.
- Android API 23+.

The library does not include filters, crop, rotation, effects, captions, multi-track editing, or permanent media storage.

## Installation

The core module contains the common contract and platform engine. The Compose module is optional.

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.saurabh-dhami:video-clip-editor-core:0.1.0")
    implementation("io.github.saurabh-dhami:video-clip-editor-compose:0.1.0") // optional UI
}
```

A host application does not need to be Kotlin Multiplatform. Native Android Kotlin, Android Views, and Jetpack Compose applications can consume the Android artifacts.

## Android usage

The input must be a readable local file with an absolute path. If the host receives a `content://` URI, the host must first copy it to a host-owned private file.

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.oneononearena.videoclip.ClipResult
import com.oneononearena.videoclip.VideoSourcePath
import com.oneononearena.videoclip.createAndroidVideoClipEditor
import com.oneononearena.videoclip.compose.ClipEditorScreen
import java.io.File

@Composable
fun VideoClipPage(
    input: File,
    onFinished: (ClipResult) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val editor = remember(context) {
        createAndroidVideoClipEditor(context.applicationContext)
    }

    ClipEditorScreen(
        source = VideoSourcePath(input.absolutePath),
        editor = editor,
        onResult = onFinished,
        onCancel = onBack,
    )
}
```

Create and retain the editor in the host's normal lifecycle rather than recreating it during every recomposition. The demo application in this repository shows a complete Android integration.

## Editor appearance (unreleased working-tree API)

The next version adds a shared Compose configuration. The published `0.1.0` artifacts do **not** include this API yet; use the local modules to try it. Both existing `ClipEditorScreen` overloads are retained.

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.oneononearena.videoclip.compose.ClipEditorScreen
import com.oneononearena.videoclip.compose.ClipEditorTypography
import com.oneononearena.videoclip.compose.ClipEditorStyle
import com.oneononearena.videoclip.compose.ClipEditorOptions
import com.oneononearena.videoclip.compose.ClipEditorLabels
import kotlin.time.Duration.Companion.seconds

// Inside the host composable, where MaterialTheme is available.
val style = ClipEditorStyle(
    handleColor = Color(0xFF90CAF9),
    selectionColor = Color(0xFF90CAF9),
    primaryButtonColor = Color(0xFF90CAF9),
    primaryButtonContentColor = Color(0xFF082B46),
    handleGripColor = Color(0xFF082B46),
    thumbnailHeight = 64.dp,
    cornerRadius = 12.dp,
    typography = ClipEditorTypography(
        title = MaterialTheme.typography.titleMedium,
        button = MaterialTheme.typography.labelLarge,
        timestamp = MaterialTheme.typography.bodySmall,
    ),
)
val options = ClipEditorOptions(
    labels = ClipEditorLabels(title = "Trim recording", useClip = "Attach clip"),
    showTimestamps = true,
    showSelectedDuration = true,
    showReset = true,
    maxSelectionDuration = 60.seconds,
)

// Inside your existing composable; source/editor/callbacks are host-owned.
ClipEditorScreen(
    source = source,
    editor = editor,
    onResult = onFinished,
    onCancel = onBack,
    style = style,
    options = options,
    onTerminalLifecycleComplete = onEditorClosed,
)
```

Defaults use the approved dark/mint design. For a light theme use `ClipEditorStyle.Light.copy(...)`; the host chooses when to switch themes. Colours also cover surfaces, preview background, text, playhead, dimmed areas, thumbnail placeholders, and secondary buttons. Labels cover UI and accessibility names. Changing style/options does not reopen the video session. The configuration uses shared Compose types (`Color`, `Dp`) only and lives in the optional Compose module.

Geometry is bounded at render time: thumbnails 48–96dp, corner radius 0–24dp, selection border 1–4dp. Non-finite dimensions fall back to defaults. These visual overrides never reduce the 48dp handle touch targets. Hosts remain responsible for contrast and providing a bounded layout with appropriate system-bar insets.

The complete video duration fits a non-scrolling strip with 6–10 representative thumbnails based on available width. Main preview retains `ContentScale.Fit`; thumbnail cells use crop for a continuous strip. These are overview thumbnails, not guaranteed frame-exact boundaries. Frame-step buttons are deliberately absent until decoding and export precision are implemented and verified. Core export, temporary-file ownership and iOS Unsupported behaviour are unchanged.

The Android demo offers Default, Host blue and Light presets before selection, then gives the editor the available screen space. Result/cleanup controls appear after completion. File cleanup still awaits terminal lifecycle completion.

### Maximum selection, typography and progress (unreleased)

`maxSelectionDuration` is an optional **screen selection policy**, not a new restriction on direct core session exports. Null preserves the previous full-duration selection. With a 60-second cap, dragging End to 70 seconds moves Start to 10 seconds; dragging Start earlier similarly moves End to keep the range at most 60 seconds. Shorter selections remain possible. Initial selection and Reset use the first `min(sourceDuration, maximum)` seconds. Values below 500ms or non-finite values return typed `InvalidRequest`; use null for no limit. Updating the cap does not reopen the source. Policy changes during export take effect on a subsequent idle composition/session, not on an in-flight operation.

Typography roles (`title`, `body`, `button`, `label`, `timestamp`) accept shared Compose `TextStyle`. Unspecified roles inherit the host's `MaterialTheme.typography`; font scaling remains enabled. The ruler displays whole seconds for readability, while Start/End retain milliseconds; export timestamps are not rounded. Playback and Reset have explicit spacing and can wrap on narrow layouts.

Opening, thumbnail generation and export show a default progress bar. Thumbnail percentage comes from real engine counts. Opening/export remain indeterminate when there is no measured progress; no artificial percentage is generated. To replace the entire progress UI, use the additional overload:

```kotlin
ClipEditorScreen(
    source = source,
    editor = editor,
    style = style,
    options = options,
    progressContent = { progress ->
        // Shared Compose UI: stage, nullable fraction (0..1), host-localized message.
        MyProgressUi(progress.stage, progress.fraction, progress.message)
    },
    onResult = onFinished,
    onCancel = onBack,
    onTerminalLifecycleComplete = onEditorClosed,
)
```

The callback, not the progress bar or success label, signals completed export. On `ClipResult.Success`, use `result.output.file.absolutePath` and retain `result.output` until your upload/copy finishes; then call `clearTemporaryFile()`. The library never automatically deletes the returned file on success. Subsequent host processing, navigation and its progress UI belong to the host. Default library success UI says “Clip ready”, not a raw result/path dump; the demo intentionally exposes output details for debugging. Existing screen overloads and core contracts remain available.

## Output and cleanup

A successful export returns `ClipResult.Success` containing a `TemporaryClipLease`:

```kotlin
when (result) {
    is ClipResult.Success -> {
        val absoluteOutputPath = result.output.file.absolutePath
        uploadOrCopy(absoluteOutputPath)

        // Call from a coroutine after the host operation completes.
        result.output.clearTemporaryFile()
    }

    else -> handleTypedResult(result)
}
```

The output path is absolute and points to a library-owned temporary MP4. Call `TemporaryClipLease.clearTemporaryFile()` after upload, copy, or cancellation. Repeated cleanup returns `TempDeleteResult.AlreadyCleared`.

Ownership rules:

- The library deletes only temporary files it created.
- The library never deletes the host input.
- Any permanent copy made by the host is owned and cleaned by the host.
- The cleanup API never recursively deletes a directory.

## Codec and device behavior

Android accepts MP4 input containing H.264 or H.265 video when the device can process the stream. Device capability varies, so unsupported codecs, HDR profiles, protected media, unavailable encoders, and invalid inputs return typed results instead of platform exceptions.

The Android implementation uses Media3 behind internal interfaces. Media3, Android `Context`, `Uri`, `Bitmap`, and codec types are not exposed by the common public contract.

## Platform status

| Platform | Version 0.1.0 |
| --- | --- |
| Android API 23+ | Video clipping, preview, thumbnails, range selection, temporary output, cleanup |
| iOS | Common contract compiles; engine and preview return typed unavailable/unsupported results |

The public common contract is designed so a later AVFoundation implementation can replace the iOS no-op without changing Android callers.

## Modules

- `video-clip-editor-core`: common contracts, Android engine, temporary-file lifecycle, iOS unavailable implementation.
- `video-clip-editor-compose`: optional shared Compose UI and Android Media3 preview.
- `demo-android`: local verification application; not published.

## License

Copyright 2026 Saurabh Dhami

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
