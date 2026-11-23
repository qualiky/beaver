import Foundation
import BeaverCore

// Testable logic for the Money tab. Everything here is a pure function of a `ForecastInput` snapshot, plus
// `MoneyActions` for writes. Views never compute amounts themselves.

// MARK: - Bills

/// One occurrence of a bill, ready for display.
struct MoneyBillRow: Identifiable, Hashable {
    var id: String { "\(occurrence.billID)-\(occurrence.dueDate.iso)" }
    let occurrence: BillOccurrence
    let status: BillStatus
    let dateText: String
    let amountText: String
    let symbol: String

    var billID: Int { occurrence.billID }
    var name: String { occurrence.name }
    var dueDate: LocalDate { occurrence.dueDate }
    var isPaid: Bool { occurrence.isPaid }
    var amount: Money { occurrence.amount }
}

/// Bill occurrences under one "Before ‹payday›" (or week) header.
struct MoneyBillGroup: Identifiable, Hashable {
    let id: String
    let title: String
    /// The payday that closes the group; nil when grouped by week.
    let payday: LocalDate?
    let rows: [MoneyBillRow]
    let total: Money
    let totalText: String
}

/// One line of the Monthly view.
struct MonthlyBillRow: Identifiable, Hashable {
    var id: Int { bill.id }
    let bill: Bill
    /// Day of the month for monthly, quarterly, yearly and one-off bills; nil for weekly style cadences.
    let dayOfMonth: Int?
    let detail: String
    let amountText: String
}

struct BillsModel {
    /// How many weeks ahead the list covers, from this week's Monday.
    static let weeksAhead = 8
    /// How far back unpaid occurrences still count as overdue.
    static let overdueLookbackWeeks = 8

    let today: LocalDate
    let fmt: FigureFormatter
    private let input: ForecastInput

    init(input: ForecastInput, currency: String, locale: Locale = .current) {
        self.input = input
        self.today = input.today
        self.fmt = FigureFormatter(currency: currency, locale: locale)
    }

    var windowStart: LocalDate { today.mondayOfWeek }
    var windowEnd: LocalDate { windowStart.adding(days: Self.weeksAhead * 7 - 1) }

    var activeBills: [Bill] { input.bills.filter(\.isActive) }
    var pausedBills: [Bill] { input.bills.filter { !$0.isActive }.sorted { $0.name < $1.name } }

    private func row(_ o: BillOccurrence) -> MoneyBillRow {
        MoneyBillRow(occurrence: o, status: o.status(asOf: today), dateText: DateText.weekdayDayMonth(o.dueDate, locale: fmt.locale),
                     amountText: fmt.string(o.amount), symbol: o.category.symbolName)
    }

    private func occurrences(in range: DateRange) -> [BillOccurrence] {
        activeBills.flatMap { $0.occurrences(in: range, payments: input.billPayments) }
            .sorted { ($0.dueDate, $0.name, $0.billID) < ($1.dueDate, $1.name, $1.billID) }
    }

    /// Unpaid occurrences before today, oldest first.
    var overdue: [MoneyBillRow] {
        let range = DateRange(start: windowStart.adding(days: -7 * Self.overdueLookbackWeeks), end: today.adding(days: -1))
        return occurrences(in: range).filter { !$0.isPaid }.map(row)
    }

    var overdueTotal: Money { overdue.map(\.amount).total() }

    /// Paydays of active income sources from the window start, far enough ahead to close the last group.
    func paydays() -> [LocalDate] {
        let range = DateRange(start: windowStart, end: windowEnd.adding(days: 62))
        let all = input.sources.filter(\.isActive).flatMap { $0.schedule.paydays(in: range) }
        return Array(Set(all)).sorted()
    }

    /// The next 8 weeks, grouped under "Before ‹payday›". A bill due on the payday itself is in that payday's group.
    /// Overdue unpaid occurrences are left out (they sit in `overdue`). With no income sources, groups are weeks.
    var groups: [MoneyBillGroup] {
        let rows = occurrences(in: DateRange(start: windowStart, end: windowEnd))
            .filter { $0.isPaid || $0.dueDate >= today }
            .map(row)
        let paydays = paydays()
        var buckets: [(key: LocalDate, title: String, payday: LocalDate?, rows: [MoneyBillRow])] = []
        for r in rows {
            let key: LocalDate
            let title: String
            let payday: LocalDate?
            if let p = paydays.first(where: { $0 >= r.dueDate }) {
                key = p; payday = p
                title = "Before \(DateText.weekdayDayMonth(p, locale: fmt.locale))"
            } else if paydays.isEmpty {
                key = r.dueDate.mondayOfWeek; payday = nil
                title = key == windowStart ? "This week" : "Week of \(DateText.dayMonth(key, locale: fmt.locale))"
            } else {
                key = r.dueDate.mondayOfWeek; payday = nil
                title = "After the last payday: week of \(DateText.dayMonth(key, locale: fmt.locale))"
            }
            if let i = buckets.firstIndex(where: { $0.key == key && $0.payday == payday }) {
                buckets[i].rows.append(r)
            } else {
                buckets.append((key, title, payday, [r]))
            }
        }
        return buckets.sorted { $0.key < $1.key }.map { b in
            let total = b.rows.map(\.amount).total()
            return MoneyBillGroup(id: "\(b.payday == nil ? "w" : "p")-\(b.key.iso)", title: b.title, payday: b.payday,
                                  rows: b.rows, total: total, totalText: fmt.string(total))
        }
    }

    /// Every active bill with its usual day or cadence. Bills that fall on a day of the month come first, ordered by
    /// that day; weekly style bills follow, by name.
    var monthly: [MonthlyBillRow] {
        let rows = activeBills.map { bill -> MonthlyBillRow in
            let day: Int?
            let detail: String
            switch bill.cadence {
            case .monthly(let dom):
                day = dom; detail = "Day \(dom) of each month"
            case .quarterly:
                day = bill.anchorDate.day; detail = "Day \(bill.anchorDate.day), every 3 months"
            case .yearly:
                day = bill.anchorDate.day; detail = "Every year on \(DateText.dayMonth(bill.anchorDate, locale: fmt.locale))"
            case .oneOff:
                day = bill.anchorDate.day; detail = "Once, \(DateText.full(bill.anchorDate, locale: fmt.locale))"
            case .weekly:
                day = nil; detail = "Every week, \(Self.weekdayName(bill.anchorDate))"
            case .fortnightly:
                day = nil; detail = "Every 2 weeks, \(Self.weekdayName(bill.anchorDate))"
            case .everyNDays(let n):
                day = nil; detail = "Every \(n) days"
            }
            return MonthlyBillRow(bill: bill, dayOfMonth: day, detail: detail, amountText: fmt.string(bill.amount))
        }
        return rows.sorted {
            switch ($0.dayOfMonth, $1.dayOfMonth) {
            case let (a?, b?): (a, $0.bill.name, $0.id) < (b, $1.bill.name, $1.id)
            case (_?, nil): true
            case (nil, _?): false
            case (nil, nil): ($0.bill.name, $0.id) < ($1.bill.name, $1.id)
            }
        }
    }

    private static func weekdayName(_ d: LocalDate) -> String { EditorLabels.weekdayName(d.weekday) }

    var isEmpty: Bool { input.bills.isEmpty }
}

// MARK: - Spending

struct WeekSpendRow: Identifiable, Hashable {
    var id: String { weekStart.iso }
    let weekStart: LocalDate
    let spent: Money
    let budget: Money
    let variance: Money
    let hasData: Bool
    let hasTotal: Bool
    let isCurrent: Bool
    /// Running total of variance up to and including this week. Nil for the current week and for weeks before any data.
    let running: Money?
}

struct SpendingModel {
    static let historyWeeks = 12

    let today: LocalDate
    let fmt: FigureFormatter
    let budget: Money
    private let input: ForecastInput

    init(input: ForecastInput, currency: String, locale: Locale = .current) {
        self.input = input
        self.today = input.today
        self.budget = input.livingBudget
        self.fmt = FigureFormatter(currency: currency, locale: locale)
    }

    var currentWeekStart: LocalDate { today.mondayOfWeek }

    func spent(weekStart: LocalDate) -> Money {
        Spending.spent(weekStart: weekStart, expenses: input.expenses, totals: input.weekSpendTotals)
    }

    func total(weekStart: LocalDate) -> Money? {
        input.weekSpendTotals.first { $0.weekStart.mondayOfWeek == weekStart.mondayOfWeek }?.amount
    }

    func expenses(weekStart: LocalDate) -> [Expense] {
        let week = weekStart.mondayOfWeek.week
        return input.expenses.filter { week.contains($0.date) }.sorted { ($0.date, $0.id) > ($1.date, $1.id) }
    }

    var spentThisWeek: Money { spent(weekStart: currentWeekStart) }
    var leftThisWeek: Money { budget - spentThisWeek }
    var varianceThisWeek: Money { Spending.variance(budget: budget, spent: spentThisWeek) }

    /// The last 12 weeks, newest first. The running total counts completed weeks that have recorded spending (a week
    /// with nothing logged is unknown, not a saving). It starts at the oldest listed week.
    var history: [WeekSpendRow] {
        let starts = (0..<Self.historyWeeks).map { currentWeekStart.adding(days: -7 * (Self.historyWeeks - 1 - $0)) }
        var running = Money.zero
        var rows: [WeekSpendRow] = []
        for start in starts {
            let spent = spent(weekStart: start)
            let hasData = Spending.hasData(weekStart: start, expenses: input.expenses, totals: input.weekSpendTotals)
            let isCurrent = start == currentWeekStart
            let variance = Spending.variance(budget: budget, spent: spent)
            if hasData && !isCurrent { running += variance }
            rows.append(WeekSpendRow(weekStart: start, spent: spent, budget: budget, variance: variance, hasData: hasData,
                                     hasTotal: total(weekStart: start) != nil, isCurrent: isCurrent,
                                     running: isCurrent ? nil : running))
        }
        return rows.reversed()
    }

    /// Sum of variance over completed weeks with spending in the last 12 weeks.
    var runningVariance: Money { history.first(where: { !$0.isCurrent })?.running ?? .zero }

    func varianceText(_ variance: Money) -> String {
        switch variance.cents {
        case 1...: "\(fmt.string(variance)) under"
        case ..<0: "\(fmt.string(-variance)) over"
        default: "On budget"
        }
    }

    func weekTitle(_ start: LocalDate) -> String {
        start == currentWeekStart ? "This week" : "Week of \(DateText.dayMonth(start, locale: fmt.locale))"
    }
}

// MARK: - Goals

struct GoalRow: Identifiable, Hashable {
    var id: Int { goal.id }
    let goal: Goal
    let saved: Money
    let progressBasisPoints: Int
    let isPrimary: Bool
    let deposits: [Deposit]
    let savedText: String
    let targetText: String
    let deadlineText: String?
    let percentText: String

    var progress: Double { Double(progressBasisPoints) / 10_000 }
}

struct GoalsModel {
    let fmt: FigureFormatter
    private let input: ForecastInput

    init(input: ForecastInput, currency: String, locale: Locale = .current) {
        self.input = input
        self.fmt = FigureFormatter(currency: currency, locale: locale)
    }

    /// The primary goal's id: the chosen one, else the first goal (as the forecast does).
    var primaryID: Int? {
        if let id = input.primaryGoalID, input.goals.contains(where: { $0.id == id }) { return id }
        return input.goals.first?.id
    }

    var rows: [GoalRow] {
        input.goals.map { goal in
            let bp = goal.progressBasisPoints(deposits: input.deposits)
            let saved = goal.saved(deposits: input.deposits)
            return GoalRow(goal: goal, saved: saved, progressBasisPoints: bp, isPrimary: goal.id == primaryID,
                           deposits: input.deposits.filter { $0.goalID == goal.id }.sorted { ($0.date, $0.id) > ($1.date, $1.id) },
                           savedText: fmt.string(saved), targetText: fmt.string(goal.target),
                           deadlineText: goal.deadline.map { "by \(DateText.full($0, locale: fmt.locale))" },
                           percentText: "\(bp / 100)%")
        }
    }
}

// MARK: - Writes

/// Every write the Money tab makes, kept out of the views so tests can drive them.
struct MoneyActions {
    let repository: Repository

    func markPaid(billID: Int, dueDate: LocalDate, paidDate: LocalDate, amount: Money) throws {
        try repository.markPaid(BillPayment(billID: billID, dueDate: dueDate, paidDate: paidDate, amount: amount))
    }

    func unmarkPaid(billID: Int, dueDate: LocalDate) throws {
        try repository.unmarkPaid(billID: billID, dueDate: dueDate)
    }

    func setActive(_ bill: Bill, _ active: Bool) throws {
        var b = bill
        b.isActive = active
        try repository.save(b)
    }

    func setWeekTotal(weekStart: LocalDate, amount: Money) throws {
        try repository.setWeekSpendTotal(weekStart: weekStart, amount: amount)
    }

    func clearWeekTotal(weekStart: LocalDate) throws { try repository.clearWeekSpendTotal(weekStart: weekStart) }

    func save(_ expense: Expense) throws { try repository.save(expense) }

    func deleteExpense(_ id: Int) throws { try repository.deleteExpense(id: id) }

    func addDeposit(goalID: Int, date: LocalDate, amount: Money, note: String) throws {
        try repository.save(Deposit(goalID: goalID, date: date, amount: amount,
                                    note: note.trimmingCharacters(in: .whitespacesAndNewlines)))
    }

    func deleteDeposit(_ id: Int) throws { try repository.deleteDeposit(id: id) }

    func makePrimary(_ goalID: Int) throws { try repository.setPrimaryGoalID(goalID) }
}

// MARK: - Sheet form models

/// The "Mark paid" sheet: amount pre-filled with the planned amount, date defaulting to today.
struct MarkPaidForm {
    let row: MoneyBillRow
    var amountText: String
    var paidDate: LocalDate

    init(row: MoneyBillRow, today: LocalDate) {
        self.row = row
        amountText = MoneyParser.text(fromCents: row.amount.cents)
        paidDate = today
    }

    var cents: Int? { MoneyParser.cents(from: amountText).flatMap { $0 > 0 ? $0 : nil } }
    var isValid: Bool { cents != nil }

    func save(using actions: MoneyActions) throws {
        guard let cents else { throw EditorError.invalid }
        try actions.markPaid(billID: row.billID, dueDate: row.dueDate, paidDate: paidDate, amount: Money(cents: cents))
    }
}

struct DepositForm {
    let goalID: Int
    var amountText = ""
    var date: LocalDate
    var note = ""

    init(goalID: Int, today: LocalDate) {
        self.goalID = goalID
        date = today
    }

    var cents: Int? { MoneyParser.cents(from: amountText).flatMap { $0 > 0 ? $0 : nil } }
    var isValid: Bool { cents != nil }

    func save(using actions: MoneyActions) throws {
        guard let cents else { throw EditorError.invalid }
        try actions.addDeposit(goalID: goalID, date: date, amount: Money(cents: cents), note: note)
    }
}

struct WeekTotalForm {
    var weekStart: LocalDate
    var amountText: String

    init(weekStart: LocalDate, existing: Money?) {
        self.weekStart = weekStart
        amountText = existing.map { MoneyParser.text(fromCents: $0.cents) } ?? ""
    }

    /// A total may be zero (nothing spent), but not negative or blank.
    var cents: Int? { MoneyParser.cents(from: amountText) }
    var isValid: Bool { cents != nil }

    func save(using actions: MoneyActions) throws {
        guard let cents else { throw EditorError.invalid }
        try actions.setWeekTotal(weekStart: weekStart, amount: Money(cents: cents))
    }
}

struct ExpenseForm {
    let id: Int
    var amountText: String
    var category: SpendCategory
    var date: LocalDate
    var note: String

    init(expense: Expense) {
        id = expense.id
        amountText = MoneyParser.text(fromCents: expense.amount.cents)
        category = expense.category
        date = expense.date
        note = expense.note
    }

    var cents: Int? { MoneyParser.cents(from: amountText).flatMap { $0 > 0 ? $0 : nil } }
    var isValid: Bool { cents != nil }

    func save(using actions: MoneyActions) throws {
        guard let cents else { throw EditorError.invalid }
        try actions.save(Expense(id: id, date: date, amount: Money(cents: cents), category: category,
                                 note: note.trimmingCharacters(in: .whitespacesAndNewlines)))
    }
}
