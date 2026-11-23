import SwiftUI

/// The one place Settings presents onboarding: the re-run flow, which keeps existing data and dismisses when done.
struct OnboardingLauncher: View {
    var body: some View {
        OnboardingView(mode: .rerun)
    }
}
