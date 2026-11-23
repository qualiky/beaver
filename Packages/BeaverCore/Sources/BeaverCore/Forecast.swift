import Foundation

/// Everything the forecast engine needs. The engine only ever sees these value types.
public struct ForecastInput: Sendable, Codable, Hashable {
    public var today: LocalDate
    public var sources: [IncomeSource]
    public var rateCards: [RateCard]
    /// Custom withholding tables; built-ins are always available.
    public var withholdingTables: [WithholdingTable]
    public var templates: [Template]
    public var shifts: [Shift]
    public var paysReceived: [PayReceived]
    public var holidays: [Holiday]
    public var unavailable: [UnavailableRange]
    public var bills: [Bill]
    public var billPayments: [BillPayment]
    public var expenses: [Expense]
    public var weekSpendTotals: [WeekSpendTotal]
    /// Weekly living budget (food, transport and so on, outside bills).
    public var livingBudget: Money
    public var goals: [Goal]
    public var deposits: [Deposit]
    /// The goal that drives the forecast; if nil the first goal is used.
    public var primaryGoalID: Int?
    /// What-if: extra net income added to every week.
    public var extraWeeklyIncome: Money

    public init(today: LocalDate, sources: [IncomeSource] = [], rateCards: [RateCard] = [],
                withholdingTables: [WithholdingTable] = [], templates: [Template] = [], shifts: [Shift] = [],
                paysReceived: [PayReceived] = [], holidays: [Holiday] = [], unavailable: [UnavailableRange] = [],
                bills: [Bill] = [], billPayments: [BillPayment] = [], expenses: [Expense] = [],
                weekSpendTotals: [WeekSpendTotal] = [], livingBudget: Money = .zero, goals: [Goal] = [],
                deposits: [Deposit] = [], primaryGoalID: Int? = nil, extraWeeklyIncome: Money = .zero) {
        self.today = today
        self.sources = sources
        self.rateCards = rateCards
        self.withholdingTables = withholdingTables
        self.templates = templates
        self.shifts = shifts
        self.paysReceived = paysReceived
        self.holidays = holidays
        self.unavailable = unavailable
        self.bills = bills
        self.billPayments = billPayments
        self.expenses = expenses
        self.weekSpendTotals = weekSpendTotals
        self.livingBudget = livingBudget
        self.goals = goals
        self.deposits = deposits
        self.primaryGoalID = primaryGoalID
        self.extraWeeklyIncome = extraWeeklyIncome
    }

    public var holidayDates: Set<LocalDate> { Set(holidays.map(\.date)) }

    /// The goal driving the forecast.
    public var primaryGoal: Goal? {
        if let id = primaryGoalID, let g = goals.first(where: { $0.id == id }) { return g }
        return goals.first
    }

    /// Expected pay for a source's payday, from shifts or (if the period has none) from templates.
    func expectedPay(source: IncomeSource, payday: LocalDate, allowTemplate: Bool) -> (pay: PeriodPay, period: DateRange?, shiftCount: Int, usedTemplate: Bool) {
        let period = source.schedule.workPeriod(forPayday: payday)
        switch source.kind {
        case .fixed:
            let amount = source.fixedAmount ?? .zero
            if source.amountIsNet {
                return (PeriodPay(gross: amount, tax: .zero, net: amount), period, 0, false)
            }
            let tax = source.taxMethod.tax(onGross: amount, frequency: source.schedule.frequency, tables: withholdingTables)
            return (PeriodPay(gross: amount, tax: tax, net: amount - tax), period, 0, false)
        case .hourly:
            guard let period else { return (PeriodPay(), nil, 0, false) }
            let card = rateCards.first { $0.id == source.rateCardID }
            var periodShifts = shifts.filter { $0.sourceID == source.id && period.contains($0.date) }
            var usedTemplate = false
            if periodShifts.isEmpty, allowTemplate {
                periodShifts = TemplateExpander.shifts(sourceID: source.id, templates: templates, range: period, unavailable: unavailable)
                usedTemplate = !periodShifts.isEmpty
            }
            let pay = PeriodPay.calculate(shifts: periodShifts, rateCard: card, holidays: holidayDates,
                                          taxMethod: source.taxMethod, frequency: source.schedule.frequency,
                                          tables: withholdingTables)
            return (pay, period, periodShifts.count, usedTemplate)
        }
    }

    /// The pay event for one source and payday.
    func payEvent(source: IncomeSource, payday: LocalDate) -> PayEvent {
        let actual = paysReceived.first { $0.sourceID == source.id && $0.payDate == payday }
        let e = expectedPay(source: source, payday: payday, allowTemplate: payday >= today || actual != nil)
        return PayEvent(sourceID: source.id, sourceName: source.name, payday: payday, workPeriod: e.period,
                        expectedGross: e.pay.gross, expectedTax: e.pay.tax, expectedNet: e.pay.net,
                        actualNet: actual?.net, shiftCount: e.shiftCount, usedTemplate: e.usedTemplate)
    }
}

/// One payment of one source: expected from shifts or templates, replaced by the actual once logged.
public struct PayEvent: Sendable, Codable, Hashable {
    public var sourceID: Int
    public var sourceName: String
    public var payday: LocalDate
    public var workPeriod: DateRange?
    public var expectedGross: Money
    public var expectedTax: Money
    public var expectedNet: Money
    public var actualNet: Money?
    public var shiftCount: Int
    /// The expected pay came from the default week template rather than rostered shifts.
    public var usedTemplate: Bool

    public var isActual: Bool { actualNet != nil }
    /// The net amount the forecast uses: actual when logged, otherwise the estimate.
    public var net: Money { actualNet ?? expectedNet }
    /// Actual minus expected, or nil if nothing is logged.
    public var variance: Money? { actualNet.map { $0 - expectedNet } }
}

/// One Monday-to-Sunday week of the forecast.
public struct WeekRow: Sendable, Codable, Hashable {
    public var weekStart: LocalDate
    public var payEvents: [PayEvent]
    /// What-if extra weekly income.
    public var extraIncome: Money
    public var bills: [BillOccurrence]
    /// The living budget for the week (the forecast starts at the current week).
    public var living: Money
    public var isCurrent: Bool
    public var isDeadlineWeek: Bool

    public var weekEnd: LocalDate { weekStart.adding(days: 6) }
    public var income: Money { payEvents.map(\.net).total() + extraIncome }
    public var billsTotal: Money { bills.map(\.amount).total() }
    public var surplus: Money { income - billsTotal - living }
    public var isDanger: Bool { surplus.cents < 0 }
}

/// The result of the forecast engine.
public struct Forecast: Sendable, Codable, Hashable {
    public var today: LocalDate
    public var goal: Goal?
    public var deadline: LocalDate?
    public var weeks: [WeekRow]
    /// Deposits logged against the primary goal.
    public var depositsTotal: Money
    /// Deposits plus the sum of weekly surplus up to the deadline.
    public var projectedAtDeadline: Money
    /// Projected minus target. Positive is ahead; without a goal the target is zero, so this is the projected surplus.
    public var gap: Money
    /// Distinct paydays from today to the deadline.
    public var paydayCount: Int
    /// (target - deposits) / paydayCount, rounded half up and never negative.
    public var requiredPerPayday: Money
    /// Weeks with negative surplus (each carries the bills due in it).
    public var dangerWeeks: [WeekRow]
    /// Sum of (actual - expected) over every logged pay.
    public var payVariance: Money
    /// Sum of (budget - spent) over past weeks that have spending recorded.
    public var spendingVariance: Money
    public var suggestedSplit: SuggestedSplit?

    public var target: Money { goal?.target ?? .zero }
    public var payEvents: [PayEvent] { weeks.flatMap(\.payEvents).sorted { ($0.payday, $0.sourceID) < ($1.payday, $1.sourceID) } }
    public var totalIncome: Money { weeks.map(\.income).total() }
    public var totalBills: Money { weeks.map(\.billsTotal).total() }
    public var totalLiving: Money { weeks.map(\.living).total() }
    public var totalSurplus: Money { weeks.map(\.surplus).total() }
    public var currentWeek: WeekRow? { weeks.first(where: \.isCurrent) }

    /// Runs the forecast for every week from the current one through the primary goal's deadline (26 weeks with no deadline).
    public static func compute(_ input: ForecastInput) -> Forecast {
        let today = input.today
        let firstWeek = today.mondayOfWeek
        let goal = input.primaryGoal
        let deadline = goal?.deadline
        let lastWeek = deadline.map { max($0.mondayOfWeek, firstWeek) } ?? firstWeek.adding(days: 25 * 7)
        let windowEnd = lastWeek.adding(days: 6)
        let cutoff = min(deadline ?? windowEnd, windowEnd)
        let eventRange = DateRange(start: firstWeek, end: cutoff)

        var events: [PayEvent] = []
        for source in input.sources where source.isActive {
            for payday in source.schedule.paydays(in: eventRange) {
                events.append(input.payEvent(source: source, payday: payday))
            }
        }
        var occurrences: [BillOccurrence] = []
        for bill in input.bills where bill.isActive {
            occurrences += bill.occurrences(in: eventRange, payments: input.billPayments)
        }

        var weeks: [WeekRow] = []
        var weekStart = firstWeek
        while weekStart <= lastWeek {
            let week = weekStart.week
            weeks.append(WeekRow(
                weekStart: weekStart,
                payEvents: events.filter { week.contains($0.payday) }.sorted { ($0.payday, $0.sourceID) < ($1.payday, $1.sourceID) },
                extraIncome: input.extraWeeklyIncome,
                bills: occurrences.filter { week.contains($0.dueDate) }.sorted { ($0.dueDate, $0.billID) < ($1.dueDate, $1.billID) },
                living: input.livingBudget,
                isCurrent: weekStart == firstWeek,
                isDeadlineWeek: weekStart == lastWeek && deadline != nil))
            weekStart = weekStart.adding(days: 7)
        }

        let deposits = goal.map { $0.saved(deposits: input.deposits) } ?? .zero
        let projected = deposits + weeks.map(\.surplus).total()
        let target = goal?.target ?? .zero
        let paydayCount = Set(events.filter { $0.payday >= today }.map(\.payday)).count
        let remaining = max(0, (target - deposits).cents)
        let required = paydayCount > 0 ? Money(cents: IntMath.roundHalfUp(remaining, paydayCount)) : Money(cents: remaining)

        var payVariance = Money.zero
        for p in input.paysReceived {
            guard let source = input.sources.first(where: { $0.id == p.sourceID }) else { continue }
            let e = input.expectedPay(source: source, payday: p.payDate, allowTemplate: true)
            payVariance += p.net - e.pay.net
        }

        var mondays = Set<LocalDate>()
        for e in input.expenses where e.date.mondayOfWeek < firstWeek { mondays.insert(e.date.mondayOfWeek) }
        for t in input.weekSpendTotals where t.weekStart.mondayOfWeek < firstWeek { mondays.insert(t.weekStart.mondayOfWeek) }
        var spendingVariance = Money.zero
        for monday in mondays {
            let spent = Spending.spent(weekStart: monday, expenses: input.expenses, totals: input.weekSpendTotals)
            spendingVariance += Spending.variance(budget: input.livingBudget, spent: spent)
        }

        var forecast = Forecast(today: today, goal: goal, deadline: deadline, weeks: weeks, depositsTotal: deposits,
                                projectedAtDeadline: projected, gap: projected - target, paydayCount: paydayCount,
                                requiredPerPayday: required, dangerWeeks: weeks.filter(\.isDanger),
                                payVariance: payVariance, spendingVariance: spendingVariance, suggestedSplit: nil)
        forecast.suggestedSplit = SuggestedSplit.make(input: input, requiredPerPayday: required)
        return forecast
    }
}
