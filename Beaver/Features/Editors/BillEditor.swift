import SwiftUI
import Observation
import BeaverCore

// MARK: - Cadence kind and presets

/// The cadence choices without their parameters (the day of month and the day count live in the editor).
enum BillCadenceKind: String, CaseIterable, Sendable, Hashable {
    case weekly, fortnightly, everyNDays, monthly, quarterly, yearly, oneOff

    var title: String {
        switch self {
        case .weekly: "Weekly"
        case .fortnightly: "Fortnightly"
        case .everyNDays: "Every N days"
        case .monthly: "Monthly on a day"
        case .quarterly: "Quarterly"
        case .yearly: "Yearly"
        case .oneOff: "One-off"
        }
    }

    init(_ cadence: Cadence) {
        switch cadence {
        case .weekly: self = .weekly
        case .fortnightly: self = .fortnightly
        case .everyNDays: self = .everyNDays
        case .monthly: self = .monthly
        case .quarterly: self = .quarterly
        case .yearly: self = .yearly
        case .oneOff: self = .oneOff
        }
    }
}

/// A common bill the user can tap to start from. A preset carries no amount: only a name, a category and a
/// sensible default cadence.
struct BillPreset: Identifiable, Hashable, Sendable {
    let name: String
    let category: BillCategory
    let cadence: BillCadenceKind

    var id: String { name }

    /// Rent, Phone, Internet, Electricity, Gas, Water, Transport card, Gym, Streaming, Music, Cloud storage,
    /// Insurance and Loan repayment.
    static let common: [BillPreset] = [
        BillPreset(name: "Rent", category: .housing, cadence: .weekly),
        BillPreset(name: "Phone", category: .utilities, cadence: .monthly),
        BillPreset(name: "Internet", category: .utilities, cadence: .monthly),
        BillPreset(name: "Electricity", category: .utilities, cadence: .quarterly),
        BillPreset(name: "Gas", category: .utilities, cadence: .quarterly),
        BillPreset(name: "Water", category: .utilities, cadence: .quarterly),
        BillPreset(name: "Transport card", category: .transport, cadence: .weekly),
        BillPreset(name: "Gym", category: .subscription, cadence: .fortnightly),
        BillPreset(name: "Streaming", category: .subscription, cadence: .monthly),
        BillPreset(name: "Music", category: .subscription, cadence: .monthly),
        BillPreset(name: "Cloud storage", category: .subscription, cadence: .monthly),
        BillPreset(name: "Insurance", category: .insurance, cadence: .monthly),
        BillPreset(name: "Loan repayment", category: .debt, cadence: .fortnightly),
    ]
}

/// A grid of tappable chips, one per preset. Selecting calls `onSelect`; the amount is left for the user.
struct BillPresetPicker: View {
    var presets: [BillPreset] = BillPreset.common
    var selectedName: String?
    var onSelect: (BillPreset) -> Void

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 120), spacing: 8, alignment: .leading)], alignment: .leading, spacing: 8) {
            ForEach(presets) { preset in
                let selected = preset.name == selectedName
                Button { onSelect(preset) } label: {
                    Label(preset.name, systemImage: EditorLabels.categorySymbol(preset.category))
                        .font(.subheadline)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .buttonBorderShape(.capsule)
                .tint(selected ? .accentColor : .secondary)
                .accessibilityLabel("\(preset.name), \(EditorLabels.categoryTitle(preset.category)), \(preset.cadence.title)")
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

// MARK: - View model

@MainActor
@Observable
final class BillEditorModel {
    enum Field: CaseIterable, Sendable { case name, amount, cadence, dueDate }

    let id: Int
    var name: String
    var amountText: String
    var cadenceKind: BillCadenceKind {
        didSet { if cadenceKind == .monthly, oldValue != .monthly { dayOfMonth = anchorDate.day } }
    }
    var everyNDaysText: String
    var dayOfMonth: Int
    var anchorDate: LocalDate {
        didSet { if cadenceKind == .monthly, anchorDate != oldValue { dayOfMonth = anchorDate.day } }
    }
    var category: BillCategory
    var account: String
    var isActive: Bool
    var note: String
    private(set) var presetName: String?
    let isNew: Bool

    init(bill: Bill? = nil, today: LocalDate = LocalDate.today()) {
        isNew = bill == nil
        id = bill?.id ?? 0
        name = bill?.name ?? ""
        amountText = bill.map { MoneyParser.text(fromCents: $0.amount.cents) } ?? ""
        anchorDate = bill?.anchorDate ?? today
        category = bill?.category ?? .other
        account = bill?.account ?? ""
        isActive = bill?.isActive ?? true
        note = bill?.note ?? ""
        cadenceKind = bill.map { BillCadenceKind($0.cadence) } ?? .monthly
        everyNDaysText = "28"
        dayOfMonth = (bill?.anchorDate ?? today).day
        switch bill?.cadence {
        case .everyNDays(let n)?: everyNDaysText = String(n)
        case .monthly(let day)?: dayOfMonth = day
        default: break
        }
    }

    /// Fills the name, category and cadence from a preset. The amount is left alone.
    func apply(_ preset: BillPreset) {
        name = preset.name
        category = preset.category
        cadenceKind = preset.cadence
        presetName = preset.name
    }

    var cadence: Cadence? {
        switch cadenceKind {
        case .weekly: .weekly
        case .fortnightly: .fortnightly
        case .everyNDays: EditorNumber.wholeNumber(from: everyNDaysText).map { .everyNDays($0) }
        case .monthly: .monthly(dayOfMonth: dayOfMonth)
        case .quarterly: .quarterly
        case .yearly: .yearly
        case .oneOff: .oneOff
        }
    }

    func issue(for field: Field) -> String? {
        switch field {
        case .name:
            return name.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a name, such as Rent." : nil
        case .amount:
            guard let cents = MoneyParser.cents(from: amountText) else { return "Enter an amount such as 49.99." }
            return cents > 0 ? nil : "The amount must be more than zero."
        case .cadence:
            switch cadenceKind {
            case .everyNDays:
                guard let n = EditorNumber.wholeNumber(from: everyNDaysText), (1...365).contains(n) else { return "Enter a number of days from 1 to 365." }
                return nil
            case .monthly:
                return (1...31).contains(dayOfMonth) ? nil : "Choose a day from 1 to 31."
            default:
                return nil
            }
        case .dueDate:
            return nil
        }
    }

    var isValid: Bool { Field.allCases.allSatisfy { issue(for: $0) == nil } }

    func makeBill() -> Bill? {
        guard isValid, let cents = MoneyParser.cents(from: amountText), let cadence else { return nil }
        let label = account.trimmingCharacters(in: .whitespaces)
        return Bill(id: id, name: name.trimmingCharacters(in: .whitespaces), amount: Money(cents: cents), cadence: cadence,
                    anchorDate: anchorDate, category: category, account: label.isEmpty ? nil : label,
                    isActive: isActive, note: note)
    }

    @discardableResult
    func save(using repository: Repository) throws -> Bill {
        guard let bill = makeBill() else { throw EditorError.invalid }
        return try repository.save(bill)
    }

    func delete(using repository: Repository) throws {
        guard id != 0 else { return }
        try repository.deleteBill(id: id)
    }
}

// MARK: - View

/// Create or edit a bill. For a new bill the common-bill chips are shown at the top. Pass `preset` to start from one.
struct BillEditor: View {
    var style: EditorStyle = .push
    var onSave: (Bill) -> Void
    var onDelete: (() -> Void)?

    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?
    @Environment(\.dismiss) private var dismiss
    @State private var model: BillEditorModel
    @State private var errorMessage: String?

    init(bill: Bill? = nil, preset: BillPreset? = nil, style: EditorStyle = .push,
         onSave: @escaping (Bill) -> Void, onDelete: (() -> Void)? = nil) {
        self.style = style
        self.onSave = onSave
        self.onDelete = onDelete
        let model = BillEditorModel(bill: bill)
        if let preset { model.apply(preset) }
        _model = State(initialValue: model)
    }

    private var currency: String { store?.settings.currency ?? "AUD" }

    var body: some View {
        EditorScaffold(title: model.isNew ? "New bill" : "Edit bill", style: style, canSave: model.isValid,
                       errorMessage: $errorMessage, onSave: save) {
            if model.isNew {
                Section("Common bills") {
                    BillPresetPicker(selectedName: model.presetName) { model.apply($0) }
                        .padding(.vertical, 4)
                }
            }

            Section("Bill") {
                TextField("Name", text: $model.name)
                    .textInputAutocapitalization(.words)
                EditorFieldMessage(model.issue(for: .name))
                MoneyField("Amount", text: $model.amountText, currencyCode: currency)
                EditorFieldMessage(model.issue(for: .amount))
                Picker("Category", selection: $model.category) {
                    ForEach(BillCategory.allCases, id: \.self) {
                        Label(EditorLabels.categoryTitle($0), systemImage: EditorLabels.categorySymbol($0)).tag($0)
                    }
                }
            }

            Section {
                Picker("Repeats", selection: $model.cadenceKind) {
                    ForEach(BillCadenceKind.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                if model.cadenceKind == .everyNDays {
                    LabeledContent("Every") {
                        HStack(spacing: 4) {
                            TextField("28", text: $model.everyNDaysText)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.trailing)
                                .accessibilityLabel("Number of days between payments")
                            Text("days").foregroundStyle(.secondary).accessibilityHidden(true)
                        }
                    }
                }
                if model.cadenceKind == .monthly {
                    Stepper("On day \(model.dayOfMonth) of the month", value: $model.dayOfMonth, in: 1...31)
                        .accessibilityLabel("Day of the month")
                        .accessibilityValue("\(model.dayOfMonth)")
                }
                EditorFieldMessage(model.issue(for: .cadence))
                DatePicker(model.cadenceKind == .oneOff ? "Due date" : "Next due", selection: $model.anchorDate.asDate,
                           displayedComponents: .date)
            } header: {
                Text("Schedule")
            } footer: {
                Text(scheduleFooter)
            }

            Section("Details (optional)") {
                TextField("Account label, e.g. Everyday card", text: $model.account)
                TextField("Note", text: $model.note, axis: .vertical)
                    .lineLimit(1...4)
            }

            Section {
                Toggle("Active", isOn: $model.isActive)
            } footer: {
                Text("Inactive bills are left out of the forecast.")
            }

            if !model.isNew {
                EditorDeleteSection(title: "Delete bill", message: "This also deletes its payment history. It can't be undone.") {
                    delete()
                }
            }
        }
    }

    private var scheduleFooter: String {
        switch model.cadenceKind {
        case .monthly: "The next due date is the first payment. After that it falls on the chosen day, or the last day of a short month."
        case .quarterly, .yearly: "Repeats from the next due date, keeping its day of the month."
        case .oneOff: "Counted once, on this date."
        default: "Repeats from the next due date. Nothing is counted before it."
        }
    }

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

#Preview("New bill") {
    NavigationStack { BillEditor { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}

#Preview("From preset") {
    NavigationStack { BillEditor(preset: BillPreset.common[3]) { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}

#Preview("Demo bill") {
    NavigationStack { BillEditor(bill: DemoData.bills[2]) { _ in } onDelete: {} }
        .environment(\.repository, EditorPreviewSupport.repository())
}

#Preview("Preset chips") {
    Form { BillPresetPicker(selectedName: "Gym") { _ in } }
}
