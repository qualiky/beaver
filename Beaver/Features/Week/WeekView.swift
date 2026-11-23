import SwiftUI

struct WeekView: View {
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            ContentUnavailableView("Week", systemImage: "hammer", description: Text("Coming soon."))
                .navigationTitle("Week")
                .settingsToolbar(isPresented: $showingSettings)
        }
    }
}

#Preview { WeekView() }
