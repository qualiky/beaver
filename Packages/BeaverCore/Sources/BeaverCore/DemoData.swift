import Foundation

/// Fictional sample data: a casual worker named "Sam". Used by previews, tests and the "Load demo data" button only.
/// A real install never seeds this, and it contains no real person's data.
public enum DemoData {
    public static let sourceID = 1
    public static let rateCardID = 1
    public static let goalID = 1
    public static let personaName = "Sam"
    /// The date the golden forecast tests use.
    public static let referenceToday: LocalDate = "2026-11-23"

    public static var rateCard: RateCard { .typicalCasual(id: rateCardID) }

    public static var source: IncomeSource {
        IncomeSource(id: sourceID, name: "Example Bar", kind: .hourly, rateCardID: rateCardID,
                     schedule: .weekly(payWeekday: .wednesday, lagWeeks: 1),
                     taxMethod: .withholdingTable(id: WithholdingTable.auScale2WeeklyID))
    }

    public static var template: Template {
        Template(id: 1, sourceID: sourceID, name: "Default week", shifts: [
            TemplateShift(weekday: .wednesday, start: "18:00", end: "23:00", breakMinutes: 30),
            TemplateShift(weekday: .thursday, start: "18:00", end: "23:00", breakMinutes: 30),
            TemplateShift(weekday: .friday, start: "18:00", end: "04:30", breakMinutes: 30),
            TemplateShift(weekday: .saturday, start: "18:00", end: "04:30", breakMinutes: 30),
            TemplateShift(weekday: .sunday, start: "17:00", end: "01:00", breakMinutes: 30),
        ])
    }

    public static var bills: [Bill] {
        [
            Bill(id: 1, name: "Rent", amount: .dollars(400), cadence: .weekly, anchorDate: "2026-11-25", category: .housing),
            Bill(id: 2, name: "Phone", amount: .dollars(30), cadence: .everyNDays(28), anchorDate: "2026-11-22", category: .utilities),
            Bill(id: 3, name: "Streaming", amount: .dollars(15, cents: 99), cadence: .monthly(dayOfMonth: 5), anchorDate: "2026-12-05", category: .subscription),
            Bill(id: 4, name: "Gym", amount: .dollars(25), cadence: .fortnightly, anchorDate: "2026-11-27", category: .subscription),
            Bill(id: 5, name: "Electricity", amount: .dollars(240), cadence: .quarterly, anchorDate: "2027-01-15", category: .utilities),
            Bill(id: 6, name: "Car service", amount: .dollars(500), cadence: .oneOff, anchorDate: "2026-12-10", category: .transport),
        ]
    }

    public static let livingBudget = Money.dollars(150)

    public static var holidays: [Holiday] {
        [("2026-12-25", "Christmas Day"), ("2026-12-26", "Boxing Day"), ("2026-12-28", "Boxing Day (observed)"),
         ("2027-01-01", "New Year's Day"), ("2027-01-26", "Australia Day")].map { Holiday(date: LocalDate(stringLiteral: $0.0), name: $0.1) }
    }

    public static var holidayDates: Set<LocalDate> { Set(holidays.map(\.date)) }

    public static var unavailable: [UnavailableRange] {
        [UnavailableRange(id: 1, from: "2026-12-02", to: "2026-12-05", note: "Trip")]
    }

    public static var goal: Goal {
        Goal(id: goalID, name: "Course fees", target: .dollars(6000), deadline: "2027-03-31")
    }

    public static var paysReceived: [PayReceived] {
        [PayReceived(id: 1, sourceID: sourceID, payDate: "2026-11-25", net: .dollars(600))]
    }

    /// The full demo state as a forecast input.
    public static func forecastInput(today: LocalDate = referenceToday) -> ForecastInput {
        ForecastInput(today: today, sources: [source], rateCards: [rateCard], templates: [template],
                      paysReceived: paysReceived, holidays: holidays, unavailable: unavailable, bills: bills,
                      livingBudget: livingBudget, goals: [goal], primaryGoalID: goalID)
    }
}
