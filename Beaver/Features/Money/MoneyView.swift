import SwiftUI

struct MoneyView: View {
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            ContentUnavailableView("Money", systemImage: "hammer", description: Text("Coming soon."))
                .navigationTitle("Money")
                .settingsToolbar(isPresented: $showingSettings)
        }
    }
}

#Preview { MoneyView() }
