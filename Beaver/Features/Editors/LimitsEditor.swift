import SwiftUI
import Observation
import BeaverCore

/// Everything the limits editor manages: the fortnightly hours limit, public holidays and unavailable ranges.
struct LimitsValue: Equatable, Sendable {
    var fortnightLimit: FortnightLimit?
    var holidays: [Holiday] = []
    var unavailable: [UnavailableRange] = []
}

// MARK: - View model

@MainActor
@Observable
final class LimitsEditorModel {
    enum Field: CaseIterable, Sendable { case hours }

    var hasLimit = false
    var hoursText = ""
    var effectiveFrom: LocalDate
    private(set) var holidays: [Holiday] = []
    /// New rows carry a negative temporary id until saved.
    private(set) var unavailable: [UnavailableRange] = []
    private(set) var isLoaded = false

    private var original = LimitsValue()
    private var nextTempID = -1

    init(value: LimitsValue? = nil, today: LocalDate = LocalDate.today()) {
        effectiveFrom = today
        if let value { apply(value) }
    }

    private func apply(_ value: LimitsValue) {
        original = value
        hasLimit = value.fortnightLimit != nil
        hoursText = value.fortnightLimit.map { String($0.hoursPerFortnight) } ?? ""
        if let limit = value.fortnightLimit { effectiveFrom = limit.effectiveFrom }
        holidays = value.holidays.sorted { $0.date < $1.date }
        unavailable = value.unavailable.sorted { $0.from < $1.from }
        isLoaded = true
    }

    /// Reads the stored limits (the latest fortnight limit is the one shown).
    func load(from repository: Repository) {
        guard !isLoaded else { return }
        let limit = ((try? repository.hoursLimits()) ?? []).max { $0.effectiveFrom < $1.effectiveFrom }
        apply(LimitsValue(fortnightLimit: limit,
                          holidays: (try? repository.holidays()) ?? [],
                          unavailable: (try? repository.unavailableRanges()) ?? []))
    }

    // MARK: Validation

    func issue(for field: Field) -> String? {
        switch field {
        case .hours:
            guard hasLimit else { return nil }
            guard let hours = EditorNumber.wholeNumber(from: hoursText), (1...336).contains(hours) else {
                return "Enter whole hours from 1 to 336."
            }
            return nil
        }
    }

    var isValid: Bool { Field.allCases.allSatisfy { issue(for: $0) == nil } }

    /// Why a holiday can't be saved, or nil. `editing` is the date the row had before editing.
    func holidayIssue(name: String, date: LocalDate, editing: LocalDate?) -> String? {
        if name.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter a name, such as Christmas Day." }
        if date != editing, holidays.contains(where: { $0.date == date }) { return "A holiday is already set on this date." }
        return nil
    }

    func unavailableIssue(from: LocalDate, to: LocalDate) -> String? {
        to < from ? "The end date can't be before the start date." : nil
    }

    // MARK: List editing

    /// Adds a holiday, or replaces the one that was on `editing`. Returns false (and changes nothing) if invalid.
    @discardableResult
    func saveHoliday(_ holiday: Holiday, editing: LocalDate? = nil) -> Bool {
        let trimmed = Holiday(date: holiday.date, name: holiday.name.trimmingCharacters(in: .whitespaces))
        guard holidayIssue(name: trimmed.name, date: trimmed.date, editing: editing) == nil else { return false }
        if let editing { holidays.removeAll { $0.date == editing } }
        holidays.append(trimmed)
        holidays.sort { $0.date < $1.date }
        return true
    }

    func removeHoliday(date: LocalDate) { holidays.removeAll { $0.date == date } }

    /// Adds a range (id 0) or replaces the one with the same id. Returns false if invalid.
    @discardableResult
    func saveUnavailable(_ range: UnavailableRange) -> Bool {
        guard unavailableIssue(from: range.from, to: range.to) == nil else { return false }
        var r = range
        r.note = r.note.trimmingCharacters(in: .whitespacesAndNewlines)
        if let i = unavailable.firstIndex(where: { $0.id == r.id && r.id != 0 }) {
            unavailable[i] = r
        } else {
            if r.id == 0 { r.id = nextTempID; nextTempID -= 1 }
            unavailable.append(r)
        }
        unavailable.sort { $0.from < $1.from }
        return true
    }

    func removeUnavailable(id: Int) { unavailable.removeAll { $0.id == id } }

    // MARK: Value and repository

    func makeValue() -> LimitsValue? {
        guard isValid else { return nil }
        var limit: FortnightLimit?
        if hasLimit, let hours = EditorNumber.wholeNumber(from: hoursText) {
            limit = FortnightLimit(id: original.fortnightLimit?.id ?? 0, hoursPerFortnight: hours, effectiveFrom: effectiveFrom)
        }
        return LimitsValue(fortnightLimit: limit, holidays: holidays,
                           unavailable: unavailable.map { var r = $0; if r.id < 0 { r.id = 0 }; return r })
    }

    /// Writes the differences to the repository and returns what is now stored.
    @discardableResult
    func save(using repository: Repository) throws -> LimitsValue {
        guard isLoaded, let value = makeValue() else { throw EditorError.invalid }

        // Fortnight limit: keep one row at most.
        let storedLimits = try repository.hoursLimits()
        var keptLimit: FortnightLimit?
        if let limit = value.fortnightLimit {
            keptLimit = try repository.save(limit)
        }
        for old in storedLimits where old.id != keptLimit?.id { try repository.deleteHoursLimit(id: old.id) }

        // Holidays are keyed by date.
        let keepDates = Set(value.holidays.map(\.date))
        for old in try repository.holidays() where !keepDates.contains(old.date) { try repository.deleteHoliday(date: old.date) }
        for holiday in value.holidays { try repository.save(holiday) }

        // Unavailable ranges are keyed by id.
        let keepIDs = Set(value.unavailable.map(\.id))
        var storedRanges: [UnavailableRange] = []
        for old in try repository.unavailableRanges() where !keepIDs.contains(old.id) { try repository.deleteUnavailableRange(id: old.id) }
        for range in value.unavailable { storedRanges.append(try repository.save(range)) }

        let result = LimitsValue(fortnightLimit: keptLimit, holidays: value.holidays, unavailable: storedRanges)
        apply(result)
        return result
    }
}

// MARK: - Views

/// Edit the fortnightly hours limit, public holidays and unavailable ranges together.
/// With `value: nil` the current stored limits are loaded from the repository.
struct LimitsEditor: View {
    var style: EditorStyle = .push
    var onSave: (LimitsValue) -> Void

    @Environment(\.repository) private var repository
    @Environment(\.dismiss) private var dismiss
    @State private var model: LimitsEditorModel
    @State private var errorMessage: String?
    @State private var holidaySheet: HolidayDraft?
    @State private var unavailableSheet: UnavailableRange?

    init(value: LimitsValue? = nil, style: EditorStyle = .push, onSave: @escaping (LimitsValue) -> Void) {
        self.style = style
        self.onSave = onSave
        _model = State(initialValue: LimitsEditorModel(value: value))
    }

    var body: some View {
        EditorScaffold(title: "Limits and dates", style: style, canSave: model.isLoaded && model.isValid,
                       errorMessage: $errorMessage, onSave: save) {
            limitSection
            holidaySection
            unavailableSection
        }
        .task { model.load(from: repository) }
        .sheet(item: $holidaySheet) { draft in
            HolidaySheet(draft: draft, model: model)
        }
        .sheet(item: $unavailableSheet) { range in
            UnavailableSheet(range: range, model: model)
        }
    }

    private var limitSection: some View {
        Section {
            Toggle("Fortnightly hours limit", isOn: $model.hasLimit)
            if model.hasLimit {
                LabeledContent("Hours") {
                    HStack(spacing: 4) {
                        TextField("48", text: $model.hoursText)
                            .keyboardType(.numberPad)
                            .multilineTextAlignment(.trailing)
                            .accessibilityLabel("Hours per fortnight")
                        Text("per fortnight").foregroundStyle(.secondary).accessibilityHidden(true)
                    }
                }
                EditorFieldMessage(model.issue(for: .hours))
                DatePicker("Effective from", selection: $model.effectiveFrom.asDate, displayedComponents: .date)
            }
        } header: {
            Text("Hours limit")
        } footer: {
            Text("Covers all income sources together, counted in fortnights starting on the Monday of the effective date. Warnings only.")
        }
    }

    private var holidaySection: some View {
        Section {
            ForEach(model.holidays, id: \.date) { holiday in
                Button { holidaySheet = HolidayDraft(holiday: holiday) } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(holiday.name).foregroundStyle(.primary)
                            Text(EditorLabels.date(holiday.date)).font(.subheadline).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityHint("Edit holiday")
            }
            .onDelete { offsets in
                let dates = offsets.map { model.holidays[$0].date }
                dates.forEach { model.removeHoliday(date: $0) }
            }
            Button { holidaySheet = HolidayDraft() } label: { Label("Add public holiday", systemImage: "plus.circle") }
        } header: {
            Text("Public holidays")
        } footer: {
            Text("Hours worked on these dates use the public holiday rate. None are pre-filled because they depend on your region.")
        }
    }

    private var unavailableSection: some View {
        Section {
            ForEach(model.unavailable) { range in
                Button { unavailableSheet = range } label: {
                    HStack {
                        VStack(alignment: .leading) {
                            Text(rangeTitle(range)).foregroundStyle(.primary)
                            if !range.note.isEmpty { Text(range.note).font(.subheadline).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityHint("Edit unavailable dates")
            }
            .onDelete { offsets in
                let ids = offsets.map { model.unavailable[$0].id }
                ids.forEach { model.removeUnavailable(id: $0) }
            }
            Button {
                let today = LocalDate.today()
                unavailableSheet = UnavailableRange(from: today, to: today)
            } label: {
                Label("Add unavailable dates", systemImage: "plus.circle")
            }
        } header: {
            Text("Unavailable dates")
        } footer: {
            Text("Default-week shifts aren't added on these dates, for travel or exams.")
        }
    }

    private func rangeTitle(_ range: UnavailableRange) -> String {
        range.from == range.to ? EditorLabels.date(range.from)
                               : "\(EditorLabels.date(range.from)) to \(EditorLabels.date(range.to))"
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

/// State for the add/edit holiday sheet. `original` is nil for a new holiday.
struct HolidayDraft: Identifiable {
    let id = UUID()
    var original: LocalDate?
    var date: LocalDate
    var name: String

    init() {
        original = nil
        date = LocalDate.today()
        name = ""
    }

    init(holiday: Holiday) {
        original = holiday.date
        date = holiday.date
        name = holiday.name
    }
}

private struct HolidaySheet: View {
    @State var draft: HolidayDraft
    let model: LimitsEditorModel
    @Environment(\.dismiss) private var dismiss

    init(draft: HolidayDraft, model: LimitsEditorModel) {
        _draft = State(initialValue: draft)
        self.model = model
    }

    private var issue: String? { model.holidayIssue(name: draft.name, date: draft.date, editing: draft.original) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Name", text: $draft.name).textInputAutocapitalization(.words)
                    DatePicker("Date", selection: $draft.date.asDate, displayedComponents: .date)
                    EditorFieldMessage(issue)
                }
                if draft.original != nil {
                    Section {
                        Button("Delete holiday", role: .destructive) {
                            if let o = draft.original { model.removeHoliday(date: o) }
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(draft.original == nil ? "New holiday" : "Edit holiday")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") {
                        if model.saveHoliday(Holiday(date: draft.date, name: draft.name), editing: draft.original) { dismiss() }
                    }
                    .disabled(issue != nil)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

private struct UnavailableSheet: View {
    @State var range: UnavailableRange
    let model: LimitsEditorModel
    @Environment(\.dismiss) private var dismiss

    init(range: UnavailableRange, model: LimitsEditorModel) {
        _range = State(initialValue: range)
        self.model = model
    }

    private var issue: String? { model.unavailableIssue(from: range.from, to: range.to) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker("From", selection: $range.from.asDate, displayedComponents: .date)
                    DatePicker("To", selection: $range.to.asDate, displayedComponents: .date)
                    EditorFieldMessage(issue)
                    TextField("Note (optional)", text: $range.note)
                }
                if range.id != 0 {
                    Section {
                        Button("Delete dates", role: .destructive) {
                            model.removeUnavailable(id: range.id)
                            dismiss()
                        }
                    }
                }
            }
            .navigationTitle(range.id == 0 ? "New dates" : "Edit dates")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { if model.saveUnavailable(range) { dismiss() } }
                        .disabled(issue != nil)
                }
            }
        }
        .presentationDetents([.medium])
    }
}

#Preview("Demo limits") {
    NavigationStack { LimitsEditor { _ in } }
        .environment(\.repository, EditorPreviewSupport.repository())
}

#Preview("Empty limits (sheet)") {
    LimitsEditor(style: .sheet) { _ in }
        .environment(\.repository, EditorPreviewSupport.repository(demo: false))
}
