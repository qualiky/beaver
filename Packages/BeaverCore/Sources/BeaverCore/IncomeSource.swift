import Foundation

/// Whether pay comes from shifts or a fixed amount.
public enum IncomeKind: String, Sendable, Codable, Hashable, CaseIterable {
    case hourly, fixed
}

/// A source of income: shift work priced by a rate card, or a fixed amount per pay period.
public struct IncomeSource: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var kind: IncomeKind
    /// Rate card for `hourly` sources.
    public var rateCardID: Int?
    /// Amount per pay period for `fixed` sources.
    public var fixedAmount: Money?
    /// For fixed sources: the amount is already net (no tax applied).
    public var amountIsNet: Bool
    public var schedule: PaySchedule
    public var taxMethod: TaxMethod
    /// Optional weekly hours caps (whole hours): soft then hard warning.
    public var weeklyCapSoftHours: Int?
    public var weeklyCapHardHours: Int?
    public var isActive: Bool

    public init(id: Int, name: String, kind: IncomeKind, rateCardID: Int? = nil, fixedAmount: Money? = nil,
                amountIsNet: Bool = false, schedule: PaySchedule, taxMethod: TaxMethod = .none,
                weeklyCapSoftHours: Int? = nil, weeklyCapHardHours: Int? = nil, isActive: Bool = true) {
        self.id = id
        self.name = name
        self.kind = kind
        self.rateCardID = rateCardID
        self.fixedAmount = fixedAmount
        self.amountIsNet = amountIsNet
        self.schedule = schedule
        self.taxMethod = taxMethod
        self.weeklyCapSoftHours = weeklyCapSoftHours
        self.weeklyCapHardHours = weeklyCapHardHours
        self.isActive = isActive
    }
}

/// One shift in a default week.
public struct TemplateShift: Sendable, Codable, Hashable {
    public var weekday: Weekday
    public var start: LocalTime
    public var end: LocalTime
    public var breakMinutes: Int

    public init(weekday: Weekday, start: LocalTime, end: LocalTime, breakMinutes: Int = 0) {
        self.weekday = weekday
        self.start = start
        self.end = end
        self.breakMinutes = breakMinutes
    }
}

/// A default week of shifts for a source. The latest template whose `effectiveFrom` is on or before a week's Monday applies.
public struct Template: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var sourceID: Int
    public var name: String
    public var effectiveFrom: LocalDate?
    public var shifts: [TemplateShift]

    public init(id: Int = 0, sourceID: Int, name: String, effectiveFrom: LocalDate? = nil, shifts: [TemplateShift]) {
        self.id = id
        self.sourceID = sourceID
        self.name = name
        self.effectiveFrom = effectiveFrom
        self.shifts = shifts
    }
}

/// Pay actually received for a payday.
public struct PayReceived: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var sourceID: Int
    public var payDate: LocalDate
    public var net: Money
    public var gross: Money?
    public var tax: Money?
    public var note: String

    public init(id: Int = 0, sourceID: Int, payDate: LocalDate, net: Money, gross: Money? = nil, tax: Money? = nil, note: String = "") {
        self.id = id
        self.sourceID = sourceID
        self.payDate = payDate
        self.net = net
        self.gross = gross
        self.tax = tax
        self.note = note
    }
}

/// A public holiday date and its name.
public struct Holiday: Sendable, Codable, Hashable {
    public var date: LocalDate
    public var name: String

    public init(date: LocalDate, name: String) {
        self.date = date
        self.name = name
    }
}

/// A range of dates the user cannot work (travel, exams). Template shifts are not generated on them.
public struct UnavailableRange: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var from: LocalDate
    public var to: LocalDate
    public var note: String

    public init(id: Int = 0, from: LocalDate, to: LocalDate, note: String = "") {
        self.id = id
        self.from = from
        self.to = to
        self.note = note
    }

    public var range: DateRange { DateRange(start: from, end: to) }
}

/// Expands templates into concrete shifts.
public enum TemplateExpander {
    /// The template in force for the week starting `weekStart`: the latest `effectiveFrom` on or before it
    /// (a nil date counts as the distant past); later entries win ties.
    public static func template(for sourceID: Int, weekStart: LocalDate, in templates: [Template]) -> Template? {
        var best: Template?
        for t in templates where t.sourceID == sourceID {
            if let from = t.effectiveFrom, from > weekStart { continue }
            if let b = best {
                let bFrom = b.effectiveFrom ?? LocalDate(year: 0, month: 1, day: 1)
                let tFrom = t.effectiveFrom ?? LocalDate(year: 0, month: 1, day: 1)
                if tFrom >= bFrom { best = t }
            } else {
                best = t
            }
        }
        return best
    }

    /// Rostered shifts a source's templates would produce in `range`, skipping unavailable dates.
    public static func shifts(sourceID: Int, templates: [Template], range: DateRange,
                              unavailable: [UnavailableRange] = []) -> [Shift] {
        var out: [Shift] = []
        for date in range.dates {
            if unavailable.contains(where: { $0.range.contains(date) }) { continue }
            guard let t = template(for: sourceID, weekStart: date.mondayOfWeek, in: templates) else { continue }
            for ts in t.shifts where ts.weekday == date.weekday {
                out.append(Shift(sourceID: sourceID, date: date, start: ts.start, end: ts.end,
                                 breakMinutes: ts.breakMinutes, status: .rostered))
            }
        }
        return out
    }
}
