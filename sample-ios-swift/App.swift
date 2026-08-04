import SwiftUI
import VideoClipEditorCore

@main
struct SampleIosSwiftApp: App {
    init() {
        let facade = IosClipEditorFactory.shared.create()
        facade.openSession(sourcePath: "/tmp/input.mp4") { result in
            precondition(result.code == .iosEngineUnavailable)
        }
    }

    var body: some Scene {
        WindowGroup {
            Text("VideoClipEditorCore iOS feasibility")
        }
    }
}
