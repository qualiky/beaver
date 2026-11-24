@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalTime

/** Lifecycle of a shift. Cancelled shifts earn nothing. */
@Serializable
enum class ShiftStatus { rostered, worked, cancelled }

/** A single shift. If `end <= start` it finishes the next day. Times are wall-clock, never UTC. */
@Serializable
data class Shift(
    val id: Int = 0,
    val sourceID: Int,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val breakMinutes: Int = 0,
    val status: ShiftStatus = ShiftStatus.rostered,
    val note: String = "",
) {
    val crossesMidnight: Boolean get() = end <= start

    /** Total minutes from start to end (24h if start equals end). */
    val durationMinutes: Int
        get() = if (crossesMidnight) 1440 - start.minutesSinceMidnight + end.minutesSinceMidnight
        else end.minutesSinceMidnight - start.minutesSinceMidnight

    /** The break clamped into `0..duration`. */
    val effectiveBreakMinutes: Int get() = minOf(maxOf(breakMinutes, 0), durationMinutes)

    /** Minutes that are paid: duration minus the unpaid break. */
    val paidMinutes: Int get() = durationMinutes - effectiveBreakMinutes

    /** The date the shift finishes on. */
    val endDate: LocalDate get() = if (crossesMidnight) date.plusDays(1) else date
}

/** Time and pay attributed to one band within a shift. */
data class BandPay(
    val band: String,
    val rateCentsPerHour: Int,
    /** Raw minutes of the shift that fell in this band (break not removed). */
    val minutes: Int,
    /** Paid hours as an exact fraction: `paidNumerator / paidDenominator` (`minutes * paid` over `duration * 60`). */
    val paidNumerator: Int,
    val paidDenominator: Int,
) {
    /** Paid hours in hundredths, rounded half up (for display). */
    val hoursHundredths: Int get() = IntMath.roundHalfUp(paidNumerator.toLong() * 100, paidDenominator.toLong()).toInt()
}

/** The priced result of one shift. */
data class ShiftPayResult(
    val durationMinutes: Int,
    val breakMinutes: Int,
    val paidMinutes: Int,
    /** Bands with at least one minute, in rate-card order (unmatched minutes appear last as "Unrated"). */
    val bands: List<BandPay>,
    val gross: Money,
) {
    val paidHoursHundredths: Int get() = IntMath.roundHalfUp(paidMinutes * 100, 60)
}

/** Splits a shift minute by minute into rate bands and prices it with exact integer maths. */
object ShiftPay {
    /** Name used for minutes no rule covers (priced at zero). */
    const val UNRATED_BAND = "Unrated"

    /**
     * Prices [shift]. Each minute takes the band of the calendar date and clock time it falls on;
     * holiday dates are looked up per minute. Gross is
     * `roundHalfUp(sum(rate * bandMinutes) * paidMinutes / (duration * 60))`.
     */
    fun calculate(shift: Shift, rateCard: RateCard, holidays: Set<LocalDate>): ShiftPayResult {
        val duration = shift.durationMinutes
        val brk = shift.effectiveBreakMinutes
        val paid = duration - brk
        val ruleCount = rateCard.rules.size
        val minutesByRule = IntArray(ruleCount + 1)

        var currentOffset = -1
        var weekday = shift.date.dayOfWeek
        var isHoliday = false
        val startMinute = shift.start.minutesSinceMidnight
        for (m in 0 until duration) {
            val absolute = startMinute + m
            val offset = absolute / 1440
            if (offset != currentOffset) {
                currentOffset = offset
                val date = shift.date.plusDays(offset.toLong())
                weekday = date.dayOfWeek
                isHoliday = date in holidays
            }
            val index = rateCard.ruleIndex(weekday, isHoliday, absolute % 1440) ?: ruleCount
            minutesByRule[index] += 1
        }

        var weighted = 0L
        rateCard.rules.forEachIndexed { i, rule -> weighted += rule.centsPerHour.toLong() * minutesByRule[i] }
        val gross = Money(IntMath.roundHalfUp(weighted * paid, duration * 60L))

        val bands = mutableListOf<BandPay>()
        for (name in rateCard.bandNames) {
            var minutes = 0
            var rate: Int? = null
            rateCard.rules.forEachIndexed { i, rule ->
                if (rule.band == name) {
                    minutes += minutesByRule[i]
                    if (rate == null) rate = rule.centsPerHour
                }
            }
            if (minutes > 0) bands.add(BandPay(name, rate ?: 0, minutes, minutes * paid, duration * 60))
        }
        if (minutesByRule[ruleCount] > 0) {
            bands.add(BandPay(UNRATED_BAND, 0, minutesByRule[ruleCount], minutesByRule[ruleCount] * paid, duration * 60))
        }
        return ShiftPayResult(duration, brk, paid, bands, gross)
    }
}

/** Paid hours of one band summed over a pay period. */
data class BandHours(
    val band: String,
    val rateCentsPerHour: Int,
    /** Paid hours in hundredths, rounded half up from the exact total. */
    val hoursHundredths: Int,
)

/** Hours, gross, tax and net for a set of shifts in one pay period. */
data class PeriodPay(
    val paidMinutes: Int = 0,
    val bands: List<BandHours> = emptyList(),
    val gross: Money = Money.ZERO,
    val tax: Money = Money.ZERO,
    val net: Money = Money.ZERO,
) {
    /** Total paid hours in hundredths (3675 is 36.75 hours). */
    val hoursHundredths: Int get() = IntMath.roundHalfUp(paidMinutes * 100, 60)

    companion object {
        /** Prices all non-cancelled shifts and applies tax once on the period gross. */
        fun calculate(
            shifts: List<Shift>,
            rateCard: RateCard?,
            holidays: Set<LocalDate>,
            taxMethod: TaxMethod = TaxMethod.None,
            frequency: PayFrequency = PayFrequency.weekly,
            tables: List<WithholdingTable> = emptyList(),
        ): PeriodPay {
            if (rateCard == null) return PeriodPay()
            var paidMinutes = 0
            var gross = Money.ZERO
            // Exact rational sums of paid hours per band (numerator, denominator), so rounding happens once.
            val sums = HashMap<String, Pair<BigInteger, BigInteger>>()
            val rates = HashMap<String, Int>()
            for (shift in shifts) {
                if (shift.status == ShiftStatus.cancelled) continue
                val r = ShiftPay.calculate(shift, rateCard, holidays)
                paidMinutes += r.paidMinutes
                gross += r.gross
                for (b in r.bands) {
                    val n = BigInteger.valueOf(b.paidNumerator.toLong())
                    val d = BigInteger.valueOf(b.paidDenominator.toLong())
                    val (sn, sd) = sums[b.band] ?: (BigInteger.ZERO to BigInteger.ONE)
                    val num = sn * d + n * sd
                    val den = sd * d
                    val g = num.gcd(den).let { if (it.signum() == 0) BigInteger.ONE else it }
                    sums[b.band] = (num / g) to (den / g)
                    rates[b.band] = b.rateCentsPerHour
                }
            }
            val order = (rateCard.bandNames + ShiftPay.UNRATED_BAND).filter { it in sums }
            val bands = order.map { name ->
                val (n, d) = sums.getValue(name)
                // roundHalfUp(n * 100 / d) = floor((2 * n * 100 + d) / (2 * d)); n and d are non-negative.
                val hundredths = (n * BigInteger.valueOf(200) + d) / (d * BigInteger.TWO)
                BandHours(name, rates[name] ?: 0, hundredths.toInt())
            }
            val tax = taxMethod.tax(gross, frequency, tables)
            return PeriodPay(paidMinutes, bands, gross, tax, gross - tax)
        }
    }
}
