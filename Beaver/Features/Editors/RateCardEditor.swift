import SwiftUI
import Observation
import BeaverCore

// MARK: - Draft rule

/// One editable line of a rate card. Text fields stay as text until validated.
struct RateRuleDraft: Identifiable, Equatable, Sendable {
    var id = UUID()
    var band = ""
    var rateText = ""
    var days: DaySelector = .weekdays
    var hasWindow = false
    var fromMinute = 9 * 60
    var toMinute = 17 * 60
    /// Set once the user edits the band or rate, so a fresh blank row shows no errors.
    var bandTouched = false
    var rateTouched = false

    init() {}

    init(rule: RateRule) {
        band = rule.band
        rateText = MoneyParser.text(fromCents: rule.centsPerHour)
        days = rule.days
        if let w = rule.window {
            hasWindow = true
            fromMinute = w.startMinute
            toMinute = w.endMinute
        }
    }

    var bandIssue: String? {
        band.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a band name, such as Weekday evening." : nil
    }

    var rateIssue: String? {
        guard let cents = MoneyParser.cents(from: rateText) else { return "Enter an hourly rate such as 30.00." }
        return cents > 0 ? nil : "The rate must be more than zero."
    }

    var windowIssue: String? {
        guard hasWindow else { return nil }
        guard (0..<1440).contains(fromMinute), (1...1440).contains(toMinute), fromMinute < toMinute else {
            return "The window must end after it starts. Use 24:00 for midnight."
        }
        return nil
    }

    var isValid: Bool { bandIssue == nil && rateIssue == nil && windowIssue == nil }

    /// The engine rule, or nil while any field is invalid.
    var rule: RateRule? {
        guard isValid, let cents = MoneyParser.cents(from: rateText) else { return nil }
        return RateRule(band: band.trimmingCharacters(in: .whitespaces), centsPerHour: cents, days: days,
                        window: hasWindow ? ClockWindow(startMinute: fromMinute, endMinute: toMinute) : nil)
    }
}

// MARK: - View model

@MainActor
@Observable
final class RateCardEditorModel {
    private(set) var id: Int
    var name: String
    var rules: [RateRuleDraft]
    /// Pristine forms show no errors: these are set by editing, or by `revealAllIssues()` on a failed save.
    private(set) var nameTouched = false
    private(set) var rulesTouched = false
    private(set) var revealAll = false

    func touchName() { nameTouched = true }
    func revealAllIssues() { revealAll = true }

    var visibleNameIssue: String? { nameTouched || revealAll ? nameIssue : nil }
    var visibleRulesIssue: String? { rulesTouched || revealAll ? rulesIssue : nil }
    func visibleBandIssue(_ draft: RateRuleDraft) -> String? { draft.bandTouched || revealAll ? draft.bandIssue : nil }
    func visibleRateIssue(_ draft: RateRuleDraft) -> String? { draft.rateTouched || revealAll ? draft.rateIssue : nil }

    init(card: RateCard? = nil) {
        id = card?.id ?? 0
        name = card?.name ?? ""
        rules = card?.rules.map(RateRuleDraft.init(rule:)) ?? []
    }

    /// Replaces this model's contents (including its id) with a stored card.
    func adopt(_ card: RateCard) {
        id = card.id
        name = card.name
        rules = card.rules.map(RateRuleDraft.init(rule:))
    }

    // Editing

    /// Replaces all rules with "Typical casual (template)". The name is kept unless it is empty.
    func applyTypicalCasual() {
        let template = RateCard.typicalCasual()
        rules = template.rules.map(RateRuleDraft.init(rule:))
        rulesTouched = true
        if name.trimmingCharacters(in: .whitespaces).isEmpty { name = template.name }
    }

    func addRule() { rules.append(RateRuleDraft()); rulesTouched = true }

    func removeRule(id: UUID) { rules.removeAll { $0.id == id }; rulesTouched = true }

    func moveRule(id: UUID, by offset: Int) {
        guard let i = rules.firstIndex(where: { $0.id == id }) else { return }
        let j = i + offset
        guard rules.indices.contains(j) else { return }
        rules.swapAt(i, j)
    }

    // Validation

    var nameIssue: String? {
        name.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a name for this rate card." : nil
    }

    var rulesIssue: String? { rules.isEmpty ? "Add at least one rate, or start from the template." : nil }

    /// Rules that are fully valid so far, in order.
    var validRules: [RateRule] { rules.compactMap(\.rule) }

    /// Weekday times no rule prices. A warning only: it never blocks saving.
    var gaps: [ClockWindow] { RateCard(name: "", rules: validRules).weekdayGaps() }

    var gapWarning: String? {
        guard !rules.isEmpty, !gaps.isEmpty else { return nil }
        let list = gaps.map(EditorLabels.window).joined(separator: ", ")
        return "No rate covers weekdays at \(list). Shifts in those times would be paid nothing."
    }

    /// True when the rules (not the name) are ready to save. Used when the card is embedded in another editor.
    var rulesAreValid: Bool { rulesIssue == nil && rules.allSatisfy(\.isValid) }

    var isValid: Bool { nameIssue == nil && rulesAreValid }

    /// The card to save, or nil while invalid. `nameOverride` is used by editors that hide the name field.
    func makeCard(nameOverride: String? = nil) -> RateCard? {
        guard rulesAreValid else { return nil }
        let cardName = (nameOverride ?? name).trimmingCharacters(in: .whitespaces)
        guard !cardName.isEmpty else { return nil }
        return RateCard(id: id, name: cardName, rules: validRules)
    }

    @discardableResult
    func save(using repository: Repository, nameOverride: String? = nil) throws -> RateCard {
        guard let card = makeCard(nameOverride: nameOverride) else { revealAllIssues(); throw EditorError.invalid }
        return try repository.save(card)
    }
}

/// Thrown when `save` is called on a model that is not valid.
enum EditorError: LocalizedError {
    case invalid

    var errorDescription: String? { "Some fields need attention before this can be saved." }
}

// MARK: - Views

/// The rate card sections, for embedding in a Form (the income source editor) or the standalone editor.
struct RateCardSections: View {
    @Bindable var model: RateCardEditorModel
    var currencyCode: String = "AUD"
    var showsName = true
    @State private var confirmTemplate = false

    var body: some View {
        if showsName {
            Section("Name") {
                TextField("Rate card name", text: $model.name)
                    .onChange(of: model.name) { model.touchName() }
                EditorFieldMessage(model.visibleNameIssue)
            }
        }

        Section {
            Button {
                if model.rules.isEmpty { model.applyTypicalCasual() } else { confirmTemplate = true }
            } label: {
                Label("Start from Typical casual (template)", systemImage: "wand.and.stars")
            }
            .confirmationDialog("Replace the current rates?", isPresented: $confirmTemplate, titleVisibility: .visible) {
                Button("Replace with template", role: .destructive) { model.applyTypicalCasual() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("The template uses made-up example rates. Edit each band's rate afterwards.")
            }
        } footer: {
            Text("Rates are examples only. Enter the dollars per hour from your own contract.")
        }

        ForEach(Array(model.rules.enumerated()), id: \.element.id) { index, draft in
            ruleSection(index: index, draft: draft)
        }

        Section {
            Button { model.addRule() } label: { Label("Add rate", systemImage: "plus.circle") }
            EditorFieldMessage(model.visibleRulesIssue)
            if let warning = model.gapWarning { EditorWarning(text: warning) }
        } footer: {
            Text("The first matching rate wins. A public holiday rate overrides the others on holidays.")
        }
    }

    @ViewBuilder
    private func ruleSection(index: Int, draft: RateRuleDraft) -> some View {
        let binding = Binding<RateRuleDraft>(
            get: { model.rules.first(where: { $0.id == draft.id }) ?? draft },
            set: { new in if let i = model.rules.firstIndex(where: { $0.id == draft.id }) { model.rules[i] = new } }
        )
        Section {
            TextField("Band name", text: Binding(
                get: { binding.wrappedValue.band },
                set: { binding.wrappedValue.band = $0; binding.wrappedValue.bandTouched = true }))
            EditorFieldMessage(model.visibleBandIssue(binding.wrappedValue))

            Picker("Days", selection: binding.days) {
                ForEach(DaySelector.allCases, id: \.self) { Text(EditorLabels.dayTitle($0)).tag($0) }
            }

            Toggle("Only part of the day", isOn: binding.hasWindow)
            if binding.wrappedValue.hasWindow {
                EditorMinutePicker(title: "From", minute: binding.fromMinute, range: 0...1425)
                EditorMinutePicker(title: "To", minute: binding.toMinute, range: 15...1440)
                EditorFieldMessage(binding.wrappedValue.windowIssue)
            }

            MoneyField("Rate", text: Binding(
                get: { binding.wrappedValue.rateText },
                set: { binding.wrappedValue.rateText = $0; binding.wrappedValue.rateTouched = true }),
                       currencyCode: currencyCode, suffix: "/ hr")
            EditorFieldMessage(model.visibleRateIssue(binding.wrappedValue))
        } header: {
            HStack {
                Text("Rate \(index + 1)")
                Spacer()
                Menu {
                    Button { model.moveRule(id: draft.id, by: -1) } label: { Label("Move up", systemImage: "arrow.up") }
                        .disabled(index == 0)
                    Button { model.moveRule(id: draft.id, by: 1) } label: { Label("Move down", systemImage: "arrow.down") }
                        .disabled(index == model.rules.count - 1)
                    Button(role: .destructive) { model.removeRule(id: draft.id) } label: { Label("Remove", systemImage: "trash") }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel("Actions for rate \(index + 1)")
            }
        }
    }
}

/// Edit or create a rate card on its own. Saves through the repository.
struct RateCardEditor: View {
    var style: EditorStyle = .push
    var onSave: (RateCard) -> Void

    @Environment(\.repository) private var repository
    @Environment(AppStore.self) private var store: AppStore?
    @Environment(\.dismiss) private var dismiss
    @State private var model: RateCardEditorModel
    @State private var errorMessage: String?

    init(card: RateCard? = nil, style: EditorStyle = .push, onSave: @escaping (RateCard) -> Void) {
        self.style = style
        self.onSave = onSave
        _model = State(initialValue: RateCardEditorModel(card: card))
    }

    var body: some View {
        EditorScaffold(title: "Rate card", style: style, canSave: model.isValid, errorMessage: $errorMessage, onSave: save) {
            RateCardSections(model: model, currencyCode: store?.settings.currency ?? "AUD")
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
}

#if DEBUG
#Preview("New") {
    NavigationStack { RateCardEditor { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}

#Preview("Demo card") {
    NavigationStack { RateCardEditor(card: DemoData.rateCard) { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository())
}
#endif
