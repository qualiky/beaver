import Foundation

/// How often a pay period occurs. Withholding tables are weekly by convention.
public enum PayFrequency: String, Sendable, Codable, Hashable, CaseIterable {
    case weekly, fortnightly, monthly
}

/// One row of a withholding table: use it when the weekly `x` is below `belowDollars`.
/// Coefficients are exact integers in ten-thousandths (0.1600 is 1600), so tax is deterministic.
public struct WithholdingRow: Sendable, Codable, Hashable {
    /// Upper bound (exclusive) in whole dollars; nil means no upper bound.
    public var belowDollars: Int?
    public var aTenThousandths: Int
    public var bTenThousandths: Int

    public init(belowDollars: Int?, aTenThousandths: Int, bTenThousandths: Int) {
        self.belowDollars = belowDollars
        self.aTenThousandths = aTenThousandths
        self.bTenThousandths = bTenThousandths
    }
}

/// A coefficient table: `x = floor(grossDollars) + 0.99`, `tax = max(0, roundHalfUp(a*x - b))` in whole dollars.
public struct WithholdingTable: Sendable, Codable, Hashable, Identifiable {
    public var id: String
    public var name: String
    public var rows: [WithholdingRow]
    public var isBuiltin: Bool
    public var note: String?

    public init(id: String, name: String, rows: [WithholdingRow], isBuiltin: Bool = false, note: String? = nil) {
        self.id = id
        self.name = name
        self.rows = rows
        self.isBuiltin = isBuiltin
        self.note = note
    }

    /// Identifier of the built-in AU Scale 2 table.
    public static let auScale2WeeklyID = "au-scale2-weekly"

    /// "AU ATO Schedule 1 - Scale 2 (weekly, tax-free threshold claimed)". Verify against the current ATO schedule before relying on it.
    public static let auScale2Weekly = WithholdingTable(
        id: auScale2WeeklyID,
        name: "AU ATO Schedule 1 \u{2013} Scale 2 (weekly, tax-free threshold claimed)",
        rows: [
            WithholdingRow(belowDollars: 361, aTenThousandths: 0, bTenThousandths: 0),
            WithholdingRow(belowDollars: 500, aTenThousandths: 1600, bTenThousandths: 578_462),
            WithholdingRow(belowDollars: 625, aTenThousandths: 2600, bTenThousandths: 1_078_462),
            WithholdingRow(belowDollars: 721, aTenThousandths: 1800, bTenThousandths: 578_462),
            WithholdingRow(belowDollars: 865, aTenThousandths: 1790, bTenThousandths: 608_377),
            WithholdingRow(belowDollars: 1282, aTenThousandths: 3227, bTenThousandths: 1_800_385),
            WithholdingRow(belowDollars: 2596, aTenThousandths: 3200, bTenThousandths: 1_765_769),
            WithholdingRow(belowDollars: 3653, aTenThousandths: 3900, bTenThousandths: 3_583_077),
            WithholdingRow(belowDollars: nil, aTenThousandths: 4700, bTenThousandths: 6_506_154),
        ],
        isBuiltin: true,
        note: "Verify against the current ATO schedule before relying on it."
    )

    /// All tables shipped with the app.
    public static let builtIn: [WithholdingTable] = [auScale2Weekly]

    /// Tax for one weekly-sized period, in cents (always whole dollars).
    public func weeklyTax(onGross gross: Money) -> Money {
        let dollars = max(0, gross.cents) / 100
        guard let row = rows.first(where: { $0.belowDollars == nil || dollars < $0.belowDollars! }) else { return .zero }
        // a * x in 1e-6 dollars: a is 1e-4, x is in cents (1e-2). b is 1e-4 dollars, scaled by 100 to 1e-6.
        let x = dollars * 100 + 99
        let micro = row.aTenThousandths * x - row.bTenThousandths * 100
        let wholeDollars = max(0, IntMath.roundHalfUp(micro, 1_000_000))
        return Money(cents: wholeDollars * 100)
    }

    /// Tax for a period of the given frequency. Fortnightly applies the table to half the gross and doubles it;
    /// monthly converts to a weekly equivalent (x 12/52), applies the table, and scales back (x 52/12).
    public func tax(onGross gross: Money, frequency: PayFrequency) -> Money {
        switch frequency {
        case .weekly:
            return weeklyTax(onGross: gross)
        case .fortnightly:
            return Money(cents: weeklyTax(onGross: Money(cents: gross.cents / 2)).cents * 2)
        case .monthly:
            let weekly = Money(cents: IntMath.roundHalfUp(gross.cents * 12, 52))
            return Money(cents: IntMath.roundHalfUp(weeklyTax(onGross: weekly).cents * 52, 12))
        }
    }
}

/// How tax is taken from gross pay in each pay period.
public enum TaxMethod: Sendable, Codable, Hashable {
    /// The amount is already net.
    case none
    /// A flat percentage in basis points (1000 is 10%), rounded half up to the cent.
    case flatPercent(basisPoints: Int)
    /// A coefficient table, by id (custom tables first, then built-ins).
    case withholdingTable(id: String)

    /// Tax on a period's gross. A missing table yields zero tax.
    public func tax(onGross gross: Money, frequency: PayFrequency, tables: [WithholdingTable] = []) -> Money {
        switch self {
        case .none:
            return .zero
        case .flatPercent(let bp):
            return Money(cents: IntMath.roundHalfUp(max(0, gross.cents) * bp, 10_000))
        case .withholdingTable(let id):
            guard let table = (tables + WithholdingTable.builtIn).first(where: { $0.id == id }) else { return .zero }
            return table.tax(onGross: gross, frequency: frequency)
        }
    }
}
