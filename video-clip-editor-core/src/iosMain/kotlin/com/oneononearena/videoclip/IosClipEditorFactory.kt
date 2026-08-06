package com.oneononearena.videoclip

public object IosClipEditorFactory {
    public fun create(): IosClipEditorFacade = IosClipEditorFacade()
}

public fun createIosVideoClipEditor(
    configuration: VideoClipEditorConfiguration = VideoClipEditorConfiguration(),
): VideoClipEditor = IosUnavailableVideoClipEditor(configuration)

public class IosClipEditorFacade internal constructor() {
    public fun openSession(
        sourcePath: String,
        completion: (IosOpenSessionResult) -> Unit,
    ) {
        completion(IosOpenSessionResult.IosEngineUnavailable)
    }
}

public sealed class IosOpenSessionResult {
    public abstract val code: IosOpenSessionCode

    public data object IosEngineUnavailable : IosOpenSessionResult() {
        override val code: IosOpenSessionCode = IosOpenSessionCode.IOS_ENGINE_UNAVAILABLE
    }
}

public enum class IosOpenSessionCode {
    IOS_ENGINE_UNAVAILABLE,
}

private class IosUnavailableVideoClipEditor(
    @Suppress("UNUSED_PARAMETER") configuration: VideoClipEditorConfiguration,
) : VideoClipEditor {
    override suspend fun openSession(source: VideoSourcePath): OpenSessionResult =
        OpenSessionResult.Unsupported(
            code = UnsupportedCode.IOS_ENGINE_UNAVAILABLE,
            diagnostic = "AVFoundation clip editing is not integrated yet.",
        )
}
