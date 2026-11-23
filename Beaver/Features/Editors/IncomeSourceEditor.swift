import SwiftUI
import Observation
import BeaverCore

// MARK: - View model

enum IncomeScheduleKind: String, CaseIterable, Sendable {
    case weekly, fortnightly, monthly

    var title: String {
        switch self {
        case .weekly: "Weekly"
        case .fortnightly: "Fortnightly"
        case .monthly: "Monthly"
        }
    }
}

enum IncomeTaxKind: String, CaseIterable, Sendable {
    case none, flat, table

    var title: String {
        switch self {
        case .none: "None"
        case .flat: "Flat %"
        case .table: "Table"
        }
    }
}

@MainActor
@Observable
final class IncomeSourceEditorModel {
    enum Field: CaseIterable, Sendable { case name, rates, amount, schedule, tax, softCap, hardCap }

    let id: Int
    var name: String
    var kind: IncomeKind {
        didSet { if kind == .hourly, scheduleKind == .monthly { scheduleKind = .weekly } }
    }
    var fixedAmountText: String
    var amountIsNet: Bool
    var scheduleKind: IncomeScheduleKind
    var payWeekday: Weekday
    var lagWeeks: Int
    var anchorPayday: LocalDate
    var dayOfMonth: Int
    var taxKind: IncomeTaxKind
    var flatPercentText: String
    var tableID: String?
    var softCapText: String
    var hardCapText: String
    var isActive: Bool
    let rateCard: RateCardEditorModel

    /// Withholding tables the user can pick (built-in first). Filled by `load(from:)`.
    private(set) var tables: [WithholdingTable] = []
    private(set) var isLoaded = false
    let isNew: Bool
    private let existingRateCardID: Int?
    private(set) var touches = FieldTouches<Field>()

    func touch(_ field: Field) { touches.touch(field) }

    /// Shows every error, as after a failed save attempt.
    func revealAllIssues() {
        touches.revealAll = true
        rateCard.revealAllIssues()
    }

    /// The field's issue, only once it has been edited or a save was attempted.
    func visibleIssue(for field: Field) -> String? {
        if field == .rates { return touches.revealAll || rateCard.rulesTouched ? issue(for: .rates) : nil }
        return touches.shows(field) ? issue(for: field) : nil
    }

    /// `newKind` is the type a new (nil) source starts as; an existing source keeps its own.
    init(source: IncomeSource? = nil, newKind: IncomeKind = .hourly, today: LocalDate = LocalDate.today()) {
        isNew = source == nil
        id = source?.id ?? 0
        name = source?.name ?? ""
        kind = source?.kind ?? newKind
        fixedAmountText = source?.fixedAmount.map { MoneyParser.text(fromCents: $0.cents) } ?? ""
        amountIsNet = source?.amountIsNet ?? false
        isActive = source?.isActive ?? true
        softCapText = source?.weeklyCapSoftHours.map(String.init) ?? ""
        hardCapText = source?.weeklyCapHardHours.map(String.init) ?? ""
        existingRateCardID = source?.rateCardID
        rateCard = RateCardEditorModel()

        // Schedule defaults: Wednesday, lag 1 (the spec default), anchor on the upcoming Wednesday.
        scheduleKind = .weekly
        payWeekday = .wednesday
        lagWeeks = 1
        anchorPayday = today
        dayOfMonth = 1
        switch source?.schedule {
        case .weekly(let day, let lag)?:
            payWeekday = day; lagWeeks = lag
        case .fortnightly(let anchor, let lag)?:
            scheduleKind = .fortnightly; anchorPayday = anchor; lagWeeks = lag
        case .monthly(let dom)?:
            scheduleKind = .monthly; dayOfMonth = dom
        case nil:
            break
        }

        taxKind = .none
        flatPercentText = ""
        tableID = nil
        switch source?.taxMethod {
        case .flatPercent(let bp)?:
            taxKind = .flat; flatPercentText = MoneyParser.text(fromCents: bp)
        case .withholdingTable(let tid)?:
            taxKind = .table; tableID = tid
        default:
            break
        }
    }

    /// Loads withholding tables and the existing rate card. Call once from `.task`.
    func load(from repository: Repository) {
        tables = (try? repository.withholdingTables()) ?? []
        if let cardID = existingRateCardID,
           let card = (try? repository.rateCards())?.first(where: { $0.id == cardID }) {
            rateCard.adopt(card)
        }
        if taxKind == .table, tableID == nil { tableID = tables.first?.id }
        isLoaded = true
    }

    // MARK: Derived

    var availableSchedules: [IncomeScheduleKind] { kind == .hourly ? [.weekly, .fortnightly] : IncomeScheduleKind.allCases }

    /// Fixed pay entered as net is never taxed.
    var taxApplies: Bool { !(kind == .fixed && amountIsNet) }

    var schedule: PaySchedule {
        switch scheduleKind {
        case .weekly: .weekly(payWeekday: payWeekday, lagWeeks: lagWeeks)
        case .fortnightly: .fortnightly(anchorPayday: anchorPayday, lagWeeks: lagWeeks)
        case .monthly: .monthly(dayOfMonth: dayOfMonth)
        }
    }

    // MARK: Validation (one message per invalid field)

    func issue(for field: Field) -> String? {
        switch field {
        case .name:
            return name.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a name, such as Example Bar." : nil
        case .rates:
            guard kind == .hourly else { return nil }
            return rateCard.rulesAreValid ? nil : "Add at least one valid rate below."
        case .amount:
            guard kind == .fixed else { return nil }
            guard let cents = MoneyParser.cents(from: fixedAmountText) else { return "Enter an amount such as 1,200.00." }
            return cents > 0 ? nil : "The amount must be more than zero."
        case .schedule:
            if scheduleKind == .monthly {
                if kind == .hourly { return "Monthly pay is only for fixed amounts." }
                if !(1...31).contains(dayOfMonth) { return "Choose a day from 1 to 31." }
            }
            if scheduleKind != .monthly, !(0...8).contains(lagWeeks) { return "Choose a lag from 0 to 8 weeks." }
            return nil
        case .tax:
            guard taxApplies else { return nil }
            switch taxKind {
            case .none:
                return nil
            case .flat:
                guard let bp = MoneyParser.basisPoints(fromPercent: flatPercentText) else { return "Enter a percentage such as 15 or 12.5." }
                return (1...10_000).contains(bp) ? nil : "The percentage must be above 0 and at most 100."
            case .table:
                guard let tableID, tables.contains(where: { $0.id == tableID }) else {
                    return tables.isEmpty ? "No withholding tables are stored yet." : "Choose a withholding table."
                }
                return nil
            }
        case .softCap:
            return capIssue(softCapText)
        case .hardCap:
            if let issue = capIssue(hardCapText) { return issue }
            if let soft = EditorNumber.wholeNumber(from: softCapText), let hard = EditorNumber.wholeNumber(from: hardCapText), hard < soft {
                return "The hard limit can't be below the soft limit."
            }
            return nil
        }
    }

    private func capIssue(_ text: String) -> String? {
        if text.trimmingCharacters(in: .whitespaces).isEmpty { return nil }
        guard let hours = EditorNumber.wholeNumber(from: text), (1...168).contains(hours) else { return "Enter whole hours from 1 to 168, or leave blank." }
        return nil
    }

    var isValid: Bool { Field.allCases.allSatisfy { issue(for: $0) == nil } }

    var taxMethod: TaxMethod? {
        guard taxApplies else { return TaxMethod.none }
        switch taxKind {
        case .none: return TaxMethod.none
        case .flat: return MoneyParser.basisPoints(fromPercent: flatPercentText).map { .flatPercent(basisPoints: $0) }
        case .table: return tableID.map { .withholdingTable(id: $0) }
        }
    }

    /// The source to save, or nil while invalid. `rateCardID` is the stored rate card for hourly sources.
    func makeSource(rateCardID: Int?) -> IncomeSource? {
        guard isValid, let taxMethod else { return nil }
        let soft = EditorNumber.wholeNumber(from: softCapText)
        let hard = EditorNumber.wholeNumber(from: hardCapText)
        return IncomeSource(
            id: id, name: name.trimmingCharacters(in: .whitespaces), kind: kind,
            rateCardID: kind == .hourly ? rateCardID : nil,
            fixedAmount: kind == .fixed ? MoneyParser.cents(from: fixedAmountText).map { Money(cents: $0) } : nil,
            amountIsNet: kind == .fixed && amountIsNet,
            schedule: schedule, taxMethod: taxMethod,
            weeklyCapSoftHours: soft, weeklyCapHardHours: hard, isActive: isActive)
    }

    // MARK: Repository

    /// Saves the rate card (hourly) and then the source. Returns the stored source.
    @discardableResult
    func save(using repository: Repository) throws -> IncomeSource {
        guard isValid else { revealAllIssues(); throw EditorError.invalid }
        var cardID: Int?
        if kind == .hourly {
            let trimmed = name.trimmingCharacters(in: .whitespaces)
            cardID = try rateCard.save(using: repository, nameOverride: "\(trimmed) rates").id
        }
        guard let source = makeSource(rateCardID: cardID) else { throw EditorError.invalid }
        return try repository.save(source)
    }

    /// Deletes the source. Its rate card goes too when no other source uses it.
    func delete(using repository: Repository) throws {
        guard id != 0 else { return }
        try repository.deleteIncomeSource(id: id)
        if let cardID = existingRateCardID, !(try repository.incomeSources()).contains(where: { $0.rateCardID == cardID }) {
            try repository.deleteRateCard(id: cardID)
        }
    }
}

// MARK: - View

/// Create or edit an income source (hourly with a rate card, or a fixed amount).
/// Pass `source: nil` to create. `onSave` receives the stored source; `onDelete` runs after a delete.
struct IncomeSourceEditor: View {
    var style: EditorStyle = .push
    var onSave: (IncomeSource) -> Void
    var onDelete: (() -> Void)?

    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?
    @Environment(\.dismiss) private var dismiss
    @State private var model: IncomeSourceEditorModel
    @State private var errorMessage: String?

    /// `kind` is the type a new source starts as (ignored when editing `source`).
    init(source: IncomeSource? = nil, kind: IncomeKind = .hourly, style: EditorStyle = .push,
         onSave: @escaping (IncomeSource) -> Void, onDelete: (() -> Void)? = nil) {
        self.style = style
        self.onSave = onSave
        self.onDelete = onDelete
        _model = State(initialValue: IncomeSourceEditorModel(source: source, newKind: kind))
    }

    private var currency: String { store?.settings.currency ?? "AUD" }

    var body: some View {
        EditorScaffold(title: model.isNew ? "New income" : "Edit income", style: style,
                       canSave: model.isLoaded && model.isValid, errorMessage: $errorMessage, onSave: save) {
            basics
            if model.kind == .hourly {
                RateCardSections(model: model.rateCard, currencyCode: currency, showsName: false)
                if !model.isNew {
                    Section {
                        NavigationLink {
                            TemplatesEditor(sourceID: model.id)
                        } label: {
                            Label("Default week of shifts", systemImage: "calendar.badge.clock")
                        }
                    }
                }
            } else {
                fixedAmount
            }
            schedule
            if model.taxApplies { tax }
            caps
            Section {
                Toggle("Active", isOn: $model.isActive)
            } footer: {
                Text("Inactive sources are left out of the forecast.")
            }
            if !model.isNew {
                EditorDeleteSection(title: "Delete income source", message: "This also deletes its shifts, templates and logged pay. It can't be undone.") {
                    delete()
                }
            }
        }
        .task { model.load(from: repository) }
    }

    // MARK: Sections

    private var basics: some View {
        Section("Income") {
            Picker("Type", selection: $model.kind) {
                Text("Hourly (shifts)").tag(IncomeKind.hourly)
                Text("Fixed amount").tag(IncomeKind.fixed)
            }
            .pickerStyle(.segmented)
            TextField("Name", text: $model.name)
                .textInputAutocapitalization(.words)
                .onChange(of: model.name) { model.touch(.name) }
            EditorFieldMessage(model.visibleIssue(for: .name))
        }
    }

    private var fixedAmount: some View {
        Section {
            MoneyField("Amount per pay", text: $model.fixedAmountText, currencyCode: currency)
                .onChange(of: model.fixedAmountText) { model.touch(.amount) }
            EditorFieldMessage(model.visibleIssue(for: .amount))
            Picker("Amount is", selection: $model.amountIsNet) {
                Text("Gross (before tax)").tag(false)
                Text("Net (take-home)").tag(true)
            }
        } header: {
            Text("Amount")
        } footer: {
            Text("Net amounts are not taxed again.")
        }
    }

    private var schedule: some View {
        Section("Pay schedule") {
            if model.availableSchedules.count > 1 {
                Picker("Paid", selection: $model.scheduleKind) {
                    ForEach(model.availableSchedules, id: \.self) { Text($0.title).tag($0) }
                }
            }
            switch model.scheduleKind {
            case .weekly:
                Picker("Payday", selection: $model.payWeekday) {
                    ForEach(Weekday.allCases, id: \.self) { Text(EditorLabels.weekdayName($0)).tag($0) }
                }
                lagStepper
            case .fortnightly:
                DatePicker("Payday (any past or future one)", selection: $model.anchorPayday.asDate,
                           displayedComponents: .date)
                lagStepper
            case .monthly:
                Stepper("Day of month: \(model.dayOfMonth)", value: $model.dayOfMonth, in: 1...31)
                    .accessibilityLabel("Day of month")
                    .accessibilityValue("\(model.dayOfMonth)")
            }
            EditorFieldMessage(model.visibleIssue(for: .schedule))
        }
    }

    @ViewBuilder
    private var lagStepper: some View {
        if model.kind == .hourly {
            Stepper(value: $model.lagWeeks, in: 0...8) {
                Text("Weeks after work: \(model.lagWeeks)")
            }
            .accessibilityLabel("Weeks between the end of the work week and payday")
            .accessibilityValue("\(model.lagWeeks)")
            Text("1 means the week just finished is paid on the next payday.")
                .font(.footnote).foregroundStyle(.secondary)
        }
    }

    private var tax: some View {
        Section {
            Picker("Tax", selection: $model.taxKind) {
                ForEach(IncomeTaxKind.allCases, id: \.self) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .onChange(of: model.taxKind) { model.touch(.tax) }
            switch model.taxKind {
            case .none:
                EmptyView()
            case .flat:
                LabeledContent("Percent") {
                    HStack(spacing: 4) {
                        TextField("0", text: $model.flatPercentText)
                            .keyboardType(.decimalPad)
                            .multilineTextAlignment(.trailing)
                            .monospacedDigit()
                            .accessibilityLabel("Flat tax percent")
                            .onChange(of: model.flatPercentText) { model.touch(.tax) }
                        Text("%").foregroundStyle(.secondary).accessibilityHidden(true)
                    }
                }
            case .table:
                Picker("Table", selection: $model.tableID) {
                    Text("Choose…").tag(String?.none)
                    ForEach(model.tables) { Text($0.name).tag(String?.some($0.id)) }
                }
            }
            EditorFieldMessage(model.visibleIssue(for: .tax))
        } header: {
            Text("Tax")
        } footer: {
            Text(model.taxKind == .none ? "No tax is taken: pay is treated as already net." : "Tax is worked out on each pay period's gross.")
        }
    }

    private var caps: some View {
        Section {
            capField("Soft limit", text: $model.softCapText, field: .softCap)
            capField("Hard limit", text: $model.hardCapText, field: .hardCap)
        } header: {
            Text("Weekly hours limits (optional)")
        } footer: {
            Text("Warnings only. Saving a shift is never blocked.")
        }
    }

    @ViewBuilder
    private func capField(_ title: LocalizedStringKey, text: Binding<String>, field: IncomeSourceEditorModel.Field) -> some View {
        LabeledContent(title) {
            HStack(spacing: 4) {
                TextField("None", text: text)
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .accessibilityLabel(title)
                    .onChange(of: text.wrappedValue) { model.touch(field) }
                    .onChange(of: text.wrappedValue) { if field == .softCap { model.touch(.hardCap) } }
                Text("hours").foregroundStyle(.secondary).accessibilityHidden(true)
            }
        }
        EditorFieldMessage(model.visibleIssue(for: field))
    }

    // MARK: Actions

    private func save() {
        do {
            let stored = try model.save(using: repository)
            onSave(stored)
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func delete() {
        do {
            try model.delete(using: repository)
            onDelete?()
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

#Preview("New hourly") {
    NavigationStack { IncomeSourceEditor { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}

#Preview("Demo source") {
    NavigationStack { IncomeSourceEditor(source: DemoData.source) { _ in } onDelete: {} }
        .environment(\.repository, EditorPreviewSupport.repository())
}

#Preview("New fixed income (sheet)") {
    IncomeSourceEditor(kind: .fixed, style: .sheet) { _ in }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}
