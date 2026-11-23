import Foundation

/// An inclusive range of dates. An empty range (`end < start`) is allowed and contains nothing.
public struct DateRange: Sendable, Codable, Hashable {
    public var start: LocalDate
    public var end: LocalDate

    public init(start: LocalDate, end: LocalDate) {
        self.start = start
        self.end = end
    }

    public func contains(_ date: LocalDate) -> Bool { date >= start && date <= end }
    public var isEmpty: Bool { end < start }
    public var dayCount: Int { isEmpty ? 0 : start.days(until: end) + 1 }

    /// Every date in the range, in order.
    public var dates: [LocalDate] { (0..<dayCount).map { start.adding(days: $0) } }
}

extension LocalDate {
    /// The Monday that starts this date's Monday-to-Sunday week.
    public var mondayOfWeek: LocalDate { adding(days: -(weekday.rawValue - 1)) }

    /// The Sunday that ends this date's week.
    public var sundayOfWeek: LocalDate { adding(days: 7 - weekday.rawValue) }

    /// The Monday-to-Sunday week containing this date.
    public var week: DateRange { DateRange(start: mondayOfWeek, end: sundayOfWeek) }

    /// The last Sunday strictly before this date.
    public var previousSunday: LocalDate { adding(days: -weekday.rawValue) }

    /// Today's date in the given time zone (the only place a time zone is used).
    public static func today(in timeZone: TimeZone = .current, now: Date = Date()) -> LocalDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day], from: now)
        return LocalDate(year: c.year!, month: c.month!, day: c.day!)
    }

    /// Midday of this date in the given zone, for handing to system date formatters.
    public func date(in timeZone: TimeZone = .current) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.firstWeekday = 2
        return calendar.date(from: DateComponents(year: year, month: month, day: day, hour: 12))!
    }
}
