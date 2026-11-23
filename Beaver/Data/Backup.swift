import Foundation
import BeaverCore

/// Every table, in dependency order. One property per SPEC §7 table.
struct BackupTables: Codable, Equatable, Sendable {
    var setting: [SettingRecord] = []
    var rateCard: [RateCardRecord] = []
    var withholdingTable: [WithholdingTableRecord] = []
    var incomeSource: [IncomeSourceRecord] = []
    var template: [TemplateRecord] = []
    var templateShift: [TemplateShiftRecord] = []
    var shift: [ShiftRecord] = []
    var payReceived: [PayReceivedRecord] = []
    var holiday: [HolidayRecord] = []
    var unavailable: [UnavailableRecord] = []
    var hoursLimit: [HoursLimitRecord] = []
    var bill: [BillRecord] = []
    var billPayment: [BillPaymentRecord] = []
    var expense: [ExpenseRecord] = []
    var weekSpendTotal: [WeekSpendTotalRecord] = []
    var goal: [GoalRecord] = []
    var deposit: [DepositRecord] = []

    /// SQL table names, children before parents.
    static let deleteOrder = ["deposit", "goal", "week_spend_total", "expense", "bill_payment", "bill", "hours_limit",
                              "unavailable", "holiday", "pay_received", "shift", "template_shift", "template",
                              "income_source", "withholding_table", "rate_card", "setting"]
}

/// The versioned JSON file written by Export and read by Import.
struct BackupDocument: Codable, Equatable, Sendable {
    var schemaVersion: Int
    var appVersion: String
    var exportedAt: Date
    var tables: BackupTables
}

enum BackupError: Error, Equatable, LocalizedError {
    /// The file is not a Beaver backup (not JSON, or no schema version).
    case notABackup
    /// A schema version this build does not know: newer than `supported`, or not a valid version number.
    case unsupportedVersion(found: Int, supported: Int)
    /// The file claims a supported version but its content is broken.
    case corrupt(String)

    var errorDescription: String? {
        switch self {
        case .notABackup:
            "This file is not a Beaver backup."
        case .unsupportedVersion(let found, let supported) where found > supported:
            "This backup was made by a newer version of Beaver (backup format \(found); this app understands up to \(supported)). Update Beaver, then try again. Nothing was changed."
        case .unsupportedVersion(let found, _):
            "This backup has an unknown format version (\(found)). Nothing was changed."
        case .corrupt(let detail):
            "This backup could not be read: \(detail). Nothing was changed."
        }
    }
}

/// Export and import of the whole database as JSON. Import replaces everything in one transaction.
struct Backup: Sendable {
    static let schemaVersion = 1
    let repository: Repository
    let appVersion: String

    init(repository: Repository,
         appVersion: String = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0") {
        self.repository = repository
        self.appVersion = appVersion
    }

    /// Builds the JSON file and records `lastExportAt`.
    func export(now: Date = Date()) throws -> Data {
        let tables = try repository.snapshotForExport(now: now)
        let document = BackupDocument(schemaVersion: Self.schemaVersion, appVersion: appVersion, exportedAt: now, tables: tables)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        return try encoder.encode(document)
    }

    /// Validates the version first, then replaces all data in one transaction.
    func `import`(_ data: Data) throws {
        struct Header: Decodable { var schemaVersion: Int }
        let header: Header
        do { header = try JSONDecoder().decode(Header.self, from: data) } catch { throw BackupError.notABackup }
        guard header.schemaVersion == Self.schemaVersion else {
            throw BackupError.unsupportedVersion(found: header.schemaVersion, supported: Self.schemaVersion)
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        let document: BackupDocument
        do { document = try decoder.decode(BackupDocument.self, from: data) }
        catch { throw BackupError.corrupt("the file's contents do not match the expected format") }
        do { try repository.replaceAll(with: document.tables) }
        catch let error as DataError { throw BackupError.corrupt(error.localizedDescription) }
        catch { throw BackupError.corrupt("the data conflicts with itself (\(error.localizedDescription))") }
    }
}
