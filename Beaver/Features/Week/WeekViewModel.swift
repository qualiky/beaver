import Foundation
import BeaverCore

// Formatting and selection logic for the Week tab. No SwiftUI here, so it is unit-testable.

struct Headline: Equatable {
    var text: String
    var detail: String?
    var tone: Tone
}

struct GoalCard: Equatable {
    var name: String
    var progress: Double
    var savedText: String
    var targetText: String
    var requiredText: String
    var paydaysText: String
    var deadlineText: String
}

struct LivingLine: Equatable {
    var budgetText: String
    var spentText: String
    var leftText: String
    var left: Money
    var tone: Tone
    /// Fraction of the budget spent, clamped to 0...1.
    var fractionSpent: Double
}

struct SplitLineModel: Identifiable, Equatable {
    var id: SplitKind { kind }
    var kind: SplitKind
    var title: String
    var symbol: String
    var fundedText: String
    var neededText: String
    /// Set when the pay does not cover this line: the part that comes out of the buffer.
    var fromBufferText: String?
}

struct SplitCard: Equatable {
    var paydayText: String
    var coversText: String
    var expectedPayText: String
    var lines: [SplitLineModel]
    var bufferText: String?
    var totalFromBufferText: String?
}

struct DangerBanner: Identifiable, Equatable {
    var id: LocalDate { weekStart }
    var weekStart: LocalDate
    var title: String
    var shortfallText: String
    var bills: [BillLine]
}

struct HoursBar: Equatable {
    enum Status: Equatable { case ok, near, over }

    /// Amber from this many hours below the limit.
    static let warnMarginHours = 4

    var usedMinutes: Int
    var limitHours: Int
    var window: DateRange
    var status: Status

    var fraction: Double { limitHours > 0 ? min(1, Double(usedMinutes) / Double(limitHours * 60)) : 0 }
    var usedText: String { Self.hoursText(usedMinutes) }
    var summary: String { "\(usedText) of \(limitHours) h" }

    static func hoursText(_ minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "\(h) h" : "\(h) h \(m) min"
    }

    static func status(usedMinutes: Int, limitHours: Int) -> Status {
        if usedMinutes > limitHours * 60 { return .over }
        if usedMinutes >= (limitHours - warnMarginHours) * 60 { return .near }
        return .ok
    }

    /// The bar for the fortnight containing `today`, or nil when no fortnightly limit is active for it.
    /// With several limits, the most recently effective one that covers today wins.
    static func make(limits: [FortnightLimit], shifts: [Shift], today: LocalDate) -> HoursBar? {
        for limit in limits.sorted(by: { $0.effectiveFrom > $1.effectiveFrom }) where limit.hoursPerFortnight > 0 {
            guard let window = HoursLimits.fortnight(containing: today, limit: limit) else { continue }
            let used = HoursLimits.workedMinutes(shifts: shifts, in: window)
            return HoursBar(usedMinutes: used, limitHours: limit.hoursPerFortnight, window: window,
                            status: status(usedMinutes: used, limitHours: limit.hoursPerFortnight))
        }
        return nil
    }
}

enum BackupReminder {
    static let maxAgeDays = 7

    /// Due when never exported, or the last export is more than 7 days old.
    static func isDue(lastExportAt: Date?, now: Date) -> Bool {
        guard let last = lastExportAt else { return true }
        return now.timeIntervalSince(last) > Double(maxAgeDays * 86_400)
    }

    static func message(lastExportAt: Date?, now: Date) -> String? {
        guard isDue(lastExportAt: lastExportAt, now: now) else { return nil }
        guard let last = lastExportAt else { return "You haven't backed up yet. Beaver keeps everything on this phone only." }
        let days = Int(now.timeIntervalSince(last) / 86_400)
        return "Your last backup was \(days) days ago. Beaver keeps everything on this phone only."
    }
}

struct WeekViewModel {
    let forecast: Forecast
    let input: ForecastInput
    let lastExportAt: Date?
    let hoursLimits: [FortnightLimit]
    let now: Date
    let fmt: FigureFormatter

    init(forecast: Forecast, input: ForecastInput, settings: AppSettings, hoursLimits: [FortnightLimit] = [],
         now: Date = Date(), locale: Locale = .current) {
        self.forecast = forecast
        self.input = input
        self.lastExportAt = settings.lastExportAt
        self.hoursLimits = hoursLimits
        self.now = now
        self.fmt = FigureFormatter(currency: settings.currency, locale: locale)
    }

    var today: LocalDate { input.today }
    var weekStart: LocalDate { today.mondayOfWeek }

    /// Nothing set up yet: no income sources and no bills.
    var isEmpty: Bool { input.sources.isEmpty && input.bills.isEmpty }

    // MARK: Headline and goal

    var headline: Headline {
        guard let goal = forecast.goal else {
            let surplus = forecast.currentWeek?.surplus ?? forecast.totalSurplus
            let word = surplus.cents < 0 ? "shortfall" : "surplus"
            return Headline(text: "This week: \(fmt.signed(surplus)) \(word)", detail: nil, tone: Tone.of(surplus))
        }
        let gap = forecast.gap
        let by = forecast.deadline.map { " by \(DateText.full($0, locale: fmt.locale))" } ?? ""
        let detail = "Projected \(fmt.string(forecast.projectedAtDeadline)) of \(fmt.string(goal.target))\(by)"
        if gap.cents == 0 { return Headline(text: "Right on target", detail: detail, tone: .neutral) }
        let word = gap.cents > 0 ? "ahead" : "behind"
        return Headline(text: "\(fmt.signed(gap)) \(word)", detail: detail, tone: Tone.of(gap))
    }

    var goalCard: GoalCard? {
        guard let goal = forecast.goal else { return nil }
        let bp = goal.progressBasisPoints(deposits: input.deposits)
        let n = forecast.paydayCount
        return GoalCard(
            name: goal.name,
            progress: Double(bp) / 10_000,
            savedText: fmt.string(forecast.depositsTotal),
            targetText: fmt.string(goal.target),
            requiredText: "\(fmt.string(forecast.requiredPerPayday)) needed per payday",
            paydaysText: n == 1 ? "1 payday left" : "\(n) paydays left",
            deadlineText: goal.deadline.map { "Deadline \(DateText.full($0, locale: fmt.locale))" } ?? "No deadline")
    }

    // MARK: This week

    /// The next payday on or after today (the split's payday when there is one).
    var nextPayday: LocalDate? {
        forecast.suggestedSplit?.payday ?? forecast.payEvents.first { $0.payday >= today }?.payday
    }

    var nextPaydayText: String? { nextPayday.map { DateText.weekdayDayMonth($0, locale: fmt.locale) } }

    /// Pay events whose payday falls in the current week.
    var payLines: [PayLine] {
        (forecast.currentWeek?.payEvents ?? []).map { PayLine($0, fmt: fmt) }
    }

    /// Bills due before the next payday: from the start of this week to the day before it (or to Sunday with no payday),
    /// plus unpaid bills that fell due in the 8 weeks before. Paid ones stay listed so they can show a tick.
    var bills: [BillLine] {
        let end = nextPayday.map { $0.adding(days: -1) } ?? weekStart.adding(days: 6)
        let window = DateRange(start: weekStart, end: end)
        let overdueWindow = DateRange(start: weekStart.adding(days: -56), end: weekStart.adding(days: -1))
        var found: [BillOccurrence] = []
        for bill in input.bills where bill.isActive {
            found += bill.occurrences(in: overdueWindow, payments: input.billPayments).filter { !$0.isPaid }
            found += bill.occurrences(in: window, payments: input.billPayments)
        }
        return found
            .sorted { ($0.dueDate, $0.billID) < ($1.dueDate, $1.billID) }
            .map { BillLine($0, today: today, fmt: fmt) }
    }

    var billsTotal: Money { bills.map(\.amount).total() }
    var billsUnpaidTotal: Money { bills.filter { !$0.isPaid }.map(\.amount).total() }

    var living: LivingLine {
        let budget = input.livingBudget
        let spent = Spending.spent(weekStart: weekStart, expenses: input.expenses, totals: input.weekSpendTotals)
        let left = budget - spent
        let fraction = budget.cents > 0 ? min(1, max(0, Double(spent.cents) / Double(budget.cents))) : (spent.cents > 0 ? 1 : 0)
        return LivingLine(budgetText: fmt.string(budget), spentText: fmt.string(spent), leftText: fmt.string(left),
                          left: left, tone: left.cents < 0 ? .negative : .neutral, fractionSpent: fraction)
    }

    // MARK: Suggested split

    var split: SplitCard? {
        guard let s = forecast.suggestedSplit else { return nil }
        let lines = s.lines.map { line -> SplitLineModel in
            let short = max(0, line.needed.cents) - line.funded.cents
            return SplitLineModel(kind: line.kind, title: Self.title(line.kind), symbol: Self.symbol(line.kind),
                                  fundedText: fmt.string(line.funded), neededText: fmt.string(line.needed),
                                  fromBufferText: short > 0 ? fmt.string(Money(cents: short)) : nil)
        }
        let locale = fmt.locale
        return SplitCard(
            paydayText: DateText.weekdayDayMonth(s.payday, locale: locale),
            coversText: "Covers until \(DateText.weekdayDayMonth(s.followingPayday, locale: locale))",
            expectedPayText: fmt.string(s.expectedPay),
            lines: lines,
            bufferText: s.buffer.cents > 0 ? fmt.string(s.buffer) : nil,
            totalFromBufferText: s.fromBuffer.cents > 0 ? fmt.string(s.fromBuffer) : nil)
    }

    static func title(_ kind: SplitKind) -> String {
        switch kind {
        case .bills: "Bills due"
        case .living: "Living budget"
        case .sinkingFund: "Bills sinking fund"
        case .goal: "Goal"
        }
    }

    static func symbol(_ kind: SplitKind) -> String {
        switch kind {
        case .bills: "doc.text"
        case .living: "cart"
        case .sinkingFund: "archivebox"
        case .goal: "flag"
        }
    }

    // MARK: Banners

    /// A banner for each of this week and next week whose surplus is negative.
    var dangerBanners: [DangerBanner] {
        forecast.weeks.prefix(2).enumerated().compactMap { index, week in
            guard week.isDanger else { return nil }
            let label = index == 0 ? "This week" : "Next week"
            let lines = week.bills.map { BillLine($0, today: today, fmt: fmt) }
            return DangerBanner(weekStart: week.weekStart, title: "\(label) is tight",
                                shortfallText: "\(fmt.string(Money(cents: -week.surplus.cents))) short", bills: lines)
        }
    }

    var hoursBar: HoursBar? { HoursBar.make(limits: hoursLimits, shifts: input.shifts, today: today) }

    /// Shown when the current fortnight is over the limit or within 4 hours of it.
    var hoursWarning: String? {
        guard let bar = hoursBar else { return nil }
        let limit = "\(bar.limitHours) h"
        switch bar.status {
        case .ok: return nil
        case .near: return "You're close to your \(limit) fortnight limit: \(bar.summary) used."
        case .over: return "You're over your \(limit) fortnight limit: \(bar.summary) used."
        }
    }

    var backupMessage: String? { BackupReminder.message(lastExportAt: lastExportAt, now: now) }
}
