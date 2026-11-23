import SwiftUI
import BeaverCore

/// How an editor is shown. `.push` expects a parent `NavigationStack`; `.sheet` wraps itself in one and adds Cancel.
enum EditorStyle: Sendable {
    case push
    case sheet
}

/// Display strings and symbols shared by the editors.
enum EditorLabels {
    static func weekdayName(_ day: Weekday) -> String {
        switch day {
        case .monday: "Monday"
        case .tuesday: "Tuesday"
        case .wednesday: "Wednesday"
        case .thursday: "Thursday"
        case .friday: "Friday"
        case .saturday: "Saturday"
        case .sunday: "Sunday"
        }
    }

    static func shortWeekday(_ day: Weekday) -> String { String(weekdayName(day).prefix(3)) }

    static func dayTitle(_ days: DaySelector) -> String {
        switch days {
        case .publicHoliday: "Public holidays"
        case .saturday: "Saturdays"
        case .sunday: "Sundays"
        case .weekdays: "Weekdays (Mon–Fri)"
        }
    }

    static func categoryTitle(_ category: BillCategory) -> String {
        switch category {
        case .housing: "Housing"
        case .utilities: "Utilities"
        case .subscription: "Subscription"
        case .transport: "Transport"
        case .debt: "Debt"
        case .insurance: "Insurance"
        case .other: "Other"
        }
    }

    static func categorySymbol(_ category: BillCategory) -> String {
        switch category {
        case .housing: "house"
        case .utilities: "bolt"
        case .subscription: "play.rectangle"
        case .transport: "tram"
        case .debt: "creditcard"
        case .insurance: "shield"
        case .other: "tag"
        }
    }

    /// "07:00", or "24:00" for minute 1440.
    static func clock(minute: Int) -> String {
        String(format: "%02d:%02d", minute / 60, minute % 60)
    }

    static func window(_ w: ClockWindow) -> String {
        "\(clock(minute: w.startMinute))–\(clock(minute: w.endMinute))"
    }

    static func date(_ d: LocalDate) -> String {
        d.date().formatted(.dateTime.day().month(.abbreviated).year())
    }
}

/// Whole-number parsing for hours and counts.
enum EditorNumber {
    /// A whole number from user text, or nil (empty, signs, decimals and other characters are rejected).
    static func wholeNumber(from text: String) -> Int? {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty, t.count <= 9, t.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int(t)
    }
}

extension LocalDate {
    /// A `Date` at noon in `timeZone`, for DatePicker and formatting. Noon avoids daylight saving edges.
    func date(in timeZone: TimeZone = .current) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        return cal.date(from: DateComponents(year: year, month: month, day: day, hour: 12)) ?? Date()
    }
}

/// Bridges BeaverCore's zone-free values to the `Date` a DatePicker needs.
enum EditorDateBridge {
    static func localDate(from date: Date, in timeZone: TimeZone = .current) -> LocalDate {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = timeZone
        let c = cal.dateComponents([.year, .month, .day], from: date)
        return LocalDate(year: c.year ?? 2000, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// A date on a fixed reference day carrying the wall-clock time.
    static func date(from time: LocalTime) -> Date {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        return cal.date(from: DateComponents(year: 2001, month: 1, day: 15, hour: time.hour, minute: time.minute)) ?? Date()
    }

    static func localTime(from date: Date) -> LocalTime {
        var cal = Calendar(identifier: .gregorian)
        cal.timeZone = .current
        let c = cal.dateComponents([.hour, .minute], from: date)
        return LocalTime(hour: c.hour ?? 0, minute: c.minute ?? 0)
    }
}

extension Binding where Value == LocalDate {
    /// This binding as a `Date` binding for `DatePicker`.
    var asDate: Binding<Date> {
        Binding<Date>(get: { wrappedValue.date() }, set: { wrappedValue = EditorDateBridge.localDate(from: $0) })
    }
}

extension Binding where Value == LocalTime {
    /// This binding as a `Date` binding for an hour-and-minute `DatePicker`.
    var asDate: Binding<Date> {
        Binding<Date>(get: { EditorDateBridge.date(from: wrappedValue) }, set: { wrappedValue = EditorDateBridge.localTime(from: $0) })
    }
}

/// Tracks which fields the user has edited, so a pristine form shows no errors. `revealAll` is set when a save is attempted.
struct FieldTouches<Field: Hashable & Sendable>: Sendable {
    private(set) var touched: Set<Field> = []
    var revealAll = false

    mutating func touch(_ field: Field) { touched.insert(field) }

    func shows(_ field: Field) -> Bool { revealAll || touched.contains(field) }
}

/// A small red inline message under an invalid field. Renders nothing when `text` is nil.
struct EditorFieldMessage: View {
    let text: String?

    init(_ text: String?) { self.text = text }

    var body: some View {
        if let text {
            Label(text, systemImage: "exclamationmark.circle.fill")
                .font(.footnote)
                .foregroundStyle(.red)
                .accessibilityLabel("Problem: \(text)")
        }
    }
}

/// An orange, non-blocking warning.
struct EditorWarning: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "exclamationmark.triangle.fill")
            .font(.footnote)
            .foregroundStyle(.orange)
            .accessibilityLabel("Warning: \(text)")
    }
}

/// The shared Form chrome: title, Save (disabled until valid), Cancel for sheets, and a save-error alert.
struct EditorScaffold<Content: View>: View {
    let title: LocalizedStringKey
    let style: EditorStyle
    let canSave: Bool
    @Binding var errorMessage: String?
    let onSave: () -> Void
    @ViewBuilder let content: Content
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        if style == .sheet {
            NavigationStack { form }
        } else {
            form
        }
    }

    private var form: some View {
        Form { content }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if style == .sheet {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: onSave)
                        .disabled(!canSave)
                        .accessibilityHint(canSave ? "" : "Fix the highlighted fields first")
                }
            }
            .alert("Couldn't save", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
    }
}

/// A destructive "Delete …" row with a confirmation dialog.
struct EditorDeleteSection: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let action: () -> Void
    @State private var confirming = false

    var body: some View {
        Section {
            Button(role: .destructive) { confirming = true } label: {
                Label(title, systemImage: "trash")
            }
            .confirmationDialog(title, isPresented: $confirming, titleVisibility: .visible) {
                Button("Delete", role: .destructive, action: action)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text(message)
            }
        }
    }
}

/// A picker of minutes since midnight in 15-minute steps (the current value is always offered).
struct EditorMinutePicker: View {
    let title: LocalizedStringKey
    @Binding var minute: Int
    let range: ClosedRange<Int>

    private var options: [Int] {
        var values = Set(stride(from: 0, through: 1440, by: 15).filter { range.contains($0) })
        values.insert(minute)
        return values.sorted()
    }

    var body: some View {
        Picker(title, selection: $minute) {
            ForEach(options, id: \.self) { Text(EditorLabels.clock(minute: $0)).tag($0) }
        }
    }
}

#if DEBUG
/// In-memory repositories for previews. Demo data is the fictional "Sam" persona only.
@MainActor
enum EditorPreviewSupport {
    static func repository(demo: Bool = true) -> Repository {
        let repo = try! Repository.inMemory()
        if demo { try? repo.loadDemoData() }
        return repo
    }
}
#endif
