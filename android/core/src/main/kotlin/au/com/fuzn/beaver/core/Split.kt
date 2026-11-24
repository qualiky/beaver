@file:UseSerializers(LocalDateSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.UseSerializers
import java.time.LocalDate

/** The kinds of allocation in a suggested split, in priority order. */
enum class SplitKind { bills, living, sinkingFund, goal }

/** One allocation: what is needed and how much the pay can fund. */
data class SplitLine(val kind: SplitKind, val needed: Money, val funded: Money)

/**
 * How to divide the next payday's money, in order: bills due before the following payday, living budget until then,
 * the bills sinking fund, the required goal amount, then buffer.
 */
data class SuggestedSplit(
    val payday: LocalDate,
    val followingPayday: LocalDate,
    val expectedPay: Money,
    val lines: List<SplitLine>,
    /** Pay left over after every need is funded. */
    val buffer: Money,
    /** Shortfall that would have to come out of the buffer when pay does not cover every need. */
    val fromBuffer: Money,
) {
    fun line(kind: SplitKind): SplitLine? = lines.firstOrNull { it.kind == kind }

    companion object {
        /** Builds the split for the first payday on or after today. Null if no active source has an upcoming payday pair. */
        internal fun make(input: ForecastInput, requiredPerPayday: Money): SuggestedSplit? {
            val today = input.today
            val active = input.sources.filter { it.isActive }
            val lookahead = DateRange(today, today.plusDays(120))
            val all = active.flatMap { it.schedule.paydays(lookahead) }.toSet().sorted()
            if (all.size < 2) return null
            val next = all[0]
            val following = all[1]
            val span = DateRange(next, following.minusDays(1))
            val days = span.dayCount.toLong()

            val expectedPay = active.mapNotNull { s ->
                if (s.schedule.paydays(DateRange(next, next)).isEmpty()) null else input.payEvent(s, next).net
            }.total()

            var bills = Money.ZERO
            var sinking = 0L
            for (bill in input.bills) {
                if (!bill.isActive) continue
                for (o in bill.occurrences(span, input.billPayments)) if (!o.isPaid) bills += o.amount
                val annual = bill.annualCostCents()
                if (annual != null && bill.cadence != Cadence.Weekly) {
                    sinking += IntMath.roundHalfUp(annual * days, 365L)
                }
            }
            val living = Money(IntMath.roundHalfUp(input.livingBudget.cents * days, 7L))
            val needs = listOf(
                SplitKind.bills to bills,
                SplitKind.living to living,
                SplitKind.sinkingFund to Money(sinking),
                SplitKind.goal to requiredPerPayday,
            )
            var remaining = maxOf(0L, expectedPay.cents)
            val lines = mutableListOf<SplitLine>()
            var short = 0L
            for ((kind, need) in needs) {
                val funded = minOf(remaining, maxOf(0L, need.cents))
                remaining -= funded
                short += maxOf(0L, need.cents) - funded
                lines.add(SplitLine(kind, need, Money(funded)))
            }
            return SuggestedSplit(next, following, expectedPay, lines, Money(remaining), Money(short))
        }
    }
}
