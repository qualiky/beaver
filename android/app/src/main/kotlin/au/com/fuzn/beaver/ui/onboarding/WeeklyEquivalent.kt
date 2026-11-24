package au.com.fuzn.beaver.ui.onboarding

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.PeriodPay
import au.com.fuzn.beaver.core.TemplateExpander
import au.com.fuzn.beaver.core.mondayOfWeek

/** Weekly equivalents for onboarding's "≈ $X per week" lines. Integer cents, rounded half up (same as iOS). */
object WeeklyEquivalent {
    /** `n / d` rounded half up, for `n >= 0` and `d > 0`. */
    fun roundDiv(n: Long, d: Long): Long = if (d <= 0) 0 else (2 * n + d) / (2 * d)

    /** What a bill costs per week on average, in cents. One-off bills count as zero (not a regular cost). */
    fun cents(amount: Money, cadence: Cadence): Long {
        val c = maxOf(0L, amount.cents)
        return when (cadence) {
            Cadence.Weekly -> c
            Cadence.Fortnightly -> roundDiv(c, 2)
            is Cadence.EveryNDays -> roundDiv(c * 7, maxOf(cadence.n, 1).toLong())
            is Cadence.Monthly -> roundDiv(c * 12, 52)
            Cadence.Quarterly -> roundDiv(c * 4, 52)
            Cadence.Yearly -> roundDiv(c, 52)
            Cadence.OneOff -> 0
        }
    }

    /** Weekly equivalent of every active, repeating bill. */
    fun bills(bills: List<Bill>): Money =
        Money(bills.filter { it.isActive }.sumOf { cents(it.amount, it.cadence) })

    /**
     * Average net income per week from the active sources. Fixed sources use their net amount per pay. Hourly sources
     * use their default week of shifts (a pay period of shifts, taxed once on the period gross); an hourly source
     * with no default week counts as zero.
     */
    fun income(input: ForecastInput): Money {
        var total = 0L
        for (source in input.sources) {
            if (!source.isActive) continue
            when (source.kind) {
                IncomeKind.fixed -> {
                    val amount = source.fixedAmount ?: Money.ZERO
                    val tax = if (source.amountIsNet) Money.ZERO
                    else source.taxMethod.tax(amount, source.schedule.frequency, input.withholdingTables)
                    val net = maxOf(0L, (amount - tax).cents)
                    total += when (source.schedule) {
                        is PaySchedule.Weekly -> net
                        is PaySchedule.Fortnightly -> roundDiv(net, 2)
                        is PaySchedule.Monthly -> roundDiv(net * 12, 52)
                    }
                }
                IncomeKind.hourly -> {
                    val weeks = if (source.schedule is PaySchedule.Fortnightly) 2 else 1
                    val start = input.today.mondayOfWeek.plusDays(7)
                    val range = DateRange(start, start.plusDays(weeks * 7L - 1))
                    val shifts = TemplateExpander.shifts(source.id, input.templates, range, input.unavailable)
                    val card = input.rateCards.firstOrNull { it.id == source.rateCardID }
                    val pay = PeriodPay.calculate(
                        shifts, card, input.holidayDates, source.taxMethod, source.schedule.frequency, input.withholdingTables,
                    )
                    total += roundDiv(maxOf(0L, pay.net.cents), weeks.toLong())
                }
            }
        }
        return Money(total)
    }
}
