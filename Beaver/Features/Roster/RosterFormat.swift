import Foundation
import SwiftUI
import BeaverCore

/// Pure formatting and parsing helpers for the Roster tab. No state, so they are easy to test.
@MainActor
enum RosterFormat {
    /// A short chip label for a rate-card band name: Day, Eve, Night, Sat, Sun, PH.
    /// Unknown names fall back to the first word, shortened to 7 characters.
    static func chip(forBand band: String) -> String {
        let name = band.lowercased()
        // Order matters: "holiday", "saturday" and "sunday" all contain "day".
        if name.contains("holiday") { return "PH" }
        if name.contains("saturday") || name.hasPrefix("sat") { return "Sat" }
        if name.contains("sunday") || name.hasPrefix("sun") { return "Sun" }
        if name.contains("evening") || name.hasPrefix("eve") { return "Eve" }
        if name.contains("night") { return "Night" }
        if name.contains("day") { return "Day" }
        let first = band.split(separator: " ").first.map(String.init) ?? band
        return String(first.prefix(7))
    }

    /// Hours from hundredths without trailing zeros: 3650 is "36.5 h", 3600 is "36 h".
    static func hours(hundredths: Int) -> String {
        let whole = hundredths / 100
        let frac = abs(hundredths % 100)
        if frac == 0 { return "\(whole) h" }
        let text = frac % 10 == 0 ? "\(frac / 10)" : String(format: "%02d", frac)
        return "\(whole).\(text) h"
    }

    /// Paid minutes as hours, rounded half up to hundredths.
    static func hours(minutes: Int) -> String { hours(hundredths: (minutes * 100 + 30) / 60) }

    /// Whole hours and minutes, e.g. 1350 is "22 h 30 m".
    static func hoursAndMinutes(_ minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        return m == 0 ? "\(h) h" : "\(h) h \(m) m"
    }

    static func money(_ money: Money, currency: String) -> String {
        MoneyText.format(cents: money.cents, currencyCode: currency)
    }

    /// Money with an explicit sign: "+$12.30", "-$4.00", "$0.00".
    static func signedMoney(_ money: Money, currency: String) -> String {
        let text = MoneyText.format(cents: abs(money.cents), currencyCode: currency)
        if money.cents > 0 { return "+" + text }
        if money.cents < 0 { return "-" + text }
        return text
    }

    /// Parses what a person types into an amount field: "1,234.50", "$12", "12,5". Nil if empty or invalid.
    static func parseMoney(_ text: String) -> Money? {
        var s = text.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "$", with: "")
            .replacingOccurrences(of: " ", with: "")
        guard !s.isEmpty else { return nil }
        if s.contains(","), !s.contains("."), let comma = s.lastIndex(of: ",") {
            let after = s.distance(from: comma, to: s.endIndex) - 1
            if after <= 2 { s = s.replacingOccurrences(of: ",", with: ".") }
        }
        s = s.replacingOccurrences(of: ",", with: "")
        guard let value = Decimal(string: s, locale: Locale(identifier: "en_US_POSIX")), value >= 0 else { return nil }
        var scaled = value * 100
        var rounded = Decimal()
        NSDecimalRound(&rounded, &scaled, 0, .plain)
        return Money(cents: NSDecimalNumber(decimal: rounded).intValue)
    }

    /// Money as plain editable text ("1234.50"), or empty for nil.
    static func editableText(_ money: Money?) -> String {
        guard let money else { return "" }
        let sign = money.cents < 0 ? "-" : ""
        let c = abs(money.cents)
        return "\(sign)\(c / 100).\(String(format: "%02d", c % 100))"
    }

    // MARK: Dates and times

    private static func referenceDate(hour: Int, minute: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        return calendar.date(from: DateComponents(year: 2001, month: 1, day: 2, hour: hour, minute: minute))
            ?? Date(timeIntervalSinceReferenceDate: 0)
    }

    /// A clock time as a localized string ("6:00 pm" or "18:00").
    static func time(_ t: LocalTime) -> String {
        referenceDate(hour: t.hour, minute: t.minute).formatted(date: .omitted, time: .shortened)
    }

    static func timeAsDate(_ t: LocalTime) -> Date { referenceDate(hour: t.hour, minute: t.minute) }

    static func localTime(from date: Date) -> LocalTime {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let c = calendar.dateComponents([.hour, .minute], from: date)
        return LocalTime(hour: c.hour ?? 0, minute: c.minute ?? 0)
    }

    static func localDate(from date: Date, in timeZone: TimeZone) -> LocalDate {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return LocalDate(year: c.year ?? 2000, month: c.month ?? 1, day: c.day ?? 1)
    }

    /// "Wed 2 Dec" (locale order).
    static func day(_ date: LocalDate, in timeZone: TimeZone) -> String {
        date.date(in: timeZone).formatted(Date.FormatStyle(timeZone: timeZone).weekday(.abbreviated).day().month(.abbreviated))
    }

    /// "2 Dec".
    static func shortDate(_ date: LocalDate, in timeZone: TimeZone) -> String {
        date.date(in: timeZone).formatted(Date.FormatStyle(timeZone: timeZone).day().month(.abbreviated))
    }

    /// "23 Nov - 29 Nov".
    static func weekTitle(_ weekStart: LocalDate, in timeZone: TimeZone) -> String {
        "\(shortDate(weekStart, in: timeZone)) \u{2013} \(shortDate(weekStart.adding(days: 6), in: timeZone))"
    }

    static func warningText(_ w: HoursWarning, sourceName: String?, timeZone: TimeZone) -> String {
        let worked = hoursAndMinutes(w.workedMinutes)
        switch w.scope {
        case .weekly:
            let kind = w.level == .hard ? "hard" : "soft"
            return "\(sourceName ?? "This source"): \(worked) this week, over the \(kind) weekly limit of \(w.limitHours) h."
        case .fortnight:
            let from = shortDate(w.period.start, in: timeZone)
            let to = shortDate(w.period.end, in: timeZone)
            return "All sources: \(worked) in the fortnight \(from) to \(to), over the limit of \(w.limitHours) h."
        }
    }
}

/// A small capsule label.
struct Chip: View {
    enum Style { case band, holiday, neutral }
    let text: String
    var style: Style = .band
    var systemImage: String?

    var body: some View {
        HStack(spacing: 3) {
            if let systemImage { Image(systemName: systemImage).imageScale(.small) }
            Text(text)
        }
        .font(.caption2.weight(.semibold))
        .padding(.horizontal, 7)
        .padding(.vertical, 3)
        .foregroundStyle(foreground)
        .background(background, in: Capsule())
    }

    private var foreground: Color {
        switch style {
        case .band: .accentColor
        case .holiday: .orange
        case .neutral: .secondary
        }
    }

    private var background: Color {
        switch style {
        case .band: Color.accentColor.opacity(0.14)
        case .holiday: Color.orange.opacity(0.16)
        case .neutral: Color.secondary.opacity(0.14)
        }
    }
}

/// Lays children out left to right and wraps onto new lines, so chips stay readable at large Dynamic Type sizes.
struct FlowLayout: Layout {
    var spacing: CGFloat = 4

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? .infinity, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = arrange(width: bounds.width, subviews: subviews)
        for (i, origin) in result.origins.enumerated() {
            subviews[i].place(at: CGPoint(x: bounds.minX + origin.x, y: bounds.minY + origin.y), proposal: .unspecified)
        }
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> (size: CGSize, origins: [CGPoint]) {
        var origins: [CGPoint] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            origins.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (CGSize(width: maxX, height: y + rowHeight), origins)
    }
}
