import SwiftUI

/// The one place Settings presents onboarding. It shows `OnboardingView()` today.
/// After the Onboarding branch merges, change the body to `OnboardingView(mode: .rerun)`.
struct OnboardingLauncher: View {
    var body: some View {
        OnboardingView()
    }
}
