import Testing
import Foundation
import BeaverCore

private let card = DemoData.rateCard
private let holidays = DemoData.holidayDates
private let table = WithholdingTable.auScale2WeeklyID

private func shift(_ date: LocalDate, _ start: LocalTime, _ end: LocalTime, brk: Int = 30) -> Shift {
    Shift(sourceID: DemoData.sourceID, date: date, start: start, end: end, breakMinutes: brk)
}

private func gross(_ s: Shift) -> Int {
    ShiftPay.calculate(shift: s, rateCard: card, holidays: holidays).gross.cents
}

private func weekPay(_ shifts: [Shift]) -> PeriodPay {
    PeriodPay.calculate(shifts: shifts, rateCard: card, holidays: holidays,
                        taxMethod: .withholdingTable(id: table), frequency: .weekly)
}

private func templateWeek(_ monday: LocalDate) -> PeriodPay {
    weekPay(TemplateExpander.shifts(sourceID: DemoData.sourceID, templates: [DemoData.template], range: monday.week))
}

private func hundredths(_ pay: PeriodPay, _ band: String) -> Int? {
    pay.bands.first { $0.band == band }?.hoursHundredths
}

@Suite("Golden tests (SPEC section 6)")
struct GoldenTests {
    // 1
    @Test func mixedWeek() {
        let shifts = [
            shift("2026-11-23", "08:30", "16:30"),
            shift("2026-11-25", "08:30", "15:30"),
            shift("2026-11-27", "17:00", "01:00"),
            shift("2026-11-28", "19:00", "03:30"),
            shift("2026-11-29", "12:00", "19:45"),
        ]
        #expect(shifts.map(gross) == [22500, 19500, 24609, 32471, 32625])
        let pay = weekPay(shifts)
        #expect(pay.hoursHundredths == 3675)
        #expect(pay.gross.cents == 131705)
        #expect(pay.tax.cents == 24500)
        #expect(pay.net.cents == 107205)
        #expect(hundredths(pay, "Weekday day") == 1588)
        #expect(hundredths(pay, "Weekday evening") == 469)
        #expect(hundredths(pay, "Saturday") == 564)
        #expect(hundredths(pay, "Sunday") == 1054)
        #expect(hundredths(pay, "Weekday night") == nil)
    }

    // 2
    @Test func templateWeekNov16() {
        let pay = templateWeek("2026-11-16")
        #expect(pay.hoursHundredths == 3650)
        #expect(pay.gross.cents == 137283)
        #expect(pay.tax.cents == 26300)
        #expect(pay.net.cents == 110983)
    }

    // 3
    @Test func withholding() {
        let t = WithholdingTable.auScale2Weekly
        #expect(t.weeklyTax(onGross: Money(cents: 30000)).cents == 0)
        #expect(t.weeklyTax(onGross: Money(cents: 73512)).cents == 7100)
        #expect(t.weeklyTax(onGross: Money(cents: 80000)).cents == 8300)
        #expect(t.weeklyTax(onGross: Money(cents: 100000)).cents == 14300)
        #expect(t.weeklyTax(onGross: Money(cents: 152000)).cents == 31000)
    }

    // 4
    @Test func thursdayLateIntoFridayMorning() {
        let r = ShiftPay.calculate(shift: shift("2026-11-26", "22:00", "02:00", brk: 0), rateCard: card, holidays: holidays)
        #expect(r.bands.map(\.band) == ["Weekday evening", "Weekday night"])
        #expect(r.bands.map(\.minutes) == [120, 120])
        #expect(r.gross.cents == 3300 * 2 + 3450 * 2)
    }

    // 5
    @Test func christmasEveIntoChristmasDay() {
        let r = ShiftPay.calculate(shift: shift("2026-12-24", "20:00", "02:00", brk: 0), rateCard: card, holidays: holidays)
        #expect(r.bands.map(\.band) == ["Public holiday", "Weekday evening"])
        #expect(r.bands.first { $0.band == "Weekday evening" }?.minutes == 240)
        #expect(r.bands.first { $0.band == "Public holiday" }?.minutes == 120)
        #expect(r.gross.cents == 3300 * 4 + 6000 * 2)
    }

    // 6
    @Test func sundayIntoMonday() {
        let r = ShiftPay.calculate(shift: shift("2026-11-29", "23:00", "01:00", brk: 0), rateCard: card, holidays: holidays)
        #expect(r.bands.map(\.band) == ["Weekday night", "Sunday"])
        #expect(r.bands.map(\.minutes) == [60, 60])
        #expect(r.gross.cents == 4500 + 3450)
    }

    // 7
    @Test func publicHolidayWeeksWithTemplate() {
        let a = templateWeek("2026-12-21")
        #expect(a.gross.cents == 177887)
        #expect(a.tax.cents == 39300)
        #expect(a.net.cents == 138587)
        let b = templateWeek("2026-12-28")
        #expect(b.gross.cents == 152997)
        #expect(b.tax.cents == 31300)
        #expect(b.net.cents == 121697)
    }

    // 8
    @Test func weeklySchedule() {
        let s = PaySchedule.weekly(payWeekday: .wednesday, lagWeeks: 1)
        #expect(s.payday(forWorkDate: "2026-11-29") == "2026-12-02")
        #expect(s.payday(forWorkDate: "2026-11-30") == "2026-12-09")
        #expect(s.workPeriod(forPayday: "2026-12-02") == DateRange(start: "2026-11-23", end: "2026-11-29"))
    }

    // 9
    @Test func monthlyFixed() {
        let s = PaySchedule.monthly(dayOfMonth: 31)
        let days = s.paydays(in: DateRange(start: "2026-11-01", end: "2027-03-31"))
        #expect(days == ["2026-11-30", "2026-12-31", "2027-01-31", "2027-02-28", "2027-03-31"])
    }

    // 10
    @Test func monthly31ClampsToFebruary() {
        let bill = Bill(name: "Pay", amount: .dollars(10), cadence: .monthly(dayOfMonth: 31), anchorDate: "2027-01-31")
        #expect(bill.dueDates(in: DateRange(start: "2027-02-01", end: "2027-02-28")) == ["2027-02-28"])
        #expect(bill.dueDates(in: DateRange(start: "2027-03-01", end: "2027-03-31")) == ["2027-03-31"])
    }

    // 11
    @Test func every28Days() {
        let phone = DemoData.bills[1]
        #expect(phone.dueDates(in: DateRange(start: "2026-11-23", end: "2027-03-31"))
                == ["2026-12-20", "2027-01-17", "2027-02-14", "2027-03-14"])
    }

    // 12
    @Test func quarterly() {
        let e = DemoData.bills[4]
        #expect(e.dueDates(in: DateRange(start: "2027-01-16", end: "2027-12-31")) == ["2027-04-15", "2027-07-15", "2027-10-15"])
        #expect(e.dueDates(in: DateRange(start: "2027-04-01", end: "2027-07-31")) == ["2027-04-15", "2027-07-15"])
    }

    // 13
    @Test func fortnightLimit() {
        let limit = FortnightLimit(hoursPerFortnight: 48, effectiveFrom: "2027-03-01")
        // 5 x 10h paid = 50h. Break 0.
        func block(from start: LocalDate) -> [Shift] {
            (0..<5).map { shift(start.adding(days: $0), "09:00", "19:00", brk: 0) }
        }
        let inside = HoursLimits.fortnightWarning(limit: limit, date: "2027-03-02", shifts: block(from: "2027-03-01"))
        #expect(inside != nil)
        #expect(inside?.workedMinutes == 3000)
        #expect(inside?.period == DateRange(start: "2027-03-01", end: "2027-03-14"))
        let before = HoursLimits.fortnightWarning(limit: limit, date: "2027-02-16", shifts: block(from: "2027-02-15"))
        #expect(before == nil)
    }

    // 14
    @Test func forecastSmokeTest() {
        let f = Forecast.compute(DemoData.forecastInput())
        let events = f.payEvents
        #expect(f.paydayCount == 19)
        #expect(events.count == 19)
        #expect(events.first?.payday == "2026-11-25")
        #expect(events.last?.payday == "2027-03-31")

        let special: [LocalDate: Int] = ["2026-11-25": 60000, "2026-12-09": 32766, "2026-12-30": 138587, "2027-01-06": 121697]
        for e in events {
            #expect(e.net.cents == special[e.payday] ?? 110983, "payday \(e.payday)")
        }
        #expect(events.filter { $0.net.cents == 110983 }.count == 15)
        #expect(events.first?.isActual == true)

        #expect(f.totalIncome.cents == 2_017_795)
        #expect(f.totalBills.cents == 874_896)
        #expect(f.totalLiving.cents == 285_000)
        #expect(f.projectedAtDeadline.cents == 857_899)
        #expect(f.gap.cents == 257_899)

        let bills = f.weeks.flatMap(\.bills)
        func sum(_ name: String) -> (Int, Int) {
            let m = bills.filter { $0.name == name }
            return (m.count, m.map(\.amount.cents).reduce(0, +))
        }
        #expect(sum("Rent") == (19, 19 * 40000))
        #expect(sum("Phone") == (4, 4 * 3000))
        #expect(sum("Streaming") == (4, 4 * 1599))
        #expect(sum("Gym") == (9, 9 * 2500))
        #expect(sum("Electricity") == (1, 24000))
        #expect(sum("Car service") == (1, 50000))
    }
}
