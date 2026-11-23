import Foundation

public enum HoursWarningLevel: String, Sendable, Codable, Hashable {
    case soft, hard
}

/// A warning that hours exceed a limit. Warnings never block saving.
public struct HoursWarning: Sendable, Codable, Hashable {
    public enum Scope: Sendable, Codable, Hashable {
        case weekly(sourceID: Int)
        case fortnight
    }

    public var scope: Scope
    public var period: DateRange
    public var workedMinutes: Int
    public var limitHours: Int
    public var level: HoursWarningLevel
}

/// A rolling-fortnight hours limit across all sources, active from a date.
public struct FortnightLimit: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var hoursPerFortnight: Int
    public var effectiveFrom: LocalDate

    public init(id: Int = 0, hoursPerFortnight: Int, effectiveFrom: LocalDate) {
        self.id = id
        self.hoursPerFortnight = hoursPerFortnight
        self.effectiveFrom = effectiveFrom
    }

    /// Fortnights are anchored on the Monday of `effectiveFrom`.
    public var anchorMonday: LocalDate { effectiveFrom.mondayOfWeek }
}

/// Hours-limit checks (whole-hour limits compared against paid minutes of non-cancelled shifts).
public enum HoursLimits {
    /// Paid minutes of non-cancelled shifts that start inside `range`, optionally for one source.
    public static func workedMinutes(shifts: [Shift], in range: DateRange, sourceID: Int? = nil) -> Int {
        shifts.reduce(0) { total, s in
            guard s.status != .cancelled, range.contains(s.date), sourceID == nil || s.sourceID == sourceID else { return total }
            return total + s.paidMinutes
        }
    }

    /// The warning for the week starting `weekStart` against the source's soft/hard caps, if any.
    public static func weeklyWarning(source: IncomeSource, weekStart: LocalDate, shifts: [Shift]) -> HoursWarning? {
        let week = weekStart.mondayOfWeek.week
        let worked = workedMinutes(shifts: shifts, in: week, sourceID: source.id)
        if let hard = source.weeklyCapHardHours, worked > hard * 60 {
            return HoursWarning(scope: .weekly(sourceID: source.id), period: week, workedMinutes: worked, limitHours: hard, level: .hard)
        }
        if let soft = source.weeklyCapSoftHours, worked > soft * 60 {
            return HoursWarning(scope: .weekly(sourceID: source.id), period: week, workedMinutes: worked, limitHours: soft, level: .soft)
        }
        return nil
    }

    /// The 14-day window containing `date`, or nil if it starts before the limit became active.
    public static func fortnight(containing date: LocalDate, limit: FortnightLimit) -> DateRange? {
        let k = IntMath.floorDiv(limit.anchorMonday.days(until: date), 14)
        guard k >= 0 else { return nil }
        let start = limit.anchorMonday.adding(days: k * 14)
        return DateRange(start: start, end: start.adding(days: 13))
    }

    /// A (hard) warning when all sources together exceed the fortnight limit in the window containing `date`.
    public static func fortnightWarning(limit: FortnightLimit, date: LocalDate, shifts: [Shift]) -> HoursWarning? {
        guard let window = fortnight(containing: date, limit: limit) else { return nil }
        let worked = workedMinutes(shifts: shifts, in: window)
        guard worked > limit.hoursPerFortnight * 60 else { return nil }
        return HoursWarning(scope: .fortnight, period: window, workedMinutes: worked,
                            limitHours: limit.hoursPerFortnight, level: .hard)
    }
}
