import Testing
import Foundation
import BeaverCore

// The golden cases (SPEC section 6) live in `fixtures/golden.json`, shared with the Kotlin port.
// These tests load that file and assert every case. Do not copy expected numbers into this file.
// To change a case, edit the fixture (and update fixtures/README.md if the schema changes).

// MARK: - Case runners

private func check(_ c: FShiftCase) {
    let f = fixture
    let r = ShiftPay.calculate(shift: c.shift.model, rateCard: f.rateCard(c.rateCard), holidays: f.holidays(c.holidaySet))
    #expect(r.durationMinutes == c.expected.durationMinutes, "\(c.id) duration")
    #expect(r.paidMinutes == c.expected.paidMinutes, "\(c.id) paid minutes")
    #expect(r.gross.cents == c.expected.grossCents, "\(c.id) gross")
    // Band order is not significant; compare by band name.
    let got = Dictionary(uniqueKeysWithValues: r.bands.map { ($0.band, [$0.minutes, $0.hoursHundredths]) })
    let want = Dictionary(uniqueKeysWithValues: c.expected.bands.map { ($0.band, [$0.minutes, $0.hoursHundredths]) })
    #expect(got == want, "\(c.id) bands (minutes, hours x100)")
}

private func check(_ c: FWeekCase) {
    let f = fixture
    let pay = PeriodPay.calculate(shifts: c.shifts.map(\.model), rateCard: f.rateCard(c.rateCard), holidays: f.holidays(c.holidaySet),
                                  taxMethod: .withholdingTable(id: c.withholdingTable), frequency: payFrequency(c.frequency),
                                  tables: [f.table(c.withholdingTable)])
    #expect(pay.paidMinutes == c.expected.paidMinutes, "\(c.id) paid minutes")
    #expect(pay.gross.cents == c.expected.grossCents, "\(c.id) gross")
    #expect(pay.tax.cents == c.expected.taxCents, "\(c.id) tax")
    #expect(pay.net.cents == c.expected.netCents, "\(c.id) net")
    let got = Dictionary(uniqueKeysWithValues: pay.bands.map { ($0.band, $0.hoursHundredths) })
    #expect(got == c.expected.bandHoursHundredths, "\(c.id) band hours x100")
}

private func shiftCases(_ ids: String...) -> [FShiftCase] { fixture.shiftCases.filter { ids.contains($0.id) } }
private func weekCases(_ ids: String...) -> [FWeekCase] { fixture.weekCases.filter { ids.contains($0.id) } }

// MARK: - Golden tests

@Suite("Golden tests (SPEC section 6, from fixtures/golden.json)")
struct GoldenTests {
    // 1
    @Test func mixedWeekShifts() {
        let list = shiftCases("1.1", "1.2", "1.3", "1.4", "1.5")
        #expect(list.count == 5)
        for c in list { check(c) }
    }

    @Test func mixedWeek() {
        let list = weekCases("1")
        #expect(list.count == 1)
        for c in list { check(c) }
    }

    // 2
    @Test func templateWeekNov16() {
        let list = weekCases("2")
        #expect(list.count == 1)
        for c in list { check(c) }
    }

    // 3
    @Test func withholding() {
        let list = fixture.taxCases.filter { $0.id == "3" }
        #expect(list.count == 1)
        for c in list {
            let table = fixture.table(c.withholdingTable)
            let freq = payFrequency(c.frequency)
            #expect(!c.samples.isEmpty)
            for s in c.samples {
                #expect(table.tax(onGross: Money(cents: s.grossCents), frequency: freq).cents == s.taxCents, "gross \(s.grossCents)")
            }
        }
    }

    // 4
    @Test func thursdayLateIntoFridayMorning() {
        let list = shiftCases("4")
        #expect(list.count == 1)
        for c in list { check(c) }
    }

    // 5
    @Test func christmasEveIntoChristmasDay() {
        let list = shiftCases("5")
        #expect(list.count == 1)
        for c in list { check(c) }
    }

    // 6
    @Test func sundayIntoMonday() {
        let list = shiftCases("6")
        #expect(list.count == 1)
        for c in list { check(c) }
    }

    // 7
    @Test func publicHolidayWeeksWithTemplate() {
        let list = weekCases("7.1", "7.2")
        #expect(list.count == 2)
        for c in list { check(c) }
    }

    // 8
    @Test func weeklySchedule() {
        let list = fixture.scheduleCases.filter { $0.id == "8" }
        #expect(list.count == 1)
        for c in list {
            let s = c.schedule.model
            for w in c.workDateToPayday ?? [] {
                #expect(s.payday(forWorkDate: w.workDate) == w.payday, "work date \(w.workDate)")
            }
            for p in c.paydayToWorkPeriod ?? [] {
                #expect(s.workPeriod(forPayday: p.payday) == DateRange(start: p.start, end: p.end), "payday \(p.payday)")
            }
        }
    }

    // 9
    @Test func monthlyFixed() {
        let list = fixture.scheduleCases.filter { $0.id == "9" }
        #expect(list.count == 1)
        for c in list {
            let s = c.schedule.model
            let p = c.paydaysInRange
            #expect(p != nil)
            #expect(s.paydays(in: p!.range.model) == p!.expected)
        }
    }

    // 10 to 12
    private func checkBills(_ id: String) {
        let list = fixture.billCases.filter { $0.id == id }
        #expect(list.count == 1)
        for c in list {
            let bill = c.bill.model()
            #expect(!c.queries.isEmpty)
            for q in c.queries {
                #expect(bill.dueDates(in: q.range.model) == q.expectedDueDates, "bill \(id) range \(q.range.start)...\(q.range.end)")
            }
        }
    }

    @Test func monthly31ClampsToFebruary() { checkBills("10") }
    @Test func every28Days() { checkBills("11") }
    @Test func quarterly() { checkBills("12") }

    // 13
    @Test func fortnightLimit() {
        let list = fixture.hoursLimitCases.filter { $0.id == "13" }
        #expect(list.count == 1)
        for c in list {
            let limit = FortnightLimit(hoursPerFortnight: c.limit.hoursPerFortnight, effectiveFrom: c.limit.effectiveFrom)
            #expect(c.scenarios.count >= 2)
            for sc in c.scenarios {
                let w = HoursLimits.fortnightWarning(limit: limit, date: sc.date, shifts: sc.shifts.map(\.model))
                #expect((w != nil) == sc.expected.warns, "\(sc.description)")
                if sc.expected.warns {
                    #expect(w?.workedMinutes == sc.expected.workedMinutes, "\(sc.description)")
                    #expect(w?.period == sc.expected.period?.model, "\(sc.description)")
                }
            }
        }
    }

    // 14
    @Test func forecastSmokeTest() {
        let list = fixture.forecastCases.filter { $0.id == "14" }
        #expect(list.count == 1)
        for c in list {
            let f = Forecast.compute(c.forecastInput(in: fixture))
            let x = c.expected
            let events = f.payEvents
            #expect(f.paydayCount == x.paydayCount)
            #expect(x.paydayNets.count == x.paydayCount)
            #expect(events.map(\.payday) == x.paydayNets.map(\.payday))
            #expect(events.map(\.net.cents) == x.paydayNets.map(\.netCents))
            #expect(events.first?.isActual == true)

            #expect(f.totalIncome.cents == x.totalIncomeCents)
            #expect(f.totalBills.cents == x.totalBillsCents)
            #expect(f.totalLiving.cents == x.totalLivingCents)
            #expect(f.projectedAtDeadline.cents == x.projectedAtDeadlineCents)
            #expect(f.gap.cents == x.gapCents)
            #expect(f.requiredPerPayday.cents == x.requiredPerPaydayCents)

            let bills = f.weeks.flatMap(\.bills)
            for t in x.billTotals {
                let m = bills.filter { $0.name == t.name }
                #expect(m.count == t.count, "\(t.name) count")
                #expect(m.map(\.amount.cents).reduce(0, +) == t.totalCents, "\(t.name) total")
            }
        }
    }
}

// MARK: - Fixture integrity

@Suite("Golden fixture integrity")
struct FixtureIntegrityTests {
    @Test func versionIsSupported() {
        #expect(fixture.fixtureVersion == supportedFixtureVersion)
    }

    @Test func coversEverySpecTest() {
        var ids = Set<String>()
        ids.formUnion(fixture.shiftCases.map(\.id))
        ids.formUnion(fixture.weekCases.map(\.id))
        ids.formUnion(fixture.taxCases.map(\.id))
        ids.formUnion(fixture.scheduleCases.map(\.id))
        ids.formUnion(fixture.billCases.map(\.id))
        ids.formUnion(fixture.hoursLimitCases.map(\.id))
        ids.formUnion(fixture.forecastCases.map(\.id))
        // "1.2" and "7.1" are sub-cases of tests 1 and 7.
        let tests = Set(ids.map { String($0.split(separator: ".")[0]) })
        #expect(tests == Set((1...14).map(String.init)))
    }

    @Test func caseIDsAreUniqueWithinSections() {
        func unique(_ ids: [String]) -> Bool { Set(ids).count == ids.count }
        #expect(unique(fixture.shiftCases.map(\.id)))
        #expect(unique(fixture.weekCases.map(\.id)))
        #expect(unique(fixture.taxCases.map(\.id)))
        #expect(unique(fixture.scheduleCases.map(\.id)))
        #expect(unique(fixture.billCases.map(\.id)))
        #expect(unique(fixture.hoursLimitCases.map(\.id)))
        #expect(unique(fixture.forecastCases.map(\.id)))
    }

    @Test func fixtureRateCardMatchesEnginePreset() {
        // The fixture's rate card is the source of truth; the built-in preset must agree with it.
        let fromFixture = fixture.rateCard("typicalCasual")
        let preset = RateCard.typicalCasual(id: fromFixture.id)
        #expect(fromFixture == preset)
        #expect(fromFixture.weekdayGaps().isEmpty)
    }

    @Test func fixtureWithholdingTableMatchesBuiltIn() {
        let fromFixture = fixture.table(WithholdingTable.auScale2WeeklyID)
        #expect(fromFixture.rows == WithholdingTable.auScale2Weekly.rows)
    }

    @Test func fixtureHolidaysMatchDemoData() {
        #expect(fixture.holidays("demo") == DemoData.holidayDates)
        let forecastDates = Set(fixture.forecastCases.flatMap { $0.input.holidays.map(\.date) })
        #expect(forecastDates == DemoData.holidayDates)
    }

    @Test func forecastFixtureInputMatchesDemoData() {
        for c in fixture.forecastCases where c.id == "14" {
            let fromFixture = Forecast.compute(c.forecastInput(in: fixture))
            let fromDemo = Forecast.compute(DemoData.forecastInput())
            #expect(fromFixture.totalIncome == fromDemo.totalIncome)
            #expect(fromFixture.gap == fromDemo.gap)
        }
    }

    @Test func decimalStringsParseWithoutFloatingPoint() {
        #expect(tenThousandths("0.1600") == 1600)
        #expect(tenThousandths("57.8462") == 578_462)
        #expect(tenThousandths("650.6154") == 6_506_154)
        #expect(tenThousandths("0.16") == 1600)
        #expect(tenThousandths("3") == 30_000)
    }
}
