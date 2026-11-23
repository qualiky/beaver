import SwiftUI
import BeaverCore

/// Parses and formats user-typed amounts as integer cents. Never uses `Double`.
///
/// Accepted: "12", "12.5", "12.50", "1,234.56", ".5", "12.", "$12". The decimal separator is a dot, and a comma is a
/// thousands separator only when it groups three digits. Rejected: empty text, signs, more than two decimals,
/// letters, bad grouping, and amounts with more than 12 whole digits.
enum MoneyParser {
    static func cents(from text: String) -> Int? {
        var t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.hasPrefix("$") { t.removeFirst(); t = t.trimmingCharacters(in: .whitespaces) }
        guard !t.isEmpty else { return nil }

        let parts = t.split(separator: ".", maxSplits: 1, omittingEmptySubsequences: false)
        let wholeText = String(parts[0])
        let fractionText = parts.count == 2 ? String(parts[1]) : ""
        guard wholeText.isEmpty == false || fractionText.isEmpty == false else { return nil }
        guard fractionText.count <= 2, fractionText.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        guard let whole = wholeDigits(wholeText) else { return nil }

        let fraction = fractionText.isEmpty ? 0 : (Int(fractionText) ?? 0) * (fractionText.count == 1 ? 10 : 1)
        return whole * 100 + fraction
    }

    /// The whole part with valid comma grouping removed. Empty text is 0 (for ".5").
    private static func wholeDigits(_ text: String) -> Int? {
        if text.isEmpty { return 0 }
        let digits: String
        if text.contains(",") {
            let groups = text.split(separator: ",", omittingEmptySubsequences: false).map(String.init)
            guard let first = groups.first, (1...3).contains(first.count),
                  groups.dropFirst().allSatisfy({ $0.count == 3 }) else { return nil }
            digits = groups.joined()
        } else {
            digits = text
        }
        guard digits.count <= 12, digits.allSatisfy({ $0.isASCII && $0.isNumber }) else { return nil }
        return Int(digits)
    }

    /// Plain editable text for an amount: 1250 becomes "12.50".
    static func text(fromCents cents: Int) -> String {
        let sign = cents < 0 ? "-" : ""
        let a = abs(cents)
        return "\(sign)\(a / 100).\(String(format: "%02d", a % 100))"
    }

    /// The currency symbol for an ISO code, e.g. "$" for AUD.
    static func symbol(for currencyCode: String) -> String {
        let f = NumberFormatter()
        f.numberStyle = .currency
        f.currencyCode = currencyCode
        return f.currencySymbol ?? currencyCode
    }

    /// Percentage text such as "12.5" as basis points (1250). Same rules as money (two decimals).
    static func basisPoints(fromPercent text: String) -> Int? {
        text.contains("$") ? nil : cents(from: text)
    }
}

/// A currency text field. The binding holds the raw text; parse it with `MoneyParser.cents(from:)`.
struct MoneyField: View {
    let title: LocalizedStringKey
    @Binding var text: String
    var currencyCode: String
    var suffix: LocalizedStringKey?

    init(_ title: LocalizedStringKey, text: Binding<String>, currencyCode: String = "AUD", suffix: LocalizedStringKey? = nil) {
        self.title = title
        self._text = text
        self.currencyCode = currencyCode
        self.suffix = suffix
    }

    var body: some View {
        LabeledContent {
            HStack(spacing: 4) {
                Text(MoneyParser.symbol(for: currencyCode)).foregroundStyle(.secondary).accessibilityHidden(true)
                TextField("0.00", text: $text)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .monospacedDigit()
                    .accessibilityLabel(title)
                if let suffix { Text(suffix).foregroundStyle(.secondary).accessibilityHidden(true) }
            }
        } label: {
            Text(title)
        }
    }
}

#Preview("MoneyField") {
    @Previewable @State var text = "1,234.56"
    Form {
        MoneyField("Amount", text: $text)
        MoneyField("Rate", text: $text, suffix: "/ hr")
    }
}
