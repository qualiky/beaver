import Foundation
import Testing
import BeaverCore
@testable import Beaver

private let enUS = Locale(identifier: "en_US")
private let today = DemoData.referenceToday // Mon 2026-11-23

@MainActor
private func demoStore() throws -> (store: AppStore, repository: Repository) {
    let repository = try Repository.inMemory()
    try repository.loadDemoData()
    return (AppStore(repository: repository, clock: { _ in DemoData.referenceToday }), repository)
}

@MainActor
private func weekModel(_ store: AppStore, limits: [FortnightLimit] = [], now: Date = Date()) -> WeekViewModel {
    WeekViewModel(forecast: store.forecast, input: store.input, settings: store.settings,
                  hoursLimits: limits, now: now, locale: enUS)
}

/// The demo input for another "today", run through the engine directly.
private func demoModel(today day: LocalDate, edit: (inout ForecastInput) -> Void = { _ in }) -> WeekViewModel {
    var input = DemoData.forecastInput(today: day)
    edit(&input)
    var settings = AppSettings()
    settings.currency = "AUD"
    return WeekViewModel(forecast: Forecast.compute(input), input: input, settings: settings, now: Date(), locale: enUS)
}

struct WeekHeadlineTests {
    @MainActor
    @Test func headlineShowsAheadForDemoData() throws {
        let (store, _) = try demoStore()
        let headline = weekModel(store).headline
        #expect(headline.text == "+$2,578.99 ahead")
        #expect(headline.tone == .positive)
        #expect(headline.detail?.contains("$8,578.99") == true)
        #expect(headline.detail?.contains("$6,000.00") == true)
    }

    @Test func headlineShowsBehindWithNegativeSign() {
        let model = demoModel(today: today) { $0.goals = [Goal(id: DemoData.goalID, name: "Big", target: .dollars(20_000), deadline: "2027-03-31")] }
        #expect(model.headline.text.hasSuffix(" behind"))
        #expect(model.headline.text.hasPrefix("-$"))
        #expect(model.headline.tone == .negative)
    }

    @Test func headlineWithoutGoalShowsThisWeekSurplus() {
        let model = demoModel(today: today) { $0.goals = []; $0.primaryGoalID = nil }
        // Week of 23 Nov: $600 actual pay - $400 rent - $25 gym - $150 living.
        #expect(model.headline.text == "This week: +$25.00 surplus")
        #expect(model.goalCard == nil)
    }

    @Test func goalCardShowsProgressAndRequiredAmount() {
        let model = demoModel(today: today) {
            $0.deposits = [Deposit(goalID: DemoData.goalID, date: "2026-11-20", amount: .dollars(1500))]
        }
        let card = model.goalCard
        #expect(card?.progress == 0.25)
        #expect(card?.savedText == "$1,500.00")
        #expect(card?.requiredText.hasSuffix("needed per payday") == true)
        #expect(card?.paydaysText == "19 paydays left")
    }

    @MainActor
    @Test func emptyStateWhenNoSourcesOrBills() throws {
        let repository = try Repository.inMemory()
        let store = AppStore(repository: repository, clock: { _ in DemoData.referenceToday })
        #expect(weekModel(store).isEmpty)
        #expect(weekModel(try demoStore().store).isEmpty == false)
    }
}

struct WeekDatesTests {
    @MainActor
    @Test func nextPaydayAndSplitUseTheRightDates() throws {
        let (store, _) = try demoStore()
        let model = weekModel(store)
        #expect(model.nextPayday == "2026-11-25")
        let split = try #require(store.forecast.suggestedSplit)
        #expect(split.payday == "2026-11-25")
        #expect(split.followingPayday == "2026-12-02")
        #expect(model.split?.lines.map(\.kind) == [.bills, .living, .sinkingFund, .goal])
        // Rent (25 Nov) is due on payday, so it belongs to this split, not to "before the next payday".
        #expect(split.line(.bills)?.needed == .dollars(400) + .dollars(25))
    }

    @Test func billsBeforeNextPaydayMidWeek() {
        // Thursday 26 Nov: next payday is Wed 2 Dec, so the window is Mon 23 Nov through Tue 1 Dec.
        let model = demoModel(today: "2026-11-26")
        #expect(model.nextPayday == "2026-12-02")
        #expect(model.bills.map(\.name) == ["Phone", "Rent", "Gym"])
        #expect(model.bills.map(\.dueDate) == ["2026-11-22", "2026-11-25", "2026-11-27"])
        #expect(model.bills.first?.isOverdue == true)
        #expect(model.billsTotal == .dollars(455))
        #expect(model.split?.paydayText == DateText.weekdayDayMonth("2026-12-02", locale: enUS))
        let split = model.forecast.suggestedSplit
        #expect(split?.followingPayday == "2026-12-09")
        // Rent 2 Dec + Streaming 5 Dec fall in 2 Dec ... 8 Dec.
        #expect(split?.line(.bills)?.needed == .dollars(400) + .dollars(15, cents: 99))
    }

    @Test func billsOnTheDayBeforePaydayAreIncludedAndPaydayBillsAreNot() {
        // Tuesday 24 Nov, payday Wed 25: only the overdue Phone bill is "before" it; Rent lands on payday.
        let model = demoModel(today: "2026-11-24")
        #expect(model.bills.map(\.name) == ["Phone"])
    }

    @Test func paidBillsStayListedWithATick() {
        let model = demoModel(today: "2026-11-26") {
            $0.billPayments = [BillPayment(billID: 1, dueDate: "2026-11-25", paidDate: "2026-11-25", amount: .dollars(400))]
        }
        let rent = model.bills.first { $0.name == "Rent" }
        #expect(rent?.isPaid == true)
        #expect(model.billsUnpaidTotal == .dollars(55))
    }

    @Test func payLinesShowActualAndDifference() {
        let model = demoModel(today: today)
        let line = model.payLines.first
        #expect(model.payLines.count == 1)
        #expect(line?.payday == "2026-11-25")
        #expect(line?.isActual == true)
        #expect(line?.actualText == "$600.00")
        #expect(line?.differenceText?.hasPrefix("-$") == true || line?.differenceText?.hasPrefix("+$") == true)
    }

    @MainActor
    @Test func markPaidAndExpensesFlowThroughTheRepository() throws {
        let (store, repository) = try demoStore()
        let before = weekModel(store).living
        #expect(before.leftText == "$150.00")

        try repository.save(Expense(date: today, amount: .dollars(40), category: .food, note: "Lunch"))
        try repository.markPaid(BillPayment(billID: 2, dueDate: "2026-11-22", paidDate: today, amount: .dollars(30)))
        try store.reload()

        let after = weekModel(store)
        #expect(after.living.leftText == "$110.00")
        #expect(after.bills.isEmpty || after.bills.allSatisfy(\.isPaid))
    }
}

struct WeekBannerTests {
    @Test func noDangerBannerWhenCurrentAndNextWeekAreFine() {
        let model = demoModel(today: today)
        // The demo has danger weeks later in December, but not this week or next.
        #expect(!model.forecast.dangerWeeks.isEmpty)
        #expect(model.dangerBanners.isEmpty)
    }

    @Test func dangerBannerForNextWeekListsCausingBills() {
        let model = demoModel(today: today) {
            $0.bills.append(Bill(id: 9, name: "Big repair", amount: .dollars(2000), cadence: .oneOff,
                                 anchorDate: "2026-12-01", category: .transport))
        }
        let banners = model.dangerBanners
        #expect(banners.count == 1)
        #expect(banners.first?.weekStart == "2026-11-30")
        #expect(banners.first?.title == "Next week is tight")
        #expect(banners.first?.bills.map(\.name).contains("Big repair") == true)
        #expect(banners.first?.shortfallText.hasSuffix("short") == true)
    }

    @Test func dangerBannersCoverBothThisAndNextWeek() {
        let model = demoModel(today: today) {
            $0.bills.append(Bill(id: 9, name: "A", amount: .dollars(2000), cadence: .oneOff, anchorDate: "2026-11-24", category: .other))
            $0.bills.append(Bill(id: 10, name: "B", amount: .dollars(2000), cadence: .oneOff, anchorDate: "2026-12-01", category: .other))
        }
        #expect(model.dangerBanners.map(\.title) == ["This week is tight", "Next week is tight"])
    }

    @Test func backupReminderLogic() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        func ago(_ days: Double) -> Date { now.addingTimeInterval(-days * 86_400) }
        #expect(BackupReminder.isDue(lastExportAt: nil, now: now))
        #expect(!BackupReminder.isDue(lastExportAt: ago(3), now: now))
        #expect(!BackupReminder.isDue(lastExportAt: ago(7), now: now))
        #expect(BackupReminder.isDue(lastExportAt: ago(8), now: now))
        #expect(BackupReminder.message(lastExportAt: ago(3), now: now) == nil)
        #expect(BackupReminder.message(lastExportAt: nil, now: now)?.contains("haven't backed up") == true)
        #expect(BackupReminder.message(lastExportAt: ago(8), now: now)?.contains("8 days") == true)
    }

    @MainActor
    @Test func backupBannerFollowsSettings() throws {
        let (store, _) = try demoStore()
        #expect(weekModel(store).backupMessage != nil) // demo data has never been exported
    }
}

struct HoursBarTests {
    private func shifts(count: Int, hours: Int = 8) -> [Shift] {
        (0..<count).map { i in
            Shift(sourceID: 1, date: LocalDate(stringLiteral: "2026-11-23").adding(days: i), start: "08:00",
                  end: LocalTime(hour: 8 + hours), breakMinutes: 0)
        }
    }
    private let limit = FortnightLimit(hoursPerFortnight: 40, effectiveFrom: "2026-11-23")

    @Test func okNearOverThresholds() {
        #expect(HoursBar.make(limits: [limit], shifts: shifts(count: 4), today: today)?.status == .ok)      // 32 h
        #expect(HoursBar.make(limits: [limit], shifts: shifts(count: 5), today: today)?.status == .near)    // 40 h, at the limit
        #expect(HoursBar.make(limits: [limit], shifts: shifts(count: 6), today: today)?.status == .over)    // 48 h
        // 36 h is exactly limit - 4: amber.
        let thirtySix = shifts(count: 4) + [Shift(sourceID: 1, date: "2026-11-28", start: "08:00", end: "12:00")]
        #expect(HoursBar.make(limits: [limit], shifts: thirtySix, today: today)?.status == .near)
    }

    @Test func nilWhenNoLimitIsActiveForTheCurrentFortnight() {
        #expect(HoursBar.make(limits: [], shifts: [], today: today) == nil)
        let later = FortnightLimit(hoursPerFortnight: 40, effectiveFrom: "2026-12-07")
        #expect(HoursBar.make(limits: [later], shifts: [], today: today) == nil)
    }

    @Test func cancelledShiftsAndOtherFortnightsDoNotCount() {
        var all = shifts(count: 2)
        all[0].status = .cancelled
        all.append(Shift(sourceID: 1, date: "2026-12-10", start: "08:00", end: "16:00"))
        let bar = HoursBar.make(limits: [limit], shifts: all, today: today)
        #expect(bar?.usedMinutes == 480)
        #expect(bar?.window == DateRange(start: "2026-11-23", end: "2026-12-06"))
    }

    @Test func warningTextOnlyWhenNearOrOver() {
        let ok = demoModel(today: today)
        #expect(ok.hoursWarning == nil)
        let over = WeekViewModel(forecast: ok.forecast, input: { var i = ok.input; i.shifts = shifts(count: 6); return i }(),
                                 settings: AppSettings(), hoursLimits: [limit], now: Date(), locale: enUS)
        #expect(over.hoursBar?.status == .over)
        #expect(over.hoursWarning?.contains("over") == true)
    }
}

struct SplitAndFormattingTests {
    @Test func fromBufferLinesAreFlagged() {
        // No pay is expected after a huge goal requirement, so the goal line is short.
        let model = demoModel(today: today) {
            $0.goals = [Goal(id: DemoData.goalID, name: "Huge", target: .dollars(500_000), deadline: "2027-03-31")]
        }
        let split = model.split
        #expect(split?.lines.last?.kind == .goal)
        #expect(split?.lines.last?.fromBufferText != nil)
        #expect(split?.totalFromBufferText != nil)
        #expect(split?.lines.first?.fromBufferText == nil)
    }

    @Test func moneyInputParsing() {
        #expect(MoneyInput.cents(from: "12") == 1200)
        #expect(MoneyInput.cents(from: "12.5") == 1250)
        #expect(MoneyInput.cents(from: "$1,234.56") == 123_456)
        #expect(MoneyInput.cents(from: ".99") == 99)
        #expect(MoneyInput.cents(from: "1.234") == nil)
        #expect(MoneyInput.cents(from: "abc") == nil)
        #expect(MoneyInput.cents(from: "") == nil)
        #expect(MoneyInput.cents(from: "-5") == nil)
    }

    @Test func figureFormatterSigns() {
        let fmt = FigureFormatter(currency: "AUD", locale: enUS)
        #expect(fmt.string(.dollars(1234, cents: 5)) == "$1,234.05")
        #expect(fmt.signed(.dollars(5)) == "+$5.00")
        #expect(fmt.signed(-Money.dollars(5)) == "-$5.00")
        #expect(fmt.signed(.zero) == "$0.00")
    }
}

struct PlanTests {
    @MainActor
    @Test func planRowsTagIncomeAndRunBalance() throws {
        let (store, _) = try demoStore()
        let model = PlanViewModel(forecast: store.forecast, settings: store.settings, locale: enUS)
        let rows = model.rows
        #expect(rows.count == store.forecast.weeks.count)
        #expect(rows.first?.incomeText == "$600.00 actual")
        #expect(rows.first?.incomeParts.first?.isActual == true)
        #expect(rows[1].incomeText.hasSuffix("est."))
        // The last balance equals the projected amount at the deadline.
        #expect(rows.last?.balanceText == "$8,578.99")
        #expect(rows.filter(\.isDanger).map(\.week) == store.forecast.dangerWeeks)
    }

    @MainActor
    @Test func planSummaryAndWhatIf() throws {
        let (store, _) = try demoStore()
        let base = PlanViewModel(forecast: store.forecast, settings: store.settings, locale: enUS)
        #expect(base.summary.gapText == "+$2,578.99")
        #expect(base.summary.projectedText == "$8,578.99")

        store.extraWeeklyIncome = .dollars(50)
        let boosted = PlanViewModel(forecast: store.forecast, settings: store.settings, locale: enUS)
        #expect(boosted.rows.first?.incomeText.contains("+ $50.00 est.") == true)
        #expect(boosted.summary.gapText != base.summary.gapText)

        store.extraWeeklyIncome = .zero
        let reset = PlanViewModel(forecast: store.forecast, settings: store.settings, locale: enUS)
        #expect(reset.summary.gapText == "+$2,578.99")
    }

    @MainActor
    @Test func livingBudgetWritesThroughRepository() throws {
        let (store, repository) = try demoStore()
        try repository.setLivingBudget(.dollars(160))
        try store.reload()
        #expect(store.settings.livingBudget == .dollars(160))
        #expect(store.forecast.gap.cents < 257_899)
    }

    @MainActor
    @Test func weekDetailListsPaydaysAndBills() throws {
        let (store, _) = try demoStore()
        let model = PlanViewModel(forecast: store.forecast, settings: store.settings, locale: enUS)
        let row = try #require(model.rows.first)
        let detail = model.detail(for: row)
        #expect(detail.pays.map(\.payday) == ["2026-11-25"])
        #expect(detail.bills.map(\.name) == ["Rent", "Gym"])
        #expect(detail.isDanger == false)
    }
}
