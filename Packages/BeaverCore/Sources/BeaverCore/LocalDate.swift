import Foundation

/// ISO weekday: Monday is 1 and Sunday is 7. Monday is the first day of the week.
public enum Weekday: Int, Sendable, Codable, Hashable, CaseIterable, Comparable {
    case monday = 1, tuesday, wednesday, thursday, friday, saturday, sunday

    public var isWeekend: Bool { self == .saturday || self == .sunday }
    public static func < (lhs: Weekday, rhs: Weekday) -> Bool { lhs.rawValue < rhs.rawValue }
}

/// A calendar date with no time zone (proleptic Gregorian). Codable as `YYYY-MM-DD`.
///
/// All arithmetic is integer based, so results never depend on the device time zone or daylight saving.
public struct LocalDate: Sendable, Hashable, Comparable, Codable, CustomStringConvertible, ExpressibleByStringLiteral {
    public let year: Int
    public let month: Int
    public let day: Int

    public init(year: Int, month: Int, day: Int) {
        self.year = year
        self.month = month
        self.day = day
    }

    /// Parses `YYYY-MM-DD`. Returns nil for malformed or impossible dates.
    public init?(iso: String) {
        let parts = iso.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3, parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), d >= 1, d <= LocalDate.daysInMonth(year: y, month: m)
        else { return nil }
        self.init(year: y, month: m, day: d)
    }

    /// Literal support, e.g. `let d: LocalDate = "2026-11-23"`. Traps on an invalid literal; use `init?(iso:)` for user input.
    public init(stringLiteral value: String) {
        guard let date = LocalDate(iso: value) else { preconditionFailure("Invalid LocalDate literal: \(value)") }
        self = date
    }

    /// Days since 1970-01-01 (negative before).
    public init(daysSinceEpoch z0: Int) {
        let z = z0 + 719_468
        let era = (z >= 0 ? z : z - 146_096) / 146_097
        let doe = z - era * 146_097
        let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        let doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        let mp = (5 * doy + 2) / 153
        let d = doy - (153 * mp + 2) / 5 + 1
        let m = mp < 10 ? mp + 3 : mp - 9
        self.init(year: yoe + era * 400 + (m <= 2 ? 1 : 0), month: m, day: d)
    }

    public var daysSinceEpoch: Int {
        let y = month <= 2 ? year - 1 : year
        let era = (y >= 0 ? y : y - 399) / 400
        let yoe = y - era * 400
        let doy = (153 * (month + (month > 2 ? -3 : 9)) + 2) / 5 + day - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    public static func isLeap(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    public static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 2: return isLeap(year) ? 29 : 28
        case 4, 6, 9, 11: return 30
        default: return 31
        }
    }

    public var daysInMonth: Int { LocalDate.daysInMonth(year: year, month: month) }

    /// `YYYY-MM-DD`.
    public var iso: String {
        func pad(_ n: Int, _ w: Int) -> String {
            let s = String(n)
            return String(repeating: "0", count: max(0, w - s.count)) + s
        }
        return "\(pad(year, 4))-\(pad(month, 2))-\(pad(day, 2))"
    }

    public var description: String { iso }

    public var weekday: Weekday {
        Weekday(rawValue: IntMath.mod(daysSinceEpoch + 3, 7) + 1)!
    }

    public func adding(days: Int) -> LocalDate { LocalDate(daysSinceEpoch: daysSinceEpoch + days) }

    /// Adds calendar months, clamping the day to the end of a shorter month.
    public func adding(months: Int) -> LocalDate {
        let index = year * 12 + (month - 1) + months
        let y = IntMath.floorDiv(index, 12)
        let m = IntMath.mod(index, 12) + 1
        return LocalDate(year: y, month: m, day: min(day, LocalDate.daysInMonth(year: y, month: m)))
    }

    /// Signed number of days from `self` to `other`.
    public func days(until other: LocalDate) -> Int { other.daysSinceEpoch - daysSinceEpoch }

    public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool {
        (lhs.year, lhs.month, lhs.day) < (rhs.year, rhs.month, rhs.day)
    }

    public init(from decoder: any Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let d = LocalDate(iso: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid date \(s)"))
        }
        self = d
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(iso)
    }
}

/// A wall-clock time of day (no date, no zone). Codable as `HH:mm`.
public struct LocalTime: Sendable, Hashable, Comparable, Codable, CustomStringConvertible, ExpressibleByStringLiteral {
    public let hour: Int
    public let minute: Int

    public init(hour: Int, minute: Int = 0) {
        self.hour = hour
        self.minute = minute
    }

    /// Minutes since midnight, `0..<1440`.
    public init(minutesSinceMidnight m: Int) {
        let n = IntMath.mod(m, 1440)
        self.init(hour: n / 60, minute: n % 60)
    }

    /// Parses `HH:mm`.
    public init?(iso: String) {
        let parts = iso.split(separator: ":", omittingEmptySubsequences: false)
        guard parts.count == 2, parts[0].count == 2, parts[1].count == 2,
              let h = Int(parts[0]), let m = Int(parts[1]), (0...23).contains(h), (0...59).contains(m)
        else { return nil }
        self.init(hour: h, minute: m)
    }

    public init(stringLiteral value: String) {
        guard let t = LocalTime(iso: value) else { preconditionFailure("Invalid LocalTime literal: \(value)") }
        self = t
    }

    public var minutesSinceMidnight: Int { hour * 60 + minute }

    public var iso: String {
        (hour < 10 ? "0" : "") + String(hour) + ":" + (minute < 10 ? "0" : "") + String(minute)
    }

    public var description: String { iso }

    public static func < (lhs: LocalTime, rhs: LocalTime) -> Bool {
        lhs.minutesSinceMidnight < rhs.minutesSinceMidnight
    }

    public init(from decoder: any Decoder) throws {
        let s = try decoder.singleValueContainer().decode(String.self)
        guard let t = LocalTime(iso: s) else {
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Invalid time \(s)"))
        }
        self = t
    }

    public func encode(to encoder: any Encoder) throws {
        var c = encoder.singleValueContainer()
        try c.encode(iso)
    }
}
