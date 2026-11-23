import SwiftUI

struct PlanView: View {
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            ContentUnavailableView("Plan", systemImage: "hammer", description: Text("Coming soon."))
                .navigationTitle("Plan")
                .settingsToolbar(isPresented: $showingSettings)
        }
    }
}

#Preview { PlanView() }
