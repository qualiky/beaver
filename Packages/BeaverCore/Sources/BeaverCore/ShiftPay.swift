import Foundation

/// Lifecycle of a shift. Cancelled shifts earn nothing.
public enum ShiftStatus: String, Sendable, Codable, Hashable, CaseIterable {
    case rostered, worked, cancelled
}

/// A single shift. If `end <= start` it finishes the next day. Times are wall-clock, never UTC.
public struct Shift: Sendable, Codable, Hashable, Identifiable {
    public var id: Int
    public var sourceID: Int
    public var date: LocalDate
    public var start: LocalTime
    public var end: LocalTime
    public var breakMinutes: Int
    public var status: ShiftStatus
    public var note: String

    public init(id: Int = 0, sourceID: Int, date: LocalDate, start: LocalTime, end: LocalTime,
                breakMinutes: Int = 0, status: ShiftStatus = .rostered, note: String = "") {
        self.id = id
        self.sourceID = sourceID
        self.date = date
        self.start = start
        self.end = end
        self.breakMinutes = breakMinutes
        self.status = status
        self.note = note
    }

    public var crossesMidnight: Bool { end <= start }

    /// Total minutes from start to end (24h if start equals end).
    public var durationMinutes: Int {
        crossesMidnight ? 1440 - start.minutesSinceMidnight + end.minutesSinceMidnight
                        : end.minutesSinceMidnight - start.minutesSinceMidnight
    }

    /// The break clamped into `0...duration`.
    public var effectiveBreakMinutes: Int { min(max(breakMinutes, 0), durationMinutes) }

    /// Minutes that are paid: duration minus the unpaid break.
    public var paidMinutes: Int { durationMinutes - effectiveBreakMinutes }

    /// The date the shift finishes on.
    public var endDate: LocalDate { crossesMidnight ? date.adding(days: 1) : date }
}

/// Time and pay attributed to one band within a shift.
public struct BandPay: Sendable, Codable, Hashable {
    public var band: String
    public var rateCentsPerHour: Int
    /// Raw minutes of the shift that fell in this band (break not removed).
    public var minutes: Int
    /// Paid hours = `paidNumerator / paidDenominator / 60`-style fraction kept exact: `minutes * paidMinutes` over `duration * 60`.
    public var paidNumerator: Int
    public var paidDenominator: Int

    /// Paid hours in hundredths, rounded half up (for display).
    public var hoursHundredths: Int {
        IntMath.roundHalfUp(paidNumerator * 100, paidDenominator)
    }
}

/// The priced result of one shift.
public struct ShiftPayResult: Sendable, Codable, Hashable {
    public var durationMinutes: Int
    public var breakMinutes: Int
    public var paidMinutes: Int
    /// Bands with at least one minute, in rate-card order (unmatched minutes appear last as "Unrated").
    public var bands: [BandPay]
    public var gross: Money

    public var paidHoursHundredths: Int { IntMath.roundHalfUp(paidMinutes * 100, 60) }
}

/// Splits a shift minute by minute into rate bands and prices it with exact integer maths.
public enum ShiftPay {
    /// Name used for minutes no rule covers (priced at zero).
    public static let unratedBand = "Unrated"

    /// Prices `shift`. Each minute takes the band of the calendar date and clock time it falls on;
    /// holiday dates are looked up per minute. Gross is
    /// `roundHalfUp(sum(rate * bandMinutes) * paidMinutes / (duration * 60))`.
    public static func calculate(shift: Shift, rateCard: RateCard, holidays: Set<LocalDate>) -> ShiftPayResult {
        let duration = shift.durationMinutes
        let brk = shift.effectiveBreakMinutes
        let paid = duration - brk
        let ruleCount = rateCard.rules.count
        var minutesByRule = [Int](repeating: 0, count: ruleCount + 1)

        var currentOffset = -1
        var weekday = shift.date.weekday
        var isHoliday = false
        let startMinute = shift.start.minutesSinceMidnight
        for m in 0..<duration {
            let absolute = startMinute + m
            let offset = absolute / 1440
            if offset != currentOffset {
                currentOffset = offset
                let date = shift.date.adding(days: offset)
                weekday = date.weekday
                isHoliday = holidays.contains(date)
            }
            let index = rateCard.ruleIndex(weekday: weekday, isHoliday: isHoliday, minute: absolute % 1440) ?? ruleCount
            minutesByRule[index] += 1
        }

        var weighted = 0
        for (i, rule) in rateCard.rules.enumerated() { weighted += rule.centsPerHour * minutesByRule[i] }
        let gross = Money(cents: IntMath.roundHalfUp(weighted * paid, duration * 60))

        var bands: [BandPay] = []
        for name in rateCard.bandNames {
            var minutes = 0
            var rate: Int?
            for (i, rule) in rateCard.rules.enumerated() where rule.band == name {
                minutes += minutesByRule[i]
                if rate == nil { rate = rule.centsPerHour }
            }
            if minutes > 0 {
                bands.append(BandPay(band: name, rateCentsPerHour: rate ?? 0, minutes: minutes,
                                     paidNumerator: minutes * paid, paidDenominator: duration * 60))
            }
        }
        if minutesByRule[ruleCount] > 0 {
            bands.append(BandPay(band: unratedBand, rateCentsPerHour: 0, minutes: minutesByRule[ruleCount],
                                 paidNumerator: minutesByRule[ruleCount] * paid, paidDenominator: duration * 60))
        }
        return ShiftPayResult(durationMinutes: duration, breakMinutes: brk, paidMinutes: paid, bands: bands, gross: gross)
    }
}

/// Paid hours of one band summed over a pay period.
public struct BandHours: Sendable, Codable, Hashable {
    public var band: String
    public var rateCentsPerHour: Int
    /// Paid hours in hundredths, rounded half up from the exact total.
    public var hoursHundredths: Int
}

/// Hours, gross, tax and net for a set of shifts in one pay period.
public struct PeriodPay: Sendable, Codable, Hashable {
    public var paidMinutes: Int
    public var bands: [BandHours]
    public var gross: Money
    public var tax: Money
    public var net: Money

    public init(paidMinutes: Int = 0, bands: [BandHours] = [], gross: Money = .zero, tax: Money = .zero, net: Money = .zero) {
        self.paidMinutes = paidMinutes
        self.bands = bands
        self.gross = gross
        self.tax = tax
        self.net = net
    }

    /// Total paid hours in hundredths (3675 is 36.75 hours).
    public var hoursHundredths: Int { IntMath.roundHalfUp(paidMinutes * 100, 60) }

    /// Prices all non-cancelled shifts and applies tax once on the period gross.
    public static func calculate(shifts: [Shift], rateCard: RateCard?, holidays: Set<LocalDate>,
                                 taxMethod: TaxMethod = .none, frequency: PayFrequency = .weekly,
                                 tables: [WithholdingTable] = []) -> PeriodPay {
        guard let rateCard else { return PeriodPay() }
        var paidMinutes = 0
        var gross = Money.zero
        var order: [String] = rateCard.bandNames + [ShiftPay.unratedBand]
        var sums: [String: Decimal] = [:]
        var rates: [String: Int] = [:]
        for shift in shifts where shift.status != .cancelled {
            let r = ShiftPay.calculate(shift: shift, rateCard: rateCard, holidays: holidays)
            paidMinutes += r.paidMinutes
            gross += r.gross
            for b in r.bands {
                sums[b.band, default: 0] += Decimal(b.paidNumerator) / Decimal(b.paidDenominator)
                rates[b.band] = b.rateCentsPerHour
            }
        }
        order = order.filter { sums[$0] != nil }
        let bands = order.map { name -> BandHours in
            var v = (sums[name] ?? 0) * 100
            var rounded = Decimal()
            NSDecimalRound(&rounded, &v, 0, .plain)
            return BandHours(band: name, rateCentsPerHour: rates[name] ?? 0,
                             hoursHundredths: NSDecimalNumber(decimal: rounded).intValue)
        }
        let tax = taxMethod.tax(onGross: gross, frequency: frequency, tables: tables)
        return PeriodPay(paidMinutes: paidMinutes, bands: bands, gross: gross, tax: tax, net: gross - tax)
    }
}
