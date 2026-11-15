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
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.mainViewController(host: host) }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
}
