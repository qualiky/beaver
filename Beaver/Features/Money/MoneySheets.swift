import SwiftUI
import BeaverCore

/// Shared chrome for the small Money sheets: Cancel, Save and an inline error.
struct MoneySheetScaffold<Content: View>: View {
    let title: LocalizedStringKey
    let canSave: Bool
    let onSave: () throws -> Void
    @ViewBuilder var content: Content
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            Form {
                content
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Palette.negative) }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        do { try onSave(); dismiss() } catch { errorMessage = error.localizedDescription }
                    }
                    .disabled(!canSave)
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

struct MarkPaidSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var form: MarkPaidForm

    init(row: MoneyBillRow, today: LocalDate) { _form = State(initialValue: MarkPaidForm(row: row, today: today)) }

    var body: some View {
        MoneySheetScaffold(title: "Mark paid", canSave: form.isValid) {
            try form.save(using: MoneyActions(repository: repository))
        } content: {
            Section {
                LabeledContent("Bill", value: form.row.name)
                LabeledContent("Due", value: form.row.dateText)
                MoneyField("Amount paid", text: $form.amountText, currencyCode: store.settings.currency)
                DatePicker("Paid on", selection: $form.paidDate.asDate, displayedComponents: .date)
            } footer: {
                Text("Change the amount if you paid something different. Later changes to the bill's amount won't alter this payment.")
            }
        }
    }
}

struct DepositSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var form: DepositForm
    let goalName: String

    init(goalID: Int, goalName: String, today: LocalDate) {
        self.goalName = goalName
        _form = State(initialValue: DepositForm(goalID: goalID, today: today))
    }

    var body: some View {
        MoneySheetScaffold(title: "Add deposit", canSave: form.isValid) {
            try form.save(using: MoneyActions(repository: repository))
        } content: {
            Section {
                MoneyField("Amount", text: $form.amountText, currencyCode: store.settings.currency)
                DatePicker("Date", selection: $form.date.asDate, displayedComponents: .date)
                TextField("Note (optional)", text: $form.note)
            } header: {
                Text(goalName)
            }
        }
    }
}

struct WeekTotalSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var form: WeekTotalForm
    let weekStarts: [LocalDate]
    let model: SpendingModel

    init(model: SpendingModel, weekStart: LocalDate) {
        self.model = model
        weekStarts = model.history.map(\.weekStart)
        _form = State(initialValue: WeekTotalForm(weekStart: weekStart, existing: model.total(weekStart: weekStart)))
    }

    var body: some View {
        MoneySheetScaffold(title: "Set week total", canSave: form.isValid) {
            try form.save(using: MoneyActions(repository: repository))
        } content: {
            Section {
                Picker("Week", selection: $form.weekStart) {
                    ForEach(weekStarts, id: \.self) { Text(model.weekTitle($0) + weekSuffix($0)).tag($0) }
                }
                MoneyField("Total spent", text: $form.amountText, currencyCode: store.settings.currency)
            } footer: {
                Text("A week total replaces that week's quick entries. Clear it to go back to the entries.")
            }
        }
        .onChange(of: form.weekStart) { _, week in
            form.amountText = model.total(weekStart: week).map { MoneyParser.text(fromCents: $0.cents) } ?? ""
        }
    }

    private func weekSuffix(_ start: LocalDate) -> String {
        start == model.currentWeekStart ? " (\(DateText.dayMonth(start, locale: model.fmt.locale)))" : ""
    }
}

struct ExpenseEditSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @State private var form: ExpenseForm

    init(expense: Expense) { _form = State(initialValue: ExpenseForm(expense: expense)) }

    var body: some View {
        MoneySheetScaffold(title: "Edit expense", canSave: form.isValid) {
            try form.save(using: MoneyActions(repository: repository))
        } content: {
            Section {
                MoneyField("Amount", text: $form.amountText, currencyCode: store.settings.currency)
                Picker("Category", selection: $form.category) {
                    ForEach(SpendCategory.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                DatePicker("Date", selection: $form.date.asDate, displayedComponents: .date)
                TextField("Note (optional)", text: $form.note)
            }
        }
    }
}
