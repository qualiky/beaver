import Foundation
import BeaverCore
import GRDB

struct SettingRecord: BeaverRecord {
    static let databaseTableName = "setting"
    var key: String
    /// A JSON fragment (for example `"AUD"`, `true`, `15000`).
    var value: String
}

/// Typed view of the `setting` table. Missing keys take these defaults.
struct AppSettings: Sendable, Equatable {
    var currency: String = "AUD"
    var timeZoneID: String = TimeZone.current.identifier
    var onboardingDone: Bool = false
    var lastExportAt: Date?
    var primaryGoalID: Int?
    var livingBudget: Money = .zero

    var timeZone: TimeZone { TimeZone(identifier: timeZoneID) ?? .current }

    enum Key {
        static let currency = "currency"
        static let timeZone = "timeZone"
        static let onboardingDone = "onboardingDone"
        static let lastExportAt = "lastExportAt"
        static let primaryGoalID = "primaryGoalId"
        static let livingBudgetCents = "livingBudgetCents"
    }

    init() {}

    init(rows: [SettingRecord]) {
        func value<T: Decodable>(_ key: String, _ type: T.Type) -> T? {
            guard let row = rows.first(where: { $0.key == key }) else { return nil }
            return try? JSONDecoder().decode(T.self, from: Data(row.value.utf8))
        }
        if let v = value(Key.currency, String.self) { currency = v }
        if let v = value(Key.timeZone, String.self) { timeZoneID = v }
        if let v = value(Key.onboardingDone, Bool.self) { onboardingDone = v }
        if let v = value(Key.lastExportAt, String.self) { lastExportAt = ISO8601DateFormatter().date(from: v) }
        primaryGoalID = value(Key.primaryGoalID, Int.self)
        if let v = value(Key.livingBudgetCents, Int.self) { livingBudget = Money(cents: v) }
    }
}
