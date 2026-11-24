package au.com.fuzn.beaver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The golden cases (SPEC section 6) live in `fixtures/golden.json`, shared with the Swift engine.
// These tests load that file and assert every case. Do not copy expected numbers into this file.

class GoldenTests {
    private fun check(c: FShiftCase) {
        val r = ShiftPay.calculate(c.shift.model, fixture.rateCard(c.rateCard), fixture.holidays(c.holidaySet))
        assertEquals(c.expected.durationMinutes, r.durationMinutes, "${c.id} duration")
        assertEquals(c.expected.paidMinutes, r.paidMinutes, "${c.id} paid minutes")
        assertEquals(c.expected.grossCents, r.gross.cents, "${c.id} gross")
        // Band order is not significant; compare by band name (minutes, hours x 100).
        val got = r.bands.associate { it.band to listOf(it.minutes, it.hoursHundredths) }
        val want = c.expected.bands.associate { it.band to listOf(it.minutes, it.hoursHundredths) }
        assertEquals(want, got, "${c.id} bands (minutes, hours x100)")
        assertEquals(c.expected.bands.size, r.bands.size, "${c.id} band count")
    }

    private fun check(c: FWeekCase) {
        val pay = PeriodPay.calculate(
            c.shifts.map { it.model }, fixture.rateCard(c.rateCard), fixture.holidays(c.holidaySet),
            TaxMethod.Table(c.withholdingTable), payFrequency(c.frequency), listOf(fixture.table(c.withholdingTable)),
        )
        assertEquals(c.expected.paidMinutes, pay.paidMinutes, "${c.id} paid minutes")
        assertEquals(c.expected.grossCents, pay.gross.cents, "${c.id} gross")
        assertEquals(c.expected.taxCents, pay.tax.cents, "${c.id} tax")
        assertEquals(c.expected.netCents, pay.net.cents, "${c.id} net")
        assertEquals(c.expected.bandHoursHundredths, pay.bands.associate { it.band to it.hoursHundredths }, "${c.id} band hours x100")
    }

    private fun shiftCases(vararg ids: String) = fixture.shiftCases.filter { it.id in ids }
    private fun weekCases(vararg ids: String) = fixture.weekCases.filter { it.id in ids }

    // 1
    @Test fun mixedWeekShifts() {
        val list = shiftCases("1.1", "1.2", "1.3", "1.4", "1.5")
        assertEquals(5, list.size)
        list.forEach(::check)
    }

    @Test fun mixedWeek() {
        val list = weekCases("1")
        assertEquals(1, list.size)
        list.forEach(::check)
    }

    // 2
    @Test fun templateWeekNov16() {
        val list = weekCases("2")
        assertEquals(1, list.size)
        list.forEach(::check)
    }

    // 3
    @Test fun withholding() {
        val list = fixture.taxCases.filter { it.id == "3" }
        assertEquals(1, list.size)
        for (c in list) {
            val table = fixture.table(c.withholdingTable)
            val freq = payFrequency(c.frequency)
            assertTrue(c.samples.isNotEmpty())
            for (s in c.samples) {
                assertEquals(s.taxCents, table.tax(Money(s.grossCents), freq).cents, "gross ${s.grossCents}")
            }
        }
    }

    // 4, 5, 6
    @Test fun thursdayLateIntoFridayMorning() {
        val list = shiftCases("4")
        assertEquals(1, list.size)
        list.forEach(::check)
    }

    @Test fun christmasEveIntoChristmasDay() {
        val list = shiftCases("5")
        assertEquals(1, list.size)
        list.forEach(::check)
    }

    @Test fun sundayIntoMonday() {
        val list = shiftCases("6")
        assertEquals(1, list.size)
        list.forEach(::check)
    }

    // 7
    @Test fun publicHolidayWeeksWithTemplate() {
        val list = weekCases("7.1", "7.2")
        assertEquals(2, list.size)
        list.forEach(::check)
    }

    // 8
    @Test fun weeklySchedule() {
        val list = fixture.scheduleCases.filter { it.id == "8" }
        assertEquals(1, list.size)
        for (c in list) {
            val s = c.schedule.model
            val workDays = c.workDateToPayday.orEmpty()
            val periods = c.paydayToWorkPeriod.orEmpty()
            assertTrue(workDays.isNotEmpty() && periods.isNotEmpty())
            for (w in workDays) assertEquals(parseDate(w.payday), s.paydayForWorkDate(parseDate(w.workDate)), "work date ${w.workDate}")
            for (p in periods) {
                assertEquals(DateRange(parseDate(p.start), parseDate(p.end)), s.workPeriod(parseDate(p.payday)), "payday ${p.payday}")
            }
        }
    }

    // 9
    @Test fun monthlyFixed() {
        val list = fixture.scheduleCases.filter { it.id == "9" }
        assertEquals(1, list.size)
        for (c in list) {
            val s = c.schedule.model
            val p = assertNotNull(c.paydaysInRange)
            assertEquals(p.expected.map(::parseDate), s.paydays(p.range.model))
        }
    }

    // 10 to 12
    private fun checkBills(id: String) {
        val list = fixture.billCases.filter { it.id == id }
        assertEquals(1, list.size)
        for (c in list) {
            val bill = c.bill.model()
            assertTrue(c.queries.isNotEmpty())
            for (q in c.queries) {
                assertEquals(q.expectedDueDates.map(::parseDate), bill.dueDates(q.range.model), "bill $id range ${q.range.start}..${q.range.end}")
            }
        }
    }

    @Test fun monthly31ClampsToFebruary() = checkBills("10")
    @Test fun every28Days() = checkBills("11")
    @Test fun quarterly() = checkBills("12")

    // 13
    @Test fun fortnightLimit() {
        val list = fixture.hoursLimitCases.filter { it.id == "13" }
        assertEquals(1, list.size)
        for (c in list) {
            val limit = FortnightLimit(hoursPerFortnight = c.limit.hoursPerFortnight, effectiveFrom = parseDate(c.limit.effectiveFrom))
            assertTrue(c.scenarios.size >= 2)
            for (sc in c.scenarios) {
                val w = HoursLimits.fortnightWarning(limit, parseDate(sc.date), sc.shifts.map { it.model })
                assertEquals(sc.expected.warns, w != null, sc.description)
                if (sc.expected.warns) {
                    assertEquals(sc.expected.workedMinutes, w?.workedMinutes, sc.description)
                    assertEquals(sc.expected.period?.model, w?.period, sc.description)
                } else {
                    assertNull(w, sc.description)
                }
            }
        }
    }

    // 14
    @Test fun forecastSmokeTest() {
        val list = fixture.forecastCases.filter { it.id == "14" }
        assertEquals(1, list.size)
        for (c in list) {
            val f = Forecast.compute(c.forecastInput(fixture))
            val x = c.expected
            val events = f.payEvents
            assertEquals(x.paydayCount, f.paydayCount)
            assertEquals(19, x.paydayCount)
            assertEquals(x.paydayCount, x.paydayNets.size)
            assertEquals(x.paydayNets.map { parseDate(it.payday) }, events.map { it.payday })
            assertEquals(x.paydayNets.map { it.netCents }, events.map { it.net.cents })
            assertTrue(events.first().isActual)

            assertEquals(x.totalIncomeCents, f.totalIncome.cents)
            assertEquals(x.totalBillsCents, f.totalBills.cents)
            assertEquals(x.totalLivingCents, f.totalLiving.cents)
            assertEquals(x.projectedAtDeadlineCents, f.projectedAtDeadline.cents)
            assertEquals(x.gapCents, f.gap.cents)
            assertEquals(x.requiredPerPaydayCents, f.requiredPerPayday.cents)
            assertEquals(31579L, f.requiredPerPayday.cents)

            val bills = f.weeks.flatMap { it.bills }
            for (t in x.billTotals) {
                val m = bills.filter { it.name == t.name }
                assertEquals(t.count, m.size, "${t.name} count")
                assertEquals(t.totalCents, m.sumOf { it.amount.cents }, "${t.name} total")
            }
            assertEquals(x.billTotals.sumOf { it.totalCents }, x.totalBillsCents)
        }
    }
}

class FixtureIntegrityTests {
    @Test fun versionIsSupported() = assertEquals(SUPPORTED_FIXTURE_VERSION, fixture.fixtureVersion)

    @Test fun coversEverySpecTest() {
        val ids = buildSet {
            addAll(fixture.shiftCases.map { it.id }); addAll(fixture.weekCases.map { it.id })
            addAll(fixture.taxCases.map { it.id }); addAll(fixture.scheduleCases.map { it.id })
            addAll(fixture.billCases.map { it.id }); addAll(fixture.hoursLimitCases.map { it.id })
            addAll(fixture.forecastCases.map { it.id })
        }
        // "1.2" and "7.1" are sub-cases of tests 1 and 7.
        assertEquals((1..14).map { it.toString() }.toSet(), ids.map { it.substringBefore('.') }.toSet())
    }

    @Test fun caseIDsAreUniqueWithinSections() {
        fun unique(ids: List<String>) = assertEquals(ids.size, ids.toSet().size)
        unique(fixture.shiftCases.map { it.id }); unique(fixture.weekCases.map { it.id })
        unique(fixture.taxCases.map { it.id }); unique(fixture.scheduleCases.map { it.id })
        unique(fixture.billCases.map { it.id }); unique(fixture.hoursLimitCases.map { it.id })
        unique(fixture.forecastCases.map { it.id })
    }

    @Test fun fixtureCaseCounts() {
        // Guards against a loader that silently drops cases.
        assertEquals(8, fixture.shiftCases.size)
        assertEquals(4, fixture.weekCases.size)
        assertEquals(1, fixture.taxCases.size)
        assertEquals(2, fixture.scheduleCases.size)
        assertEquals(3, fixture.billCases.size)
        assertEquals(1, fixture.hoursLimitCases.size)
        assertEquals(1, fixture.forecastCases.size)
    }

    @Test fun fixtureRateCardMatchesEnginePreset() {
        val fromFixture = fixture.rateCard("typicalCasual")
        assertEquals(RateCard.typicalCasual(fromFixture.id), fromFixture)
        assertTrue(fromFixture.weekdayGaps().isEmpty())
    }

    @Test fun fixtureWithholdingTableMatchesBuiltIn() {
        assertEquals(WithholdingTable.auScale2Weekly.rows, fixture.table(WithholdingTable.AU_SCALE2_WEEKLY_ID).rows)
    }

    @Test fun fixtureHolidaysMatchDemoData() {
        assertEquals(DemoData.holidayDates, fixture.holidays("demo"))
        val forecastDates = fixture.forecastCases.flatMap { c -> c.input.holidays.map { parseDate(it.date) } }.toSet()
        assertEquals(DemoData.holidayDates, forecastDates)
    }

    /** Parity: the demo data equals the fixture's forecast input (the built-in table is covered by the engine). */
    @Test fun forecastFixtureInputEqualsDemoData() {
        val c = fixture.forecastCases.single { it.id == "14" }
        assertEquals(DemoData.forecastInput(), c.forecastInput(fixture, includeCustomTable = false))
        val withTable = c.forecastInput(fixture)
        assertEquals(Forecast.compute(withTable), Forecast.compute(DemoData.forecastInput()))
    }

    @Test fun decimalStringsParseWithoutFloatingPoint() {
        assertEquals(1600, tenThousandths("0.1600"))
        assertEquals(578_462, tenThousandths("57.8462"))
        assertEquals(6_506_154, tenThousandths("650.6154"))
        assertEquals(1600, tenThousandths("0.16"))
        assertEquals(30_000, tenThousandths("3"))
    }
}
