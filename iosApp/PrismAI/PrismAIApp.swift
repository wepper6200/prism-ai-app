import SwiftUI
import PrismShared

@main
struct PrismAIApp: App {
    @Environment(\.scenePhase) private var phase
    private let host = AppleHost()
    var body: some Scene {
        WindowGroup {
            PrismRoot(host: host).ignoresSafeArea()
                .onChange(of: phase) { _, phase in MainViewControllerKt.setApplicationActive(active: phase == .active) }
        }
    }
}
private struct PrismRoot: UIViewControllerRepresentable {
    let host: AppleHost
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.mainViewController(host: host)
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--prism-smoke") {
            let model = host.dataDirectory() + "/tiny.gguf"
            AppleSmokeKt.runOfflineSmoke(modelPath: model) { error in
                let result = error.map { "FAILED: " + $0 } ?? "OK: streaming, deterministic reuse, cancellation, Russian TTS/ASR and VAD"
                _ = self.host.write(path: self.host.dataDirectory() + "/smoke-result.txt", data: Data(result.utf8))
                return KotlinUnit()
            }
        }
        #endif
        return controller
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
