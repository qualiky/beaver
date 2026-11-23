import SwiftUI
import BeaverCore

struct PlanView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository

    @State private var showingSettings = false
    @State private var selectedRow: PlanRow?
    /// Working copy of the saved living budget, so rapid stepper taps never read a stale value.
    @State private var livingCents: Int?
    /// The living budget when this tab was opened (or Settings last closed), restored by "Reset what-ifs".
    @State private var baselineLivingCents: Int?
    @State private var errorMessage: String?

    private var model: PlanViewModel { PlanViewModel(forecast: store.forecast, settings: store.settings) }
    private var currentLiving: Int { livingCents ?? store.settings.livingBudget.cents }
    private var isWhatIfActive: Bool {
        store.extraWeeklyIncome.cents != 0 || currentLiving != (baselineLivingCents ?? currentLiving)
    }

    var body: some View {
        let model = model
        NavigationStack {
            List {
                summarySection(model.summary)
                whatIfSection(model)
                Section("Week by week") {
                    ForEach(model.rows) { row in
                        Button { selectedRow = row } label: { PlanRowView(row: row) }
                            .buttonStyle(.plain)
                            .listRowBackground(row.isDanger ? Palette.negative.opacity(0.12) : nil)
                            .accessibilityLabel(row.accessibilityLabel)
                            .accessibilityHint("Shows pay and bills for this week")
                    }
                }
            }
            .navigationTitle("Plan")
            .settingsToolbar(isPresented: $showingSettings)
            .sheet(item: $selectedRow) { row in
                PlanWeekDetailSheet(detail: model.detail(for: row))
            }
            .alert("Couldn't save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(errorMessage ?? "") }
        }
        .onAppear { syncLiving(force: false) }
        .onChange(of: showingSettings) { _, showing in if !showing { syncLiving(force: true) } }
    }

    private func syncLiving(force: Bool) {
        if force || livingCents == nil { livingCents = store.settings.livingBudget.cents }
        if force || baselineLivingCents == nil { baselineLivingCents = store.settings.livingBudget.cents }
    }

    // MARK: Sections

    private func summarySection(_ s: PlanSummary) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(s.gapLabel).font(.subheadline).foregroundStyle(.secondary)
                HStack(spacing: 6) {
                    if let symbol = s.gapTone.symbol { Image(systemName: symbol).accessibilityHidden(true) }
                    Text(s.gapText).font(.largeTitle.bold()).monospacedDigit().minimumScaleFactor(0.6).lineLimit(1)
                }
                .foregroundStyle(s.gapTone.color)
            }
            .accessibilityElement(children: .combine)
            LabeledContent(s.projectedLabel) { Text(s.projectedText).monospacedDigit() }
            LabeledContent("Pay vs expected") {
                Text(s.payVarianceText).monospacedDigit().foregroundStyle(s.payVarianceTone.color)
            }
            LabeledContent("Spending to date") {
                Text("\(s.spendingVarianceText) \(s.spendingVarianceNote)").monospacedDigit()
                    .foregroundStyle(s.spendingVarianceTone.color)
            }
        } header: {
            Text("Summary")
        }
    }

    private func whatIfSection(_ model: PlanViewModel) -> some View {
        Section {
            Stepper(value: Binding(get: { currentLiving }, set: setLiving), in: 0...1_000_000, step: 1_000) {
                LabeledContent("Living budget") {
                    Text("\(model.fmt.string(Money(cents: currentLiving))) / week").monospacedDigit()
                }
            }
            .accessibilityLabel("Living budget per week")
            .accessibilityValue(model.fmt.string(Money(cents: currentLiving)))
            Stepper(value: Binding(get: { store.extraWeeklyIncome.cents }, set: { store.extraWeeklyIncome = Money(cents: $0) }),
                    in: 0...1_000_000, step: 2_500) {
                LabeledContent("Extra income") {
                    Text("\(model.fmt.string(store.extraWeeklyIncome)) / week").monospacedDigit()
                }
            }
            .accessibilityLabel("Extra weekly income, what-if only")
            .accessibilityValue(model.fmt.string(store.extraWeeklyIncome))
            Button("Reset what-ifs", action: resetWhatIfs).disabled(!isWhatIfActive)
        } header: {
            Text("What if")
        } footer: {
            Text("The living budget is saved to your settings. Extra income is only for trying things out and is cleared when you close the app.")
        }
    }

    private func setLiving(_ cents: Int) {
        livingCents = cents
        do { try repository.setLivingBudget(Money(cents: cents)) } catch { errorMessage = error.localizedDescription }
    }

    private func resetWhatIfs() {
        store.extraWeeklyIncome = .zero
        if let baseline = baselineLivingCents, baseline != currentLiving { setLiving(baseline) }
    }
}

private struct PlanRowView: View {
    let row: PlanRow

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 3) {
                HStack(spacing: 6) {
                    if row.isDanger {
                        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.negative)
                    }
                    Text("\(row.title) · \(row.paydaysText)").font(.subheadline.weight(.semibold))
                }
                Text("In \(row.incomeText) · Bills \(row.billsText) · Living \(row.livingText)")
                    .font(.caption).foregroundStyle(.secondary)
                if row.isCurrent || row.isDeadlineWeek {
                    Text(row.isCurrent ? "This week" : "Deadline week")
                        .font(.caption2.weight(.semibold)).foregroundStyle(Color.accentColor)
                }
            }
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 3) {
                Text(row.surplusText).font(.subheadline.weight(.bold))
                    .foregroundStyle(row.isDanger ? Palette.negative : (row.surplus.cents > 0 ? Palette.positive : .primary))
                Text(row.balanceText).font(.caption).foregroundStyle(.secondary)
            }
            .layoutPriority(1)
        }
        .monospacedDigit()
        .contentShape(Rectangle())
        .padding(.vertical, 2)
        .accessibilityElement(children: .ignore)
    }
}

#Preview {
    let repository = try! Repository.inMemory()
    try! repository.loadDemoData()
    return PlanView()
        .environment(AppStore(repository: repository, clock: { _ in DemoData.referenceToday }))
        .environment(\.repository, repository)
}
