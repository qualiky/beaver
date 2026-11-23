import Foundation

/// When an income source is paid and which work period each payday covers.
public enum PaySchedule: Sendable, Codable, Hashable {
    /// Paid every week on `payWeekday`; `lagWeeks` 1 means the week just finished is paid.
    case weekly(payWeekday: Weekday, lagWeeks: Int)
    /// Paid every 14 days from `anchorPayday` in both directions.
    case fortnightly(anchorPayday: LocalDate, lagWeeks: Int)
    /// Paid on `dayOfMonth` (clamped to the month end). Fixed-amount sources only.
    case monthly(dayOfMonth: Int)

    public var frequency: PayFrequency {
        switch self {
        case .weekly: .weekly
        case .fortnightly: .fortnightly
        case .monthly: .monthly
        }
    }

    /// Paydays inside the inclusive range, in order.
    public func paydays(in range: DateRange) -> [LocalDate] {
        guard !range.isEmpty else { return [] }
        switch self {
        case .weekly(let payWeekday, _):
            let offset = IntMath.mod(payWeekday.rawValue - range.start.weekday.rawValue, 7)
            var d = range.start.adding(days: offset)
            var out: [LocalDate] = []
            while d <= range.end { out.append(d); d = d.adding(days: 7) }
            return out
        case .fortnightly(let anchor, _):
            let k = -IntMath.floorDiv(-anchor.days(until: range.start), 14) // ceil((start - anchor) / 14)
            var d = anchor.adding(days: k * 14)
            var out: [LocalDate] = []
            while d <= range.end { out.append(d); d = d.adding(days: 14) }
            return out
        case .monthly(let dom):
            var out: [LocalDate] = []
            var month = LocalDate(year: range.start.year, month: range.start.month, day: 1)
            while month <= range.end {
                let day = min(max(dom, 1), month.daysInMonth)
                let d = LocalDate(year: month.year, month: month.month, day: day)
                if range.contains(d) { out.append(d) }
                month = month.adding(months: 1)
            }
            return out
        }
    }

    /// The Mon-Sun work period (7 days weekly, 14 days fortnightly) paid on `payday`. Nil for monthly schedules.
    public func workPeriod(forPayday payday: LocalDate) -> DateRange? {
        switch self {
        case .weekly(_, let lag):
            let sunday = payday.adding(days: -(lag - 1) * 7).previousSunday
            return DateRange(start: sunday.adding(days: -6), end: sunday)
        case .fortnightly(_, let lag):
            let sunday = payday.adding(days: -(lag - 1) * 7).previousSunday
            return DateRange(start: sunday.adding(days: -13), end: sunday)
        case .monthly:
            return nil
        }
    }

    /// The payday that pays work done on `date`. Nil for monthly schedules.
    public func payday(forWorkDate date: LocalDate) -> LocalDate? {
        switch self {
        case .weekly(let payWeekday, let lag):
            let sunday = date.sundayOfWeek
            return sunday.adding(days: payWeekday.rawValue + (lag - 1) * 7)
        case .fortnightly(let anchor, let lag):
            let anchorSunday = anchor.adding(days: -(lag - 1) * 7).previousSunday
            let firstDay = anchorSunday.adding(days: -13)
            let k = IntMath.floorDiv(firstDay.days(until: date), 14)
            return anchor.adding(days: k * 14)
        case .monthly:
            return nil
        }
    }
}
