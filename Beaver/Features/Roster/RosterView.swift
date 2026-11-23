import SwiftUI

struct RosterView: View {
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            ContentUnavailableView("Roster", systemImage: "hammer", description: Text("Coming soon."))
                .navigationTitle("Roster")
                .settingsToolbar(isPresented: $showingSettings)
        }
    }
}

#Preview { RosterView() }
