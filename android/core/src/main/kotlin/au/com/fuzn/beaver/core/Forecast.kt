@file:UseSerializers(LocalDateSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

/** Everything the forecast engine needs. The engine only ever sees these value types. */
@Serializable
data class ForecastInput(
    val today: LocalDate,
    val sources: List<IncomeSource> = emptyList(),
    val rateCards: List<RateCard> = emptyList(),
    /** Custom withholding tables; built-ins are always available. */
    val withholdingTables: List<WithholdingTable> = emptyList(),
    val templates: List<Template> = emptyList(),
    val shifts: List<Shift> = emptyList(),
    val paysReceived: List<PayReceived> = emptyList(),
    val holidays: List<Holiday> = emptyList(),
    val unavailable: List<UnavailableRange> = emptyList(),
    val bills: List<Bill> = emptyList(),
    val billPayments: List<BillPayment> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val weekSpendTotals: List<WeekSpendTotal> = emptyList(),
    /** Weekly living budget (food, transport and so on, outside bills). */
    val livingBudget: Money = Money.ZERO,
    val goals: List<Goal> = emptyList(),
    val deposits: List<Deposit> = emptyList(),
    /** The goal that drives the forecast; if null the first goal is used. */
    val primaryGoalID: Int? = null,
    /** What-if: extra net income added to every week. */
    val extraWeeklyIncome: Money = Money.ZERO,
) {
    val holidayDates: Set<LocalDate> get() = holidays.map { it.date }.toSet()

    /** The goal driving the forecast. */
    val primaryGoal: Goal?
        get() {
            primaryGoalID?.let { id -> goals.firstOrNull { it.id == id }?.let { return it } }
            return goals.firstOrNull()
        }

    internal data class ExpectedPay(val pay: PeriodPay, val period: DateRange?, val shiftCount: Int, val usedTemplate: Boolean)

    /** Expected pay for a source's payday, from shifts or (if the period has none) from templates. */
    internal fun expectedPay(source: IncomeSource, payday: LocalDate, allowTemplate: Boolean): ExpectedPay {
        val period = source.schedule.workPeriod(payday)
        when (source.kind) {
            IncomeKind.fixed -> {
                val amount = source.fixedAmount ?: Money.ZERO
                if (source.amountIsNet) {
                    return ExpectedPay(PeriodPay(gross = amount, tax = Money.ZERO, net = amount), period, 0, false)
                }
                val tax = source.taxMethod.tax(amount, source.schedule.frequency, withholdingTables)
                return ExpectedPay(PeriodPay(gross = amount, tax = tax, net = amount - tax), period, 0, false)
            }
            IncomeKind.hourly -> {
                if (period == null) return ExpectedPay(PeriodPay(), null, 0, false)
                val card = rateCards.firstOrNull { it.id == source.rateCardID }
                var periodShifts = shifts.filter { it.sourceID == source.id && it.date in period }
                var usedTemplate = false
                if (periodShifts.isEmpty() && allowTemplate) {
                    periodShifts = TemplateExpander.shifts(source.id, templates, period, unavailable)
                    usedTemplate = periodShifts.isNotEmpty()
                }
                val pay = PeriodPay.calculate(periodShifts, card, holidayDates, source.taxMethod,
                    source.schedule.frequency, withholdingTables)
                return ExpectedPay(pay, period, periodShifts.size, usedTemplate)
            }
        }
    }

    /** The pay event for one source and payday. */
    internal fun payEvent(source: IncomeSource, payday: LocalDate): PayEvent {
        val actual = paysReceived.firstOrNull { it.sourceID == source.id && it.payDate == payday }
        val e = expectedPay(source, payday, allowTemplate = payday >= today || actual != null)
        return PayEvent(source.id, source.name, payday, e.period, e.pay.gross, e.pay.tax, e.pay.net,
            actual?.net, e.shiftCount, e.usedTemplate)
    }
}

/** One payment of one source: expected from shifts or templates, replaced by the actual once logged. */
data class PayEvent(
    val sourceID: Int,
    val sourceName: String,
    val payday: LocalDate,
    val workPeriod: DateRange?,
    val expectedGross: Money,
    val expectedTax: Money,
    val expectedNet: Money,
    val actualNet: Money?,
    val shiftCount: Int,
    /** The expected pay came from the default week template rather than rostered shifts. */
    val usedTemplate: Boolean,
) {
    val isActual: Boolean get() = actualNet != null

    /** The net amount the forecast uses: actual when logged, otherwise the estimate. */
    val net: Money get() = actualNet ?: expectedNet

    /** Actual minus expected, or null if nothing is logged. */
    val variance: Money? get() = actualNet?.let { it - expectedNet }
}

/** One Monday-to-Sunday week of the forecast. */
data class WeekRow(
    val weekStart: LocalDate,
    val payEvents: List<PayEvent>,
    /** What-if extra weekly income. */
    val extraIncome: Money,
    val bills: List<BillOccurrence>,
    /** The living budget for the week (the forecast starts at the current week). */
    val living: Money,
    val isCurrent: Boolean,
    val isDeadlineWeek: Boolean,
) {
    val weekEnd: LocalDate get() = weekStart.plusDays(6)
    val income: Money get() = payEvents.map { it.net }.total() + extraIncome
    val billsTotal: Money get() = bills.map { it.amount }.total()
    val surplus: Money get() = income - billsTotal - living
    val isDanger: Boolean get() = surplus.cents < 0
}

/** The result of the forecast engine. */
data class Forecast(
    val today: LocalDate,
    val goal: Goal?,
    val deadline: LocalDate?,
    val weeks: List<WeekRow>,
    /** Deposits logged against the primary goal. */
    val depositsTotal: Money,
    /** Deposits plus the sum of weekly surplus up to the deadline. */
    val projectedAtDeadline: Money,
    /** Projected minus target. Positive is ahead; without a goal the target is zero, so this is the projected surplus. */
    val gap: Money,
    /** Distinct paydays from today to the deadline. */
    val paydayCount: Int,
    /** (target - deposits) / paydayCount, rounded half up and never negative. */
    val requiredPerPayday: Money,
    /** Weeks with negative surplus (each carries the bills due in it). */
    val dangerWeeks: List<WeekRow>,
    /** Sum of (actual - expected) over every logged pay. */
    val payVariance: Money,
    /** Sum of (budget - spent) over past weeks that have spending recorded. */
    val spendingVariance: Money,
    val suggestedSplit: SuggestedSplit?,
) {
    val target: Money get() = goal?.target ?: Money.ZERO
    val payEvents: List<PayEvent>
        get() = weeks.flatMap { it.payEvents }.sortedWith(compareBy<PayEvent>({ it.payday }, { it.sourceID }))
    val totalIncome: Money get() = weeks.map { it.income }.total()
    val totalBills: Money get() = weeks.map { it.billsTotal }.total()
    val totalLiving: Money get() = weeks.map { it.living }.total()
    val totalSurplus: Money get() = weeks.map { it.surplus }.total()
    val currentWeek: WeekRow? get() = weeks.firstOrNull { it.isCurrent }

    companion object {
        /**
         * Runs the forecast for every week from the current one through the primary goal's deadline
         * (26 weeks with no deadline).
         */
        fun compute(input: ForecastInput): Forecast {
            val today = input.today
            val firstWeek = today.mondayOfWeek
            val goal = input.primaryGoal
            val deadline = goal?.deadline
            val lastWeek = deadline?.let { maxOf(it.mondayOfWeek, firstWeek) } ?: firstWeek.plusDays(25 * 7L)
            val windowEnd = lastWeek.plusDays(6)
            val cutoff = minOf(deadline ?: windowEnd, windowEnd)
            val eventRange = DateRange(firstWeek, cutoff)

            val events = mutableListOf<PayEvent>()
            for (source in input.sources) {
                if (!source.isActive) continue
                for (payday in source.schedule.paydays(eventRange)) events.add(input.payEvent(source, payday))
            }
            val occurrences = mutableListOf<BillOccurrence>()
            for (bill in input.bills) {
                if (!bill.isActive) continue
                occurrences += bill.occurrences(eventRange, input.billPayments)
            }

            val weeks = mutableListOf<WeekRow>()
            var weekStart = firstWeek
            while (weekStart <= lastWeek) {
                val week = weekStart.week
                weeks.add(WeekRow(
                    weekStart = weekStart,
                    payEvents = events.filter { it.payday in week }.sortedWith(compareBy<PayEvent>({ it.payday }, { it.sourceID })),
                    extraIncome = input.extraWeeklyIncome,
                    bills = occurrences.filter { it.dueDate in week }.sortedWith(compareBy<BillOccurrence>({ it.dueDate }, { it.billID })),
                    living = input.livingBudget,
                    isCurrent = weekStart == firstWeek,
                    isDeadlineWeek = weekStart == lastWeek && deadline != null,
                ))
                weekStart = weekStart.plusDays(7)
            }

            val deposits = goal?.saved(input.deposits) ?: Money.ZERO
            val projected = deposits + weeks.map { it.surplus }.total()
            val target = goal?.target ?: Money.ZERO
            val paydayCount = events.filter { it.payday >= today }.map { it.payday }.toSet().size
            val remaining = maxOf(0L, (target - deposits).cents)
            val required = if (paydayCount > 0) Money(IntMath.roundHalfUp(remaining, paydayCount.toLong())) else Money(remaining)

            var payVariance = Money.ZERO
            for (p in input.paysReceived) {
                val source = input.sources.firstOrNull { it.id == p.sourceID } ?: continue
                val e = input.expectedPay(source, p.payDate, allowTemplate = true)
                payVariance += p.net - e.pay.net
            }

            val mondays = mutableSetOf<LocalDate>()
            for (e in input.expenses) if (e.date.mondayOfWeek < firstWeek) mondays.add(e.date.mondayOfWeek)
            for (t in input.weekSpendTotals) if (t.weekStart.mondayOfWeek < firstWeek) mondays.add(t.weekStart.mondayOfWeek)
            var spendingVariance = Money.ZERO
            for (monday in mondays) {
                val spent = Spending.spent(monday, input.expenses, input.weekSpendTotals)
                spendingVariance += Spending.variance(input.livingBudget, spent)
            }

            return Forecast(
                today = today, goal = goal, deadline = deadline, weeks = weeks, depositsTotal = deposits,
                projectedAtDeadline = projected, gap = projected - target, paydayCount = paydayCount,
                requiredPerPayday = required, dangerWeeks = weeks.filter { it.isDanger },
                payVariance = payVariance, spendingVariance = spendingVariance,
                suggestedSplit = SuggestedSplit.make(input, required),
            )
        }
    }
}
