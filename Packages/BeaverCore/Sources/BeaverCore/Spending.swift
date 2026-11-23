import Foundation

public enum SpendCategory: String, Sendable, Codable, Hashable, CaseIterable {
    case food, transport, coffeeDrinks, goingOut, shopping, other
}

/// A quick spending entry.
public struct Expense: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var date: LocalDate
    public var amount: Money
    public var category: SpendCategory
    public var note: String

    public init(id: Int = 0, date: LocalDate, amount: Money, category: SpendCategory = .other, note: String = "") {
        self.id = id
        self.date = date
        self.amount = amount
        self.category = category
        self.note = note
    }
}

/// One total for a whole week, replacing that week's entries.
public struct WeekSpendTotal: Sendable, Codable, Hashable {
    public var weekStart: LocalDate
    public var amount: Money

    public init(weekStart: LocalDate, amount: Money) {
        self.weekStart = weekStart
        self.amount = amount
    }
}

/// Weekly living spending maths.
public enum Spending {
    /// Spent in the week containing `weekStart`: the week total if set, otherwise the sum of entries.
    public static func spent(weekStart: LocalDate, expenses: [Expense], totals: [WeekSpendTotal]) -> Money {
        let monday = weekStart.mondayOfWeek
        if let t = totals.first(where: { $0.weekStart.mondayOfWeek == monday }) { return t.amount }
        let week = monday.week
        return expenses.filter { week.contains($0.date) }.map(\.amount).total()
    }

    /// Whether any spending was recorded for the week.
    public static func hasData(weekStart: LocalDate, expenses: [Expense], totals: [WeekSpendTotal]) -> Bool {
        let monday = weekStart.mondayOfWeek
        let week = monday.week
        return totals.contains { $0.weekStart.mondayOfWeek == monday } || expenses.contains { week.contains($0.date) }
    }

    /// Budget minus spent. Positive means under budget.
    public static func variance(budget: Money, spent: Money) -> Money { budget - spent }
}
