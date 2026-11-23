import Foundation

/// The kinds of allocation in a suggested split, in priority order.
public enum SplitKind: String, Sendable, Codable, Hashable, CaseIterable {
    case bills, living, sinkingFund, goal
}

/// One allocation: what is needed and how much the pay can fund.
public struct SplitLine: Sendable, Codable, Hashable {
    public var kind: SplitKind
    public var needed: Money
    public var funded: Money
}

/// How to divide the next payday's money, in order: bills due before the following payday, living budget until then,
/// the bills sinking fund, the required goal amount, then buffer.
public struct SuggestedSplit: Sendable, Codable, Hashable {
    public var payday: LocalDate
    public var followingPayday: LocalDate
    public var expectedPay: Money
    public var lines: [SplitLine]
    /// Pay left over after every need is funded.
    public var buffer: Money
    /// Shortfall that would have to come out of the buffer when pay does not cover every need.
    public var fromBuffer: Money

    public func line(_ kind: SplitKind) -> SplitLine? { lines.first { $0.kind == kind } }

    /// Builds the split for the first payday on or after today. Nil if no active source has an upcoming payday pair.
    static func make(input: ForecastInput, requiredPerPayday: Money) -> SuggestedSplit? {
        let today = input.today
        let active = input.sources.filter(\.isActive)
        let lookahead = DateRange(start: today, end: today.adding(days: 120))
        let all = Set(active.flatMap { $0.schedule.paydays(in: lookahead) }).sorted()
        guard all.count >= 2 else { return nil }
        let next = all[0], following = all[1]
        let span = DateRange(start: next, end: following.adding(days: -1))
        let days = span.dayCount

        let expectedPay = active.compactMap { s -> Money? in
            s.schedule.paydays(in: DateRange(start: next, end: next)).isEmpty ? nil : input.payEvent(source: s, payday: next).net
        }.total()

        var bills = Money.zero
        var sinking = 0
        for bill in input.bills where bill.isActive {
            for o in bill.occurrences(in: span, payments: input.billPayments) where !o.isPaid { bills += o.amount }
            if let annual = bill.annualCostCents(), bill.cadence != .weekly {
                sinking += IntMath.roundHalfUp(annual * days, 365)
            }
        }
        let living = Money(cents: IntMath.roundHalfUp(input.livingBudget.cents * days, 7))
        let needs: [(SplitKind, Money)] = [(.bills, bills), (.living, living), (.sinkingFund, Money(cents: sinking)),
                                           (.goal, requiredPerPayday)]
        var remaining = max(0, expectedPay.cents)
        var lines: [SplitLine] = []
        var short = 0
        for (kind, need) in needs {
            let funded = min(remaining, max(0, need.cents))
            remaining -= funded
            short += max(0, need.cents) - funded
            lines.append(SplitLine(kind: kind, needed: need, funded: Money(cents: funded)))
        }
        return SuggestedSplit(payday: next, followingPayday: following, expectedPay: expectedPay, lines: lines,
                              buffer: Money(cents: remaining), fromBuffer: Money(cents: short))
    }
}
