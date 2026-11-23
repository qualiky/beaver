import Foundation

public enum BillCategory: String, Sendable, Codable, Hashable, CaseIterable {
    case housing, utilities, subscription, transport, debt, insurance, other
}

/// How often a bill recurs.
public enum Cadence: Sendable, Codable, Hashable {
    case weekly
    case fortnightly
    case everyNDays(Int)
    /// Due on `dayOfMonth` each month (clamped to the month end) after the anchor occurrence.
    case monthly(dayOfMonth: Int)
    /// Every 3 months from the anchor, keeping the anchor's day of month (clamped).
    case quarterly
    /// Every 12 months from the anchor (29 Feb clamps to 28 Feb).
    case yearly
    case oneOff
}

/// A recurring (or one-off) expense. `anchorDate` is the first due date; nothing is generated before it.
public struct Bill: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var amount: Money
    public var cadence: Cadence
    public var anchorDate: LocalDate
    public var category: BillCategory
    public var account: String?
    public var isActive: Bool
    public var note: String

    public init(id: Int = 0, name: String, amount: Money, cadence: Cadence, anchorDate: LocalDate,
                category: BillCategory = .other, account: String? = nil, isActive: Bool = true, note: String = "") {
        self.id = id
        self.name = name
        self.amount = amount
        self.cadence = cadence
        self.anchorDate = anchorDate
        self.category = category
        self.account = account
        self.isActive = isActive
        self.note = note
    }

    /// The k-th due date (k = 0 is the anchor), or nil if the cadence has no such occurrence.
    func dueDate(index k: Int) -> LocalDate? {
        switch cadence {
        case .weekly: return anchorDate.adding(days: 7 * k)
        case .fortnightly: return anchorDate.adding(days: 14 * k)
        case .everyNDays(let n): return anchorDate.adding(days: max(n, 1) * k)
        case .monthly(let dom):
            if k == 0 { return anchorDate }
            let target = LocalDate(year: anchorDate.year, month: anchorDate.month, day: 1).adding(months: k)
            return LocalDate(year: target.year, month: target.month, day: min(max(dom, 1), target.daysInMonth))
        case .quarterly: return anchorDate.adding(months: 3 * k)
        case .yearly: return anchorDate.adding(months: 12 * k)
        case .oneOff: return k == 0 ? anchorDate : nil
        }
    }

    /// Due dates inside the inclusive range, never before the anchor. Ignores `isActive`.
    public func dueDates(in range: DateRange) -> [LocalDate] {
        guard !range.isEmpty, range.end >= anchorDate else { return [] }
        var k = 0
        switch cadence {
        case .weekly, .fortnightly, .everyNDays:
            let step: Int
            switch cadence {
            case .weekly: step = 7
            case .fortnightly: step = 14
            case .everyNDays(let n): step = max(n, 1)
            default: step = 1
            }
            let diff = anchorDate.days(until: range.start)
            k = diff > 0 ? (diff + step - 1) / step : 0
        default:
            k = 0
        }
        var out: [LocalDate] = []
        while let d = dueDate(index: k), d <= range.end {
            if d >= range.start { out.append(d) }
            k += 1
        }
        return out
    }

    /// Occurrences in the range with paid state applied from `payments`.
    public func occurrences(in range: DateRange, payments: [BillPayment] = []) -> [BillOccurrence] {
        dueDates(in: range).map { due in
            let payment = payments.first { $0.billID == id && $0.dueDate == due }
            return BillOccurrence(billID: id, name: name, category: category, dueDate: due,
                                  plannedAmount: amount, paidDate: payment?.paidDate, paidAmount: payment?.amount)
        }
    }

    /// Approximate cost per year, in cents (nil for one-off bills). Used for the sinking fund.
    func annualCostCents() -> Int? {
        switch cadence {
        case .weekly: return amount.cents * 52
        case .fortnightly: return amount.cents * 26
        case .everyNDays(let n): return IntMath.roundHalfUp(amount.cents * 365, max(n, 1))
        case .monthly: return amount.cents * 12
        case .quarterly: return amount.cents * 4
        case .yearly: return amount.cents
        case .oneOff: return nil
        }
    }
}

/// A logged payment of one occurrence, with the actual amount and date.
public struct BillPayment: Sendable, Codable, Hashable {
    public var billID: Int
    public var dueDate: LocalDate
    public var paidDate: LocalDate
    public var amount: Money

    public init(billID: Int, dueDate: LocalDate, paidDate: LocalDate, amount: Money) {
        self.billID = billID
        self.dueDate = dueDate
        self.paidDate = paidDate
        self.amount = amount
    }
}

public enum BillStatus: String, Sendable, Codable, Hashable {
    case paid, due, overdue
}

/// One due date of a bill.
public struct BillOccurrence: Sendable, Codable, Hashable {
    public var billID: Int
    public var name: String
    public var category: BillCategory
    public var dueDate: LocalDate
    public var plannedAmount: Money
    public var paidDate: LocalDate?
    public var paidAmount: Money?

    public init(billID: Int, name: String, category: BillCategory, dueDate: LocalDate,
                plannedAmount: Money, paidDate: LocalDate? = nil, paidAmount: Money? = nil) {
        self.billID = billID
        self.name = name
        self.category = category
        self.dueDate = dueDate
        self.plannedAmount = plannedAmount
        self.paidDate = paidDate
        self.paidAmount = paidAmount
    }

    public var isPaid: Bool { paidDate != nil }

    /// The paid amount if paid, otherwise the planned amount.
    public var amount: Money { paidAmount ?? plannedAmount }

    /// Paid, overdue (unpaid and before `today`), or due.
    public func status(asOf today: LocalDate) -> BillStatus {
        if isPaid { return .paid }
        return dueDate < today ? .overdue : .due
    }
}
