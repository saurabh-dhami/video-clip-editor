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
