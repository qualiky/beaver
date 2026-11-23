import Foundation
import BeaverCore
import GRDB

struct BillRecord: BeaverRecord {
    static let databaseTableName = "bill"
    var id: Int?
    var name: String
    var amountCents: Int
    var cadenceJson: Cadence
    var anchorDate: String
    var category: String
    var account: String?
    var active: Bool
    var note: String

    init(_ b: Bill) {
        id = recordID(b.id); name = b.name; amountCents = b.amount.cents; cadenceJson = b.cadence
        anchorDate = b.anchorDate.iso; category = b.category.rawValue; account = b.account; active = b.isActive; note = b.note
    }
    func core() throws -> Bill {
        Bill(id: id ?? 0, name: name, amount: Money(cents: amountCents), cadence: cadenceJson,
             anchorDate: try parseDate(anchorDate), category: try parseEnum(category, as: BillCategory.self),
             account: account, isActive: active, note: note)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct BillPaymentRecord: BeaverRecord {
    static let databaseTableName = "bill_payment"
    var id: Int?
    var billId: Int
    var dueDate: String
    var paidDate: String
    var amountCents: Int

    init(_ p: BillPayment) {
        id = nil; billId = p.billID; dueDate = p.dueDate.iso; paidDate = p.paidDate.iso; amountCents = p.amount.cents
    }
    func core() throws -> BillPayment {
        BillPayment(billID: billId, dueDate: try parseDate(dueDate), paidDate: try parseDate(paidDate),
                    amount: Money(cents: amountCents))
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct ExpenseRecord: BeaverRecord {
    static let databaseTableName = "expense"
    var id: Int?
    var date: String
    var amountCents: Int
    var category: String
    var note: String

    init(_ e: Expense) {
        id = recordID(e.id); date = e.date.iso; amountCents = e.amount.cents; category = e.category.rawValue; note = e.note
    }
    func core() throws -> Expense {
        Expense(id: id ?? 0, date: try parseDate(date), amount: Money(cents: amountCents),
                category: try parseEnum(category, as: SpendCategory.self), note: note)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct WeekSpendTotalRecord: BeaverRecord {
    static let databaseTableName = "week_spend_total"
    var weekStart: String
    var amountCents: Int

    init(_ t: WeekSpendTotal) { weekStart = t.weekStart.iso; amountCents = t.amount.cents }
    func core() throws -> WeekSpendTotal {
        WeekSpendTotal(weekStart: try parseDate(weekStart), amount: Money(cents: amountCents))
    }
}

struct GoalRecord: BeaverRecord {
    static let databaseTableName = "goal"
    var id: Int?
    var name: String
    var targetCents: Int
    var deadline: String?

    init(_ g: Goal) { id = recordID(g.id); name = g.name; targetCents = g.target.cents; deadline = g.deadline?.iso }
    func core() throws -> Goal {
        Goal(id: id ?? 0, name: name, target: Money(cents: targetCents), deadline: try deadline.map(parseDate))
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}

struct DepositRecord: BeaverRecord {
    static let databaseTableName = "deposit"
    var id: Int?
    var goalId: Int
    var date: String
    var amountCents: Int
    var note: String

    init(_ d: Deposit) {
        id = recordID(d.id); goalId = d.goalID; date = d.date.iso; amountCents = d.amount.cents; note = d.note
    }
    func core() throws -> Deposit {
        Deposit(id: id ?? 0, goalID: goalId, date: try parseDate(date), amount: Money(cents: amountCents), note: note)
    }
    mutating func didInsert(_ inserted: InsertionSuccess) { id = Int(inserted.rowID) }
}
