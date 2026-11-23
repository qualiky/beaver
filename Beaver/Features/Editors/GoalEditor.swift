import SwiftUI
import Observation
import BeaverCore

// MARK: - View model

@MainActor
@Observable
final class GoalEditorModel {
    enum Field: CaseIterable, Sendable { case name, target, deadline, saved }

    let id: Int
    var name: String
    var targetText: String
    var hasDeadline: Bool
    var deadline: LocalDate
    /// Amount already saved. Only used for a new goal: it becomes an initial deposit.
    var savedText: String
    var makePrimary: Bool
    let isNew: Bool
    private(set) var today: LocalDate
    private let originalDeadline: LocalDate?
    private let wasPrimary: Bool

    /// - Parameters:
    ///   - isPrimary: whether `goal` is currently the primary goal (the `primaryGoalId` setting).
    ///   - isFirstGoal: true when no other goal exists, so a new goal defaults to primary.
    init(goal: Goal? = nil, isPrimary: Bool = false, isFirstGoal: Bool = true, today: LocalDate = LocalDate.today()) {
        isNew = goal == nil
        self.today = today
        id = goal?.id ?? 0
        name = goal?.name ?? ""
        targetText = goal.map { MoneyParser.text(fromCents: $0.target.cents) } ?? ""
        hasDeadline = goal?.deadline != nil
        deadline = goal?.deadline ?? today.adding(months: 6)
        originalDeadline = goal?.deadline
        savedText = ""
        wasPrimary = isPrimary
        makePrimary = goal == nil ? isFirstGoal : isPrimary
    }

    /// Loads whether this goal is primary and whether other goals exist. Call from `.task`.
    func load(from repository: Repository) {
        let settings = try? repository.settings()
        if let settings { today = LocalDate.today(in: settings.timeZone) }
        let goals = (try? repository.goals()) ?? []
        if isNew {
            makePrimary = goals.isEmpty
        } else {
            makePrimary = settings?.primaryGoalID == id
        }
    }

    func issue(for field: Field) -> String? {
        switch field {
        case .name:
            return name.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a name, such as Course fees." : nil
        case .target:
            guard let cents = MoneyParser.cents(from: targetText) else { return "Enter a target such as 6,000.00." }
            return cents > 0 ? nil : "The target must be more than zero."
        case .deadline:
            guard hasDeadline else { return nil }
            // An unchanged existing deadline is allowed even if it has passed.
            if deadline == originalDeadline { return nil }
            return deadline > today ? nil : "Choose a date after today."
        case .saved:
            guard isNew, !savedText.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return MoneyParser.cents(from: savedText) == nil ? "Enter an amount such as 250.00, or leave blank." : nil
        }
    }

    var isValid: Bool { Field.allCases.allSatisfy { issue(for: $0) == nil } }

    /// Initial deposit in cents, for a new goal with a positive saved amount.
    var initialDepositCents: Int? {
        guard isNew, let cents = MoneyParser.cents(from: savedText), cents > 0 else { return nil }
        return cents
    }

    func makeGoal() -> Goal? {
        guard isValid, let target = MoneyParser.cents(from: targetText) else { return nil }
        return Goal(id: id, name: name.trimmingCharacters(in: .whitespaces), target: Money(cents: target),
                    deadline: hasDeadline ? deadline : nil)
    }

    /// Saves the goal, then its initial deposit (new goals only), then the primary-goal setting.
    @discardableResult
    func save(using repository: Repository) throws -> Goal {
        guard let goal = makeGoal() else { throw EditorError.invalid }
        let stored = try repository.save(goal)
        if let cents = initialDepositCents {
            try repository.save(Deposit(goalID: stored.id, date: today, amount: Money(cents: cents), note: "Already saved"))
        }
        if makePrimary {
            try repository.setPrimaryGoalID(stored.id)
        } else if wasPrimary {
            try repository.setPrimaryGoalID(nil)
        }
        return stored
    }

    func delete(using repository: Repository) throws {
        guard id != 0 else { return }
        try repository.deleteGoal(id: id)
        if (try repository.settings()).primaryGoalID == id { try repository.setPrimaryGoalID(nil) }
    }
}

// MARK: - View

/// Create or edit a savings goal. For a new goal, "Already saved" becomes an initial deposit.
struct GoalEditor: View {
    var style: EditorStyle = .push
    var onSave: (Goal) -> Void
    var onDelete: (() -> Void)?

    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?
    @Environment(\.dismiss) private var dismiss
    @State private var model: GoalEditorModel
    @State private var errorMessage: String?

    init(goal: Goal? = nil, style: EditorStyle = .push, onSave: @escaping (Goal) -> Void, onDelete: (() -> Void)? = nil) {
        self.style = style
        self.onSave = onSave
        self.onDelete = onDelete
        _model = State(initialValue: GoalEditorModel(goal: goal))
    }

    private var currency: String { store?.settings.currency ?? "AUD" }

    var body: some View {
        EditorScaffold(title: model.isNew ? "New goal" : "Edit goal", style: style, canSave: model.isValid,
                       errorMessage: $errorMessage, onSave: save) {
            Section("Goal") {
                TextField("Name", text: $model.name)
                    .textInputAutocapitalization(.words)
                EditorFieldMessage(model.issue(for: .name))
                MoneyField("Target", text: $model.targetText, currencyCode: currency)
                EditorFieldMessage(model.issue(for: .target))
            }

            Section {
                Toggle("Deadline", isOn: $model.hasDeadline)
                if model.hasDeadline {
                    DatePicker("Save by", selection: $model.deadline.asDate, displayedComponents: .date)
                    EditorFieldMessage(model.issue(for: .deadline))
                }
            } footer: {
                Text("Without a deadline the forecast looks 26 weeks ahead.")
            }

            if model.isNew {
                Section {
                    MoneyField("Already saved", text: $model.savedText, currencyCode: currency)
                    EditorFieldMessage(model.issue(for: .saved))
                } footer: {
                    Text("Counted as a first deposit towards this goal. Leave blank if you're starting from zero.")
                }
            }

            Section {
                Toggle("Make primary goal", isOn: $model.makePrimary)
            } footer: {
                Text("The primary goal drives the Week tab.")
            }

            if !model.isNew {
                EditorDeleteSection(title: "Delete goal", message: "This also deletes its deposits. It can't be undone.") {
                    delete()
                }
            }
        }
        .task { model.load(from: repository) }
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

#Preview("New goal") {
    NavigationStack { GoalEditor { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}

#Preview("Demo goal") {
    NavigationStack { GoalEditor(goal: DemoData.goal) { _ in } onDelete: {} }
        .environment(\.repository, EditorPreviewSupport.repository())
}
