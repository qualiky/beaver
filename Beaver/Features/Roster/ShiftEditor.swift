import SwiftUI
import Observation
import BeaverCore

/// Form state for adding or editing one shift, with a live pay preview.
@MainActor
@Observable
final class ShiftEditorModel: Identifiable {
    static let breakPresets = [0, 15, 30, 45, 60]

    let existingID: Int?
    let sources: [IncomeSource]
    let timeZone: TimeZone
    @ObservationIgnored private let rateCards: [RateCard]
    @ObservationIgnored private let holidays: [Holiday]
    @ObservationIgnored private let lastShifts: [Int: Shift]

    var sourceID: Int {
        didSet { if oldValue != sourceID, existingID == nil { applyDefaults(for: sourceID) } }
    }
    var date: LocalDate
    var start: LocalTime
    var end: LocalTime
    var breakMinutes: Int
    var status: ShiftStatus
    var note: String
    /// True while the custom break stepper is showing.
    var customBreak: Bool

    nonisolated var id: String { existingID.map { "edit-\($0)" } ?? "new" }

    init(editing shift: Shift?, defaultDate: LocalDate, sources: [IncomeSource], rateCards: [RateCard],
         holidays: [Holiday], lastShifts: [Int: Shift], timeZone: TimeZone) {
        self.existingID = shift?.id
        self.sources = sources
        self.rateCards = rateCards
        self.holidays = holidays
        self.lastShifts = lastShifts
        self.timeZone = timeZone
        if let shift {
            sourceID = shift.sourceID
            date = shift.date
            start = shift.start
            end = shift.end
            breakMinutes = shift.breakMinutes
            status = shift.status
            note = shift.note
            customBreak = !Self.breakPresets.contains(shift.breakMinutes)
        } else {
            let first = sources.first?.id ?? 0
            sourceID = first
            date = defaultDate
            // Fast path: start from the last shift entered for the source, else a plain 9 to 5 with no break.
            let last = lastShifts[first]
            start = last?.start ?? "09:00"
            end = last?.end ?? "17:00"
            breakMinutes = last?.breakMinutes ?? 0
            status = .rostered
            note = ""
            customBreak = !Self.breakPresets.contains(last?.breakMinutes ?? 0)
        }
    }

    /// Copies start, end and break from the last shift entered for `sourceID` (adding only).
    func applyDefaults(for sourceID: Int) {
        guard let last = lastShifts[sourceID] else { return }
        start = last.start
        end = last.end
        breakMinutes = last.breakMinutes
        customBreak = !Self.breakPresets.contains(last.breakMinutes)
    }

    var shift: Shift {
        Shift(id: existingID ?? 0, sourceID: sourceID, date: date, start: start, end: end,
              breakMinutes: breakMinutes, status: status, note: note.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    var selectedSource: IncomeSource? { sources.first { $0.id == sourceID } }

    var rateCard: RateCard? {
        guard let id = selectedSource?.rateCardID else { return nil }
        return rateCards.first { $0.id == id }
    }

    var pay: ShiftPayResult? {
        rateCard.map { ShiftPay.calculate(shift: shift, rateCard: $0, holidays: Set(holidays.map(\.date))) }
    }

    /// Gross counted for the shift as it stands: nothing when cancelled.
    var gross: Money { status == .cancelled ? .zero : (pay?.gross ?? .zero) }

    var finishesNextDay: Bool { shift.crossesMidnight }

    var holidayNames: [String] {
        var names: [String] = []
        for d in [date] + (finishesNextDay ? [date.adding(days: 1)] : []) {
            if let n = holidays.first(where: { $0.date == d })?.name, !names.contains(n) { names.append(n) }
        }
        return names
    }

    var durationText: String { RosterFormat.hoursAndMinutes(shift.durationMinutes) }
}

struct ShiftEditorView: View {
    @Bindable var editor: ShiftEditorModel
    let currency: String
    let onSave: (Shift) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Shift") {
                    if editor.sources.count > 1 {
                        Picker("Source", selection: $editor.sourceID) {
                            ForEach(editor.sources) { Text($0.name).tag($0.id) }
                        }
                    } else if let source = editor.selectedSource {
                        LabeledContent("Source", value: source.name)
                    }
                    DatePicker("Date", selection: dateBinding, displayedComponents: .date)
                        .environment(\.timeZone, editor.timeZone)
                    DatePicker("Start", selection: timeBinding(\.start), displayedComponents: .hourAndMinute)
                    DatePicker("End", selection: timeBinding(\.end), displayedComponents: .hourAndMinute)
                    if editor.finishesNextDay {
                        Label("Finishes next day", systemImage: "moon.stars")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    breakControl
                }

                Section("Status") {
                    Picker("Status", selection: $editor.status) {
                        ForEach(ShiftStatus.allCases, id: \.self) { Text($0.rosterTitle).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    TextField("Note", text: $editor.note, axis: .vertical)
                        .lineLimit(1...3)
                }

                previewSection
            }
            .navigationTitle(editor.existingID == nil ? "Add shift" : "Edit shift")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(editor.shift)
                        dismiss()
                    }
                    .disabled(editor.selectedSource == nil)
                }
            }
        }
    }

    // MARK: Break

    private var breakSelection: Binding<Int> {
        Binding(
            get: { editor.customBreak ? -1 : editor.breakMinutes },
            set: { value in
                if value < 0 {
                    editor.customBreak = true
                } else {
                    editor.customBreak = false
                    editor.breakMinutes = value
                }
            })
    }

    @ViewBuilder private var breakControl: some View {
        Picker("Break", selection: breakSelection) {
            ForEach(ShiftEditorModel.breakPresets, id: \.self) { Text($0 == 0 ? "None" : "\($0) min").tag($0) }
            Text("Custom").tag(-1)
        }
        if editor.customBreak {
            Stepper(value: $editor.breakMinutes, in: 0...480, step: 5) {
                Text("Break: \(editor.breakMinutes) min").monospacedDigit()
            }
            .accessibilityLabel("Custom break")
            .accessibilityValue("\(editor.breakMinutes) minutes")
        }
    }

    // MARK: Preview

    @ViewBuilder private var previewSection: some View {
        Section("Pay preview") {
            if let pay = editor.pay {
                ForEach(pay.bands, id: \.band) { band in
                    HStack {
                        Chip(text: RosterFormat.chip(forBand: band.band))
                        Text(band.band)
                        Spacer()
                        VStack(alignment: .trailing) {
                            Text(RosterFormat.hours(hundredths: band.hoursHundredths)).monospacedDigit()
                            Text("\(RosterFormat.money(Money(cents: band.rateCentsPerHour), currency: currency))/h")
                                .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                    }
                    .accessibilityElement(children: .combine)
                }
                if !editor.holidayNames.isEmpty {
                    LabeledContent("Public holiday") {
                        FlowLayout {
                            ForEach(editor.holidayNames, id: \.self) { Chip(text: $0, style: .holiday, systemImage: "star.fill") }
                        }
                    }
                }
                LabeledContent("Paid") {
                    Text("\(RosterFormat.hours(minutes: pay.paidMinutes)) of \(editor.durationText)").monospacedDigit()
                }
                HStack {
                    Text("Gross").font(.headline)
                    Spacer()
                    MoneyText(cents: editor.gross.cents, currencyCode: currency).font(.headline)
                }
                .accessibilityElement(children: .combine)
                if editor.status == .cancelled {
                    Text("Cancelled shifts earn nothing.").font(.footnote).foregroundStyle(.secondary)
                }
            } else {
                Text("This source has no rate card yet, so pay can not be previewed.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    // MARK: Bindings

    private var dateBinding: Binding<Date> {
        Binding(
            get: { editor.date.date(in: editor.timeZone) },
            set: { editor.date = RosterFormat.localDate(from: $0, in: editor.timeZone) })
    }

    private func timeBinding(_ keyPath: ReferenceWritableKeyPath<ShiftEditorModel, LocalTime>) -> Binding<Date> {
        Binding(
            get: { RosterFormat.timeAsDate(editor[keyPath: keyPath]) },
            set: { editor[keyPath: keyPath] = RosterFormat.localTime(from: $0) })
    }
}

extension ShiftStatus {
    var rosterTitle: String {
        switch self {
        case .rostered: "Rostered"
        case .worked: "Worked"
        case .cancelled: "Cancelled"
        }
    }

    var rosterSymbol: String {
        switch self {
        case .rostered: "calendar"
        case .worked: "checkmark.circle.fill"
        case .cancelled: "xmark.circle"
        }
    }
}
