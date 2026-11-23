import SwiftUI

@main
struct BeaverApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var model = AppModel.live()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(model.store)
                .environment(\.repository, model.repository)
                .task { await model.store.run() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { model.store.refreshToday() }
                }
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        Group {
            if model.onboardingDone {
                MainTabView()
            } else {
                OnboardingView()
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if let detail = model.startupError { StartupErrorBanner(detail: detail) }
        }
    }
}
