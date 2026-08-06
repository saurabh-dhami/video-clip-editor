# Video Clip Editor V1 — Frozen Source/API Baseline

**Baseline source commit:** `92f78412796113f2abe27f55be0125e9373c9f1c`
**Scope:** published Kotlin source surface in `video-clip-editor-core` and `video-clip-editor-compose`; Android/iOS platform entry points included.
**Rule:** implementation may not add, remove, rename, reorder, or change a public signature, payload, enum entry, default, or visibility below without a versioned public-API decision.

This is the checked-in B0 baseline. It is intentionally source-form because the current build has no `apiDump`/`apiCheck` task. Before release publication, target-specific binary dumps must be compared to this baseline; they supplement rather than replace this initial freeze.

## Common core — `com.oneononearena.videoclip`

```kotlin
@JvmInline value class VideoSourcePath(val value: String)

data class ClipRange(
  val start: Duration,
  val endExclusive: Duration,
)

data class VideoClipEditorConfiguration(
  val minimumClipDuration: Duration = 500.milliseconds,
  val maximumFrameCount: Int = 24,
  val maximumThumbnailDimensionPx: Int = 160,
)

data class FrameStripRequest(val frameCount: Int)

data class VideoMetadata(
  val duration: Duration,
  val displayWidthPx: Int,
  val displayHeightPx: Int,
  val hasAudio: Boolean,
)

interface VideoClipEditor {
  suspend fun openSession(source: VideoSourcePath): OpenSessionResult
}

interface ClipEditorSession {
  val metadata: VideoMetadata
  fun frames(request: FrameStripRequest): Flow<FrameStripEvent>
  suspend fun createClip(range: ClipRange): ClipResult
  suspend fun close()
}

interface TemporaryClipLease {
  val file: TemporaryVideoFile
  suspend fun clearTemporaryFile(): TempDeleteResult
}
```

Library-created public types:

```kotlin
class ThumbnailFrame internal constructor(
  val requestedTime: Duration,
  val actualTime: Duration,
  val widthPx: Int,
  val heightPx: Int,
  encodedJpeg: ByteArray,
) {
  fun copyEncodedJpeg(): ByteArray
}

class TemporaryVideoFile internal constructor(
  val absolutePath: String,
  internal val opaqueId: String,
)

public object FeasibilityMarker
```

Result payloads:

```kotlin
sealed interface OpenSessionResult {
  data class Open(val session: ClipEditorSession)
  data class Unsupported(val code: UnsupportedCode, val diagnostic: String?)
  data class InvalidRequest(val code: ValidationCode, val diagnostic: String?)
  data class Failed(val failure: VideoEditFailure)
}

sealed interface FrameStripEvent {
  data class Frame(val value: ThumbnailFrame)
  data class Progress(val emitted: Int, val total: Int)
  data object Complete
  data class InvalidRequest(val code: ValidationCode, val diagnostic: String?)
  data class Unsupported(val code: UnsupportedCode, val diagnostic: String?)
  data class Failed(val error: VideoEditFailure)
}

sealed interface ClipResult {
  data class Success(val output: TemporaryClipLease, val sourceRange: ClipRange)
  data class Unsupported(val code: UnsupportedCode, val diagnostic: String?)
  data class InvalidRequest(val code: ValidationCode, val diagnostic: String?)
  data class Failed(val failure: VideoEditFailure)
}

data class VideoEditFailure(
  val code: FailureCode,
  val retryable: Boolean,
  val diagnostic: String?,
)

sealed interface TempDeleteResult {
  data object Cleared
  data object AlreadyCleared
  data class Failed(val failure: VideoEditFailure)
}
```

Frozen enum entries, in declaration order:

```kotlin
enum class UnsupportedCode {
  IOS_ENGINE_UNAVAILABLE,
  UNSUPPORTED_CONTAINER,
  UNSUPPORTED_VIDEO_CODEC,
  UNSUPPORTED_AUDIO_CODEC,
  DRM_PROTECTED,
  HDR_UNSUPPORTED,
  INPUT_TOO_LARGE,
  INPUT_TOO_LONG,
  DEVICE_ENCODER_UNAVAILABLE,
}

enum class ValidationCode {
  PATH_NOT_ABSOLUTE,
  PATH_NOT_REGULAR_FILE,
  SOURCE_INSIDE_TEMP_ROOT,
  RANGE_NEGATIVE,
  RANGE_ORDER_INVALID,
  RANGE_BELOW_MINIMUM,
  RANGE_EXCEEDS_DURATION,
  INVALID_FRAME_REQUEST,
  SESSION_CLOSED,
  OPERATION_IN_PROGRESS,
}

enum class FailureCode {
  METADATA_READ_FAILED,
  FRAME_EXTRACTION_FAILED,
  EXPORT_FAILED,
  EXPORT_CANCELLED,
  TEMP_CREATE_FAILED,
  TEMP_RENAME_FAILED,
  TEMP_DELETE_FAILED,
}
```

## Android-only — `com.oneononearena.videoclip`

```kotlin
fun createAndroidVideoClipEditor(
  context: Context,
  configuration: VideoClipEditorConfiguration = VideoClipEditorConfiguration(),
): VideoClipEditor
```

`Context` is intentionally public only at this Android factory boundary. It is absent from the common contract.

## iOS-only — `com.oneononearena.videoclip`

Primary common-contract factory:

```kotlin
fun createIosVideoClipEditor(
  configuration: VideoClipEditorConfiguration = VideoClipEditorConfiguration(),
): VideoClipEditor
```

V1 result: `openSession` returns `OpenSessionResult.Unsupported(UnsupportedCode.IOS_ENGINE_UNAVAILABLE, diagnostic)`.

Legacy Swift callback facade — frozen separately:

```kotlin
object IosClipEditorFactory {
  fun create(): IosClipEditorFacade
}

class IosClipEditorFacade internal constructor() {
  fun openSession(
    sourcePath: String,
    completion: (IosOpenSessionResult) -> Unit,
  )
}

sealed class IosOpenSessionResult {
  abstract val code: IosOpenSessionCode
  data object IosEngineUnavailable : IosOpenSessionResult()
}

enum class IosOpenSessionCode { IOS_ENGINE_UNAVAILABLE }
```

V1 callback result: `IosOpenSessionResult.IosEngineUnavailable`. This result type is distinct from common `OpenSessionResult`.

## Optional Compose — `com.oneononearena.videoclip.compose`

```kotlin
@Composable
fun ClipEditorScreen(
  source: VideoSourcePath,
  editor: VideoClipEditor,
  onResult: (ClipResult) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
)
```

No navigation, Activity Result, player, upload, URI, bitmap, Media3, or platform-native type is added to the common public surface.

## R1 verification evidence — 2026-08-06

Fresh KMP, Compose, and iOS ARM64-simulator tests completed successfully, and the Android API-36 device suite completed 43/43 with the HEVC production-factory round trip and temporary-lease assertions. The baseline common contract, iOS factory, and optional Compose `ClipEditorScreen` source files have no diff from `92f78412796113f2abe27f55be0125e9373c9f1c`; `git diff --check` also passed. This section records evidence only and changes no declaration above. Physical Samsung release evidence remains pending in `docs/verification/2026-08-06-android-hevc-release-gate.md`.
