import Foundation

/// A savings goal with an optional deadline.
public struct Goal: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var name: String
    public var target: Money
    public var deadline: LocalDate?

    public init(id: Int = 0, name: String, target: Money, deadline: LocalDate? = nil) {
        self.id = id
        self.name = name
        self.target = target
        self.deadline = deadline
    }

    /// Sum of deposits logged against this goal.
    public func saved(deposits: [Deposit]) -> Money {
        deposits.filter { $0.goalID == id }.map(\.amount).total()
    }

    /// Progress in basis points (10000 is complete), clamped to 0...10000.
    public func progressBasisPoints(deposits: [Deposit]) -> Int {
        guard target.cents > 0 else { return 10_000 }
        return min(10_000, max(0, saved(deposits: deposits).cents * 10_000 / target.cents))
    }
}

/// Money put towards a goal.
public struct Deposit: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var goalID: Int
    public var date: LocalDate
    public var amount: Money
    public var note: String

    public init(id: Int = 0, goalID: Int, date: LocalDate, amount: Money, note: String = "") {
        self.id = id
        self.goalID = goalID
        self.date = date
        self.amount = amount
        self.note = note
    }
}
