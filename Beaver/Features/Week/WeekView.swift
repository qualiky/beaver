import SwiftUI
import BeaverCore

struct WeekView: View {
    @Environment(AppStore.self) private var store
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 12) {
                Text(store.forecast.goal == nil ? "Projected surplus" : "Ahead / behind goal")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                MoneyText(cents: store.forecast.gap.cents, currencyCode: store.settings.currency)
                    .font(.largeTitle.bold())
                    .accessibilityIdentifier("gapValue")
                Text("Full Week tab coming soon.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .navigationTitle("Week")
            .settingsToolbar(isPresented: $showingSettings)
        }
    }
}

#Preview {
    let repository = try! Repository.inMemory()
    try! repository.loadDemoData()
    return WeekView().environment(AppStore(repository: repository, clock: { _ in DemoData.referenceToday }))
}
