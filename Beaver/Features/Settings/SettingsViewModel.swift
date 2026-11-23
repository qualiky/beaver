import Foundation
import Observation
import BeaverCore

/// All Settings logic that is not layout: backup, demo data, erase, preferences and withholding tables.
@MainActor
@Observable
final class SettingsViewModel {
    /// A destructive action waiting for in-app confirmation.
    enum PendingAction: Equatable, Identifiable {
        case importBackup(fileName: String)
        case loadDemo
        case erase
        var id: String {
            switch self {
            case .importBackup: "import"
            case .loadDemo: "demo"
            case .erase: "erase"
            }
        }
    }

    private(set) var pending: PendingAction?
    /// Shown in an alert. Cleared by the view.
    var errorMessage: String?
    /// A short success line ("Backup imported.").
    var statusMessage: String?
    /// The file chosen for import, held until the user confirms.
    private(set) var pendingImportData: Data?
    private(set) var tables: [WithholdingTable] = []
    private(set) var settings = AppSettings()

    @ObservationIgnored private let repository: Repository
    @ObservationIgnored private let didChange: @MainActor () -> Void
    @ObservationIgnored private let appVersion: String

    init(repository: Repository, appVersion: String? = nil, didChange: @escaping @MainActor () -> Void = {}) {
        self.repository = repository
        self.didChange = didChange
        self.appVersion = appVersion ?? Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        refresh()
    }

    func refresh() {
        do {
            settings = try repository.settings()
            tables = try repository.withholdingTables()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: Export

    /// `beaver-backup-YYYY-MM-DD.json`, with the date in the app's time zone.
    static func exportFilename(for date: Date, timeZone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "beaver-backup-%04d-%02d-%02d.json", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    /// Builds the backup (which records `lastExportAt`) and its file name.
    func makeExport(now: Date = Date()) -> (data: Data, filename: String)? {
        do {
            let data = try Backup(repository: repository, appVersion: appVersion).export(now: now)
            changed()
            return (data, Self.exportFilename(for: now, timeZone: settings.timeZone))
        } catch {
            errorMessage = "The backup could not be created: \(error.localizedDescription)"
            return nil
        }
    }

    /// "Last exported: 24 Nov 2026" or "Never exported".
    var lastExportedText: String {
        guard let date = settings.lastExportAt else { return "Never exported" }
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        f.timeZone = settings.timeZone
        return "Last exported: " + f.string(from: date)
    }

    // MARK: Import

    /// Holds the chosen file and asks for confirmation. Nothing is changed yet.
    func stageImport(data: Data, fileName: String) {
        pendingImportData = data
        pending = .importBackup(fileName: fileName)
    }

    func importFailedToRead(_ error: Error) {
        errorMessage = "The file could not be opened: \(error.localizedDescription)"
    }

    private func confirmImport() {
        defer { pendingImportData = nil }
        guard let data = pendingImportData else { return }
        do {
            try Backup(repository: repository, appVersion: appVersion).import(data)
            statusMessage = "Backup imported. Everything was replaced."
            changed()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: Demo and erase

    func requestLoadDemo() { pending = .loadDemo }
    func requestErase() { pending = .erase }

    func cancelPending() {
        pending = nil
        pendingImportData = nil
    }

    /// Runs the action the user just confirmed.
    func confirmPending() {
        guard let action = pending else { return }
        pending = nil
        switch action {
        case .importBackup: confirmImport()
        case .loadDemo: loadDemo()
        case .erase: eraseAll()
        }
    }

    func loadDemo() {
        do {
            try repository.loadDemoData()
            statusMessage = "Demo data loaded."
            changed()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Wipes every user row and clears every setting (so `onboardingDone` is false). The built-in withholding table stays.
    func eraseAll() {
        do {
            try repository.replaceAll(with: BackupTables())
            statusMessage = "All data erased."
            changed()
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: Preferences

    func setCurrency(_ code: String) { write { try $0.setCurrency(code) } }
    func setTimeZone(_ id: String) { write { try $0.setTimeZone(id) } }

    /// Returns false (and changes nothing) when the text is not an amount.
    @discardableResult
    func setLivingBudget(text: String) -> Bool {
        guard let cents = MoneyParser.cents(from: text) else { return false }
        write { try $0.setLivingBudget(Money(cents: cents)) }
        return true
    }

    private func write(_ body: (Repository) throws -> Void) {
        do { try body(repository); changed() } catch { errorMessage = error.localizedDescription }
    }

    // MARK: Withholding tables

    static func isEditable(_ table: WithholdingTable) -> Bool { !table.isBuiltin }

    /// An editable copy of `table`. The original is untouched.
    @discardableResult
    func duplicate(_ table: WithholdingTable) -> WithholdingTable? {
        var copy = table
        copy.id = "custom-" + UUID().uuidString.lowercased().prefix(8)
        copy.name = table.name + " (copy)"
        copy.isBuiltin = false
        copy.note = nil
        return saveTable(copy)
    }

    @discardableResult
    func saveTable(_ table: WithholdingTable) -> WithholdingTable? {
        do {
            let stored = try repository.save(table)
            changed()
            return stored
        } catch {
            errorMessage = error.localizedDescription
            return nil
        }
    }

    func deleteTable(_ table: WithholdingTable) {
        write { try $0.deleteWithholdingTable(id: table.id) }
    }

    private func changed() {
        refresh()
        didChange()
    }
}

/// Edits a custom withholding table as text rows. `belowX` is whole dollars (empty = no limit);
/// `a` and `b` are decimals with up to four places, stored as ten-thousandths.
struct WithholdingRowDraft: Identifiable, Equatable {
    var id = UUID()
    var belowX: String
    var a: String
    var b: String

    init(row: WithholdingRow? = nil) {
        belowX = row?.belowDollars.map(String.init) ?? ""
        a = row.map { Self.text(tenThousandths: $0.aTenThousandths) } ?? "0"
        b = row.map { Self.text(tenThousandths: $0.bTenThousandths) } ?? "0"
    }

    /// 1600 becomes "0.16"; 578462 becomes "57.8462".
    static func text(tenThousandths v: Int) -> String {
        let whole = v / 10_000
        var frac = String(format: "%04d", v % 10_000)
        while frac.hasSuffix("0") { frac.removeLast() }
        return frac.isEmpty ? "\(whole)" : "\(whole).\(frac)"
    }

    /// "0.16" as 1600. Up to four decimals, no sign.
    static func tenThousandths(from text: String) -> Int? {
        let t = text.trimmingCharacters(in: .whitespaces)
        let parts = t.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false).map(String.init)
        guard !t.isEmpty, parts[0].allSatisfy({ $0.isASCII && $0.isNumber }), parts[0].count <= 8 else { return nil }
        let fracText = parts.count == 2 ? parts[1] : ""
        guard fracText.count <= 4, fracText.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        guard !(parts[0].isEmpty && fracText.isEmpty) else { return nil }
        let padded = fracText + String(repeating: "0", count: 4 - fracText.count)
        return (Int(parts[0].isEmpty ? "0" : parts[0]) ?? 0) * 10_000 + (Int(padded) ?? 0)
    }

    var row: WithholdingRow? {
        let limit = belowX.trimmingCharacters(in: .whitespaces)
        var below: Int?
        if !limit.isEmpty {
            guard limit.allSatisfy({ $0.isASCII && $0.isNumber }), let v = Int(limit) else { return nil }
            below = v
        }
        guard let aValue = Self.tenThousandths(from: a), let bValue = Self.tenThousandths(from: b) else { return nil }
        return WithholdingRow(belowDollars: below, aTenThousandths: aValue, bTenThousandths: bValue)
    }
}

/// Validation and building for the withholding row editor.
@MainActor
@Observable
final class WithholdingEditorModel {
    var name: String
    var drafts: [WithholdingRowDraft]
    let source: WithholdingTable

    init(table: WithholdingTable) {
        source = table
        name = table.name
        drafts = table.rows.map { WithholdingRowDraft(row: $0) }
    }

    var nameIssue: String? { name.trimmingCharacters(in: .whitespaces).isEmpty ? "Enter a name." : nil }

    func issue(for draft: WithholdingRowDraft) -> String? {
        draft.row == nil ? "Use whole dollars for the limit (or leave it empty) and numbers like 0.16 for a and b." : nil
    }

    /// Rows must have rising limits, with at most one row (the last) without a limit.
    var orderIssue: String? {
        let rows = drafts.compactMap(\.row)
        guard rows.count == drafts.count else { return nil }
        if rows.isEmpty { return "Add at least one row." }
        var last = Int.min
        for (i, row) in rows.enumerated() {
            if let below = row.belowDollars {
                if below <= last { return "Limits must increase from one row to the next." }
                last = below
            } else if i != rows.count - 1 {
                return "Only the last row can have no limit."
            }
        }
        return nil
    }

    var isValid: Bool {
        nameIssue == nil && orderIssue == nil && !drafts.isEmpty && drafts.allSatisfy { $0.row != nil }
    }

    func addRow() { drafts.append(WithholdingRowDraft()) }
    func removeRows(at offsets: IndexSet) { drafts.remove(atOffsets: offsets) }

    func build() -> WithholdingTable? {
        guard isValid else { return nil }
        var t = source
        t.name = name.trimmingCharacters(in: .whitespaces)
        t.rows = drafts.compactMap(\.row)
        return t
    }
}
