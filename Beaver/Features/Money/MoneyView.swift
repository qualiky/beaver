import SwiftUI
import BeaverCore

enum MoneySegment: String, CaseIterable, Identifiable {
    case bills = "Bills", spending = "Spending", goals = "Goals"
    var id: String { rawValue }
}

/// The Money tab: bills, spending and goals behind a segmented control.
struct MoneyView: View {
    @State private var segment: MoneySegment = .bills
    @State private var showingSettings = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("Section", selection: $segment) {
                    ForEach(MoneySegment.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)

                switch segment {
                case .bills: BillsSection()
                case .spending: SpendingSection()
                case .goals: GoalsSection()
                }
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Money")
            .navigationBarTitleDisplayMode(.inline)
            .settingsToolbar(isPresented: $showingSettings)
        }
    }
}

#if DEBUG
@MainActor
enum MoneyPreviewSupport {
    static func repository() -> Repository {
        let repo = try! Repository.inMemory()
        try? repo.loadDemoData()
        return repo
    }

    static func store(_ repo: Repository) -> AppStore {
        AppStore(repository: repo, clock: { _ in DemoData.referenceToday })
    }
}

#Preview {
    let repo = MoneyPreviewSupport.repository()
    MoneyView()
        .environment(\.repository, repo)
        .environment(MoneyPreviewSupport.store(repo))
}
#endif
