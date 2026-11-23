import SwiftUI
import BeaverCore

// MARK: - Shared pieces

/// A big title and one line of explanation at the top of a step.
private struct StepHeader: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey?

    init(_ title: LocalizedStringKey, subtitle: LocalizedStringKey? = nil) {
        self.title = title
        self.subtitle = subtitle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
            if let subtitle { Text(subtitle).font(.subheadline).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets(top: 8, leading: 4, bottom: 4, trailing: 4))
    }
}

/// A large tappable choice with an icon, a title and a hint.
private struct ChoiceButton: View {
    let title: LocalizedStringKey
    let hint: LocalizedStringKey
    let systemImage: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.title2)
                    .frame(width: 36)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    Text(hint).font(.footnote).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Image(systemName: "plus.circle.fill").foregroundStyle(.tint).accessibilityHidden(true)
            }
            .frame(minHeight: 56)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

private enum Describe {
    static func cadence(_ c: Cadence) -> String {
        switch c {
        case .weekly: "weekly"
        case .fortnightly: "fortnightly"
        case .everyNDays(let n): "every \(n) days"
        case .monthly: "monthly"
        case .quarterly: "quarterly"
        case .yearly: "yearly"
        case .oneOff: "one-off"
        }
    }

    static func schedule(_ s: PaySchedule) -> String {
        switch s {
        case .weekly: "paid weekly"
        case .fortnightly: "paid fortnightly"
        case .monthly: "paid monthly"
        }
    }

    static func source(_ s: IncomeSource, _ fmt: FigureFormatter) -> String {
        switch s.kind {
        case .hourly:
            return "Hourly, \(schedule(s.schedule))"
        case .fixed:
            let amount = s.fixedAmount.map { fmt.string($0) + ", " } ?? ""
            return amount + schedule(s.schedule)
        }
    }
}

// MARK: - 1 Welcome

struct WelcomeStep: View {
    @Bindable var model: OnboardingModel
    @Binding var showImporter: Bool

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: "leaf.fill")
                        .font(.largeTitle)
                        .foregroundStyle(.tint)
                        .accessibilityHidden(true)
                    Text("Welcome to Beaver").font(.title.bold()).accessibilityAddTraits(.isHeader)
                    Text("Beaver turns your shifts or salary into expected pay, tracks your bills and spending, and tells you each week if you're ahead or behind on your goal.")
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 4)
            }
            .listRowBackground(Color.clear)

            Section {
                Label {
                    Text("Everything stays on this iPhone. No account, no bank login, nothing is sent anywhere.")
                } icon: {
                    Image(systemName: "lock.shield.fill").foregroundStyle(.green)
                }
                .accessibilityElement(children: .combine)
            } header: {
                Text("Your privacy")
            }

            Section {
                Picker("Currency", selection: Binding(get: { model.currency }, set: { model.setCurrency($0) })) {
                    ForEach(model.currencyChoices, id: \.self) { code in
                        Text("\(code) \(MoneyParser.symbol(for: code))").tag(code)
                    }
                }
                TimeZonePicker(selection: Binding(get: { model.timeZoneID }, set: { model.setTimeZone($0) }))
            } header: {
                Text("Your money")
            } footer: {
                Text("Taken from your iPhone. Change them if they're not right.")
            }

            Section {
                Button {
                    showImporter = true
                } label: {
                    Label("Restore from backup", systemImage: "arrow.down.doc")
                }
            } footer: {
                Text("Already use Beaver? Pick your backup file (JSON) and skip setup.")
            }
        }
    }
}

// MARK: - 2 Income

struct IncomeStep: View {
    var model: OnboardingModel
    let present: (OnboardingSheet) -> Void
    @State private var deleting: IncomeSource?

    var body: some View {
        List {
            StepHeader("How do you get paid?", subtitle: "Add what you earn. You can add more later.")

            if !model.sources.isEmpty {
                Section("Your income") {
                    ForEach(model.sources) { source in
                        Button { present(.editIncome(source)) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(source.name).font(.headline)
                                    Text(Describe.source(source, model.formatter)).font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary).accessibilityHidden(true)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint("Edit")
                        .swipeActions {
                            Button("Delete", role: .destructive) { deleting = source }
                            if source.kind == .hourly {
                                Button("Usual week") { present(.usualWeek(source.id)) }.tint(.indigo)
                            }
                        }
                    }
                }
            }

            Section(model.sources.isEmpty ? "Pick one" : "Add another") {
                ChoiceButton(title: "Shifts / hourly", hint: "Casual or shift work, paid by the hour", systemImage: "clock") {
                    present(.newIncome(.hourly))
                }
                ChoiceButton(title: "Salary / fixed amount", hint: "The same amount each pay", systemImage: "banknote") {
                    present(.newIncome(.fixed))
                }
            }
        }
        .confirmationDialog("Delete \(deleting?.name ?? "this income")?", isPresented: Binding(
            get: { deleting != nil }, set: { if !$0 { deleting = nil } }
        ), titleVisibility: .visible, presenting: deleting) { source in
            Button("Delete", role: .destructive) { model.deleteSource(source) }
        } message: { _ in
            Text("This also deletes its shifts and default week. It can't be undone.")
        }
    }
}

// MARK: - 3 Bills

struct BillsStep: View {
    var model: OnboardingModel
    let present: (OnboardingSheet) -> Void

    var body: some View {
        List {
            StepHeader("What bills do you pay?", subtitle: "Tap a common one, then fill in the amount.")

            Section {
                BillPresetPicker { present(.newBill($0)) }
                    .padding(.vertical, 4)
                Button { present(.newBill(nil)) } label: {
                    Label("Add custom", systemImage: "plus")
                }
            }

            if !model.bills.isEmpty {
                Section {
                    ForEach(model.bills) { bill in
                        Button { present(.editBill(bill)) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(bill.name).font(.headline)
                                    Text(Describe.cadence(bill.cadence).capitalized).font(.footnote).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(model.formatter.string(bill.amount)).monospacedDigit()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        .accessibilityHint("Edit")
                        .swipeActions {
                            Button("Delete", role: .destructive) { model.deleteBill(bill) }
                        }
                    }
                } header: {
                    Text("Your bills")
                } footer: {
                    Text("≈ \(model.formatter.string(model.weeklyBills)) per week")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.primary)
                        .accessibilityLabel("About \(model.formatter.string(model.weeklyBills)) per week")
                }
            }
        }
    }
}

// MARK: - 4 Living money

struct LivingStep: View {
    @Bindable var model: OnboardingModel

    var body: some View {
        Form {
            StepHeader("Living money", subtitle: "How much do you spend each week outside your bills?")
            Section {
                MoneyField("Per week", text: $model.livingText, currencyCode: model.formatter.currency, suffix: "/ week")
                EditorFieldMessage(model.livingIssue)
            } footer: {
                Text("Food, transport, coffee and fun: everything that isn't a bill.")
            }
            if model.hasIncome {
                Section {
                    let fmt = model.formatter
                    Text("Your income ≈ \(fmt.string(model.weeklyIncome))/week, bills ≈ \(fmt.string(model.weeklyBills))/week")
                        .font(.subheadline)
                }
            }
        }
    }
}

// MARK: - 5 Goal

struct GoalStep: View {
    var model: OnboardingModel
    let present: (OnboardingSheet) -> Void

    var body: some View {
        List {
            StepHeader("Saving for something?", subtitle: "Optional. Beaver tells you each week if you're on track.")
            if !model.goals.isEmpty {
                Section("Your goals") {
                    ForEach(model.goals) { goal in
                        Button { present(.goal(goal)) } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(goal.name).font(.headline)
                                    if let d = goal.deadline {
                                        Text("By \(DateText.full(d))").font(.footnote).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Text(model.formatter.string(goal.target)).monospacedDigit()
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityElement(children: .combine)
                        .accessibilityHint("Edit")
                    }
                }
            }
            Section {
                ChoiceButton(title: model.goals.isEmpty ? "Add a goal" : "Add another goal",
                             hint: "A name, a target and a date", systemImage: "flag") {
                    present(.goal(nil))
                }
            } footer: {
                Text("Your first goal becomes your main one.")
            }
        }
    }
}

// MARK: - 6 Limits

struct LimitsStep: View {
    var model: OnboardingModel
    let present: (OnboardingSheet) -> Void

    var body: some View {
        List {
            StepHeader("Limits and dates", subtitle: "Optional. Beaver warns you, it never stops you.")
            Section {
                Text("Add a fortnightly hours limit (for example a student visa), public holidays, and dates you can't work.")
                if let summary = model.limitsSummary {
                    Label(summary, systemImage: "checkmark.circle.fill").foregroundStyle(.green)
                }
                Button { present(.limits) } label: {
                    Label(model.limitsSummary == nil ? "Set limits and dates" : "Edit limits and dates", systemImage: "calendar.badge.exclamationmark")
                }
            }
        }
    }
}

// MARK: - 7 Summary

struct SummaryStep: View {
    var model: OnboardingModel

    var body: some View {
        let s = model.summary
        let fmt = model.formatter
        List {
            Section {
                VStack(alignment: .leading, spacing: 6) {
                    Text(s.isEmpty ? "All set" : s.headline)
                        .font(.largeTitle.bold())
                        .minimumScaleFactor(0.7)
                        .foregroundStyle(s.isEmpty ? .primary : (s.weeklySurplus.cents < 0 && !s.hasGoal ? Color.red : Color.primary))
                        .accessibilityAddTraits(.isHeader)
                    if let detail = s.detail, !s.isEmpty { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
                    if s.isEmpty {
                        Text("You skipped setup. Add your income, bills and goal any time from the app.")
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 4)
            } header: {
                Text("Your first forecast")
            }

            if !s.isEmpty {
                Section("Key numbers, per week") {
                    LabeledContent("Income", value: "≈ " + fmt.string(s.weeklyIncome))
                    LabeledContent("Bills", value: "≈ " + fmt.string(s.weeklyBills))
                    LabeledContent("Living money", value: fmt.string(s.weeklyLiving))
                    if s.hasGoal, let need = s.requiredPerPayday {
                        LabeledContent("Needed per payday", value: fmt.string(need))
                    }
                }
                .monospacedDigit()
            }
        }
    }
}

// MARK: - Previews

#if DEBUG
@MainActor
private func demoModel() -> OnboardingModel {
    let repo = try! Repository.inMemory()
    try? repo.loadDemoData()
    return OnboardingModel(appModel: AppModel(repository: repo), mode: .rerun)
}

#Preview("Summary, demo") {
    NavigationStack { SummaryStep(model: demoModel()) }
}

#Preview("Bills, demo") {
    let model = demoModel()
    return NavigationStack { BillsStep(model: model, present: { _ in }) }
        .environment(\.repository, model.appModel.repository)
}
#endif
