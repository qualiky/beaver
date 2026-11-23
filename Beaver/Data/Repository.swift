import Foundation
import BeaverCore
import GRDB

/// The only type that runs SQL. Views and models talk to BeaverCore value types through this API.
/// Every `save` returns the stored value (with its assigned id when it was new, id 0).
final class Repository: Sendable {
    private let writer: any DatabaseWriter

    init(database: AppDatabase) { writer = database.writer }

    /// A repository over a private in-memory database (previews and tests).
    static func inMemory() throws -> Repository { Repository(database: try .makeInMemory()) }

    // MARK: Settings

    func settings() throws -> AppSettings { try writer.read { try Self.settings($0) } }

    static func settings(_ db: Database) throws -> AppSettings {
        AppSettings(rows: try SettingRecord.fetchAll(db))
    }

    func setCurrency(_ code: String) throws { try setSetting(AppSettings.Key.currency, code) }
    func setTimeZone(_ id: String) throws { try setSetting(AppSettings.Key.timeZone, id) }
    func setOnboardingDone(_ done: Bool) throws { try setSetting(AppSettings.Key.onboardingDone, done) }
    func setPrimaryGoalID(_ id: Int?) throws { try setSetting(AppSettings.Key.primaryGoalID, id) }
    func setLivingBudget(_ amount: Money) throws { try setSetting(AppSettings.Key.livingBudgetCents, amount.cents) }

    private func setSetting<T: Encodable>(_ key: String, _ value: T?) throws {
        try writer.write { try Self.setSetting($0, key, value) }
    }

    private static func setSetting<T: Encodable>(_ db: Database, _ key: String, _ value: T?) throws {
        guard let value else {
            _ = try SettingRecord.deleteOne(db, key: key)
            return
        }
        let json = String(decoding: try JSONEncoder().encode(value), as: UTF8.self)
        try put(SettingRecord(key: key, value: json), db)
    }

    static func isoString(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }

    // MARK: Income sources and rate cards

    func incomeSources() throws -> [IncomeSource] {
        try writer.read { try IncomeSourceRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ source: IncomeSource) throws -> IncomeSource {
        try writer.write { db in var r = IncomeSourceRecord(source); try r.save(db); return try r.core() }
    }
    func deleteIncomeSource(id: Int) throws { try delete(IncomeSourceRecord.self, key: id) }

    func rateCards() throws -> [RateCard] {
        try writer.read { try RateCardRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ card: RateCard) throws -> RateCard {
        try writer.write { db in var r = RateCardRecord(card); try r.save(db); return try r.core() }
    }
    func deleteRateCard(id: Int) throws { try delete(RateCardRecord.self, key: id) }

    // MARK: Withholding tables

    /// Built-in and custom tables, built-ins first.
    func withholdingTables() throws -> [WithholdingTable] {
        try writer.read { try WithholdingTableRecord.order(Column("builtin").desc, Column("name")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ table: WithholdingTable) throws -> WithholdingTable {
        try writer.write { db in
            if let existing = try WithholdingTableRecord.fetchOne(db, key: table.id), existing.builtin { throw DataError.builtinReadOnly }
            var stored = table
            stored.isBuiltin = false
            let r = WithholdingTableRecord(stored)
            try Self.put(r, db)
            return try r.core()
        }
    }
    func deleteWithholdingTable(id: String) throws {
        try writer.write { db in
            if let existing = try WithholdingTableRecord.fetchOne(db, key: id), existing.builtin { throw DataError.builtinReadOnly }
            _ = try WithholdingTableRecord.deleteOne(db, key: id)
        }
    }

    // MARK: Templates

    func templates() throws -> [Template] { try writer.read { try Self.templates($0) } }

    static func templates(_ db: Database) throws -> [Template] {
        let shifts = try TemplateShiftRecord.order(Column("id")).fetchAll(db)
        let byTemplate = Dictionary(grouping: shifts, by: \.templateId)
        return try TemplateRecord.order(Column("id")).fetchAll(db).map { try $0.core(shifts: byTemplate[$0.id ?? 0] ?? []) }
    }

    /// Saves the template and replaces its shifts.
    @discardableResult func save(_ template: Template) throws -> Template {
        try writer.write { db in
            var r = TemplateRecord(template)
            try r.save(db)
            let templateID = r.id ?? 0
            _ = try TemplateShiftRecord.filter(Column("template_id") == templateID).deleteAll(db)
            var stored: [TemplateShiftRecord] = []
            for s in template.shifts {
                var row = TemplateShiftRecord(s, templateID: templateID)
                try row.insert(db)
                stored.append(row)
            }
            return try r.core(shifts: stored)
        }
    }
    func deleteTemplate(id: Int) throws { try delete(TemplateRecord.self, key: id) }

    // MARK: Shifts and pay received

    func shifts() throws -> [Shift] {
        try writer.read { try Self.shifts($0) }
    }
    static func shifts(_ db: Database) throws -> [Shift] {
        try ShiftRecord.order(Column("date"), Column("start"), Column("id")).fetchAll(db).map { try $0.core() }
    }
    func shifts(in range: DateRange, sourceID: Int? = nil) throws -> [Shift] {
        try writer.read { try Self.shifts($0, in: range, sourceID: sourceID) }
    }
    static func shifts(_ db: Database, in range: DateRange, sourceID: Int?) throws -> [Shift] {
        var request = ShiftRecord.filter(Column("date") >= range.start.iso && Column("date") <= range.end.iso)
        if let sourceID { request = request.filter(Column("source_id") == sourceID) }
        return try request.order(Column("date"), Column("start"), Column("id")).fetchAll(db).map { try $0.core() }
    }
    @discardableResult func save(_ shift: Shift) throws -> Shift {
        try writer.write { db in var r = ShiftRecord(shift); try r.save(db); return try r.core() }
    }
    /// Saves several shifts in one transaction (copy last week, apply template).
    @discardableResult func save(shifts: [Shift]) throws -> [Shift] {
        try writer.write { db in
            try shifts.map { var r = ShiftRecord($0); try r.save(db); return try r.core() }
        }
    }
    func deleteShift(id: Int) throws { try delete(ShiftRecord.self, key: id) }

    func paysReceived() throws -> [PayReceived] {
        try writer.read { try PayReceivedRecord.order(Column("pay_date"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ pay: PayReceived) throws -> PayReceived {
        try writer.write { db in var r = PayReceivedRecord(pay); try r.save(db); return try r.core() }
    }
    func deletePayReceived(id: Int) throws { try delete(PayReceivedRecord.self, key: id) }

    // MARK: Holidays, unavailable ranges, hours limits

    func holidays() throws -> [Holiday] {
        try writer.read { try HolidayRecord.order(Column("date")).fetchAll($0).map { try $0.core() } }
    }
    /// A holiday is keyed by date: saving an existing date renames it.
    func save(_ holiday: Holiday) throws { try writer.write { try Self.put(HolidayRecord(holiday), $0) } }
    func deleteHoliday(date: LocalDate) throws { try delete(HolidayRecord.self, key: date.iso) }

    func unavailableRanges() throws -> [UnavailableRange] {
        try writer.read { try UnavailableRecord.order(Column("date_from"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ range: UnavailableRange) throws -> UnavailableRange {
        try writer.write { db in var r = UnavailableRecord(range); try r.save(db); return try r.core() }
    }
    func deleteUnavailableRange(id: Int) throws { try delete(UnavailableRecord.self, key: id) }

    func hoursLimits() throws -> [FortnightLimit] {
        try writer.read { try HoursLimitRecord.order(Column("effective_from"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ limit: FortnightLimit) throws -> FortnightLimit {
        try writer.write { db in var r = HoursLimitRecord(limit); try r.save(db); return try r.core() }
    }
    func deleteHoursLimit(id: Int) throws { try delete(HoursLimitRecord.self, key: id) }

    // MARK: Bills

    func bills() throws -> [Bill] {
        try writer.read { try BillRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ bill: Bill) throws -> Bill {
        try writer.write { db in var r = BillRecord(bill); try r.save(db); return try r.core() }
    }
    func deleteBill(id: Int) throws { try delete(BillRecord.self, key: id) }

    func billPayments() throws -> [BillPayment] {
        try writer.read { try BillPaymentRecord.order(Column("due_date"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    /// Marks one occurrence paid. A second call for the same bill and due date replaces the first.
    func markPaid(_ payment: BillPayment) throws {
        try writer.write { db in
            var r = BillPaymentRecord(payment)
            r.id = try BillPaymentRecord
                .filter(Column("bill_id") == payment.billID && Column("due_date") == payment.dueDate.iso)
                .fetchOne(db)?.id
            try r.save(db)
        }
    }
    func unmarkPaid(billID: Int, dueDate: LocalDate) throws {
        try writer.write { db in
            _ = try BillPaymentRecord
                .filter(Column("bill_id") == billID && Column("due_date") == dueDate.iso).deleteAll(db)
        }
    }

    // MARK: Spending

    func expenses() throws -> [Expense] {
        try writer.read { try ExpenseRecord.order(Column("date"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    func expenses(in range: DateRange) throws -> [Expense] {
        try writer.read { db in
            try ExpenseRecord.filter(Column("date") >= range.start.iso && Column("date") <= range.end.iso)
                .order(Column("date"), Column("id")).fetchAll(db).map { try $0.core() }
        }
    }
    @discardableResult func save(_ expense: Expense) throws -> Expense {
        try writer.write { db in var r = ExpenseRecord(expense); try r.save(db); return try r.core() }
    }
    func deleteExpense(id: Int) throws { try delete(ExpenseRecord.self, key: id) }

    func weekSpendTotals() throws -> [WeekSpendTotal] {
        try writer.read { try WeekSpendTotalRecord.order(Column("week_start")).fetchAll($0).map { try $0.core() } }
    }
    /// A week total replaces that week's quick entries in the forecast. Stored against the Monday.
    func setWeekSpendTotal(weekStart: LocalDate, amount: Money) throws {
        try writer.write { try Self.put(WeekSpendTotalRecord(WeekSpendTotal(weekStart: weekStart.mondayOfWeek, amount: amount)), $0) }
    }
    func clearWeekSpendTotal(weekStart: LocalDate) throws {
        try delete(WeekSpendTotalRecord.self, key: weekStart.mondayOfWeek.iso)
    }

    // MARK: Goals

    func goals() throws -> [Goal] {
        try writer.read { try GoalRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ goal: Goal) throws -> Goal {
        try writer.write { db in var r = GoalRecord(goal); try r.save(db); return try r.core() }
    }
    func deleteGoal(id: Int) throws { try delete(GoalRecord.self, key: id) }

    func deposits() throws -> [Deposit] {
        try writer.read { try DepositRecord.order(Column("date"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    @discardableResult func save(_ deposit: Deposit) throws -> Deposit {
        try writer.write { db in var r = DepositRecord(deposit); try r.save(db); return try r.core() }
    }
    func deleteDeposit(id: Int) throws { try delete(DepositRecord.self, key: id) }

    /// Inserts or updates a record without needing a mutable copy at every call site.
    private static func put<R: MutablePersistableRecord>(_ record: R, _ db: Database, insertOnly: Bool = false) throws {
        var r = record
        if insertOnly { try r.insert(db) } else { try r.save(db) }
    }

    private func delete<R: BeaverRecord & TableRecord, K: DatabaseValueConvertible>(_: R.Type, key: K) throws {
        try writer.write { db in _ = try R.deleteOne(db, key: key) }
    }

    // MARK: Forecast input

    /// Everything the forecast engine needs, read in one consistent snapshot.
    func forecastInput(today: LocalDate) throws -> ForecastInput {
        try writer.read { try Self.forecastInput($0, today: today) }
    }

    static func forecastInput(_ db: Database, today: LocalDate) throws -> ForecastInput {
        func all<R: BeaverRecord & TableRecord, T>(_ type: R.Type, order: [any SQLOrderingTerm], _ convert: (R) throws -> T) throws -> [T] {
            try R.order(order).fetchAll(db).map(convert)
        }
        let settings = try Self.settings(db)
        return ForecastInput(
            today: today,
            sources: try all(IncomeSourceRecord.self, order: [Column("id")]) { try $0.core() },
            rateCards: try all(RateCardRecord.self, order: [Column("id")]) { try $0.core() },
            withholdingTables: try WithholdingTableRecord.filter(Column("builtin") == false).order(Column("name"))
                .fetchAll(db).map { try $0.core() },
            templates: try templates(db),
            shifts: try shifts(db),
            paysReceived: try all(PayReceivedRecord.self, order: [Column("pay_date"), Column("id")]) { try $0.core() },
            holidays: try all(HolidayRecord.self, order: [Column("date")]) { try $0.core() },
            unavailable: try all(UnavailableRecord.self, order: [Column("date_from"), Column("id")]) { try $0.core() },
            bills: try all(BillRecord.self, order: [Column("id")]) { try $0.core() },
            billPayments: try all(BillPaymentRecord.self, order: [Column("due_date"), Column("id")]) { try $0.core() },
            expenses: try all(ExpenseRecord.self, order: [Column("date"), Column("id")]) { try $0.core() },
            weekSpendTotals: try all(WeekSpendTotalRecord.self, order: [Column("week_start")]) { try $0.core() },
            livingBudget: settings.livingBudget,
            goals: try all(GoalRecord.self, order: [Column("id")]) { try $0.core() },
            deposits: try all(DepositRecord.self, order: [Column("date"), Column("id")]) { try $0.core() },
            primaryGoalID: settings.primaryGoalID)
    }

    // Fortnight limits are not part of ForecastInput (the engine only needs them for warnings),
    // so they are read separately through `hoursLimits()`.

    // MARK: Observation

    /// Streams a fresh value every time any table that `fetch` reads changes. The first value is the current state.
    func observe<T: Sendable>(_ fetch: @escaping @Sendable (Database) throws -> T) -> AsyncThrowingStream<T, any Error> {
        let writer = self.writer
        return AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    for try await value in ValueObservation.tracking(fetch).values(in: writer) {
                        continuation.yield(value)
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    func observeSettings() -> AsyncThrowingStream<AppSettings, any Error> { observe { try Self.settings($0) } }
    func observeIncomeSources() -> AsyncThrowingStream<[IncomeSource], any Error> {
        observe { try IncomeSourceRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    func observeBills() -> AsyncThrowingStream<[Bill], any Error> {
        observe { try BillRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    func observeBillPayments() -> AsyncThrowingStream<[BillPayment], any Error> {
        observe { try BillPaymentRecord.order(Column("due_date"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    func observeShifts(in range: DateRange, sourceID: Int? = nil) -> AsyncThrowingStream<[Shift], any Error> {
        observe { try Self.shifts($0, in: range, sourceID: sourceID) }
    }
    func observeExpenses(in range: DateRange) -> AsyncThrowingStream<[Expense], any Error> {
        observe { db in
            try ExpenseRecord.filter(Column("date") >= range.start.iso && Column("date") <= range.end.iso)
                .order(Column("date"), Column("id")).fetchAll(db).map { try $0.core() }
        }
    }
    func observeGoals() -> AsyncThrowingStream<[Goal], any Error> {
        observe { try GoalRecord.order(Column("id")).fetchAll($0).map { try $0.core() } }
    }
    func observeDeposits() -> AsyncThrowingStream<[Deposit], any Error> {
        observe { try DepositRecord.order(Column("date"), Column("id")).fetchAll($0).map { try $0.core() } }
    }
    /// Settings and the engine input together, recomputed when either changes.
    func observeSnapshot(today: @escaping @Sendable (TimeZone) -> LocalDate) -> AsyncThrowingStream<StoreSnapshot, any Error> {
        observe { db in
            let settings = try Self.settings(db)
            return StoreSnapshot(settings: settings,
                                 input: try Self.forecastInput(db, today: today(settings.timeZone)))
        }
    }

    // MARK: Demo data

    /// Wipes all personal data and inserts the fictional "Sam" from BeaverCore's `DemoData`.
    func loadDemoData() throws {
        try writer.write { db in
            try Self.wipe(db)
            try Self.put(RateCardRecord(DemoData.rateCard), db)
            try Self.put(IncomeSourceRecord(DemoData.source), db)
            let template = DemoData.template
            try Self.put(TemplateRecord(template), db)
            for s in template.shifts { var row = TemplateShiftRecord(s, templateID: template.id); try row.insert(db) }
            for p in DemoData.paysReceived { try Self.put(PayReceivedRecord(p), db) }
            for h in DemoData.holidays { try Self.put(HolidayRecord(h), db) }
            for u in DemoData.unavailable { try Self.put(UnavailableRecord(u), db) }
            for b in DemoData.bills { try Self.put(BillRecord(b), db) }
            try Self.put(GoalRecord(DemoData.goal), db)
            try Self.setSetting(db, AppSettings.Key.currency, "AUD")
            try Self.setSetting(db, AppSettings.Key.onboardingDone, true)
            try Self.setSetting(db, AppSettings.Key.primaryGoalID, DemoData.goalID)
            try Self.setSetting(db, AppSettings.Key.livingBudgetCents, DemoData.livingBudget.cents)
        }
    }

    /// Deletes everything except built-in reference data (children first, so foreign keys hold).
    static func wipe(_ db: Database) throws {
        for table in BackupTables.deleteOrder where table != "withholding_table" { try db.execute(sql: "DELETE FROM \(table)") }
        try db.execute(sql: "DELETE FROM withholding_table WHERE builtin = 0")
    }

    // MARK: Backup support

    /// Records lastExportAt and reads every table, in one transaction.
    func snapshotForExport(now: Date) throws -> BackupTables {
        try writer.write { db in
            try Self.setSetting(db, AppSettings.Key.lastExportAt, Self.isoString(now))
            return try Self.backupTables(db)
        }
    }

    func backupTables() throws -> BackupTables { try writer.read { try Self.backupTables($0) } }

    static func backupTables(_ db: Database) throws -> BackupTables {
        BackupTables(
            setting: try SettingRecord.order(Column("key")).fetchAll(db),
            rateCard: try RateCardRecord.order(Column("id")).fetchAll(db),
            withholdingTable: try WithholdingTableRecord.order(Column("id")).fetchAll(db),
            incomeSource: try IncomeSourceRecord.order(Column("id")).fetchAll(db),
            template: try TemplateRecord.order(Column("id")).fetchAll(db),
            templateShift: try TemplateShiftRecord.order(Column("id")).fetchAll(db),
            shift: try ShiftRecord.order(Column("id")).fetchAll(db),
            payReceived: try PayReceivedRecord.order(Column("id")).fetchAll(db),
            holiday: try HolidayRecord.order(Column("date")).fetchAll(db),
            unavailable: try UnavailableRecord.order(Column("id")).fetchAll(db),
            hoursLimit: try HoursLimitRecord.order(Column("id")).fetchAll(db),
            bill: try BillRecord.order(Column("id")).fetchAll(db),
            billPayment: try BillPaymentRecord.order(Column("id")).fetchAll(db),
            expense: try ExpenseRecord.order(Column("id")).fetchAll(db),
            weekSpendTotal: try WeekSpendTotalRecord.order(Column("week_start")).fetchAll(db),
            goal: try GoalRecord.order(Column("id")).fetchAll(db),
            deposit: try DepositRecord.order(Column("id")).fetchAll(db))
    }

    /// Replaces every table with `tables` in one transaction. Any failure rolls the whole import back.
    func replaceAll(with tables: BackupTables) throws {
        try writer.write { db in
            for table in BackupTables.deleteOrder { try db.execute(sql: "DELETE FROM \(table)") }
            for r in tables.setting { try Self.put(r, db, insertOnly: true) }
            for r in tables.rateCard { try Self.put(r, db, insertOnly: true) }
            for r in tables.withholdingTable { try Self.put(r, db, insertOnly: true) }
            for r in tables.incomeSource { try Self.put(r, db, insertOnly: true) }
            for r in tables.template { try Self.put(r, db, insertOnly: true) }
            for r in tables.templateShift { try Self.put(r, db, insertOnly: true) }
            for r in tables.shift { try Self.put(r, db, insertOnly: true) }
            for r in tables.payReceived { try Self.put(r, db, insertOnly: true) }
            for r in tables.holiday { try Self.put(r, db, insertOnly: true) }
            for r in tables.unavailable { try Self.put(r, db, insertOnly: true) }
            for r in tables.hoursLimit { try Self.put(r, db, insertOnly: true) }
            for r in tables.bill { try Self.put(r, db, insertOnly: true) }
            for r in tables.billPayment { try Self.put(r, db, insertOnly: true) }
            for r in tables.expense { try Self.put(r, db, insertOnly: true) }
            for r in tables.weekSpendTotal { try Self.put(r, db, insertOnly: true) }
            for r in tables.goal { try Self.put(r, db, insertOnly: true) }
            for r in tables.deposit { try Self.put(r, db, insertOnly: true) }
            // The built-in reference table always exists, even if the backup predates it.
            if try WithholdingTableRecord.fetchOne(db, key: WithholdingTable.auScale2WeeklyID) == nil {
                try Self.put(WithholdingTableRecord(.auScale2Weekly), db, insertOnly: true)
            }
            // Fail the import (and roll back) if any foreign key is broken.
            if let violation = try Row.fetchOne(db, sql: "PRAGMA foreign_key_check") {
                throw DataError.corrupt("broken reference in table \(violation["table"] as String? ?? "?")")
            }
        }
    }

    /// Rows the user (or demo data) put in the database. Built-in reference tables are not counted.
    func userRowCount() throws -> Int {
        try writer.read { db in
            var total = try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM withholding_table WHERE builtin = 0") ?? 0
            for table in BackupTables.deleteOrder where table != "withholding_table" {
                total += try Int.fetchOne(db, sql: "SELECT COUNT(*) FROM \(table)") ?? 0
            }
            return total
        }
    }

    func tableNames() throws -> [String] {
        try writer.read { try String.fetchAll($0, sql: "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'grdb_migrations'") }
    }

    func foreignKeysEnabled() throws -> Bool {
        try writer.read { try Bool.fetchOne($0, sql: "PRAGMA foreign_keys") ?? false }
    }
}

/// Settings and engine input read together.
struct StoreSnapshot: Sendable, Equatable {
    var settings: AppSettings
    var input: ForecastInput
}
