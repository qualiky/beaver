import Foundation
import BeaverCore

// Decoder for `fixtures/golden.json` (see fixtures/README.md). The file is the single source of truth for the
// golden cases; this file only maps its data to BeaverCore types. Nothing here is a copy of the fixture.

// MARK: - Loading

enum FixtureError: Error, CustomStringConvertible {
    case notFound(String)
    case invalid(String)
    var description: String {
        switch self {
        case .notFound(let s): return "fixtures/golden.json not found (searched upward from \(s))"
        case .invalid(let s): return "invalid fixture: \(s)"
        }
    }
}

/// Walks up from this source file until a directory holds `fixtures/golden.json` (the repo root).
private func locateFixture(from path: String = #filePath) throws -> URL {
    var dir = URL(fileURLWithPath: path).deletingLastPathComponent()
    for _ in 0..<12 {
        let candidate = dir.appendingPathComponent("fixtures/golden.json")
        if FileManager.default.fileExists(atPath: candidate.path) { return candidate }
        let parent = dir.deletingLastPathComponent()
        if parent == dir { break }
        dir = parent
    }
    throw FixtureError.notFound(path)
}

/// The supported fixture schema version. Bump it together with fixtures/README.md and the Kotlin tests.
let supportedFixtureVersion = 1

/// Decoded once for the whole test run.
let fixture: Fixture = {
    do {
        let data = try Data(contentsOf: try locateFixture())
        return try JSONDecoder().decode(Fixture.self, from: data)
    } catch {
        fatalError("Cannot load golden fixture: \(error)")
    }
}()

// MARK: - Schema

struct Fixture: Decodable, Sendable {
    var fixtureVersion: Int
    var rateCards: [FRateCard]
    var holidaySets: [FHolidaySet]
    var withholdingTables: [FWithholdingTable]
    var shiftCases: [FShiftCase]
    var weekCases: [FWeekCase]
    var taxCases: [FTaxCase]
    var scheduleCases: [FScheduleCase]
    var billCases: [FBillCase]
    var hoursLimitCases: [FHoursLimitCase]
    var forecastCases: [FForecastCase]

    func rateCard(_ id: String) -> RateCard {
        guard let c = rateCards.first(where: { $0.id == id }) else { fatalError("unknown rateCard \(id)") }
        return c.model
    }
    func holidays(_ id: String) -> Set<LocalDate> {
        guard let s = holidaySets.first(where: { $0.id == id }) else { fatalError("unknown holidaySet \(id)") }
        return Set(s.holidays.map(\.date))
    }
    func table(_ id: String) -> WithholdingTable {
        guard let t = withholdingTables.first(where: { $0.id == id }) else { fatalError("unknown withholdingTable \(id)") }
        return t.model
    }
}

struct FRateCard: Decodable, Sendable {
    var id: String
    var name: String
    var rules: [FRule]
    /// Rate card ids are strings in the fixture; the engine uses an Int id, which tests set to 1.
    var model: RateCard { RateCard(id: 1, name: name, rules: rules.map(\.model)) }
}

struct FRule: Decodable, Sendable {
    struct Window: Decodable, Sendable { var from: String; var to: String }
    var band: String
    var centsPerHour: Int
    var days: String
    var window: Window?

    var model: RateRule {
        let selector: DaySelector
        switch days {
        case "publicHoliday": selector = .publicHoliday
        case "saturday": selector = .saturday
        case "sunday": selector = .sunday
        case "weekday": selector = .weekdays
        default: fatalError("unknown day selector \(days)")
        }
        return RateRule(band: band, centsPerHour: centsPerHour, days: selector,
                        window: window.map { ClockWindow(startMinute: clockMinutes($0.from), endMinute: clockMinutes($0.to)) })
    }
}

/// "HH:mm" to minutes since midnight; "24:00" is allowed (end of day).
func clockMinutes(_ s: String) -> Int {
    let p = s.split(separator: ":").compactMap { Int($0) }
    guard p.count == 2 else { fatalError("bad clock time \(s)") }
    return p[0] * 60 + p[1]
}

struct FHoliday: Decodable, Sendable {
    var date: LocalDate
    var name: String
}

struct FHolidaySet: Decodable, Sendable {
    var id: String
    var holidays: [FHoliday]
}

struct FWithholdingRow: Decodable, Sendable {
    var belowDollars: Int?
    var a: String
    var b: String
}

struct FWithholdingTable: Decodable, Sendable {
    var id: String
    var name: String
    var rows: [FWithholdingRow]

    var model: WithholdingTable {
        WithholdingTable(id: id, name: name, rows: rows.map {
            WithholdingRow(belowDollars: $0.belowDollars, aTenThousandths: tenThousandths($0.a), bTenThousandths: tenThousandths($0.b))
        })
    }
}

/// Parses a non-negative decimal string with at most 4 fraction digits into ten-thousandths, without floating point.
func tenThousandths(_ s: String) -> Int {
    let parts = s.split(separator: ".", omittingEmptySubsequences: false)
    guard parts.count <= 2, let whole = Int(parts[0]) else { fatalError("bad decimal \(s)") }
    var frac = parts.count == 2 ? String(parts[1]) : ""
    guard frac.count <= 4, frac.allSatisfy(\.isNumber) else { fatalError("bad decimal \(s)") }
    frac += String(repeating: "0", count: 4 - frac.count)
    return whole * 10_000 + Int(frac)!
}

struct FShift: Decodable, Sendable {
    var date: LocalDate
    var start: LocalTime
    var end: LocalTime
    var breakMinutes: Int
    var model: Shift { Shift(sourceID: 1, date: date, start: start, end: end, breakMinutes: breakMinutes) }
}

struct FBandMinutes: Decodable, Sendable {
    var band: String
    var minutes: Int
    var hoursHundredths: Int
}

struct FShiftCase: Decodable, Sendable {
    struct Expected: Decodable, Sendable {
        var durationMinutes: Int
        var paidMinutes: Int
        var bands: [FBandMinutes]
        var grossCents: Int
    }
    var id: String
    var description: String
    var rateCard: String
    var holidaySet: String
    var shift: FShift
    var expected: Expected
}

struct FWeekCase: Decodable, Sendable {
    struct Expected: Decodable, Sendable {
        var paidMinutes: Int
        var grossCents: Int
        var taxCents: Int
        var netCents: Int
        var bandHoursHundredths: [String: Int]
    }
    var id: String
    var description: String
    var rateCard: String
    var holidaySet: String
    var withholdingTable: String
    var frequency: String
    var shifts: [FShift]
    var expected: Expected
}

struct FTaxCase: Decodable, Sendable {
    struct Sample: Decodable, Sendable { var grossCents: Int; var taxCents: Int }
    var id: String
    var description: String
    var withholdingTable: String
    var frequency: String
    var samples: [Sample]
}

func payFrequency(_ s: String) -> PayFrequency {
    guard let f = PayFrequency(rawValue: s) else { fatalError("unknown frequency \(s)") }
    return f
}

func weekday(_ s: String) -> Weekday {
    guard let w = Weekday.allCases.first(where: { "\($0)" == s }) else { fatalError("unknown weekday \(s)") }
    return w
}

struct FRange: Decodable, Sendable {
    var start: LocalDate
    var end: LocalDate
    var model: DateRange { DateRange(start: start, end: end) }
}

struct FSchedule: Decodable, Sendable {
    var type: String
    var payWeekday: String?
    var lagWeeks: Int?
    var anchorPayday: LocalDate?
    var dayOfMonth: Int?

    var model: PaySchedule {
        switch type {
        case "weekly": return .weekly(payWeekday: weekday(payWeekday!), lagWeeks: lagWeeks!)
        case "fortnightly": return .fortnightly(anchorPayday: anchorPayday!, lagWeeks: lagWeeks!)
        case "monthly": return .monthly(dayOfMonth: dayOfMonth!)
        default: fatalError("unknown schedule type \(type)")
        }
    }
}

struct FScheduleCase: Decodable, Sendable {
    struct WorkDay: Decodable, Sendable { var workDate: LocalDate; var payday: LocalDate }
    struct Period: Decodable, Sendable { var payday: LocalDate; var start: LocalDate; var end: LocalDate }
    struct Paydays: Decodable, Sendable { var range: FRange; var expected: [LocalDate] }
    var id: String
    var description: String
    var schedule: FSchedule
    var workDateToPayday: [WorkDay]?
    var paydayToWorkPeriod: [Period]?
    var paydaysInRange: Paydays?
}

struct FCadence: Decodable, Sendable {
    var type: String
    var n: Int?
    var dayOfMonth: Int?
    var model: Cadence {
        switch type {
        case "weekly": return .weekly
        case "fortnightly": return .fortnightly
        case "everyNDays": return .everyNDays(n!)
        case "monthly": return .monthly(dayOfMonth: dayOfMonth!)
        case "quarterly": return .quarterly
        case "yearly": return .yearly
        case "oneOff": return .oneOff
        default: fatalError("unknown cadence \(type)")
        }
    }
}

struct FBill: Decodable, Sendable {
    var name: String
    var amountCents: Int
    var category: String
    var cadence: FCadence
    var anchorDate: LocalDate
    func model(id: Int = 0) -> Bill {
        guard let cat = BillCategory(rawValue: category) else { fatalError("unknown category \(category)") }
        return Bill(id: id, name: name, amount: Money(cents: amountCents), cadence: cadence.model, anchorDate: anchorDate, category: cat)
    }
}

struct FBillCase: Decodable, Sendable {
    struct Query: Decodable, Sendable { var range: FRange; var expectedDueDates: [LocalDate] }
    var id: String
    var description: String
    var bill: FBill
    var queries: [Query]
}

struct FHoursLimitCase: Decodable, Sendable {
    struct Limit: Decodable, Sendable { var hoursPerFortnight: Int; var effectiveFrom: LocalDate }
    struct Expected: Decodable, Sendable { var warns: Bool; var workedMinutes: Int?; var period: FRange? }
    struct Scenario: Decodable, Sendable {
        var description: String
        var date: LocalDate
        var shifts: [FShift]
        var expected: Expected
    }
    var id: String
    var description: String
    var limit: Limit
    var scenarios: [Scenario]
}

struct FForecastCase: Decodable, Sendable {
    struct Source: Decodable, Sendable {
        var name: String
        var kind: String
        var rateCard: String
        var withholdingTable: String
        var schedule: FSchedule
    }
    struct TShift: Decodable, Sendable {
        var weekday: String
        var start: LocalTime
        var end: LocalTime
        var breakMinutes: Int
    }
    struct Tmpl: Decodable, Sendable { var name: String; var shifts: [TShift] }
    struct Unavailable: Decodable, Sendable { var from: LocalDate; var to: LocalDate; var note: String }
    struct GoalIn: Decodable, Sendable { var name: String; var targetCents: Int; var deadline: LocalDate; var depositsCents: Int }
    struct Pay: Decodable, Sendable { var payDate: LocalDate; var netCents: Int }
    struct Input: Decodable, Sendable {
        var today: LocalDate
        var source: Source
        var template: Tmpl
        var bills: [FBill]
        var livingBudgetCents: Int
        var holidays: [FHoliday]
        var unavailable: [Unavailable]
        var goal: GoalIn
        var paysReceived: [Pay]
    }
    struct Payday: Decodable, Sendable { var payday: LocalDate; var netCents: Int }
    struct BillTotal: Decodable, Sendable { var name: String; var count: Int; var totalCents: Int }
    struct Expected: Decodable, Sendable {
        var paydayCount: Int
        var paydayNets: [Payday]
        var totalIncomeCents: Int
        var totalBillsCents: Int
        var totalLivingCents: Int
        var projectedAtDeadlineCents: Int
        var gapCents: Int
        var requiredPerPaydayCents: Int
        var billTotals: [BillTotal]
    }
    var id: String
    var description: String
    var input: Input
    var expected: Expected

    /// Maps the fixture's demo input to the engine's `ForecastInput`.
    func forecastInput(in fixture: Fixture) -> ForecastInput {
        let sourceID = 1
        let cardID = 1
        let i = input
        precondition(i.source.kind == "hourly", "forecast fixture supports hourly sources only")
        var card = fixture.rateCard(i.source.rateCard)
        card.id = cardID
        let source = IncomeSource(id: sourceID, name: i.source.name, kind: .hourly, rateCardID: cardID,
                                  schedule: i.source.schedule.model,
                                  taxMethod: .withholdingTable(id: i.source.withholdingTable))
        let template = BeaverCore.Template(id: 1, sourceID: sourceID, name: i.template.name, shifts: i.template.shifts.map {
            TemplateShift(weekday: weekday($0.weekday), start: $0.start, end: $0.end, breakMinutes: $0.breakMinutes)
        })
        let goal = BeaverCore.Goal(id: 1, name: i.goal.name, target: Money(cents: i.goal.targetCents), deadline: i.goal.deadline)
        return ForecastInput(
            today: i.today, sources: [source], rateCards: [card],
            withholdingTables: [fixture.table(i.source.withholdingTable)],
            templates: [template],
            paysReceived: i.paysReceived.map { PayReceived(id: 1, sourceID: sourceID, payDate: $0.payDate, net: Money(cents: $0.netCents)) },
            holidays: i.holidays.map { Holiday(date: $0.date, name: $0.name) },
            unavailable: i.unavailable.enumerated().map { UnavailableRange(id: $0.offset + 1, from: $0.element.from, to: $0.element.to, note: $0.element.note) },
            bills: i.bills.enumerated().map { $0.element.model(id: $0.offset + 1) },
            livingBudget: Money(cents: i.livingBudgetCents), goals: [goal],
            deposits: i.goal.depositsCents > 0 ? [Deposit(goalID: 1, date: i.today, amount: Money(cents: i.goal.depositsCents))] : [],
            primaryGoalID: 1)
    }
}
