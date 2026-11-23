import Foundation

/// Which days a rate rule applies to.
public enum DaySelector: String, Sendable, Codable, Hashable, CaseIterable {
    case publicHoliday, saturday, sunday
    /// Monday to Friday.
    case weekdays
}

/// A clock window `[startMinute, endMinute)` in minutes since midnight. `endMinute` may be 1440 (24:00).
public struct ClockWindow: Sendable, Codable, Hashable {
    public var startMinute: Int
    public var endMinute: Int

    public init(startMinute: Int, endMinute: Int) {
        self.startMinute = startMinute
        self.endMinute = endMinute
    }

    /// Hours-based convenience, e.g. `ClockWindow(fromHour: 19, toHour: 24)`.
    public init(fromHour: Int, toHour: Int) {
        self.init(startMinute: fromHour * 60, endMinute: toHour * 60)
    }

    public func contains(minute: Int) -> Bool { minute >= startMinute && minute < endMinute }
}

/// One line of a rate card: a named band, its hourly rate and where it applies.
public struct RateRule: Sendable, Codable, Hashable {
    public var band: String
    public var centsPerHour: Int
    public var days: DaySelector
    /// Optional clock window; nil means all day.
    public var window: ClockWindow?

    public init(band: String, centsPerHour: Int, days: DaySelector, window: ClockWindow? = nil) {
        self.band = band
        self.centsPerHour = centsPerHour
        self.days = days
        self.window = window
    }

    func matches(weekday: Weekday, isHoliday: Bool, minute: Int) -> Bool {
        if let window, !window.contains(minute: minute) { return false }
        switch days {
        case .publicHoliday: return isHoliday
        case .saturday: return weekday == .saturday
        case .sunday: return weekday == .sunday
        case .weekdays: return !weekday.isWeekend
        }
    }
}

/// A user's pay rates. Rules are ordered and the first match wins; a public holiday rule overrides the rest on holidays.
public struct RateCard: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var rules: [RateRule]

    public init(id: Int = 0, name: String, rules: [RateRule]) {
        self.id = id
        self.name = name
        self.rules = rules
    }

    /// Distinct band names in rule order.
    public var bandNames: [String] {
        var seen = Set<String>()
        return rules.compactMap { seen.insert($0.band).inserted ? $0.band : nil }
    }

    /// Index of the rule that prices a given minute, or nil if no rule covers it.
    func ruleIndex(weekday: Weekday, isHoliday: Bool, minute: Int) -> Int? {
        if isHoliday,
           let i = rules.firstIndex(where: { $0.days == .publicHoliday && $0.matches(weekday: weekday, isHoliday: true, minute: minute) }) {
            return i
        }
        return rules.firstIndex { $0.days != .publicHoliday && $0.matches(weekday: weekday, isHoliday: isHoliday, minute: minute) }
    }

    /// Weekday (Mon-Fri) clock windows that no rule covers. An empty result means the card is complete.
    public func weekdayGaps() -> [ClockWindow] {
        var gaps: [ClockWindow] = []
        var gapStart: Int?
        for minute in 0..<1440 {
            let covered = rules.contains { $0.days == .weekdays && $0.matches(weekday: .monday, isHoliday: false, minute: minute) }
            if !covered, gapStart == nil { gapStart = minute }
            if covered, let s = gapStart {
                gaps.append(ClockWindow(startMinute: s, endMinute: minute))
                gapStart = nil
            }
        }
        if let s = gapStart { gaps.append(ClockWindow(startMinute: s, endMinute: 1440)) }
        return gaps
    }

    /// "Typical casual (template)": fictional rates users override in onboarding.
    public static func typicalCasual(id: Int = 0) -> RateCard {
        RateCard(id: id, name: "Typical casual (template)", rules: [
            RateRule(band: "Public holiday", centsPerHour: 6000, days: .publicHoliday),
            RateRule(band: "Weekday day", centsPerHour: 3000, days: .weekdays, window: ClockWindow(fromHour: 7, toHour: 19)),
            RateRule(band: "Weekday evening", centsPerHour: 3300, days: .weekdays, window: ClockWindow(fromHour: 19, toHour: 24)),
            RateRule(band: "Weekday night", centsPerHour: 3450, days: .weekdays, window: ClockWindow(fromHour: 0, toHour: 7)),
            RateRule(band: "Saturday", centsPerHour: 3750, days: .saturday),
            RateRule(band: "Sunday", centsPerHour: 4500, days: .sunday),
        ])
    }
}
