import SwiftUI
import Observation
import BeaverCore

/// Form state for logging pay received.
@MainActor
@Observable
final class LogPayModel {
    let roster: RosterViewModel

    var sourceID: Int {
        didSet { if oldValue != sourceID { sourceChanged() } }
    }
    var payday: LocalDate
    var netText = ""
    var grossText = ""
    var taxText = ""
    var note = ""

    init(roster: RosterViewModel) {
        self.roster = roster
        let first = roster.hourlySources.first ?? roster.sourcesForPay.first
        sourceID = first?.id ?? 0
        payday = first.flatMap { roster.paydays(for: $0).first(where: { $0 <= roster.today }) ?? roster.paydays(for: $0).first } ?? roster.today
        loadExisting()
    }

    var source: IncomeSource? { roster.source(id: sourceID) }

    var paydays: [LocalDate] {
        var list = source.map { roster.paydays(for: $0) } ?? []
        if !list.contains(payday) { list.insert(payday, at: 0) }
        return list
    }

    var expectedNet: Money? { source.map { roster.expectedNet(for: $0, payday: payday) } }

    var actualNet: Money? { RosterFormat.parseMoney(netText) }

    /// Actual minus expected net, or nil until an amount is entered.
    var difference: Money? {
        guard let actual = actualNet, let expected = expectedNet else { return nil }
        return RosterViewModel.difference(actual: actual, expected: expected)
    }

    var existing: PayReceived? { roster.pays.first { $0.sourceID == sourceID && $0.payDate == payday } }

    var canSave: Bool { source != nil && actualNet != nil }

    private func sourceChanged() {
        guard let source else { return }
        let list = roster.paydays(for: source)
        payday = list.first(where: { $0 <= roster.today }) ?? list.first ?? payday
        loadExisting()
    }

    func paydayChanged() { loadExisting() }

    /// Fills the fields from an already logged pay for this source and payday, or clears them.
    func loadExisting() {
        if let pay = existing { load(pay) } else { netText = ""; grossText = ""; taxText = ""; note = "" }
    }

    func load(_ pay: PayReceived) {
        if sourceID != pay.sourceID { sourceID = pay.sourceID }
        payday = pay.payDate
        netText = RosterFormat.editableText(pay.net)
        grossText = RosterFormat.editableText(pay.gross)
        taxText = RosterFormat.editableText(pay.tax)
        note = pay.note
    }

    @discardableResult
    func save() -> Bool {
        guard let net = actualNet else { return false }
        return roster.logPay(sourceID: sourceID, payDate: payday, net: net, gross: RosterFormat.parseMoney(grossText),
                             tax: RosterFormat.parseMoney(taxText), note: note.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/// "+$12.00 above expected" in green, "-$4.00 below expected" in red.
struct DifferenceLabel: View {
    let difference: Money
    let currency: String

    var body: some View {
        Label {
            Text(text).monospacedDigit()
        } icon: {
            Image(systemName: symbol)
        }
        .foregroundStyle(color)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibility)
    }

    private var text: String {
        if difference.cents == 0 { return "On target" }
        return "\(RosterFormat.signedMoney(difference, currency: currency)) \(difference.cents > 0 ? "above" : "below")"
    }

    private var accessibility: String {
        if difference.cents == 0 { return "Exactly as expected" }
        let amount = RosterFormat.money(Money(cents: abs(difference.cents)), currency: currency)
        return "\(amount) \(difference.cents > 0 ? "above" : "below") expected"
    }

    private var symbol: String {
        difference.cents > 0 ? "arrow.up.circle.fill" : difference.cents < 0 ? "arrow.down.circle.fill" : "equal.circle.fill"
    }

    private var color: Color { difference.cents > 0 ? .green : difference.cents < 0 ? .red : .secondary }
}

struct LogPayView: View {
    @State var model: LogPayModel
    @Environment(\.dismiss) private var dismiss

    private var roster: RosterViewModel { model.roster }
    private var currency: String { roster.currency }

    var body: some View {
        NavigationStack {
            Form {
                if roster.sourcesForPay.isEmpty {
                    Section {
                        ContentUnavailableView("No income sources", systemImage: "briefcase",
                                               description: Text("Add an income source in Settings first."))
                    }
                } else {
                    entrySection
                    recentSection
                }
            }
            .navigationTitle("Log pay received")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        if model.save() { dismiss() }
                    }
                    .disabled(!model.canSave)
                }
            }
        }
    }

    @ViewBuilder private var entrySection: some View {
        Section {
            Picker("Source", selection: $model.sourceID) {
                ForEach(roster.sourcesForPay) { Text($0.name).tag($0.id) }
            }
            Picker("Payday", selection: $model.payday) {
                ForEach(model.paydays, id: \.self) { Text(RosterFormat.day($0, in: roster.timeZone)).tag($0) }
            }
            .onChange(of: model.payday) { _, _ in model.paydayChanged() }
            if let expected = model.expectedNet {
                LabeledContent("Expected net") { MoneyText(cents: expected.cents, currencyCode: currency) }
            }
        }
        Section("Actual pay") {
            amountField("Net received", text: $model.netText)
            amountField("Gross (optional)", text: $model.grossText)
            amountField("Tax (optional)", text: $model.taxText)
            TextField("Note", text: $model.note, axis: .vertical).lineLimit(1...3)
            if let difference = model.difference {
                HStack {
                    Text("Difference")
                    Spacer()
                    DifferenceLabel(difference: difference, currency: currency)
                }
            }
            if model.existing != nil {
                Text("Saving will update the pay already logged for this payday.")
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
    }

    private func amountField(_ title: String, text: Binding<String>) -> some View {
        HStack {
            Text(title)
            Spacer()
            TextField("0.00", text: text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .monospacedDigit()
                .frame(maxWidth: 140)
                .accessibilityLabel(title)
        }
    }

    @ViewBuilder private var recentSection: some View {
        Section("Recent pays") {
            if roster.recentPays.isEmpty {
                Text("Nothing logged yet.").foregroundStyle(.secondary)
            }
            ForEach(roster.recentPays.prefix(10)) { pay in
                Button { model.load(pay) } label: { PayRow(pay: pay, roster: roster) }
                    .buttonStyle(.plain)
                    .swipeActions {
                        Button(role: .destructive) { roster.deletePay(pay) } label: { Label("Delete", systemImage: "trash") }
                    }
            }
        }
    }
}

private struct PayRow: View {
    let pay: PayReceived
    let roster: RosterViewModel

    var body: some View {
        let source = roster.source(id: pay.sourceID)
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(source?.name ?? "Unknown source").font(.body)
                Spacer()
                MoneyText(cents: pay.net.cents, currencyCode: roster.currency)
            }
            HStack {
                Text(RosterFormat.day(pay.payDate, in: roster.timeZone)).font(.caption).foregroundStyle(.secondary)
                Spacer()
                if let source {
                    DifferenceLabel(difference: RosterViewModel.difference(actual: pay.net, expected: roster.expectedNet(for: source, payday: pay.payDate)),
                                    currency: roster.currency)
                        .font(.caption)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }
}
