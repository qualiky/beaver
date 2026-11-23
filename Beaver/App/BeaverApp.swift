import SwiftUI

@main
struct BeaverApp: App {
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        if model.onboardingDone {
            MainTabView()
        } else {
            OnboardingView()
        }
    }
}
