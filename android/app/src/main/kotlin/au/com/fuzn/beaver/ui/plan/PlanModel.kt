package au.com.fuzn.beaver.ui.plan

import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.WeekRow
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.BillLine
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.common.PayLine
import au.com.fuzn.beaver.ui.common.Tone
import java.time.LocalDate
import java.util.Locale

// Formatting and row building for the Plan tab. No Compose here, so it is unit-testable.

data class PlanSummary(
    val hasGoal: Boolean,
    val projectedLabel: String,
    val projectedText: String,
    val gapLabel: String,
    val gapText: String,
    val gapTone: Tone,
    val payVarianceText: String,
    val payVarianceTone: Tone,
    val spendingVarianceText: String,
    val spendingVarianceNote: String,
    val spendingVarianceTone: Tone,
)

data class IncomePart(val text: String, val isActual: Boolean) {
    /** "$600.00 actual" or "$1,109.83 est." */
    val label: String get() = "$text ${if (isActual) "actual" else "est."}"
}

data class PlanRow(
    val weekStart: LocalDate,
    val week: WeekRow,
    val title: String,
    val paydaysText: String,
    val incomeParts: List<IncomePart>,
    val incomeText: String,
    val billsText: String,
    val livingText: String,
    val surplus: Money,
    val surplusText: String,
    val balanceText: String,
) {
    val id: String get() = weekStart.toString()
    val isDanger: Boolean get() = week.isDanger
    val isCurrent: Boolean get() = week.isCurrent
    val isDeadlineWeek: Boolean get() = week.isDeadlineWeek

    val accessibilityLabel: String
        get() {
            val parts = mutableListOf("Week $title")
            if (isCurrent) parts += "Current week"
            if (isDanger) parts += "Danger week, spending exceeds income"
            if (isDeadlineWeek) parts += "Deadline week"
            parts += listOf(
                paydaysText, "Income $incomeText", "Bills $billsText", "Living $livingText",
                "Surplus ${au.com.fuzn.beaver.ui.common.spokenMoney(surplusText)}", "Projected balance $balanceText",
            )
            return parts.joinToString(". ")
        }
}

data class PlanWeekDetail(
    val title: String,
    val pays: List<PayLine>,
    val bills: List<BillLine>,
    val incomeText: String,
    val extraIncomeText: String?,
    val billsText: String,
    val livingText: String,
    val surplusText: String,
    val surplusTone: Tone,
    val isDanger: Boolean,
    val isDeadlineWeek: Boolean,
)

class PlanModel(val forecast: Forecast, settings: AppSettings, locale: Locale = Locale.getDefault()) {
    val today: LocalDate = forecast.today
    val fmt = FigureFormatter(settings.currency, locale)

    val summary: PlanSummary
        get() {
            val hasGoal = forecast.goal != null
            val gap = forecast.gap
            val spending = forecast.spendingVariance
            return PlanSummary(
                hasGoal = hasGoal,
                projectedLabel = if (hasGoal) "Projected at deadline" else "Projected surplus",
                projectedText = fmt.string(forecast.projectedAtDeadline),
                gapLabel = if (hasGoal) (if (gap.cents < 0) "Behind goal" else "Ahead of goal") else "Surplus",
                gapText = fmt.signed(gap),
                gapTone = Tone.of(gap),
                payVarianceText = fmt.signed(forecast.payVariance),
                payVarianceTone = Tone.of(forecast.payVariance),
                spendingVarianceText = fmt.signed(spending),
                spendingVarianceNote = when {
                    spending.cents > 0 -> "under budget"
                    spending.cents < 0 -> "over budget"
                    else -> "on budget"
                },
                spendingVarianceTone = Tone.of(spending),
            )
        }

    /** One row per forecast week. The balance is deposits so far plus the running total of weekly surplus. */
    val rows: List<PlanRow>
        get() {
            var balance = forecast.depositsTotal
            return forecast.weeks.map { week ->
                balance += week.surplus
                val parts = week.payEvents.map { IncomePart(fmt.string(it.net), it.isActual) }.toMutableList()
                if (week.extraIncome.cents != 0L) parts += IncomePart(fmt.string(week.extraIncome), false)
                val incomeText = if (parts.isEmpty()) fmt.string(Money.ZERO) else parts.joinToString(" + ") { it.label }
                val paydays = week.payEvents.map { DateText.weekdayDayMonth(it.payday, fmt.locale) }
                PlanRow(
                    weekStart = week.weekStart, week = week,
                    title = DateText.range(week.weekStart, week.weekEnd, fmt.locale),
                    paydaysText = if (paydays.isEmpty()) "No payday" else "Pay ${paydays.joinToString(", ")}",
                    incomeParts = parts, incomeText = incomeText,
                    billsText = fmt.string(week.billsTotal), livingText = fmt.string(week.living),
                    surplus = week.surplus, surplusText = fmt.signed(week.surplus), balanceText = fmt.string(balance),
                )
            }
        }

    fun detail(row: PlanRow): PlanWeekDetail {
        val week = row.week
        return PlanWeekDetail(
            title = row.title,
            pays = week.payEvents.map { PayLine.of(it, fmt) },
            bills = week.bills.map { BillLine.of(it, today, fmt) },
            incomeText = row.incomeText,
            extraIncomeText = if (week.extraIncome.cents != 0L) fmt.string(week.extraIncome) else null,
            billsText = row.billsText, livingText = row.livingText, surplusText = row.surplusText,
            surplusTone = Tone.of(week.surplus), isDanger = week.isDanger, isDeadlineWeek = week.isDeadlineWeek,
        )
    }
}

/**
 * The what-if state of the Plan tab. [livingCents] is the working copy of the saved living budget (so rapid taps
 * never read a stale value) and [baselineLivingCents] is the budget when the tab opened, which reset restores.
 * Extra weekly income lives in `AppStore` (memory only); it is passed in here to decide whether reset does anything.
 */
data class WhatIfState(val livingCents: Long, val baselineLivingCents: Long) {
    fun isActive(extraWeeklyIncome: Money): Boolean = extraWeeklyIncome.cents != 0L || livingCents != baselineLivingCents

    fun withLiving(cents: Long) = copy(livingCents = cents.coerceIn(0, MAX_LIVING_CENTS))

    companion object {
        const val LIVING_STEP_CENTS = 1_000L
        const val EXTRA_STEP_CENTS = 2_500L
        const val MAX_LIVING_CENTS = 1_000_000L
        const val MAX_EXTRA_CENTS = 1_000_000L

        fun starting(livingBudget: Money) = WhatIfState(livingBudget.cents, livingBudget.cents)
    }
}
