import Foundation
import Testing
import BeaverCore
import GRDB
@testable import Beaver

private func makeRepository() throws -> Repository { try Repository.inMemory() }

struct MigrationTests {
    @Test func migrationCreatesEveryTable() throws {
        let repo = try makeRepository()
        let names = Set(try repo.tableNames())
        let expected: Set<String> = ["setting", "rate_card", "withholding_table", "income_source", "template", "template_shift",
                                     "shift", "pay_received", "holiday", "unavailable", "hours_limit", "bill", "bill_payment",
                                     "expense", "week_spend_total", "goal", "deposit"]
        #expect(expected.isSubset(of: names))
    }

    @Test func foreignKeysAreEnforced() throws {
        let repo = try makeRepository()
        #expect(try repo.foreignKeysEnabled())
        // A shift for a source that does not exist must be rejected.
        #expect(throws: (any Error).self) {
            try repo.save(Shift(sourceID: 999, date: "2026-11-23", start: "09:00", end: "17:00"))
        }
        // Deleting a source cascades to its shifts.
        let source = try repo.save(IncomeSource(id: 0, name: "Test job", kind: .fixed, fixedAmount: .dollars(100), schedule: .weekly(payWeekday: .friday, lagWeeks: 0)))
        try repo.save(Shift(sourceID: source.id, date: "2026-11-23", start: "09:00", end: "17:00"))
        try repo.deleteIncomeSource(id: source.id)
        #expect(try repo.shifts().isEmpty)
    }

    @Test func persistentDatabaseReopensWithData() throws {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("beaver-test-\(UUID().uuidString).sqlite")
        defer { for suffix in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: url.path + suffix) } }
        do {
            let repo = Repository(database: try .makePersistent(at: url))
            try repo.save(Goal(name: "Saved", target: .dollars(10)))
        }
        let reopened = Repository(database: try .makePersistent(at: url))
        #expect(try reopened.goals().map(\.name) == ["Saved"])
        #expect(try reopened.foreignKeysEnabled())
    }

    @Test func freshDatabaseIsEmpty() throws {
        let repo = try makeRepository()
        let settings = try repo.settings()
        #expect(settings.onboardingDone == false)
        #expect(settings.lastExportAt == nil)
        #expect(try repo.userRowCount() == 0)
        // Only the built-in reference table exists.
        let tables = try repo.withholdingTables()
        #expect(tables.map(\.id) == [WithholdingTable.auScale2WeeklyID])
        #expect(tables.first?.isBuiltin == true)
        #expect(tables.first == WithholdingTable.auScale2Weekly)
        #expect(try repo.forecastInput(today: "2026-11-23").withholdingTables.isEmpty)
    }
}

struct RoundTripTests {
    @Test func everyRecordTypeRoundTrips() throws {
        let repo = try makeRepository()

        let card = try repo.save(RateCard.typicalCasual())
        #expect(card.id > 0)
        #expect(try repo.rateCards() == [card])

        var source = IncomeSource(id: 0, name: "Cafe", kind: .hourly, rateCardID: card.id,
                                  schedule: .fortnightly(anchorPayday: "2026-11-25", lagWeeks: 1),
                                  taxMethod: .flatPercent(basisPoints: 1500), weeklyCapSoftHours: 30, weeklyCapHardHours: 38)
        source = try repo.save(source)
        let salary = try repo.save(IncomeSource(id: 0, name: "Salary", kind: .fixed, fixedAmount: .dollars(4800), amountIsNet: true,
                                                schedule: .monthly(dayOfMonth: 31), taxMethod: .none, isActive: false))
        #expect(try repo.incomeSources() == [source, salary])

        let custom = WithholdingTable(id: "custom-1", name: "Mine", rows: [WithholdingRow(belowDollars: 500, aTenThousandths: 1000, bTenThousandths: 0),
                                                                           WithholdingRow(belowDollars: nil, aTenThousandths: 2000, bTenThousandths: 100)])
        try repo.save(custom)
        #expect(try repo.withholdingTables().last == custom)
        #expect(try repo.forecastInput(today: "2026-11-23").withholdingTables == [custom])
        #expect(throws: DataError.builtinReadOnly) { try repo.deleteWithholdingTable(id: WithholdingTable.auScale2WeeklyID) }
        #expect(throws: DataError.builtinReadOnly) { try repo.save(WithholdingTable.auScale2Weekly) }

        let template = try repo.save(Template(sourceID: source.id, name: "Week", effectiveFrom: "2026-12-01", shifts: [
            TemplateShift(weekday: .friday, start: "18:00", end: "04:30", breakMinutes: 30),
            TemplateShift(weekday: .monday, start: "09:00", end: "12:00")]))
        #expect(template.id > 0)
        #expect(try repo.templates() == [template])
        var edited = template
        edited.shifts = [TemplateShift(weekday: .sunday, start: "10:00", end: "14:00")]
        try repo.save(edited)
        #expect(try repo.templates() == [edited])

        let shift = try repo.save(Shift(sourceID: source.id, date: "2026-11-27", start: "17:00", end: "01:00", breakMinutes: 30, status: .worked, note: "busy"))
        #expect(try repo.shifts() == [shift])
        #expect(try repo.shifts(in: DateRange(start: "2026-11-23", end: "2026-11-29"), sourceID: source.id) == [shift])
        #expect(try repo.shifts(in: DateRange(start: "2026-11-30", end: "2026-12-06")).isEmpty)

        let pay = try repo.save(PayReceived(sourceID: source.id, payDate: "2026-12-02", net: .dollars(600), gross: .dollars(700), tax: .dollars(100), note: "ok"))
        #expect(try repo.paysReceived() == [pay])

        let holiday = Holiday(date: "2026-12-25", name: "Christmas Day")
        try repo.save(holiday)
        #expect(try repo.holidays() == [holiday])
        let unavailable = try repo.save(UnavailableRange(from: "2026-12-02", to: "2026-12-05", note: "Trip"))
        #expect(try repo.unavailableRanges() == [unavailable])
        let limit = try repo.save(FortnightLimit(hoursPerFortnight: 48, effectiveFrom: "2027-03-01"))
        #expect(try repo.hoursLimits() == [limit])

        let bill = try repo.save(Bill(id: 0, name: "Rent", amount: .dollars(400), cadence: .monthly(dayOfMonth: 31), anchorDate: "2026-11-30",
                                      category: .housing, account: "Joint", isActive: false, note: "n"))
        let every = try repo.save(Bill(id: 0, name: "Phone", amount: .dollars(30), cadence: .everyNDays(28), anchorDate: "2026-11-22"))
        #expect(try repo.bills() == [bill, every])
        let payment = BillPayment(billID: bill.id, dueDate: "2026-11-30", paidDate: "2026-12-01", amount: .dollars(401))
        try repo.markPaid(payment)
        try repo.markPaid(BillPayment(billID: bill.id, dueDate: "2026-11-30", paidDate: "2026-12-01", amount: .dollars(402)))
        #expect(try repo.billPayments() == [BillPayment(billID: bill.id, dueDate: "2026-11-30", paidDate: "2026-12-01", amount: .dollars(402))])
        try repo.unmarkPaid(billID: bill.id, dueDate: "2026-11-30")
        #expect(try repo.billPayments().isEmpty)

        let expense = try repo.save(Expense(date: "2026-11-24", amount: .dollars(12, cents: 50), category: .coffeeDrinks, note: "flat white"))
        #expect(try repo.expenses() == [expense])
        #expect(try repo.expenses(in: DateRange(start: "2026-11-23", end: "2026-11-29")) == [expense])
        try repo.setWeekSpendTotal(weekStart: "2026-11-25", amount: .dollars(99))
        #expect(try repo.weekSpendTotals() == [WeekSpendTotal(weekStart: "2026-11-23", amount: .dollars(99))])
        try repo.clearWeekSpendTotal(weekStart: "2026-11-23")
        #expect(try repo.weekSpendTotals().isEmpty)

        let goal = try repo.save(Goal(name: "Fees", target: .dollars(6000), deadline: "2027-03-31"))
        let deposit = try repo.save(Deposit(goalID: goal.id, date: "2026-11-24", amount: .dollars(50), note: "first"))
        #expect(try repo.goals() == [goal])
        #expect(try repo.deposits() == [deposit])

        try repo.setCurrency("USD")
        try repo.setTimeZone("Australia/Sydney")
        try repo.setOnboardingDone(true)
        try repo.setPrimaryGoalID(goal.id)
        try repo.setLivingBudget(.dollars(150))
        let settings = try repo.settings()
        #expect(settings.currency == "USD")
        #expect(settings.timeZoneID == "Australia/Sydney")
        #expect(settings.onboardingDone)
        #expect(settings.primaryGoalID == goal.id)
        #expect(settings.livingBudget == .dollars(150))
        try repo.setPrimaryGoalID(nil)
        #expect(try repo.settings().primaryGoalID == nil)

        // Deleting a goal removes its deposits; deleting a rate card detaches sources.
        try repo.deleteGoal(id: goal.id)
        #expect(try repo.deposits().isEmpty)
        try repo.deleteRateCard(id: card.id)
        #expect(try repo.incomeSources().first?.rateCardID == nil)
    }
}

struct DemoForecastTests {
    @Test func demoDataReproducesGoldenForecast() throws {
        let repo = try makeRepository()
        try repo.loadDemoData()
        let input = try repo.forecastInput(today: "2026-11-23")
        let forecast = Forecast.compute(input)
        #expect(forecast.totalIncome.cents == 2_017_795)
        #expect(forecast.totalBills.cents == 874_896)
        #expect(forecast.gap.cents == 257_899)
        #expect(forecast.paydayCount == 19)
        // The database round trip must equal the pure engine input exactly.
        #expect(input == DemoData.forecastInput(today: "2026-11-23"))
        #expect(try repo.settings().onboardingDone)
    }

    @Test func loadDemoDataReplacesExistingData() throws {
        let repo = try makeRepository()
        try repo.save(Expense(date: "2026-11-24", amount: .dollars(5)))
        try repo.loadDemoData()
        try repo.loadDemoData()
        #expect(try repo.expenses().isEmpty)
        #expect(try repo.bills().count == 6)
        #expect(try repo.withholdingTables().count == 1)
    }
}

struct BackupTests {
    @Test func backupRoundTripRestoresEverything() throws {
        let repo = try makeRepository()
        try repo.loadDemoData()
        try repo.save(Expense(date: "2026-11-24", amount: .dollars(12, cents: 34), category: .food, note: "lunch"))
        try repo.markPaid(BillPayment(billID: 1, dueDate: "2026-11-25", paidDate: "2026-11-25", amount: .dollars(400)))
        try repo.save(Deposit(goalID: 1, date: "2026-11-24", amount: .dollars(20)))
        let gapBefore = Forecast.compute(try repo.forecastInput(today: "2026-11-23")).gap

        let backup = Backup(repository: repo, appVersion: "9.9")
        let data = try backup.export(now: Date(timeIntervalSince1970: 1_800_000_000))
        #expect(try repo.settings().lastExportAt != nil)
        let tablesBefore = try repo.backupTables()

        let json = try #require(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(json["schemaVersion"] as? Int == 1)
        #expect(json["appVersion"] as? String == "9.9")
        #expect(json["exportedAt"] != nil)
        let tables = try #require(json["tables"] as? [String: Any])
        #expect(tables.count == 17)

        // Wipe everything, then restore into a different database.
        let other = try makeRepository()
        try other.loadDemoData()
        try other.save(Expense(date: "2026-01-01", amount: .dollars(1)))
        try Backup(repository: other).import(data)
        #expect(try other.backupTables() == tablesBefore)
        #expect(Forecast.compute(try other.forecastInput(today: "2026-11-23")).gap == gapBefore)

        // And onto an empty database.
        let empty = try makeRepository()
        try Backup(repository: empty).import(data)
        #expect(try empty.backupTables() == tablesBefore)
        #expect(Forecast.compute(try empty.forecastInput(today: "2026-11-23")).gap == gapBefore)
        #expect(try empty.settings().lastExportAt == repo.settings().lastExportAt)
    }

    @Test func importRejectsNewerSchemaAndLeavesDataAlone() throws {
        let repo = try makeRepository()
        try repo.loadDemoData()
        let before = try repo.backupTables()
        let bad = Data(#"{"schemaVersion":99,"appVersion":"9","exportedAt":"2030-01-01T00:00:00Z","tables":{"future":[]}}"#.utf8)
        #expect(throws: BackupError.unsupportedVersion(found: 99, supported: 1)) { try Backup(repository: repo).import(bad) }
        let message = BackupError.unsupportedVersion(found: 99, supported: 1).localizedDescription
        #expect(message.contains("newer version"))
        #expect(throws: BackupError.unsupportedVersion(found: 0, supported: 1)) {
            try Backup(repository: repo).import(Data(#"{"schemaVersion":0}"#.utf8))
        }
        #expect(throws: BackupError.notABackup) { try Backup(repository: repo).import(Data("hello".utf8)) }
        #expect(try repo.backupTables() == before)
    }

    @Test func brokenImportRollsBack() throws {
        let repo = try makeRepository()
        try repo.loadDemoData()
        let before = try repo.backupTables()
        var tables = before
        tables.shift = [ShiftRecord(Shift(sourceID: 12345, date: "2026-11-23", start: "09:00", end: "10:00"))]
        let doc = BackupDocument(schemaVersion: 1, appVersion: "1", exportedAt: Date(), tables: tables)
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        #expect(throws: BackupError.self) { try Backup(repository: repo).import(try encoder.encode(doc)) }
        #expect(try repo.backupTables() == before)
    }
}

@MainActor
struct StoreTests {
    @Test func storeReflectsDemoDataAndDatabaseChanges() async throws {
        let repo = try makeRepository()
        try repo.loadDemoData()
        let store = AppStore(repository: repo, clock: { _ in "2026-11-23" })
        #expect(store.forecast.gap.cents == 257_899)
        #expect(store.settings.onboardingDone)

        let task = Task { await store.run() }
        defer { task.cancel() }
        try repo.save(Expense(date: "2026-11-23", amount: .dollars(10)))
        try repo.setLivingBudget(.dollars(200))
        for _ in 0..<100 where store.input.livingBudget != .dollars(200) { try await Task.sleep(for: .milliseconds(50)) }
        #expect(store.input.livingBudget == .dollars(200))
        #expect(store.forecast.gap.cents < 257_899)
        store.extraWeeklyIncome = .dollars(10)
        #expect(store.forecast.gap.cents > 0)
    }

    @Test func appModelReadsPersistedOnboardingFlag() throws {
        let repo = try makeRepository()
        #expect(AppModel(repository: repo).onboardingDone == false)
        try repo.setOnboardingDone(true)
        #expect(AppModel(repository: repo).onboardingDone)
        let model = AppModel(repository: try makeRepository())
        model.completeOnboarding()
        #expect(model.onboardingDone)
    }
}
