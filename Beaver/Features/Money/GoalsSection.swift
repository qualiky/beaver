import SwiftUI
import BeaverCore

/// The Goals segment: progress, deposits and the primary goal.
struct GoalsSection: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var depositFor: GoalRow?
    @State private var editorGoal: Goal?
    @State private var showingNewGoal = false
    @State private var errorMessage: String?

    private var model: GoalsModel { GoalsModel(input: store.input, currency: store.settings.currency) }
    private var actions: MoneyActions { MoneyActions(repository: repository) }

    var body: some View {
        let model = model
        let rows = model.rows
        List {
            if rows.isEmpty {
                ContentUnavailableView("No goals yet", systemImage: "target",
                                       description: Text("Add a savings goal and log deposits as you go."))
                    .listRowBackground(Color.clear)
            }
            ForEach(rows) { row in goalSection(row, model: model) }
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showingNewGoal = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add goal")
            }
        }
        .sheet(isPresented: $showingNewGoal) { GoalEditor(style: .sheet) { _ in } }
        .sheet(item: $editorGoal) { goal in GoalEditor(goal: goal, style: .sheet) { _ in } onDelete: {} }
        .sheet(item: $depositFor) { row in
            DepositSheet(goalID: row.goal.id, goalName: row.goal.name, today: store.input.today)
        }
        .alert("Couldn't save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func goalSection(_ row: GoalRow, model: GoalsModel) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(row.goal.name).font(.headline)
                    if row.isPrimary {
                        Label("Primary", systemImage: "star.fill")
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(Color.accentColor.opacity(0.15), in: Capsule())
                            .foregroundStyle(Color.accentColor)
                    }
                    Spacer()
                    Text(row.percentText).font(.subheadline.weight(.semibold)).monospacedDigit()
                }
                ProgressView(value: row.progress)
                    .accessibilityLabel("Progress")
                    .accessibilityValue(row.percentText)
                HStack {
                    Text("\(row.savedText) of \(row.targetText)").monospacedDigit()
                    Spacer()
                    if let deadline = row.deadlineText { Text(deadline) }
                }
                .font(.footnote)
                .foregroundStyle(.secondary)
            }
            .padding(.vertical, 4)
            .accessibilityElement(children: .combine)

            Button { depositFor = row } label: { Label("Add deposit", systemImage: "plus.circle") }
            Button { editorGoal = row.goal } label: { Label("Edit goal", systemImage: "pencil") }
            if !row.isPrimary {
                Button { run { try actions.makePrimary(row.goal.id) } } label: { Label("Make primary", systemImage: "star") }
            }

            ForEach(row.deposits) { deposit in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(DateText.full(deposit.date, locale: model.fmt.locale))
                        if !deposit.note.isEmpty { Text(deposit.note).font(.footnote).foregroundStyle(.secondary) }
                    }
                    Spacer()
                    Text(model.fmt.string(deposit.amount)).monospacedDigit()
                }
                .accessibilityElement(children: .combine)
                .accessibilityAction(named: "Delete") { run { try actions.deleteDeposit(deposit.id) } }
                .swipeActions(edge: .trailing) {
                    Button(role: .destructive) { run { try actions.deleteDeposit(deposit.id) } } label: {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
        } header: {
            Text(row.deposits.isEmpty ? "" : "Deposits")
        }
    }

    private func run(_ work: () throws -> Void) {
        do { try work() } catch { errorMessage = error.localizedDescription }
    }
}

#if DEBUG
#Preview("Goals") {
    let repo = MoneyPreviewSupport.repository()
    NavigationStack { GoalsSection() }
        .environment(\.repository, repo)
        .environment(MoneyPreviewSupport.store(repo))
}
#endif
