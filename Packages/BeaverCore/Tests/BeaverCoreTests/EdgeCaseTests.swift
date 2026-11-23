import Testing
import Foundation
import BeaverCore

private let card = DemoData.rateCard
private let holidays = DemoData.holidayDates

private func shift(_ date: LocalDate, _ start: LocalTime, _ end: LocalTime, brk: Int = 0,
                   status: ShiftStatus = .rostered, source: Int = DemoData.sourceID) -> Shift {
    Shift(sourceID: source, date: date, start: start, end: end, breakMinutes: brk, status: status)
}

@Suite("Dates and times")
struct DateTests {
    @Test func weekdaysAndArithmetic() {
        #expect(LocalDate("2026-11-23").weekday == .monday)
        #expect(LocalDate("2026-11-29").weekday == .sunday)
        #expect(LocalDate("1970-01-01").weekday == .thursday)
        #expect(LocalDate("2026-11-25").mondayOfWeek == "2026-11-23")
        #expect(LocalDate("2026-11-29").mondayOfWeek == "2026-11-23")
        #expect(LocalDate("2026-11-23").adding(days: -1) == "2026-11-22")
        #expect(LocalDate("2028-02-28").adding(days: 1) == "2028-02-29")
        #expect(LocalDate("2027-01-31").adding(months: 1) == "2027-02-28")
        #expect(LocalDate("2026-11-23").days(until: "2027-03-31") == 128)
    }

    @Test func isoCodable() throws {
        let d: LocalDate = "2026-03-09"
        let t: LocalTime = "07:05"
        let data = try JSONEncoder().encode(["d": d.iso])
        #expect(String(decoding: data, as: UTF8.self) == #"{"d":"2026-03-09"}"#)
        #expect(try JSONDecoder().decode(LocalDate.self, from: Data(#""2026-03-09""#.utf8)) == d)
        #expect(try JSONDecoder().decode(LocalTime.self, from: Data(#""07:05""#.utf8)) == t)
        #expect(throws: DecodingError.self) { try JSONDecoder().decode(LocalDate.self, from: Data(#""2026-02-30""#.utf8)) }
        #expect(LocalTime(iso: "24:00") == nil)
    }

    @Test func forecastInputRoundTripsThroughJSON() throws {
        let input = DemoData.forecastInput()
        let data = try JSONEncoder().encode(input)
        #expect(try JSONDecoder().decode(ForecastInput.self, from: data) == input)
    }

    /// Australian DST starts Sun 2026-10-04 at 02:00. Wall-clock minutes must not move.
    @Test func shiftAcrossDSTChangePaysWallClockMinutes() {
        let s = shift("2026-10-03", "22:00", "06:00")
        let r = ShiftPay.calculate(shift: s, rateCard: card, holidays: holidays)
        #expect(r.durationMinutes == 480)
        #expect(r.paidMinutes == 480)
        // 2h Saturday at 37.50 + 6h Sunday at 45.00
        #expect(r.gross.cents == 2 * 3750 + 6 * 4500)
    }
}

@Suite("Shift pay edge cases")
struct ShiftEdgeTests {
    @Test func zeroBreakPaysFullDuration() {
        let r = ShiftPay.calculate(shift: shift("2026-11-23", "09:00", "17:00", brk: 0), rateCard: card, holidays: holidays)
        #expect(r.paidMinutes == 480)
        #expect(r.gross.cents == 24000)
    }

    @Test func breakEqualToDurationPaysNothing() {
        let r = ShiftPay.calculate(shift: shift("2026-11-23", "09:00", "17:00", brk: 480), rateCard: card, holidays: holidays)
        #expect(r.paidMinutes == 0)
        #expect(r.gross.cents == 0)
        // Oversized breaks clamp.
        let over = ShiftPay.calculate(shift: shift("2026-11-23", "09:00", "17:00", brk: 999), rateCard: card, holidays: holidays)
        #expect(over.gross.cents == 0)
    }

    @Test func emptyWeek() {
        let pay = PeriodPay.calculate(shifts: [], rateCard: card, holidays: holidays,
                                      taxMethod: .withholdingTable(id: WithholdingTable.auScale2WeeklyID))
        #expect(pay == PeriodPay())
        #expect(pay.hoursHundredths == 0)
    }

    @Test func cancelledShiftsEarnNothing() {
        let pay = PeriodPay.calculate(shifts: [shift("2026-11-23", "09:00", "17:00", status: .cancelled)],
                                      rateCard: card, holidays: holidays)
        #expect(pay.gross.cents == 0)
    }

    @Test func equalStartAndEndIsTwentyFourHours() {
        let s = shift("2026-11-23", "08:00", "08:00")
        #expect(s.durationMinutes == 1440)
        #expect(s.crossesMidnight)
    }

    @Test func rateCardGapsAreReported() {
        #expect(card.weekdayGaps().isEmpty)
        var broken = card
        broken.rules.removeAll { $0.band == "Weekday night" }
        #expect(broken.weekdayGaps() == [ClockWindow(startMinute: 0, endMinute: 420)])
        let r = ShiftPay.calculate(shift: shift("2026-11-23", "05:00", "07:00"), rateCard: broken, holidays: holidays)
        #expect(r.bands.map(\.band) == [ShiftPay.unratedBand])
        #expect(r.gross.cents == 0)
    }

    @Test func holidayOverridesOtherBandsAndPeriodTaxIsOnTotal() {
        // Fri 2026-12-25 is a holiday: the whole day uses the holiday band.
        let r = ShiftPay.calculate(shift: shift("2026-12-25", "10:00", "12:00"), rateCard: card, holidays: holidays)
        #expect(r.bands.map(\.band) == ["Public holiday"])
        #expect(r.gross.cents == 12000)
    }
}

@Suite("Tax")
struct TaxTests {
    @Test func flatPercentRoundsHalfUp() {
        #expect(TaxMethod.flatPercent(basisPoints: 1000).tax(onGross: Money(cents: 12345), frequency: .weekly).cents == 1235)
        #expect(TaxMethod.none.tax(onGross: Money(cents: 12345), frequency: .weekly).cents == 0)
    }

    @Test func fortnightlyHalvesAndDoubles() {
        let t = WithholdingTable.auScale2Weekly
        #expect(t.tax(onGross: Money(cents: 200000), frequency: .fortnightly).cents
                == 2 * t.weeklyTax(onGross: Money(cents: 100000)).cents)
    }
}

@Suite("Schedules")
struct ScheduleTests {
    let fortnightly = PaySchedule.fortnightly(anchorPayday: "2026-11-25", lagWeeks: 1)

    @Test func fortnightlyPaydaysBothDirections() {
        #expect(fortnightly.paydays(in: DateRange(start: "2026-11-01", end: "2026-12-31"))
                == ["2026-11-11", "2026-11-25", "2026-12-09", "2026-12-23"])
        #expect(fortnightly.paydays(in: DateRange(start: "2026-11-26", end: "2026-12-08")).isEmpty)
    }

    @Test func fortnightlyWorkPeriodAndLookup() {
        #expect(fortnightly.workPeriod(forPayday: "2026-12-09") == DateRange(start: "2026-11-23", end: "2026-12-06"))
        #expect(fortnightly.payday(forWorkDate: "2026-12-06") == "2026-12-09")
        #expect(fortnightly.payday(forWorkDate: "2026-11-23") == "2026-12-09")
        #expect(fortnightly.payday(forWorkDate: "2026-11-22") == "2026-11-25")
        #expect(fortnightly.payday(forWorkDate: "2026-11-08") == "2026-11-11")
    }

    @Test func weeklyLagTwoPaysTheWeekBefore() {
        let s = PaySchedule.weekly(payWeekday: .wednesday, lagWeeks: 2)
        #expect(s.workPeriod(forPayday: "2026-12-09") == DateRange(start: "2026-11-23", end: "2026-11-29"))
        #expect(s.payday(forWorkDate: "2026-11-29") == "2026-12-09")
    }

    @Test func monthlyHasNoWorkPeriod() {
        #expect(PaySchedule.monthly(dayOfMonth: 15).workPeriod(forPayday: "2026-12-15") == nil)
    }
}

@Suite("Bills")
struct BillTests {
    @Test func oneOffAppearsOnceAndNeverBeforeAnchor() {
        let b = Bill(name: "Service", amount: .dollars(500), cadence: .oneOff, anchorDate: "2026-12-10")
        #expect(b.dueDates(in: DateRange(start: "2026-01-01", end: "2030-01-01")) == ["2026-12-10"])
        #expect(b.dueDates(in: DateRange(start: "2026-12-11", end: "2030-01-01")).isEmpty)
        #expect(b.dueDates(in: DateRange(start: "2026-01-01", end: "2026-12-09")).isEmpty)
    }

    @Test func yearlyClampsLeapDay() {
        let b = Bill(name: "Rego", amount: .dollars(800), cadence: .yearly, anchorDate: "2028-02-29")
        #expect(b.dueDates(in: DateRange(start: "2028-01-01", end: "2031-12-31"))
                == ["2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28"])
    }

    @Test func monthlyAfterOffDayAnchorUsesDayOfMonth() {
        let b = Bill(name: "X", amount: .dollars(1), cadence: .monthly(dayOfMonth: 5), anchorDate: "2026-11-20")
        #expect(b.dueDates(in: DateRange(start: "2026-11-01", end: "2027-01-31")) == ["2026-11-20", "2026-12-05", "2027-01-05"])
    }

    @Test func paidAndOverdueStatus() {
        let bill = Bill(id: 7, name: "Rent", amount: .dollars(400), cadence: .weekly, anchorDate: "2026-11-02")
        let pay = BillPayment(billID: 7, dueDate: "2026-11-09", paidDate: "2026-11-10", amount: .dollars(410))
        let occ = bill.occurrences(in: DateRange(start: "2026-11-02", end: "2026-11-16"), payments: [pay])
        let today: LocalDate = "2026-11-12"
        #expect(occ.map { $0.status(asOf: today) } == [.overdue, .paid, .due])
        #expect(occ[1].amount == .dollars(410))
        #expect(occ[0].amount == .dollars(400))
        #expect(bill.occurrences(in: DateRange(start: "2026-11-12", end: "2026-11-12"))
            .isEmpty)
        #expect(occ[2].status(asOf: "2026-11-16") == .due)
    }
}

@Suite("Hours limits, spending and goals")
struct MiscTests {
    @Test func weeklyCaps() {
        let source = IncomeSource(id: 1, name: "S", kind: .hourly, schedule: .weekly(payWeekday: .wednesday, lagWeeks: 1),
                                  weeklyCapSoftHours: 20, weeklyCapHardHours: 30)
        func week(hours: Int) -> [Shift] {
            [shift("2026-11-23", "00:00", "00:00", brk: 1440 - 20 * 60), shift("2026-11-24", "00:00", "00:00", brk: 1440 - (hours - 20) * 60)]
        }
        #expect(HoursLimits.weeklyWarning(source: source, weekStart: "2026-11-23", shifts: week(hours: 20)) == nil)
        #expect(HoursLimits.weeklyWarning(source: source, weekStart: "2026-11-24", shifts: week(hours: 21))?.level == .soft)
        #expect(HoursLimits.weeklyWarning(source: source, weekStart: "2026-11-23", shifts: week(hours: 31))?.level == .hard)
        #expect(HoursLimits.weeklyWarning(source: source, weekStart: "2026-11-30", shifts: week(hours: 31)) == nil)
    }

    @Test func spendingWeekTotalReplacesEntries() {
        let monday: LocalDate = "2026-11-16"
        let entries = [Expense(date: "2026-11-17", amount: .dollars(20)), Expense(date: "2026-11-22", amount: .dollars(30))]
        #expect(Spending.spent(weekStart: monday, expenses: entries, totals: []) == .dollars(50))
        #expect(Spending.spent(weekStart: monday, expenses: entries, totals: [WeekSpendTotal(weekStart: monday, amount: .dollars(90))]) == .dollars(90))
        #expect(Spending.variance(budget: .dollars(150), spent: .dollars(90)) == .dollars(60))
        #expect(Spending.variance(budget: .dollars(150), spent: .dollars(170)) == .dollars(-20))
    }

    @Test func goalProgress() {
        let g = Goal(id: 2, name: "G", target: .dollars(1000))
        let d = [Deposit(goalID: 2, date: "2026-11-01", amount: .dollars(250)), Deposit(goalID: 3, date: "2026-11-01", amount: .dollars(999))]
        #expect(g.saved(deposits: d) == .dollars(250))
        #expect(g.progressBasisPoints(deposits: d) == 2500)
    }
}

@Suite("Templates and forecast")
struct ForecastTests {
    @Test func latestEffectiveTemplateWins() {
        let base = Template(id: 1, sourceID: 1, name: "Base", shifts: [TemplateShift(weekday: .monday, start: "09:00", end: "10:00")])
        let sem = Template(id: 2, sourceID: 1, name: "Semester", effectiveFrom: "2027-02-22",
                           shifts: [TemplateShift(weekday: .tuesday, start: "09:00", end: "10:00")])
        let before = TemplateExpander.shifts(sourceID: 1, templates: [base, sem], range: DateRange(start: "2027-02-15", end: "2027-02-21"))
        #expect(before.map(\.date) == ["2027-02-15"])
        let after = TemplateExpander.shifts(sourceID: 1, templates: [sem, base], range: DateRange(start: "2027-02-22", end: "2027-02-28"))
        #expect(after.map(\.date) == ["2027-02-23"])
    }

    @Test func unavailableDatesSkipTemplateShifts() {
        let s = TemplateExpander.shifts(sourceID: DemoData.sourceID, templates: [DemoData.template],
                                        range: DateRange(start: "2026-11-30", end: "2026-12-06"),
                                        unavailable: DemoData.unavailable)
        #expect(s.map(\.date) == ["2026-12-06"])
    }

    @Test func fixedMonthlyIncomeForecast() {
        let salary = IncomeSource(id: 9, name: "Salary", kind: .fixed, fixedAmount: .dollars(4800), amountIsNet: true,
                                  schedule: .monthly(dayOfMonth: 31))
        let input = ForecastInput(today: "2026-11-23", sources: [salary], livingBudget: .dollars(150))
        let f = Forecast.compute(input)
        #expect(f.weeks.count == 26)
        #expect(f.goal == nil)
        let paydays = f.payEvents.map(\.payday)
        #expect(paydays.prefix(3) == ["2026-11-30", "2026-12-31", "2027-01-31"])
        #expect(f.totalIncome.cents == 480000 * paydays.count)
        #expect(f.gap == f.projectedAtDeadline)
    }

    @Test func fixedGrossTaxedByMethod() {
        let s = IncomeSource(id: 9, name: "Job", kind: .fixed, fixedAmount: .dollars(1000), amountIsNet: false,
                             schedule: .weekly(payWeekday: .friday, lagWeeks: 1), taxMethod: .flatPercent(basisPoints: 2000))
        let f = Forecast.compute(ForecastInput(today: "2026-11-23", sources: [s]))
        #expect(f.payEvents.first?.net == .dollars(800))
    }

    @Test func emptyInputIsAllZeroWithNoSplit() {
        let f = Forecast.compute(ForecastInput(today: "2026-11-23"))
        #expect(f.weeks.count == 26)
        #expect(f.totalSurplus == .zero)
        #expect(f.dangerWeeks.isEmpty)
        #expect(f.suggestedSplit == nil)
        #expect(f.requiredPerPayday == .zero)
    }

    @Test func demoRequiredPerPaydayDangerAndVariance() {
        let f = Forecast.compute(DemoData.forecastInput())
        #expect(f.requiredPerPayday.cents == 31579) // 600000 / 19
        #expect(f.depositsTotal == .zero)
        #expect(f.payVariance.cents == 60000 - 110983)
        #expect(f.spendingVariance == .zero)
        #expect(f.weeks.count == 19)
        #expect(f.weeks.last?.isDeadlineWeek == true)
        #expect(f.weeks.first?.isCurrent == true)
        // The first week pays only the logged $600 against $400 rent and $150 living, then gym/other bills.
        #expect(f.dangerWeeks.allSatisfy { $0.surplus.cents < 0 })
        #expect(f.dangerWeeks.contains { $0.weekStart == "2026-12-07" }) // short pay, $500 car service + rent
    }

    @Test func pastSpendingVarianceAndDeposits() {
        var input = DemoData.forecastInput()
        input.expenses = [Expense(date: "2026-11-17", amount: .dollars(100)), Expense(date: "2026-11-24", amount: .dollars(999))]
        input.deposits = [Deposit(goalID: DemoData.goalID, date: "2026-11-10", amount: .dollars(1000))]
        let f = Forecast.compute(input)
        #expect(f.spendingVariance == .dollars(50)) // only the past week counts
        #expect(f.depositsTotal == .dollars(1000))
        #expect(f.gap.cents == 257_899 + 100_000)
        #expect(f.requiredPerPayday.cents == IntMathCheck.half(500_000, 19))
    }

    @Test func extraWeeklyIncomeWhatIf() {
        var input = DemoData.forecastInput()
        input.extraWeeklyIncome = .dollars(10)
        let f = Forecast.compute(input)
        #expect(f.gap.cents == 257_899 + 19 * 1000)
    }

    @Test func suggestedSplitFollowsPriorityOrder() throws {
        let f = Forecast.compute(DemoData.forecastInput())
        let split = try #require(f.suggestedSplit)
        #expect(split.payday == "2026-11-25")
        #expect(split.followingPayday == "2026-12-02")
        #expect(split.expectedPay.cents == 60000)
        #expect(split.line(.bills)?.needed.cents == 40000 + 2500) // rent + gym before 2 Dec
        #expect(split.line(.living)?.needed.cents == 15000)
        #expect(split.line(.goal)?.needed.cents == 31579)
        #expect(split.lines.map(\.kind) == [.bills, .living, .sinkingFund, .goal])
        // Pay of $600 funds bills and living first, then sinking fund, then the goal gets what is left.
        #expect(split.line(.bills)?.funded.cents == 42500)
        #expect(split.line(.living)?.funded.cents == 15000)
        let needed = split.lines.map(\.needed.cents).reduce(0, +)
        #expect(split.buffer == .zero)
        #expect(split.fromBuffer.cents == needed - 60000)
        #expect(split.lines.map(\.funded.cents).reduce(0, +) == 60000)
    }

    @Test func splitWithSurplusGoesToBuffer() throws {
        var input = DemoData.forecastInput()
        input.paysReceived = []
        let split = try #require(Forecast.compute(input).suggestedSplit)
        #expect(split.expectedPay.cents == 110983)
        #expect(split.fromBuffer == .zero)
        #expect(split.buffer.cents == 110983 - split.lines.map(\.needed.cents).reduce(0, +))
    }
}

private enum IntMathCheck {
    static func half(_ n: Int, _ d: Int) -> Int { (2 * n + d) / (2 * d) }
}
