import SwiftUI

/// Displays an amount in integer cents using a currency code (for example "AUD").
struct MoneyText: View {
    let cents: Int
    let currencyCode: String

    init(cents: Int, currencyCode: String = "AUD") {
        self.cents = cents
        self.currencyCode = currencyCode
    }

    static func format(cents: Int, currencyCode: String) -> String {
        let amount = Decimal(cents) / 100
        return amount.formatted(.currency(code: currencyCode))
    }

    var body: some View {
        Text(Self.format(cents: cents, currencyCode: currencyCode))
            .monospacedDigit()
    }
}

#Preview { MoneyText(cents: 123_456) }
