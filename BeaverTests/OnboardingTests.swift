import Foundation
import Testing
import BeaverCore
@testable import Beaver

@MainActor
private func makeModel(mode: OnboardingModel.Mode = .firstRun, demo: Bool = false) throws -> OnboardingModel {
    let repo = try Repository.inMemory()
    if demo { try repo.loadDemoData() }
    return OnboardingModel(appModel: AppModel(repository: repo), mode: mode, deviceCurrency: "USD",
                           deviceTimeZone: TimeZone(identifier: "Australia/Sydney")!)
}

// MARK: - Weekly equivalents

struct WeeklyEquivalentTests {
    @Test func weeklyBillIsItself() {
        #expect(WeeklyEquivalent.cents(amount: .dollars(400), cadence: .weekly) == 40000)
    }

    @Test func monthlyRoundsHalfUp() {
        // 1599 * 12 / 52 = 368.97...
        #expect(WeeklyEquivalent.cents(amount: .dollars(15, cents: 99), cadence: .monthly(dayOfMonth: 5)) == 369)
    }

    @Test func quarterlyIsAmountTimesFourOver52() {
        // 24000 * 4 / 52 = 1846.15...
        #expect(WeeklyEquivalent.cents(amount: .dollars(240), cadence: .quarterly) == 1846)
    }

    @Test func otherCadences() {
        #expect(WeeklyEquivalent.cents(amount: Money(cents: 101), cadence: .fortnightly) == 51)
        #expect(WeeklyEquivalent.cents(amount: .dollars(10), cadence: .everyNDays(14)) == 500)
        #expect(WeeklyEquivalent.cents(amount: .dollars(520), cadence: .yearly) == 1000)
        #expect(WeeklyEquivalent.cents(amount: .dollars(99), cadence: .oneOff) == 0)
    }

    @Test func billTotalSkipsInactive() {
        let day: LocalDate = "2026-11-25"
        let bills = [
            Bill(name: "Rent", amount: .dollars(400), cadence: .weekly, anchorDate: day),
            Bill(name: "Phone", amount: .dollars(15, cents: 99), cadence: .monthly(dayOfMonth: 25), anchorDate: day),
            Bill(name: "Old", amount: .dollars(100), cadence: .weekly, anchorDate: day, isActive: false),
        ]
        #expect(WeeklyEquivalent.bills(bills).cents == 40000 + 369)
    }

    @Test func fixedIncomeWeeklyEquivalent() {
        func input(_ schedule: PaySchedule, _ cents: Int) -> ForecastInput {
            let s = IncomeSource(id: 1, name: "Salary", kind: .fixed, fixedAmount: Money(cents: cents), amountIsNet: true, schedule: schedule)
            return ForecastInput(today: "2026-11-23", sources: [s])
        }
        #expect(WeeklyEquivalent.income(input(.weekly(payWeekday: .wednesday, lagWeeks: 1), 100_000)).cents == 100_000)
        #expect(WeeklyEquivalent.income(input(.fortnightly(anchorPayday: "2026-11-25", lagWeeks: 1), 200_000)).cents == 100_000)
        #expect(WeeklyEquivalent.income(input(.monthly(dayOfMonth: 1), 433_333)).cents == 100_000)
    }

    @Test func hourlyIncomeUsesDefaultWeek() throws {
        let repo = try Repository.inMemory()
        try repo.loadDemoData()
        let input = try repo.forecastInput(today: DemoData.referenceToday)
        #expect(WeeklyEquivalent.income(input).cents > 0)
    }
}

// MARK: - Navigation

@MainActor
struct OnboardingNavigationTests {
    @Test func startsOnWelcomeAndWalksForward() throws {
        let m = try makeModel()
        #expect(m.step == .welcome)
        #expect(m.isFirst && !m.canSkip)
        var seen = [m.step]
        while m.next() { seen.append(m.step) }
        #expect(seen == OnboardingModel.Step.allCases)
        #expect(m.isLast)
        #expect(!m.next())
        #expect(!m.skip())
    }

    @Test func skipWorksOnSteps2To6Only() throws {
        let m = try makeModel()
        #expect(!m.skip())
        #expect(m.step == .welcome)
        m.next()
        for expected in [OnboardingModel.Step.bills, .living, .goal, .limits, .summary] {
            #expect(m.canSkip)
            #expect(m.skip())
            #expect(m.step == expected)
        }
        #expect(!m.canSkip)
    }

    @Test func backStopsAtWelcome() throws {
        let m = try makeModel()
        m.back()
        #expect(m.step == .welcome)
        m.next(); m.next()
        m.back()
        #expect(m.step == .income)
        m.back()
        #expect(m.step == .welcome)
        m.back()
        #expect(m.step == .welcome)
    }

    @Test func progressRunsFromOneToSeven() throws {
        let m = try makeModel()
        #expect(m.stepNumber == 1 && m.stepCount == 7)
        while m.next() {}
        #expect(m.stepNumber == 7 && m.progress == 1)
    }

    @Test func invalidLivingBudgetBlocksNextButNotSkip() throws {
        let m = try makeModel()
        m.next(); m.next(); m.next()
        #expect(m.step == .living)
        m.livingText = "abc"
        #expect(!m.canAdvance)
        #expect(!m.next())
        #expect(m.step == .living)
        #expect(m.skip())
        #expect(m.step == .goal)
        #expect(try m.appModel.repository.settings().livingBudget == .zero)
    }

    @Test func nextSavesLivingBudgetAndSkipDoesNot() throws {
        let m = try makeModel()
        m.next(); m.next(); m.next()
        m.livingText = "$150.50"
        #expect(m.next())
        #expect(try m.appModel.repository.settings().livingBudget.cents == 15050)
        #expect(m.appModel.store.settings.livingBudget.cents == 15050)
    }

    @Test func welcomeNextSavesDeviceDefaults() throws {
        let m = try makeModel()
        #expect(m.currency == "USD")
        #expect(m.timeZoneID == "Australia/Sydney")
        m.next()
        let s = try m.appModel.repository.settings()
        #expect(s.currency == "USD")
        #expect(s.timeZoneID == "Australia/Sydney")
    }

    @Test func settersWriteSettings() throws {
        let m = try makeModel()
        m.setCurrency("NZD")
        m.setTimeZone("Pacific/Auckland")
        let s = try m.appModel.repository.settings()
        #expect(s.currency == "NZD")
        #expect(s.timeZoneID == "Pacific/Auckland")
    }

    @Test func rerunShowsExistingData() throws {
        let m = try makeModel(mode: .rerun, demo: true)
        #expect(m.currency == "AUD")
        #expect(m.sources.count == 1)
        #expect(!m.bills.isEmpty)
        #expect(!m.goals.isEmpty)
        #expect(m.livingText == MoneyParser.text(fromCents: DemoData.livingBudget.cents))
        #expect(m.limitsSummary != nil)
    }

    @Test func deleteSourceAndBill() throws {
        let m = try makeModel(mode: .rerun, demo: true)
        let bill = try #require(m.bills.first)
        m.deleteBill(bill)
        #expect(!m.bills.contains(bill))
        let source = try #require(m.sources.first)
        m.deleteSource(source)
        #expect(m.sources.isEmpty)
        #expect(try m.appModel.repository.rateCards().isEmpty)
    }
}

// MARK: - Finish and restore

@MainActor
struct OnboardingFinishTests {
    @Test func finishSetsOnboardingDone() throws {
        let m = try makeModel()
        #expect(!m.appModel.onboardingDone)
        while m.next() {}
        m.finish()
        #expect(m.appModel.onboardingDone)
        #expect(try m.appModel.repository.settings().onboardingDone)
        #expect(m.isFinished)
    }

    @Test func rerunFinishLeavesDataAlone() throws {
        let m = try makeModel(mode: .rerun, demo: true)
        let before = try m.appModel.repository.userRowCount()
        m.finish()
        #expect(m.isFinished)
        #expect(try m.appModel.repository.userRowCount() == before)
    }

    @Test func summaryWithGoalAndWithout() throws {
        let m = try makeModel(mode: .rerun, demo: true)
        let withGoal = m.summary
        #expect(withGoal.hasGoal)
        #expect(withGoal.headline.contains("ahead") || withGoal.headline.contains("behind") || withGoal.headline == "Right on target")

        let empty = try makeModel()
        let none = empty.summary
        #expect(none.isEmpty)
        #expect(none.headline.hasPrefix("Weekly "))
    }

    @Test func restoreFromValidExportCompletesOnboarding() throws {
        let source = try Repository.inMemory()
        try source.loadDemoData()
        let data = try Backup(repository: source, appVersion: "1").export()

        let m = try makeModel()
        m.stageRestore(data: data)
        #expect(m.pendingRestore != nil)
        #expect(m.confirmRestore())
        #expect(m.appModel.onboardingDone)
        #expect(m.isFinished)
        #expect(m.restoreError == nil)
        #expect(try m.appModel.repository.incomeSources().count == 1)
    }

    @Test func nothingChangesBeforeConfirmation() throws {
        let source = try Repository.inMemory()
        try source.loadDemoData()
        let data = try Backup(repository: source, appVersion: "1").export()

        let m = try makeModel()
        m.stageRestore(data: data)
        m.cancelRestore()
        #expect(!m.confirmRestore())
        #expect(try m.appModel.repository.userRowCount() == 0)
        #expect(!m.appModel.onboardingDone)
    }

    @Test func invalidFileShowsErrorAndLeavesDatabaseUntouched() throws {
        let m = try makeModel()
        try m.appModel.repository.save(Bill(name: "Rent", amount: .dollars(400), cadence: .weekly, anchorDate: "2026-11-25"))
        let before = try m.appModel.repository.userRowCount()

        m.stageRestore(data: Data("this is not a backup".utf8))
        #expect(!m.confirmRestore())
        #expect(m.restoreError == BackupError.notABackup.localizedDescription)
        #expect(!m.appModel.onboardingDone)
        #expect(!m.isFinished)
        #expect(try m.appModel.repository.userRowCount() == before)
        #expect(try m.appModel.repository.bills().count == 1)
    }

    @Test func newerSchemaIsRefused() throws {
        let m = try makeModel()
        m.stageRestore(data: Data(#"{"schemaVersion": 99, "tables": {}}"#.utf8))
        #expect(!m.confirmRestore())
        #expect(m.restoreError != nil)
        #expect(try m.appModel.repository.userRowCount() == 0)
    }
}
