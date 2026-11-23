import SwiftUI
import BeaverCore

/// A pill showing under or over budget, always with a symbol and words.
struct VarianceChip: View {
    let variance: Money
    let text: String

    var body: some View {
        let tone = Tone.of(variance)
        Label(text, systemImage: tone == .negative ? "arrow.up.circle.fill" : tone == .positive ? "arrow.down.circle.fill" : "equal.circle.fill")
            .font(.footnote.weight(.semibold))
            .foregroundStyle(tone.color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(tone.color.opacity(0.12), in: Capsule())
            .accessibilityLabel("Variance: \(text)")
    }
}

/// The Spending segment: this week's card, expenses, and the last 12 weeks.
struct SpendingSection: View {
    @Environment(AppStore.self) private var store
    @State private var showingAdd = false

    private var model: SpendingModel { SpendingModel(input: store.input, currency: store.settings.currency) }

    var body: some View {
        let model = model
        List {
            Section {
                thisWeekCard(model)
            }
            WeekExpensesSections(weekStart: model.currentWeekStart)
            historySection(model)
        }
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { showingAdd = true } label: { Image(systemName: "plus") }
                    .accessibilityLabel("Add expense")
            }
        }
        .sheet(isPresented: $showingAdd) { AddExpenseSheet() }
    }

    private func thisWeekCard(_ model: SpendingModel) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("This week").font(.headline)
                Spacer()
                if model.total(weekStart: model.currentWeekStart) != nil { TotalSetBadge() }
                VarianceChip(variance: model.varianceThisWeek, text: model.varianceText(model.varianceThisWeek))
            }
            HStack(alignment: .top) {
                figure("Budget", model.fmt.string(model.budget))
                Spacer()
                figure("Spent", model.fmt.string(model.spentThisWeek))
                Spacer()
                figure("Left", model.fmt.string(model.leftThisWeek), tone: Tone.of(model.leftThisWeek))
            }
            ProgressView(value: Double(min(max(model.spentThisWeek.cents, 0), max(model.budget.cents, 1))),
                         total: Double(max(model.budget.cents, 1)))
                .tint(model.leftThisWeek.cents < 0 ? Palette.negative : Color.accentColor)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private func figure(_ label: String, _ value: String, tone: Tone = .neutral) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title3.weight(.semibold)).monospacedDigit().foregroundStyle(tone == .negative ? Palette.negative : .primary)
        }
    }

    private func historySection(_ model: SpendingModel) -> some View {
        Section {
            ForEach(model.history) { row in
                NavigationLink {
                    WeekSpendDetailView(weekStart: row.weekStart)
                } label: {
                    historyRow(row, model: model)
                }
            }
        } header: {
            Text("Last 12 weeks")
        } footer: {
            Text("Running total adds up completed weeks that have spending recorded, from the oldest shown. Weeks with nothing logged are skipped.")
        }
    }

    private func historyRow(_ row: WeekSpendRow, model: SpendingModel) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(model.weekTitle(row.weekStart)).font(.body.weight(row.isCurrent ? .semibold : .regular))
                if row.hasTotal { TotalSetBadge() }
                Spacer()
                if row.hasData {
                    Text(model.varianceText(row.variance))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(Tone.of(row.variance).color)
                }
            }
            HStack {
                Text(row.hasData ? "Spent \(model.fmt.string(row.spent)) of \(model.fmt.string(row.budget))" : "Nothing recorded")
                Spacer()
                if row.hasData, let running = row.running {
                    Text("Running \(model.fmt.signed(running))").foregroundStyle(Tone.of(running).color)
                }
            }
            .font(.footnote)
            .foregroundStyle(.secondary)
            .monospacedDigit()
        }
        .accessibilityElement(children: .combine)
    }
}

struct TotalSetBadge: View {
    var body: some View {
        Text("Total set")
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(Color.accentColor.opacity(0.15), in: Capsule())
            .foregroundStyle(Color.accentColor)
    }
}

/// One week's expenses with swipe to edit or delete, plus set and clear week total.
struct WeekExpensesSections: View {
    let weekStart: LocalDate
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var editing: Expense?
    @State private var showingTotal = false
    @State private var errorMessage: String?

    private var model: SpendingModel { SpendingModel(input: store.input, currency: store.settings.currency) }
    private var actions: MoneyActions { MoneyActions(repository: repository) }

    var body: some View {
        let model = model
        let total = model.total(weekStart: weekStart)
        let expenses = model.expenses(weekStart: weekStart)
        Section {
            if let total {
                LabeledContent {
                    Text(model.fmt.string(total)).monospacedDigit()
                } label: {
                    HStack { Text("Week total"); TotalSetBadge() }
                }
                Button("Clear total", role: .destructive) { run { try actions.clearWeekTotal(weekStart: weekStart) } }
            }
            Button { showingTotal = true } label: {
                Label(total == nil ? "Set week total" : "Change week total", systemImage: "sum")
            }
            ForEach(expenses) { expense in
                expenseRow(expense, model: model, dimmed: total != nil)
            }
            if expenses.isEmpty {
                Text("No quick entries").foregroundStyle(.secondary)
            }
        } header: {
            Text(weekStart == model.currentWeekStart ? "Expenses this week" : "Expenses")
        } footer: {
            if total != nil, !expenses.isEmpty {
                Text("Entries are kept but ignored while a week total is set.")
            }
        }
        .sheet(item: $editing) { ExpenseEditSheet(expense: $0) }
        .sheet(isPresented: $showingTotal) { WeekTotalSheet(model: model, weekStart: weekStart) }
        .alert("Couldn't save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: { Text(errorMessage ?? "") }
    }

    private func expenseRow(_ expense: Expense, model: SpendingModel, dimmed: Bool) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(expense.category.title)
                Text([DateText.weekdayDayMonth(expense.date, locale: model.fmt.locale), expense.note]
                    .filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Spacer()
            Text(model.fmt.string(expense.amount)).monospacedDigit()
        }
        .opacity(dimmed ? 0.5 : 1)
        .accessibilityElement(children: .combine)
        .accessibilityAction(named: "Edit") { editing = expense }
        .accessibilityAction(named: "Delete") { delete(expense) }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { delete(expense) } label: { Label("Delete", systemImage: "trash") }
            Button { editing = expense } label: { Label("Edit", systemImage: "pencil") }.tint(.blue)
        }
    }

    private func delete(_ expense: Expense) { run { try actions.deleteExpense(expense.id) } }

    private func run(_ work: () throws -> Void) {
        do { try work() } catch { errorMessage = error.localizedDescription }
    }
}

/// A past week's detail: its entries and total.
struct WeekSpendDetailView: View {
    let weekStart: LocalDate
    @Environment(AppStore.self) private var store

    var body: some View {
        let model = SpendingModel(input: store.input, currency: store.settings.currency)
        let spent = model.spent(weekStart: weekStart)
        List {
            Section {
                LabeledContent("Budget", value: model.fmt.string(model.budget))
                LabeledContent("Spent", value: model.fmt.string(spent))
                LabeledContent("Variance") {
                    VarianceChip(variance: Spending.variance(budget: model.budget, spent: spent),
                                 text: model.varianceText(Spending.variance(budget: model.budget, spent: spent)))
                }
            }
            .monospacedDigit()
            WeekExpensesSections(weekStart: weekStart)
        }
        .navigationTitle(model.weekTitle(weekStart))
        .navigationBarTitleDisplayMode(.inline)
    }
}

#if DEBUG
#Preview("Spending") {
    let repo = MoneyPreviewSupport.repository()
    NavigationStack { SpendingSection() }
        .environment(\.repository, repo)
        .environment(MoneyPreviewSupport.store(repo))
}
#endif
