import SwiftUI
import BeaverCore

/// Quick-add for a living expense (amount, category, note). Saved with today's date.
struct AddExpenseSheet: View {
    @Environment(AppStore.self) private var store
    @Environment(\.repository) private var repository
    @Environment(\.dismiss) private var dismiss

    @State private var amountText = ""
    @State private var category: SpendCategory = .food
    @State private var note = ""
    @State private var errorMessage: String?
    @FocusState private var amountFocused: Bool

    private var cents: Int? {
        guard let c = MoneyInput.cents(from: amountText), c > 0 else { return nil }
        return c
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Amount", text: $amountText)
                        .keyboardType(.decimalPad)
                        .monospacedDigit()
                        .focused($amountFocused)
                        .accessibilityLabel("Amount in \(store.settings.currency)")
                    Picker("Category", selection: $category) {
                        ForEach(SpendCategory.allCases, id: \.self) { Text($0.title).tag($0) }
                    }
                    TextField("Note (optional)", text: $note)
                }
                if let errorMessage {
                    Section { Text(errorMessage).foregroundStyle(Palette.negative) }
                }
            }
            .navigationTitle("Add expense")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save).disabled(cents == nil)
                }
            }
            .onAppear { amountFocused = true }
        }
        .presentationDetents([.medium])
    }

    private func save() {
        guard let cents else { return }
        do {
            try repository.save(Expense(date: store.input.today, amount: Money(cents: cents), category: category,
                                        note: note.trimmingCharacters(in: .whitespacesAndNewlines)))
            dismiss()
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}
