import Foundation
import Testing
import BeaverCore
@testable import Beaver

private let demoToday: LocalDate = "2026-11-23"

@MainActor
private func demoModel() throws -> (SettingsViewModel, Repository) {
    let repo = try Repository.inMemory()
    try repo.loadDemoData()
    return (SettingsViewModel(repository: repo, appVersion: "test"), repo)
}

@MainActor
struct SettingsBackupTests {
    @Test func exportFilenameUsesDateInTimeZone() {
        // 2026-11-23 14:30 UTC is already the 24th in Sydney.
        let date = ISO8601DateFormatter().date(from: "2026-11-23T14:30:00Z")!
        #expect(SettingsViewModel.exportFilename(for: date, timeZone: TimeZone(identifier: "UTC")!) == "beaver-backup-2026-11-23.json")
        #expect(SettingsViewModel.exportFilename(for: date, timeZone: TimeZone(identifier: "Australia/Sydney")!) == "beaver-backup-2026-11-24.json")
    }

    @Test func exportUpdatesLastExportAt() throws {
        let (model, repo) = try demoModel()
        #expect(try repo.settings().lastExportAt == nil)
        #expect(model.lastExportedText == "Never exported")
        let now = ISO8601DateFormatter().date(from: "2026-11-23T10:00:00Z")!
        let result = try #require(model.makeExport(now: now))
        #expect(result.filename.hasPrefix("beaver-backup-2026-11-"))
        #expect(try repo.settings().lastExportAt == now)
        #expect(model.lastExportedText.hasPrefix("Last exported: "))
        #expect(!result.data.isEmpty)
    }

    @Test func importNeedsConfirmationThenReplaces() throws {
        let (source, sourceRepo) = try demoModel()
        let data = try #require(source.makeExport()).data
        _ = sourceRepo

        let targetRepo = try Repository.inMemory()
        let target = SettingsViewModel(repository: targetRepo, appVersion: "test")
        target.stageImport(data: data, fileName: "b.json")
        #expect(target.pending == .importBackup(fileName: "b.json"))
        #expect(try targetRepo.userRowCount() == 0) // nothing changed before confirmation
        target.confirmPending()
        #expect(target.errorMessage == nil)
        #expect(try targetRepo.userRowCount() > 0)
        #expect(try targetRepo.settings().onboardingDone)
    }

    @Test func cancelledImportChangesNothing() throws {
        let (model, repo) = try demoModel()
        let before = try repo.backupTables()
        model.stageImport(data: Data("{}".utf8), fileName: "x.json")
        model.cancelPending()
        #expect(model.pending == nil)
        #expect(model.pendingImportData == nil)
        #expect(try repo.backupTables() == before)
    }

    @Test func badImportShowsErrorAndKeepsData() throws {
        let (model, repo) = try demoModel()
        let before = try repo.backupTables()
        model.stageImport(data: Data("not json".utf8), fileName: "x.json")
        model.confirmPending()
        #expect(model.errorMessage == BackupError.notABackup.errorDescription)
        #expect(try repo.backupTables() == before)

        model.errorMessage = nil
        model.stageImport(data: Data(#"{"schemaVersion": 99}"#.utf8), fileName: "new.json")
        model.confirmPending()
        #expect(model.errorMessage?.contains("newer version") == true)
        #expect(try repo.backupTables() == before)
    }
}

@MainActor
struct SettingsDataTests {
    @Test func eraseResetsOnboardingAndKeepsBuiltInTable() throws {
        let (model, repo) = try demoModel()
        #expect(try repo.settings().onboardingDone)
        model.requestErase()
        #expect(model.pending == .erase)
        model.confirmPending()
        #expect(try repo.settings().onboardingDone == false)
        #expect(try repo.userRowCount() == 0)
        let tables = try repo.withholdingTables()
        #expect(tables.map(\.id) == [WithholdingTable.auScale2WeeklyID])
        #expect(tables.first?.isBuiltin == true)
    }

    @Test func demoDataGivesGoldenForecastGap() throws {
        let repo = try Repository.inMemory()
        let model = SettingsViewModel(repository: repo, appVersion: "test")
        model.requestLoadDemo()
        #expect(model.pending == .loadDemo)
        #expect(try repo.userRowCount() == 0) // not loaded until confirmed
        model.confirmPending()
        let forecast = Forecast.compute(try repo.forecastInput(today: demoToday))
        #expect(forecast.gap.cents == 257_899)
    }

    @Test func preferencesWriteThroughRepository() throws {
        let (model, repo) = try demoModel()
        model.setCurrency("NZD")
        model.setTimeZone("Pacific/Auckland")
        #expect(model.setLivingBudget(text: "175.50"))
        #expect(!model.setLivingBudget(text: "abc"))
        let s = try repo.settings()
        #expect(s.currency == "NZD")
        #expect(s.timeZoneID == "Pacific/Auckland")
        #expect(s.livingBudget.cents == 17_550)
    }
}

@MainActor
struct SettingsWithholdingTests {
    @Test func duplicateIsEditableAndBuiltInStaysReadOnly() throws {
        let repo = try Repository.inMemory()
        let model = SettingsViewModel(repository: repo, appVersion: "test")
        let builtIn = try #require(model.tables.first)
        #expect(!SettingsViewModel.isEditable(builtIn))

        // The built-in table can not be saved over.
        #expect(model.saveTable(builtIn) == nil)
        #expect(model.errorMessage != nil)
        model.errorMessage = nil

        let copy = try #require(model.duplicate(builtIn))
        #expect(copy.id != builtIn.id)
        #expect(SettingsViewModel.isEditable(copy))
        #expect(model.tables.count == 2)

        let editor = WithholdingEditorModel(table: copy)
        editor.drafts[0].a = "0.05"
        editor.drafts[0].b = "1.5"
        let edited = try #require(editor.build())
        let saved = try #require(model.saveTable(edited))
        #expect(saved.rows[0].aTenThousandths == 500)
        #expect(saved.rows[0].bTenThousandths == 15_000)

        // The built-in rows are unchanged.
        let stored = try repo.withholdingTables()
        #expect(stored.first(where: { $0.id == builtIn.id })?.rows == WithholdingTable.auScale2Weekly.rows)
    }

    @Test func rowDraftParsing() {
        #expect(WithholdingRowDraft.tenThousandths(from: "0.16") == 1600)
        #expect(WithholdingRowDraft.tenThousandths(from: "57.8462") == 578_462)
        #expect(WithholdingRowDraft.tenThousandths(from: ".5") == 5000)
        #expect(WithholdingRowDraft.tenThousandths(from: "1.23456") == nil)
        #expect(WithholdingRowDraft.tenThousandths(from: "-1") == nil)
        #expect(WithholdingRowDraft.tenThousandths(from: "") == nil)
        #expect(WithholdingRowDraft.text(tenThousandths: 578_462) == "57.8462")
        #expect(WithholdingRowDraft.text(tenThousandths: 1600) == "0.16")
        #expect(WithholdingRowDraft.text(tenThousandths: 0) == "0")
        // Round trip the whole built-in table.
        for row in WithholdingTable.auScale2Weekly.rows {
            #expect(WithholdingRowDraft(row: row).row == row)
        }
    }

    @Test func editorValidatesOrder() {
        let editor = WithholdingEditorModel(table: WithholdingTable.auScale2Weekly)
        #expect(editor.isValid)
        editor.drafts[0].belowX = "9999"
        #expect(editor.orderIssue != nil)
        editor.drafts[0].belowX = "361"
        editor.drafts[0].a = "x"
        #expect(!editor.isValid)
    }
}

@MainActor
struct PayReminderTests {
    private func demoForecast() -> Forecast { Forecast.compute(DemoData.forecastInput(today: demoToday)) }

    @Test func demoPaydaysScheduleTheEveAt8pm() {
        let reminders = PayReminderPlanner.reminders(forecast: demoForecast(), formatter: FigureFormatter(currency: "AUD", locale: Locale(identifier: "en_AU")))
        // Paydays are Wednesdays: Nov 25, Dec 2, 9, 16 (within 28 days of Mon Nov 23).
        #expect(reminders.map(\.payday) == ["2026-11-25", "2026-12-02", "2026-12-09", "2026-12-16"])
        #expect(reminders.map(\.fireDate) == ["2026-11-24", "2026-12-01", "2026-12-08", "2026-12-15"])
        #expect(reminders.allSatisfy { $0.hour == 20 })
        #expect(reminders[0].identifier == "beaver.payday-eve.2026-11-25")
        #expect(reminders[0].body.hasPrefix("Pay day tomorrow: expected $"))
        #expect(reminders[0].body.contains("Bills due before next payday: $"))
    }

    @Test func passedEveIsSkipped() {
        let late = PayReminderPlanner.reminders(
            forecast: Forecast.compute(DemoData.forecastInput(today: "2026-11-24")), nowMinutes: 21 * 60,
            formatter: FigureFormatter(currency: "AUD"))
        #expect(late.first?.payday == "2026-12-02")
        let early = PayReminderPlanner.reminders(
            forecast: Forecast.compute(DemoData.forecastInput(today: "2026-11-24")), nowMinutes: 9 * 60,
            formatter: FigureFormatter(currency: "AUD"))
        #expect(early.first?.fireDate == "2026-11-24")
    }

    @Test func noSourcesMeansNoReminders() {
        let empty = Forecast.compute(ForecastInput(today: demoToday))
        #expect(PayReminderPlanner.reminders(forecast: empty, formatter: FigureFormatter(currency: "AUD")).isEmpty)
    }

    final class FakeCenter: ReminderCenter, @unchecked Sendable {
        var granted: Bool
        var scheduled: [PayReminder] = []
        var removed = 0
        init(granted: Bool) { self.granted = granted }
        func requestAuthorization() async -> Bool { granted }
        func isAuthorized() async -> Bool { granted }
        func replace(_ reminders: [PayReminder], timeZone: TimeZone) async { scheduled = reminders }
        func removeAll() async { removed += 1; scheduled = [] }
    }

    private func defaults() -> UserDefaults {
        let d = UserDefaults(suiteName: "beaver.tests.\(UUID().uuidString)")!
        return d
    }

    @Test func offByDefaultAndPermissionOnlyOnEnable() async {
        let center = FakeCenter(granted: true)
        let scheduler = PayReminderScheduler(center: center, defaults: defaults())
        #expect(!scheduler.isEnabled)
        await scheduler.reschedule(forecast: demoForecast(), settings: AppSettings())
        #expect(center.scheduled.isEmpty) // off: nothing scheduled

        #expect(await scheduler.enable(forecast: demoForecast(), settings: AppSettings()))
        #expect(scheduler.isEnabled)
        #expect(!center.scheduled.isEmpty)
        await scheduler.disable()
        #expect(!scheduler.isEnabled)
        #expect(center.scheduled.isEmpty)
    }

    @Test func declinedPermissionStaysOff() async {
        let center = FakeCenter(granted: false)
        let scheduler = PayReminderScheduler(center: center, defaults: defaults())
        #expect(await scheduler.enable(forecast: demoForecast(), settings: AppSettings()) == false)
        #expect(!scheduler.isEnabled)
    }
}
