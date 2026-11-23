import SwiftUI
import Observation
import BeaverCore

// MARK: - Draft shift

/// One editable shift in a default week.
struct TemplateShiftDraft: Identifiable, Equatable, Sendable {
    var id = UUID()
    var weekday: Weekday
    var start: LocalTime
    var end: LocalTime
    var breakMinutes: Int

    init(weekday: Weekday = .monday, start: LocalTime = "09:00", end: LocalTime = "17:00", breakMinutes: Int = 30) {
        self.weekday = weekday
        self.start = start
        self.end = end
        self.breakMinutes = breakMinutes
    }

    init(_ shift: TemplateShift) {
        self.init(weekday: shift.weekday, start: shift.start, end: shift.end, breakMinutes: shift.breakMinutes)
    }

    var crossesMidnight: Bool { end <= start }

    var durationMinutes: Int {
        crossesMidnight ? 1440 - start.minutesSinceMidnight + end.minutesSinceMidnight
                        : end.minutesSinceMidnight - start.minutesSinceMidnight
    }

    var issue: String? {
        if start == end { return "Start and end can't be the same time." }
        if breakMinutes < 0 { return "The break can't be negative." }
        if breakMinutes >= durationMinutes { return "The break must be shorter than the shift." }
        return nil
    }

    var shift: TemplateShift? {
        issue == nil ? TemplateShift(weekday: weekday, start: start, end: end, breakMinutes: breakMinutes) : nil
    }

    /// Paid hours as text, e.g. "4.5".
    var paidHoursText: String {
        let paid = max(0, durationMinutes - breakMinutes)
        let tenths = (paid * 10 + 30) / 60 // nearest tenth of an hour, for display only
        return "\(tenths / 10).\(tenths % 10)"
    }
}

// MARK: - View model

@MainActor
@Observable
final class TemplateEditorModel {
    let id: Int
    let sourceID: Int
    var name: String
    var hasEffectiveFrom: Bool
    var effectiveFrom: LocalDate
    var shifts: [TemplateShiftDraft]
    private(set) var nameTouched = false
    private(set) var shiftsTouched = false
    private(set) var revealAll = false

    func touchName() { nameTouched = true }
    func revealAllIssues() { revealAll = true }
    var visibleNameIssue: String? { nameTouched || revealAll ? nameIssue : nil }
    var visibleShiftsIssue: String? { shiftsTouched || revealAll ? shiftsIssue : nil }

    init(sourceID: Int, template: Template? = nil, today: LocalDate = LocalDate.today()) {
        self.sourceID = template?.sourceID ?? sourceID
        id = template?.id ?? 0
        name = template?.name ?? "Default week"
        hasEffectiveFrom = template?.effectiveFrom != nil
        effectiveFrom = template?.effectiveFrom ?? today
        shifts = template?.shifts.map(TemplateShiftDraft.init) ?? []
    }

    /// Adds a shift on the first weekday without one (or Monday), with a 9 to 5 default and a 30 minute break.
    func addShift() {
        let used = Set(shifts.map(\.weekday))
        shifts.append(TemplateShiftDraft(weekday: Weekday.allCases.first { !used.contains($0) } ?? .monday))
        shiftsTouched = true
    }

    func removeShift(id: UUID) { shifts.removeAll { $0.id == id }; shiftsTouched = true }

    var nameIssue: String? {
        name.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a name, such as Semester 1." : nil
    }

    var shiftsIssue: String? { shifts.isEmpty ? "Add at least one shift." : nil }

    /// Total paid minutes in the week, for the footer summary.
    var paidMinutes: Int { shifts.reduce(0) { $0 + max(0, $1.durationMinutes - $1.breakMinutes) } }

    var isValid: Bool { nameIssue == nil && shiftsIssue == nil && shifts.allSatisfy { $0.issue == nil } }

    /// The template to save, shifts ordered by weekday then start. Nil while invalid.
    func makeTemplate() -> Template? {
        guard isValid else { return nil }
        let ordered = shifts.compactMap(\.shift).sorted {
            ($0.weekday, $0.start) < ($1.weekday, $1.start)
        }
        return Template(id: id, sourceID: sourceID, name: name.trimmingCharacters(in: .whitespaces),
                        effectiveFrom: hasEffectiveFrom ? effectiveFrom : nil, shifts: ordered)
    }

    @discardableResult
    func save(using repository: Repository) throws -> Template {
        guard let template = makeTemplate() else { revealAllIssues(); throw EditorError.invalid }
        return try repository.save(template)
    }

    func delete(using repository: Repository) throws {
        guard id != 0 else { return }
        try repository.deleteTemplate(id: id)
    }
}

// MARK: - Views

/// Edit one default week of shifts for an hourly source, optionally effective from a date.
struct TemplateEditor: View {
    var style: EditorStyle = .push
    var onSave: (Template) -> Void
    var onDelete: (() -> Void)?

    @Environment(\.repository) private var repository
    @Environment(\.dismiss) private var dismiss
    @State private var model: TemplateEditorModel
    @State private var errorMessage: String?
    private let isNew: Bool

    init(sourceID: Int, template: Template? = nil, style: EditorStyle = .push,
         onSave: @escaping (Template) -> Void, onDelete: (() -> Void)? = nil) {
        self.style = style
        self.onSave = onSave
        self.onDelete = onDelete
        isNew = template == nil
        _model = State(initialValue: TemplateEditorModel(sourceID: sourceID, template: template))
    }

    var body: some View {
        EditorScaffold(title: isNew ? "New default week" : "Edit default week", style: style,
                       canSave: model.isValid, errorMessage: $errorMessage, onSave: save) {
            Section("Name") {
                TextField("Name", text: $model.name)
                    .onChange(of: model.name) { model.touchName() }
                EditorFieldMessage(model.visibleNameIssue)
            }

            Section {
                Toggle("Starts on a date", isOn: $model.hasEffectiveFrom)
                if model.hasEffectiveFrom {
                    DatePicker("Effective from", selection: $model.effectiveFrom.asDate, displayedComponents: .date)
                }
            } footer: {
                Text(model.hasEffectiveFrom
                     ? "Used from the week containing this date, until a later default week starts."
                     : "Used for every week unless a later default week starts.")
            }

            ForEach(Array(model.shifts.enumerated()), id: \.element.id) { index, draft in
                shiftSection(index: index, draft: draft)
            }

            Section {
                Button { model.addShift() } label: { Label("Add shift", systemImage: "plus.circle") }
                EditorFieldMessage(model.visibleShiftsIssue)
            } footer: {
                if !model.shifts.isEmpty {
                    Text("About \(model.paidMinutes / 60) h \(model.paidMinutes % 60) min paid per week.")
                }
            }

            if !isNew {
                EditorDeleteSection(title: "Delete default week", message: "Weeks that used it fall back to an earlier default week, if any.") {
                    delete()
                }
            }
        }
    }

    @ViewBuilder
    private func shiftSection(index: Int, draft: TemplateShiftDraft) -> some View {
        let binding = Binding<TemplateShiftDraft>(
            get: { model.shifts.first(where: { $0.id == draft.id }) ?? draft },
            set: { new in if let i = model.shifts.firstIndex(where: { $0.id == draft.id }) { model.shifts[i] = new } }
        )
        Section {
            Picker("Day", selection: binding.weekday) {
                ForEach(Weekday.allCases, id: \.self) { Text(EditorLabels.weekdayName($0)).tag($0) }
            }
            DatePicker("Start", selection: binding.start.asDate, displayedComponents: .hourAndMinute)
            DatePicker("End", selection: binding.end.asDate, displayedComponents: .hourAndMinute)
            if binding.wrappedValue.crossesMidnight && binding.wrappedValue.start != binding.wrappedValue.end {
                Label("Finishes next day", systemImage: "moon.stars")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            Stepper(value: binding.breakMinutes, in: 0...240, step: 5) {
                Text("Unpaid break: \(binding.wrappedValue.breakMinutes) min")
            }
            .accessibilityLabel("Unpaid break")
            .accessibilityValue("\(binding.wrappedValue.breakMinutes) minutes")
            EditorFieldMessage(binding.wrappedValue.issue)
        } header: {
            HStack {
                Text("\(EditorLabels.weekdayName(binding.wrappedValue.weekday)) shift")
                Spacer()
                Button(role: .destructive) { model.removeShift(id: draft.id) } label: {
                    Image(systemName: "trash")
                }
                .accessibilityLabel("Remove shift \(index + 1)")
            }
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

/// Lists every default week of a source and opens `TemplateEditor` to add, edit or delete them.
struct TemplatesEditor: View {
    let sourceID: Int
    var style: EditorStyle = .push

    @Environment(\.repository) private var repository
    @Environment(\.dismiss) private var dismiss
    @State private var templates: [Template] = []
    @State private var errorMessage: String?

    init(sourceID: Int, style: EditorStyle = .push) {
        self.sourceID = sourceID
        self.style = style
    }

    var body: some View {
        if style == .sheet {
            NavigationStack { list.toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } } }
        } else {
            list
        }
    }

    private var list: some View {
        List {
            if templates.isEmpty {
                ContentUnavailableView("No default week", systemImage: "calendar.badge.clock",
                                       description: Text("Add the shifts you usually work. They fill future weeks in the forecast."))
                    .listRowBackground(Color.clear)
            }
            ForEach(templates) { template in
                NavigationLink {
                    TemplateEditor(sourceID: sourceID, template: template) { _ in reload() } onDelete: { reload() }
                } label: {
                    TemplateRow(template: template)
                }
            }
            .onDelete(perform: delete)
            Section {
                NavigationLink {
                    TemplateEditor(sourceID: sourceID) { _ in reload() }
                } label: {
                    Label("Add default week", systemImage: "plus.circle")
                }
            }
        }
        .navigationTitle("Default weeks")
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
        .alert("Couldn't delete", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(errorMessage ?? "")
        }
    }

    private func reload() {
        templates = ((try? repository.templates()) ?? [])
            .filter { $0.sourceID == sourceID }
            .sorted { ($0.effectiveFrom ?? LocalDate(year: 0, month: 1, day: 1)) < ($1.effectiveFrom ?? LocalDate(year: 0, month: 1, day: 1)) }
    }

    private func delete(at offsets: IndexSet) {
        do {
            for i in offsets { try repository.deleteTemplate(id: templates[i].id) }
        } catch {
            errorMessage = error.localizedDescription
        }
        reload()
    }
}

private struct TemplateRow: View {
    let template: Template

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(template.name).font(.headline)
            Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
    }

    private var subtitle: String {
        let days = Set(template.shifts.map(\.weekday)).sorted().map(EditorLabels.shortWeekday).joined(separator: ", ")
        let from = template.effectiveFrom.map { "From \(EditorLabels.date($0))" } ?? "Always"
        return "\(from) · \(template.shifts.count) shifts · \(days)"
    }
}

#Preview("Demo template") {
    NavigationStack { TemplateEditor(sourceID: DemoData.sourceID, template: DemoData.template) { _ in } onDelete: {} }
        .environment(\.repository, EditorPreviewSupport.repository())
}

#Preview("New template") {
    NavigationStack { TemplateEditor(sourceID: DemoData.sourceID) { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository())
}

#Preview("All default weeks") {
    NavigationStack { TemplatesEditor(sourceID: DemoData.sourceID) }
        .environment(\.repository, EditorPreviewSupport.repository())
}
