import Foundation

/// An amount of money in integer minor units (cents). Never uses floating point.
public struct Money: Sendable, Codable, Hashable, Comparable {
    public var cents: Int

    public init(cents: Int) { self.cents = cents }

    /// Whole dollars plus optional cents, e.g. `Money.dollars(30, cents: 50)` is $30.50.
    public static func dollars(_ dollars: Int, cents: Int = 0) -> Money {
        Money(cents: dollars * 100 + (dollars < 0 ? -cents : cents))
    }

    public static let zero = Money(cents: 0)

    public var isNegative: Bool { cents < 0 }

    public static func < (lhs: Money, rhs: Money) -> Bool { lhs.cents < rhs.cents }
    public static func + (lhs: Money, rhs: Money) -> Money { Money(cents: lhs.cents + rhs.cents) }
    public static func - (lhs: Money, rhs: Money) -> Money { Money(cents: lhs.cents - rhs.cents) }
    public static prefix func - (value: Money) -> Money { Money(cents: -value.cents) }
    public static func * (lhs: Money, rhs: Int) -> Money { Money(cents: lhs.cents * rhs) }
    public static func += (lhs: inout Money, rhs: Money) { lhs.cents += rhs.cents }
    public static func -= (lhs: inout Money, rhs: Money) { lhs.cents -= rhs.cents }

    public init(from decoder: any Decoder) throws {
        cents = try decoder.singleValueContainer().decode(Int.self)
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(cents)
    }
}

extension Sequence where Element == Money {
    /// Sum of all amounts.
    public func total() -> Money { reduce(.zero, +) }
}

/// Exact integer helpers used across the engine.
enum IntMath {
    /// Floor division (rounds toward negative infinity). `b` must not be zero.
    static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    /// `n / d` rounded half up (toward positive infinity on ties). `d` must be positive.
    static func roundHalfUp(_ n: Int, _ d: Int) -> Int {
        floorDiv(2 * n + d, 2 * d)
    }

    /// Positive modulo.
    static func mod(_ a: Int, _ b: Int) -> Int { a - floorDiv(a, b) * b }
}
