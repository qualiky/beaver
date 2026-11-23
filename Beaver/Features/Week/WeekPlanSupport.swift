import SwiftUI
import BeaverCore

// Shared formatting and styling for the Week and Plan tabs. Pure helpers, no state.

/// Formats `Money` in the user's currency. Uses the narrow symbol ("$" for AUD) so amounts stay short.
struct FigureFormatter: Sendable {
    var currency: String
    var locale: Locale

    init(currency: String, locale: Locale = .current) {
        self.currency = currency
        self.locale = locale
    }

    private func magnitude(_ cents: Int) -> String {
        (Decimal(abs(cents)) / 100).formatted(.currency(code: currency).presentation(.narrow).locale(locale))
    }

    /// "$1,234.00" or "-$5.00".
    func string(_ money: Money) -> String {
        money.cents < 0 ? "-" + magnitude(money.cents) : magnitude(money.cents)
    }

    /// "+$1.00", "-$1.00", or "$0.00" for zero.
    func signed(_ money: Money) -> String {
        switch money.cents {
        case 1...: "+" + magnitude(money.cents)
        case ..<0: "-" + magnitude(money.cents)
        default: magnitude(0)
        }
    }
}

enum DateText {
    private static let utc = TimeZone(identifier: "UTC")!

    static func date(_ day: LocalDate) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(from: DateComponents(year: day.year, month: day.month, day: day.day)) ?? Date(timeIntervalSince1970: 0)
    }

    private static func style(locale: Locale) -> Date.FormatStyle {
        Date.FormatStyle(locale: locale, calendar: Calendar(identifier: .gregorian), timeZone: utc)
    }

    /// "Wed, Nov 25" (order follows the locale).
    static func weekdayDayMonth(_ day: LocalDate, locale: Locale = .current) -> String {
        date(day).formatted(style(locale: locale).weekday(.abbreviated).day().month(.abbreviated))
    }

    /// "Nov 25".
    static func dayMonth(_ day: LocalDate, locale: Locale = .current) -> String {
        date(day).formatted(style(locale: locale).day().month(.abbreviated))
    }

    /// "Nov 25, 2026".
    static func full(_ day: LocalDate, locale: Locale = .current) -> String {
        date(day).formatted(style(locale: locale).day().month(.abbreviated).year())
    }

    static func range(_ start: LocalDate, _ end: LocalDate, locale: Locale = .current) -> String {
        "\(dayMonth(start, locale: locale)) – \(dayMonth(end, locale: locale))"
    }
}

/// Parses a typed amount ("12", "12.5", "$1,234.50") into cents without floating point.
enum MoneyInput {
    static func cents(from text: String) -> Int? {
        let cleaned = text.filter { !$0.isWhitespace && $0 != "," && $0 != "$" }
        guard !cleaned.isEmpty else { return nil }
        let parts = cleaned.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count <= 2, parts.allSatisfy({ $0.allSatisfy { $0.isASCII && $0.isNumber } }) else { return nil }
        let whole = parts[0].isEmpty ? "0" : parts[0]
        var fraction = parts.count == 2 ? parts[1] : ""
        guard fraction.count <= 2, whole.count <= 9 else { return nil }
        while fraction.count < 2 { fraction += "0" }
        guard let w = Int(whole), let f = Int(fraction) else { return nil }
        return w * 100 + f
    }
}

/// Status of a figure. Always paired with a symbol or word so colour is never the only signal.
enum Tone: Sendable, Hashable {
    case positive, negative, warning, neutral

    var color: Color {
        switch self {
        case .positive: Palette.positive
        case .negative: Palette.negative
        case .warning: Palette.warning
        case .neutral: .primary
        }
    }

    var symbol: String? {
        switch self {
        case .positive: "arrow.up.right"
        case .negative: "arrow.down.right"
        case .warning: "exclamationmark.triangle.fill"
        case .neutral: nil
        }
    }

    static func of(_ money: Money) -> Tone {
        money.cents > 0 ? .positive : money.cents < 0 ? .negative : .neutral
    }
}

/// Semantic colours with light and dark variants (system green and red are too pale as text on white).
enum Palette {
    private static func dynamic(light: (CGFloat, CGFloat, CGFloat), dark: (CGFloat, CGFloat, CGFloat)) -> Color {
        Color(uiColor: UIColor { traits in
            let c = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: c.0, green: c.1, blue: c.2, alpha: 1)
        })
    }

    static let positive = dynamic(light: (0.0, 0.42, 0.18), dark: (0.36, 0.85, 0.52))
    static let negative = dynamic(light: (0.74, 0.08, 0.10), dark: (1.0, 0.46, 0.42))
    static let warning = dynamic(light: (0.62, 0.34, 0.0), dark: (1.0, 0.74, 0.28))
}

extension SpendCategory {
    var title: String {
        switch self {
        case .food: "Food"
        case .transport: "Transport"
        case .coffeeDrinks: "Coffee & drinks"
        case .goingOut: "Going out"
        case .shopping: "Shopping"
        case .other: "Other"
        }
    }
}

extension BillCategory {
    var symbolName: String {
        switch self {
        case .housing: "house"
        case .utilities: "bolt"
        case .subscription: "play.rectangle"
        case .transport: "tram"
        case .debt: "creditcard"
        case .insurance: "umbrella"
        case .other: "doc.text"
        }
    }
}

/// A bill occurrence ready for display.
struct BillLine: Identifiable, Hashable {
    var id: String { "\(billID)-\(dueDate.iso)" }
    let billID: Int
    let name: String
    let dueDate: LocalDate
    let amount: Money
    let amountText: String
    let dateText: String
    let isPaid: Bool
    let isOverdue: Bool
    let symbol: String

    init(_ o: BillOccurrence, today: LocalDate, fmt: FigureFormatter) {
        billID = o.billID
        name = o.name
        dueDate = o.dueDate
        amount = o.amount
        amountText = fmt.string(o.amount)
        dateText = DateText.weekdayDayMonth(o.dueDate, locale: fmt.locale)
        isPaid = o.isPaid
        isOverdue = o.status(asOf: today) == .overdue
        symbol = o.category.symbolName
    }
}

/// A pay event ready for display.
struct PayLine: Identifiable, Hashable {
    var id: String { "\(sourceID)-\(payday.iso)" }
    let sourceID: Int
    let sourceName: String
    let payday: LocalDate
    let paydayText: String
    let expected: Money
    let expectedText: String
    let actualText: String?
    let differenceText: String?
    let differenceTone: Tone
    let isActual: Bool
    let fromTemplate: Bool
    let net: Money

    init(_ e: PayEvent, fmt: FigureFormatter) {
        sourceID = e.sourceID
        sourceName = e.sourceName
        payday = e.payday
        paydayText = DateText.weekdayDayMonth(e.payday, locale: fmt.locale)
        expected = e.expectedNet
        expectedText = fmt.string(e.expectedNet)
        actualText = e.actualNet.map(fmt.string)
        differenceText = e.variance.map(fmt.signed)
        differenceTone = e.variance.map(Tone.of) ?? .neutral
        isActual = e.isActual
        fromTemplate = e.usedTemplate
        net = e.net
    }
}
