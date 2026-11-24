package au.com.fuzn.beaver.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val card = DemoData.rateCard
private val holidays = DemoData.holidayDates

private fun d(s: String): LocalDate = parseDate(s)
private fun t(s: String): LocalTime = parseTime(s)
private fun range(a: String, b: String) = DateRange(d(a), d(b))

private fun shift(date: String, start: String, end: String, brk: Int = 0, status: ShiftStatus = ShiftStatus.rostered,
                  source: Int = DemoData.SOURCE_ID) =
    Shift(sourceID = source, date = d(date), start = t(start), end = t(end), breakMinutes = brk, status = status)

class DateTests {
    @Test fun weekdaysAndArithmetic() {
        assertEquals(DayOfWeek.MONDAY, d("2026-11-23").dayOfWeek)
        assertEquals(DayOfWeek.SUNDAY, d("2026-11-29").dayOfWeek)
        assertEquals(DayOfWeek.THURSDAY, d("1970-01-01").dayOfWeek)
        assertEquals(d("2026-11-23"), d("2026-11-25").mondayOfWeek)
        assertEquals(d("2026-11-23"), d("2026-11-29").mondayOfWeek)
        assertEquals(d("2026-11-29"), d("2026-11-25").sundayOfWeek)
        assertEquals(d("2026-11-22"), d("2026-11-23").previousSunday)
        assertEquals(d("2026-11-22"), d("2026-11-23").plusDays(-1))
        assertEquals(d("2028-02-29"), d("2028-02-28").plusDays(1))
        assertEquals(d("2027-02-28"), d("2027-01-31").plusMonths(1))
        assertEquals(128, d("2026-11-23").daysUntil(d("2027-03-31")))
    }

    @Test fun strictParsing() {
        assertNull(parseIsoDate("2026-02-30"))
        assertNull(parseIsoDate("2026-2-3"))
        assertNull(parseIsoDate("26-02-03"))
        assertNull(parseIsoDate("2026-02-3x"))
        assertNull(parseIsoTime("24:00"))
        assertNull(parseIsoTime("7:05"))
        assertEquals(LocalTime.of(7, 5), parseIsoTime("07:05"))
        assertEquals(LocalTime.of(0, 0), localTimeOfMinutes(1440))
        assertEquals(LocalTime.of(23, 59), localTimeOfMinutes(-1))
    }

    @Test fun dateRangeBasics() {
        val r = range("2026-11-23", "2026-11-25")
        assertEquals(3, r.dayCount)
        assertEquals(listOf(d("2026-11-23"), d("2026-11-24"), d("2026-11-25")), r.dates)
        assertTrue(d("2026-11-25") in r)
        assertTrue(range("2026-11-25", "2026-11-23").isEmpty)
        assertEquals(0, range("2026-11-25", "2026-11-23").dayCount)
        assertEquals(range("2026-11-23", "2026-11-29"), d("2026-11-26").week)
    }

    /** Australian DST starts Sun 2026-10-04 at 02:00. Wall-clock minutes must not move. */
    @Test fun shiftAcrossDSTChangePaysWallClockMinutes() {
        val r = ShiftPay.calculate(shift("2026-10-03", "22:00", "06:00"), card, holidays)
        assertEquals(480, r.durationMinutes)
        assertEquals(480, r.paidMinutes)
        // 2h Saturday at 37.50 + 6h Sunday at 45.00
        assertEquals(2L * 3750 + 6 * 4500, r.gross.cents)
    }
}

class ShiftEdgeTests {
    @Test fun zeroBreakPaysFullDuration() {
        val r = ShiftPay.calculate(shift("2026-11-23", "09:00", "17:00", brk = 0), card, holidays)
        assertEquals(480, r.paidMinutes)
        assertEquals(24000L, r.gross.cents)
    }

    @Test fun breakEqualToDurationPaysNothing() {
        val r = ShiftPay.calculate(shift("2026-11-23", "09:00", "17:00", brk = 480), card, holidays)
        assertEquals(0, r.paidMinutes)
        assertEquals(0L, r.gross.cents)
        // Oversized breaks clamp.
        val over = ShiftPay.calculate(shift("2026-11-23", "09:00", "17:00", brk = 999), card, holidays)
        assertEquals(0L, over.gross.cents)
        assertEquals(480, over.breakMinutes)
    }

    @Test fun emptyWeek() {
        val pay = PeriodPay.calculate(emptyList(), card, holidays, TaxMethod.Table(WithholdingTable.AU_SCALE2_WEEKLY_ID))
        assertEquals(PeriodPay(), pay)
        assertEquals(0, pay.hoursHundredths)
    }

    @Test fun noRateCardIsEmptyPay() {
        assertEquals(PeriodPay(), PeriodPay.calculate(listOf(shift("2026-11-23", "09:00", "17:00")), null, holidays))
    }

    @Test fun cancelledShiftsEarnNothing() {
        val pay = PeriodPay.calculate(listOf(shift("2026-11-23", "09:00", "17:00", status = ShiftStatus.cancelled)), card, holidays)
        assertEquals(0L, pay.gross.cents)
        assertTrue(pay.bands.isEmpty())
    }

    @Test fun equalStartAndEndIsTwentyFourHours() {
        val s = shift("2026-11-23", "08:00", "08:00")
        assertEquals(1440, s.durationMinutes)
        assertTrue(s.crossesMidnight)
        assertEquals(d("2026-11-24"), s.endDate)
    }

    @Test fun rateCardGapsAreReported() {
        assertTrue(card.weekdayGaps().isEmpty())
        val broken = card.copy(rules = card.rules.filter { it.band != "Weekday night" })
        assertEquals(listOf(ClockWindow(0, 420)), broken.weekdayGaps())
        val r = ShiftPay.calculate(shift("2026-11-23", "05:00", "07:00"), broken, holidays)
        assertEquals(listOf(ShiftPay.UNRATED_BAND), r.bands.map { it.band })
        assertEquals(0L, r.gross.cents)
    }

    @Test fun trailingGapIsReported() {
        val onlyDay = RateCard(name = "x", rules = listOf(RateRule("Day", 3000, DaySelector.weekdays, ClockWindow.hours(7, 19))))
        assertEquals(listOf(ClockWindow(0, 420), ClockWindow(1140, 1440)), onlyDay.weekdayGaps())
    }

    @Test fun holidayOverridesOtherBandsAndPeriodTaxIsOnTotal() {
        // Fri 2026-12-25 is a holiday: the whole day uses the holiday band.
        val r = ShiftPay.calculate(shift("2026-12-25", "10:00", "12:00"), card, holidays)
        assertEquals(listOf("Public holiday"), r.bands.map { it.band })
        assertEquals(12000L, r.gross.cents)
    }

    @Test fun roundsShiftGrossHalfUp() {
        // 1 minute at $30/hr with no break is exactly 50 cents; 1 minute with a 0 break at 3300 is 55.
        val one = ShiftPay.calculate(shift("2026-11-23", "09:00", "09:01"), card, holidays)
        assertEquals(50L, one.gross.cents)
        // 7 paid minutes of 8 total (1 minute break) at 3000/h: 3000 * 8 * 7 / (8 * 60) = 350 exactly.
        val odd = ShiftPay.calculate(shift("2026-11-23", "09:00", "09:08", brk = 1), card, holidays)
        assertEquals(350L, odd.gross.cents)
        // 3000 * 1 / 60 * ... tie case: 1 paid minute of 3 at 3000: 3000*3*1/(3*60) = 50 exactly; use 4500 rate.
        val tie = ShiftPay.calculate(shift("2026-11-29", "10:00", "10:03", brk = 2), card, holidays)
        assertEquals(IntMath.roundHalfUp(4500L * 3 * 1, 3L * 60), tie.gross.cents)
        assertEquals(75L, tie.gross.cents)
    }
}

class TaxTests {
    @Test fun flatPercentRoundsHalfUp() {
        assertEquals(1235L, TaxMethod.FlatPercent(1000).tax(Money(12345), PayFrequency.weekly).cents)
        assertEquals(0L, TaxMethod.None.tax(Money(12345), PayFrequency.weekly).cents)
    }

    @Test fun fortnightlyHalvesAndDoubles() {
        val table = WithholdingTable.auScale2Weekly
        assertEquals(2 * table.weeklyTax(Money(100000)).cents, table.tax(Money(200000), PayFrequency.fortnightly).cents)
    }

    @Test fun monthlyConvertsToWeeklyAndBack() {
        val table = WithholdingTable.auScale2Weekly
        // 4333.33 a month is about 1000 a week: weekly tax 143 -> 143 * 52 / 12 = 619.67 -> $620.
        val gross = Money(433_333)
        val weekly = Money(IntMath.roundHalfUp(gross.cents * 12, 52L))
        assertEquals(IntMath.roundHalfUp(table.weeklyTax(weekly).cents * 52, 12L), table.tax(gross, PayFrequency.monthly).cents)
    }

    @Test fun missingTableYieldsZeroAndCustomTablesWin() {
        assertEquals(Money.ZERO, TaxMethod.Table("nope").tax(Money(100000), PayFrequency.weekly))
        val custom = WithholdingTable("au-scale2-weekly", "override", listOf(WithholdingRow(null, 0, 0)))
        assertEquals(Money.ZERO, TaxMethod.Table("au-scale2-weekly").tax(Money(100000), PayFrequency.weekly, listOf(custom)))
        assertEquals(14300L, TaxMethod.Table("au-scale2-weekly").tax(Money(100000), PayFrequency.weekly).cents)
    }

    @Test fun negativeGrossIsZeroTax() {
        assertEquals(Money.ZERO, WithholdingTable.auScale2Weekly.weeklyTax(Money(-5000)))
        assertEquals(Money.ZERO, TaxMethod.FlatPercent(1000).tax(Money(-5000), PayFrequency.weekly))
    }
}

class ScheduleTests {
    private val fortnightly = PaySchedule.Fortnightly(d("2026-11-25"), 1)

    @Test fun fortnightlyPaydaysBothDirections() {
        assertEquals(
            listOf("2026-11-11", "2026-11-25", "2026-12-09", "2026-12-23").map(::d),
            fortnightly.paydays(range("2026-11-01", "2026-12-31")),
        )
        assertTrue(fortnightly.paydays(range("2026-11-26", "2026-12-08")).isEmpty())
    }

    @Test fun fortnightlyWorkPeriodAndLookup() {
        assertEquals(range("2026-11-23", "2026-12-06"), fortnightly.workPeriod(d("2026-12-09")))
        assertEquals(d("2026-12-09"), fortnightly.paydayForWorkDate(d("2026-12-06")))
        assertEquals(d("2026-12-09"), fortnightly.paydayForWorkDate(d("2026-11-23")))
        assertEquals(d("2026-11-25"), fortnightly.paydayForWorkDate(d("2026-11-22")))
        assertEquals(d("2026-11-11"), fortnightly.paydayForWorkDate(d("2026-11-08")))
    }

    @Test fun weeklyLagTwoPaysTheWeekBefore() {
        val s = PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 2)
        assertEquals(range("2026-11-23", "2026-11-29"), s.workPeriod(d("2026-12-09")))
        assertEquals(d("2026-12-09"), s.paydayForWorkDate(d("2026-11-29")))
    }

    @Test fun weeklyPaydaysAndRangeBoundaries() {
        val s = PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1)
        assertEquals(listOf("2026-11-25", "2026-12-02").map(::d), s.paydays(range("2026-11-25", "2026-12-02")))
        assertEquals(listOf(d("2026-12-02")), s.paydays(range("2026-11-26", "2026-12-02")))
        assertTrue(s.paydays(range("2026-12-02", "2026-11-25")).isEmpty())
        assertEquals(PayFrequency.weekly, s.frequency)
    }

    @Test fun monthlyHasNoWorkPeriod() {
        val s = PaySchedule.Monthly(15)
        assertNull(s.workPeriod(d("2026-12-15")))
        assertNull(s.paydayForWorkDate(d("2026-12-15")))
        assertEquals(PayFrequency.monthly, s.frequency)
    }

    @Test fun monthlyClampsToMonthEndInLeapYear() {
        assertEquals(listOf(d("2028-02-29")), PaySchedule.Monthly(31).paydays(range("2028-02-01", "2028-02-29")))
    }
}

class BillTests {
    @Test fun oneOffAppearsOnceAndNeverBeforeAnchor() {
        val b = Bill(name = "Service", amount = Money.dollars(500), cadence = Cadence.OneOff, anchorDate = d("2026-12-10"))
        assertEquals(listOf(d("2026-12-10")), b.dueDates(range("2026-01-01", "2030-01-01")))
        assertTrue(b.dueDates(range("2026-12-11", "2030-01-01")).isEmpty())
        assertTrue(b.dueDates(range("2026-01-01", "2026-12-09")).isEmpty())
    }

    @Test fun yearlyClampsLeapDay() {
        val b = Bill(name = "Rego", amount = Money.dollars(800), cadence = Cadence.Yearly, anchorDate = d("2028-02-29"))
        assertEquals(
            listOf("2028-02-29", "2029-02-28", "2030-02-28", "2031-02-28").map(::d),
            b.dueDates(range("2028-01-01", "2031-12-31")),
        )
    }

    @Test fun monthlyAfterOffDayAnchorUsesDayOfMonth() {
        val b = Bill(name = "X", amount = Money.dollars(1), cadence = Cadence.Monthly(5), anchorDate = d("2026-11-20"))
        assertEquals(listOf("2026-11-20", "2026-12-05", "2027-01-05").map(::d), b.dueDates(range("2026-11-01", "2027-01-31")))
    }

    @Test fun monthlyDayClampingFollowsTheCalendar() {
        val b = Bill(name = "X", amount = Money.dollars(1), cadence = Cadence.Monthly(31), anchorDate = d("2027-01-31"))
        assertEquals(
            listOf("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30").map(::d),
            b.dueDates(range("2027-01-01", "2027-04-30")),
        )
        val leap = b.copy(anchorDate = d("2028-01-31"))
        assertEquals(listOf(d("2028-02-29")), leap.dueDates(range("2028-02-01", "2028-02-29")))
    }

    @Test fun nothingBeforeAnchorForRecurringBills() {
        val b = Bill(name = "Rent", amount = Money.dollars(400), cadence = Cadence.Weekly, anchorDate = d("2026-11-25"))
        assertTrue(b.dueDates(range("2026-11-01", "2026-11-24")).isEmpty())
        assertEquals(listOf(d("2026-11-25")), b.dueDates(range("2026-11-01", "2026-11-25")))
        assertEquals(listOf(d("2026-12-09")), b.dueDates(range("2026-12-03", "2026-12-09")))
    }

    @Test fun fortnightlyAndEveryNDaysStepFromAnchor() {
        val f = Bill(name = "Gym", amount = Money.dollars(25), cadence = Cadence.Fortnightly, anchorDate = d("2026-11-27"))
        assertEquals(listOf("2026-12-11", "2026-12-25").map(::d), f.dueDates(range("2026-12-01", "2026-12-31")))
        val n = Bill(name = "Phone", amount = Money.dollars(30), cadence = Cadence.EveryNDays(28), anchorDate = d("2026-11-22"))
        assertEquals(listOf("2026-12-20", "2027-01-17").map(::d), n.dueDates(range("2026-12-01", "2027-01-31")))
        // A non-positive step is treated as 1 day, never an infinite loop.
        val z = n.copy(cadence = Cadence.EveryNDays(0))
        assertEquals(3, z.dueDates(range("2026-11-22", "2026-11-24")).size)
    }

    @Test fun quarterlyKeepsAnchorDayAndClamps() {
        val b = Bill(name = "Q", amount = Money.dollars(1), cadence = Cadence.Quarterly, anchorDate = d("2027-01-31"))
        assertEquals(listOf("2027-04-30", "2027-07-31").map(::d), b.dueDates(range("2027-02-01", "2027-07-31")))
    }

    @Test fun paidAndOverdueStatus() {
        val bill = Bill(id = 7, name = "Rent", amount = Money.dollars(400), cadence = Cadence.Weekly, anchorDate = d("2026-11-02"))
        val pay = BillPayment(7, d("2026-11-09"), d("2026-11-10"), Money.dollars(410))
        val occ = bill.occurrences(range("2026-11-02", "2026-11-16"), listOf(pay))
        val today = d("2026-11-12")
        assertEquals(listOf(BillStatus.overdue, BillStatus.paid, BillStatus.due), occ.map { it.status(today) })
        assertEquals(Money.dollars(410), occ[1].amount)
        assertEquals(Money.dollars(400), occ[0].amount)
        assertTrue(bill.occurrences(range("2026-11-12", "2026-11-12")).isEmpty())
        assertEquals(BillStatus.due, occ[2].status(d("2026-11-16")))
    }
}

class MiscTests {
    @Test fun weeklyCaps() {
        val source = IncomeSource(id = 1, name = "S", kind = IncomeKind.hourly, schedule = PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1),
            weeklyCapSoftHours = 20, weeklyCapHardHours = 30)
        fun week(hours: Int) = listOf(
            shift("2026-11-23", "00:00", "00:00", brk = 1440 - 20 * 60),
            shift("2026-11-24", "00:00", "00:00", brk = 1440 - (hours - 20) * 60),
        )
        assertNull(HoursLimits.weeklyWarning(source, d("2026-11-23"), week(20)))
        assertEquals(HoursWarningLevel.soft, HoursLimits.weeklyWarning(source, d("2026-11-24"), week(21))?.level)
        val hard = assertNotNull(HoursLimits.weeklyWarning(source, d("2026-11-23"), week(31)))
        assertEquals(HoursWarningLevel.hard, hard.level)
        assertEquals(30, hard.limitHours)
        assertEquals(HoursWarning.Scope.Weekly(1), hard.scope)
        assertNull(HoursLimits.weeklyWarning(source, d("2026-11-30"), week(31)))
    }

    @Test fun fortnightLimitIgnoresCancelledAndRespectsAnchor() {
        val limit = FortnightLimit(hoursPerFortnight = 48, effectiveFrom = d("2027-03-03")) // anchored on Mon 2027-03-01
        assertEquals(d("2027-03-01"), limit.anchorMonday)
        assertNull(HoursLimits.fortnight(d("2027-02-28"), limit))
        assertEquals(range("2027-03-01", "2027-03-14"), HoursLimits.fortnight(d("2027-03-14"), limit))
        assertEquals(range("2027-03-15", "2027-03-28"), HoursLimits.fortnight(d("2027-03-15"), limit))
        val cancelled = (1..5).map { shift("2027-03-0$it", "09:00", "19:00", status = ShiftStatus.cancelled) }
        assertNull(HoursLimits.fortnightWarning(limit, d("2027-03-02"), cancelled))
        val worked = (1..5).map { shift("2027-03-0$it", "09:00", "19:00") }
        val w = assertNotNull(HoursLimits.fortnightWarning(limit, d("2027-03-02"), worked))
        assertEquals(HoursWarningLevel.hard, w.level)
        assertEquals(HoursWarning.Scope.Fortnight, w.scope)
        assertEquals(3000, w.workedMinutes)
    }

    @Test fun spendingWeekTotalReplacesEntries() {
        val monday = d("2026-11-16")
        val entries = listOf(Expense(date = d("2026-11-17"), amount = Money.dollars(20)), Expense(date = d("2026-11-22"), amount = Money.dollars(30)))
        assertEquals(Money.dollars(50), Spending.spent(monday, entries, emptyList()))
        assertEquals(Money.dollars(90), Spending.spent(monday, entries, listOf(WeekSpendTotal(monday, Money.dollars(90)))))
        assertEquals(Money.dollars(60), Spending.variance(Money.dollars(150), Money.dollars(90)))
        assertEquals(Money.dollars(-20), Spending.variance(Money.dollars(150), Money.dollars(170)))
        assertTrue(Spending.hasData(d("2026-11-18"), entries, emptyList()))
        assertTrue(!Spending.hasData(d("2026-11-25"), entries, emptyList()))
    }

    @Test fun goalProgress() {
        val g = Goal(id = 2, name = "G", target = Money.dollars(1000))
        val dep = listOf(Deposit(goalID = 2, date = d("2026-11-01"), amount = Money.dollars(250)),
            Deposit(goalID = 3, date = d("2026-11-01"), amount = Money.dollars(999)))
        assertEquals(Money.dollars(250), g.saved(dep))
        assertEquals(2500, g.progressBasisPoints(dep))
        assertEquals(10_000, Goal(name = "free", target = Money.ZERO).progressBasisPoints(dep))
        assertEquals(10_000, Goal(id = 3, name = "over", target = Money.dollars(100)).progressBasisPoints(dep))
    }

    @Test fun moneyHelpers() {
        assertEquals(Money(3050), Money.dollars(30, 50))
        assertEquals(Money(-3050), Money.dollars(-30, 50))
        assertEquals(Money(300), Money(100) * 3)
        assertEquals(Money(-100), -Money(100))
        assertTrue(Money(1) < Money(2))
        assertEquals(Money(6), listOf(Money(1), Money(2), Money(3)).total())
        assertEquals(3, IntMath.roundHalfUp(5, 2)) // 2.5 rounds up
        assertEquals(-2, IntMath.roundHalfUp(-5, 2)) // -2.5 rounds toward +infinity
        assertEquals(2, IntMath.roundHalfUp(7, 4)) // 1.75
    }

    @Test fun periodBandHoursSumExactlyBeforeRounding() {
        // Three 20-minute weekday-day shifts (no break): 3 x 1/3 h = 1.00 h exactly, not 3 x 0.33.
        val shifts = listOf(shift("2026-11-23", "09:00", "09:20"), shift("2026-11-24", "09:00", "09:20"), shift("2026-11-25", "09:00", "09:20"))
        val pay = PeriodPay.calculate(shifts, card, holidays)
        assertEquals(100, pay.bands.single().hoursHundredths)
        assertEquals(100, pay.hoursHundredths)
    }
}

class ForecastTests {
    @Test fun latestEffectiveTemplateWins() {
        val base = Template(id = 1, sourceID = 1, name = "Base", shifts = listOf(TemplateShift(DayOfWeek.MONDAY, t("09:00"), t("10:00"))))
        val sem = Template(id = 2, sourceID = 1, name = "Semester", effectiveFrom = d("2027-02-22"),
            shifts = listOf(TemplateShift(DayOfWeek.TUESDAY, t("09:00"), t("10:00"))))
        val before = TemplateExpander.shifts(1, listOf(base, sem), range("2027-02-15", "2027-02-21"))
        assertEquals(listOf(d("2027-02-15")), before.map { it.date })
        val after = TemplateExpander.shifts(1, listOf(sem, base), range("2027-02-22", "2027-02-28"))
        assertEquals(listOf(d("2027-02-23")), after.map { it.date })
    }

    @Test fun templateTieGoesToTheLaterEntry() {
        val a = Template(id = 1, sourceID = 1, name = "A", shifts = emptyList())
        val b = Template(id = 2, sourceID = 1, name = "B", shifts = emptyList())
        assertEquals("B", TemplateExpander.template(1, d("2026-11-23"), listOf(a, b))?.name)
        assertNull(TemplateExpander.template(2, d("2026-11-23"), listOf(a, b)))
    }

    @Test fun unavailableDatesSkipTemplateShifts() {
        val s = TemplateExpander.shifts(DemoData.SOURCE_ID, listOf(DemoData.template), range("2026-11-30", "2026-12-06"), DemoData.unavailable)
        assertEquals(listOf(d("2026-12-06")), s.map { it.date })
    }

    @Test fun fixedMonthlyIncomeForecast() {
        val salary = IncomeSource(id = 9, name = "Salary", kind = IncomeKind.fixed, fixedAmount = Money.dollars(4800), amountIsNet = true,
            schedule = PaySchedule.Monthly(31))
        val f = Forecast.compute(ForecastInput(today = d("2026-11-23"), sources = listOf(salary), livingBudget = Money.dollars(150)))
        assertEquals(26, f.weeks.size)
        assertNull(f.goal)
        val paydays = f.payEvents.map { it.payday }
        assertEquals(listOf("2026-11-30", "2026-12-31", "2027-01-31").map(::d), paydays.take(3))
        assertEquals(480000L * paydays.size, f.totalIncome.cents)
        assertEquals(f.projectedAtDeadline, f.gap)
    }

    @Test fun fixedGrossTaxedByMethod() {
        val s = IncomeSource(id = 9, name = "Job", kind = IncomeKind.fixed, fixedAmount = Money.dollars(1000), amountIsNet = false,
            schedule = PaySchedule.Weekly(DayOfWeek.FRIDAY, 1), taxMethod = TaxMethod.FlatPercent(2000))
        val f = Forecast.compute(ForecastInput(today = d("2026-11-23"), sources = listOf(s)))
        assertEquals(Money.dollars(800), f.payEvents.first().net)
    }

    @Test fun emptyInputIsAllZeroWithNoSplit() {
        val f = Forecast.compute(ForecastInput(today = d("2026-11-23")))
        assertEquals(26, f.weeks.size)
        assertEquals(Money.ZERO, f.totalSurplus)
        assertTrue(f.dangerWeeks.isEmpty())
        assertNull(f.suggestedSplit)
        assertEquals(Money.ZERO, f.requiredPerPayday)
    }

    @Test fun demoRequiredPerPaydayDangerAndVariance() {
        val f = Forecast.compute(DemoData.forecastInput())
        assertEquals(31579L, f.requiredPerPayday.cents) // 600000 / 19
        assertEquals(Money.ZERO, f.depositsTotal)
        assertEquals(60000L - 110983, f.payVariance.cents)
        assertEquals(Money.ZERO, f.spendingVariance)
        assertEquals(19, f.weeks.size)
        assertTrue(f.weeks.last().isDeadlineWeek)
        assertTrue(f.weeks.first().isCurrent)
        assertTrue(f.dangerWeeks.all { it.surplus.cents < 0 })
        assertTrue(f.dangerWeeks.any { it.weekStart == d("2026-12-07") }) // short pay, $500 car service + rent
    }

    @Test fun pastSpendingVarianceAndDeposits() {
        val input = DemoData.forecastInput().copy(
            expenses = listOf(Expense(date = d("2026-11-17"), amount = Money.dollars(100)), Expense(date = d("2026-11-24"), amount = Money.dollars(999))),
            deposits = listOf(Deposit(goalID = DemoData.GOAL_ID, date = d("2026-11-10"), amount = Money.dollars(1000))),
        )
        val f = Forecast.compute(input)
        assertEquals(Money.dollars(50), f.spendingVariance) // only the past week counts
        assertEquals(Money.dollars(1000), f.depositsTotal)
        assertEquals(257_899L + 100_000, f.gap.cents)
        assertEquals(IntMath.roundHalfUp(500_000L, 19L), f.requiredPerPayday.cents)
    }

    @Test fun extraWeeklyIncomeWhatIf() {
        val f = Forecast.compute(DemoData.forecastInput().copy(extraWeeklyIncome = Money.dollars(10)))
        assertEquals(257_899L + 19 * 1000, f.gap.cents)
    }

    @Test fun suggestedSplitFollowsPriorityOrder() {
        val f = Forecast.compute(DemoData.forecastInput())
        val split = assertNotNull(f.suggestedSplit)
        assertEquals(d("2026-11-25"), split.payday)
        assertEquals(d("2026-12-02"), split.followingPayday)
        assertEquals(60000L, split.expectedPay.cents)
        assertEquals(40000L + 2500, split.line(SplitKind.bills)?.needed?.cents) // rent + gym before 2 Dec
        assertEquals(15000L, split.line(SplitKind.living)?.needed?.cents)
        assertEquals(31579L, split.line(SplitKind.goal)?.needed?.cents)
        assertEquals(listOf(SplitKind.bills, SplitKind.living, SplitKind.sinkingFund, SplitKind.goal), split.lines.map { it.kind })
        assertEquals(42500L, split.line(SplitKind.bills)?.funded?.cents)
        assertEquals(15000L, split.line(SplitKind.living)?.funded?.cents)
        val needed = split.lines.sumOf { it.needed.cents }
        assertEquals(Money.ZERO, split.buffer)
        assertEquals(needed - 60000, split.fromBuffer.cents)
        assertEquals(60000L, split.lines.sumOf { it.funded.cents })
    }

    @Test fun splitWithSurplusGoesToBuffer() {
        val split = assertNotNull(Forecast.compute(DemoData.forecastInput().copy(paysReceived = emptyList())).suggestedSplit)
        assertEquals(110983L, split.expectedPay.cents)
        assertEquals(Money.ZERO, split.fromBuffer)
        assertEquals(110983L - split.lines.sumOf { it.needed.cents }, split.buffer.cents)
    }
}

class SerializationTests {
    @Test fun forecastInputRoundTripsThroughJson() {
        val input = DemoData.forecastInput()
        val text = BeaverJson.encodeToString(ForecastInput.serializer(), input)
        assertEquals(input, BeaverJson.decodeFromString(ForecastInput.serializer(), text))
    }

    @Test fun datesTimesAndMoneyUseSwiftWireFormats() {
        val shift = Shift(id = 3, sourceID = 1, date = d("2026-03-09"), start = t("07:05"), end = t("00:00"), breakMinutes = 30)
        assertEquals(
            """{"id":3,"sourceID":1,"date":"2026-03-09","start":"07:05","end":"00:00","breakMinutes":30,"status":"rostered","note":""}""",
            BeaverJson.encodeToString(Shift.serializer(), shift),
        )
        assertEquals("1234", BeaverJson.encodeToString(Money.serializer(), Money(1234)))
        assertFailsWith<kotlinx.serialization.SerializationException> {
            BeaverJson.decodeFromString(Shift.serializer(), """{"sourceID":1,"date":"2026-02-30","start":"07:05","end":"08:00"}""")
        }
    }

    @Test fun enumsWithPayloadsUseSwiftSynthesizedShape() {
        fun <T> enc(s: kotlinx.serialization.KSerializer<T>, v: T) = BeaverJson.encodeToString(s, v)
        assertEquals("""{"weekly":{"payWeekday":3,"lagWeeks":1}}""", enc(PaySchedule.serializer(), PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1)))
        assertEquals("""{"fortnightly":{"anchorPayday":"2026-11-25","lagWeeks":1}}""",
            enc(PaySchedule.serializer(), PaySchedule.Fortnightly(d("2026-11-25"), 1)))
        assertEquals("""{"monthly":{"dayOfMonth":31}}""", enc(PaySchedule.serializer(), PaySchedule.Monthly(31)))
        assertEquals("""{"none":{}}""", enc(TaxMethod.serializer(), TaxMethod.None))
        assertEquals("""{"flatPercent":{"basisPoints":1000}}""", enc(TaxMethod.serializer(), TaxMethod.FlatPercent(1000)))
        assertEquals("""{"withholdingTable":{"id":"au-scale2-weekly"}}""", enc(TaxMethod.serializer(), TaxMethod.Table("au-scale2-weekly")))
        assertEquals("""{"weekly":{}}""", enc(Cadence.serializer(), Cadence.Weekly))
        assertEquals("""{"everyNDays":{"_0":28}}""", enc(Cadence.serializer(), Cadence.EveryNDays(28)))
        assertEquals("""{"monthly":{"dayOfMonth":5}}""", enc(Cadence.serializer(), Cadence.Monthly(5)))
        for (c in listOf(Cadence.Weekly, Cadence.Fortnightly, Cadence.EveryNDays(3), Cadence.Monthly(9), Cadence.Quarterly, Cadence.Yearly, Cadence.OneOff)) {
            assertEquals(c, BeaverJson.decodeFromString(Cadence.serializer(), enc(Cadence.serializer(), c)))
        }
    }

    @Test fun nilOptionalsAreOmittedAndMissingOnesDecode() {
        val goal = Goal(id = 1, name = "G", target = Money(5000))
        assertEquals("""{"id":1,"name":"G","target":5000}""", BeaverJson.encodeToString(Goal.serializer(), goal))
        assertEquals(goal, BeaverJson.decodeFromString(Goal.serializer(), """{"id":1,"name":"G","target":5000}"""))
    }

    @Test fun builtInTableRoundTrips() {
        val table = WithholdingTable.auScale2Weekly
        assertEquals(table, BeaverJson.decodeFromString(WithholdingTable.serializer(), BeaverJson.encodeToString(WithholdingTable.serializer(), table)))
    }
}
