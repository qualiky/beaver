package au.com.fuzn.beaver.ui.money

import androidx.compose.ui.graphics.vector.ImageVector
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillOccurrence
import au.com.fuzn.beaver.core.BillStatus
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.Spending
import au.com.fuzn.beaver.core.mondayOfWeek
import au.com.fuzn.beaver.core.total
import au.com.fuzn.beaver.core.week
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.editors.EditorLabels
import java.time.LocalDate
import java.util.Locale

// Pure logic for the Money tab. Everything here is a function of a `ForecastInput` snapshot (the store's `input`),
// so it is unit-tested without Compose. Screens never compute amounts themselves.

// region Bills

/** One occurrence of a bill, ready for display. */
data class MoneyBillRow(
    val occurrence: BillOccurrence,
    val status: BillStatus,
    val dateText: String,
    val amountText: String,
    val icon: ImageVector,
) {
    val id: String get() = "${occurrence.billID}-${occurrence.dueDate}"
    val billID: Int get() = occurrence.billID
    val name: String get() = occurrence.name
    val dueDate: LocalDate get() = occurrence.dueDate
    val isPaid: Boolean get() = occurrence.isPaid
    val amount: Money get() = occurrence.amount
}

/** Bill occurrences under one "Before ‹payday›" (or week) header. */
data class MoneyBillGroup(
    val id: String,
    val title: String,
    /** The payday that closes the group; null when grouped by week. */
    val payday: LocalDate?,
    val rows: List<MoneyBillRow>,
    val total: Money,
    val totalText: String,
)

/** One line of the Monthly view. */
data class MonthlyBillRow(
    val bill: Bill,
    /** Day of the month for monthly, quarterly, yearly and one-off bills; null for weekly style cadences. */
    val dayOfMonth: Int?,
    val detail: String,
    val amountText: String,
    val icon: ImageVector,
) {
    val id: Int get() = bill.id
}

class BillsModel(
    private val input: ForecastInput,
    currency: String,
    locale: Locale = Locale.getDefault(),
) {
    val today: LocalDate = input.today
    val fmt = FigureFormatter(currency, locale)

    val windowStart: LocalDate get() = today.mondayOfWeek
    val windowEnd: LocalDate get() = windowStart.plusDays(WEEKS_AHEAD * 7L - 1)

    val activeBills: List<Bill> get() = input.bills.filter { it.isActive }
    val pausedBills: List<Bill> get() = input.bills.filter { !it.isActive }.sortedWith(compareBy({ it.name }, { it.id }))
    val isEmpty: Boolean get() = input.bills.isEmpty()

    private fun row(o: BillOccurrence) = MoneyBillRow(
        occurrence = o, status = o.status(today), dateText = DateText.weekdayDayMonth(o.dueDate, fmt.locale),
        amountText = fmt.string(o.amount), icon = BillIcons.icon(o.name, o.category),
    )

    private fun occurrences(range: DateRange): List<BillOccurrence> =
        activeBills.flatMap { it.occurrences(range, input.billPayments) }
            .sortedWith(compareBy({ it.dueDate }, { it.name }, { it.billID }))

    /** Unpaid occurrences before today, oldest first. */
    val overdue: List<MoneyBillRow>
        get() = occurrences(DateRange(windowStart.minusDays(7L * OVERDUE_LOOKBACK_WEEKS), today.minusDays(1)))
            .filter { !it.isPaid }.map(::row)

    val overdueTotal: Money get() = overdue.map { it.amount }.total()

    /** Paydays of active income sources from the window start, far enough ahead to close the last group. */
    fun paydays(): List<LocalDate> {
        val range = DateRange(windowStart, windowEnd.plusDays(62))
        return input.sources.filter { it.isActive }.flatMap { it.schedule.paydays(range) }.distinct().sorted()
    }

    private class Bucket(val key: LocalDate, val title: String, val payday: LocalDate?, val rows: MutableList<MoneyBillRow>)

    /**
     * The next 8 weeks, grouped under "Before ‹payday›". A bill due on the payday itself is in that payday's group
     * (the first payday on or after its due date). Overdue unpaid occurrences are left out: they sit in [overdue].
     * With no income sources, groups are weeks.
     */
    val groups: List<MoneyBillGroup>
        get() {
            val rows = occurrences(DateRange(windowStart, windowEnd)).filter { it.isPaid || it.dueDate >= today }.map(::row)
            val paydays = paydays()
            val buckets = mutableListOf<Bucket>()
            for (r in rows) {
                val payday = paydays.firstOrNull { it >= r.dueDate }
                val key: LocalDate
                val title: String
                if (payday != null) {
                    key = payday
                    title = "Before ${DateText.weekdayDayMonth(payday, fmt.locale)}"
                } else {
                    key = r.dueDate.mondayOfWeek
                    title = when {
                        paydays.isNotEmpty() -> "After the last payday: week of ${DateText.dayMonth(key, fmt.locale)}"
                        key == windowStart -> "This week"
                        else -> "Week of ${DateText.dayMonth(key, fmt.locale)}"
                    }
                }
                val existing = buckets.firstOrNull { it.key == key && it.payday == payday }
                if (existing != null) existing.rows.add(r) else buckets.add(Bucket(key, title, payday, mutableListOf(r)))
            }
            return buckets.sortedBy { it.key }.map { b ->
                val total = b.rows.map { it.amount }.total()
                MoneyBillGroup(
                    id = "${if (b.payday == null) "w" else "p"}-${b.key}", title = b.title, payday = b.payday,
                    rows = b.rows, total = total, totalText = fmt.string(total),
                )
            }
        }

    /**
     * Every active bill with its usual day or cadence. Bills that fall on a day of the month come first, ordered by
     * that day; weekly style bills follow, by name.
     */
    val monthly: List<MonthlyBillRow>
        get() = activeBills.map { bill ->
            val (day, detail) = when (val c = bill.cadence) {
                is Cadence.Monthly -> c.dayOfMonth to "Day ${c.dayOfMonth} of each month"
                Cadence.Quarterly -> bill.anchorDate.dayOfMonth to "Day ${bill.anchorDate.dayOfMonth}, every 3 months"
                Cadence.Yearly -> bill.anchorDate.dayOfMonth to "Every year on ${DateText.dayMonth(bill.anchorDate, fmt.locale)}"
                Cadence.OneOff -> bill.anchorDate.dayOfMonth to "Once, ${DateText.full(bill.anchorDate, fmt.locale)}"
                Cadence.Weekly -> null to "Every week, ${weekdayName(bill.anchorDate)}"
                Cadence.Fortnightly -> null to "Every 2 weeks, ${weekdayName(bill.anchorDate)}"
                is Cadence.EveryNDays -> null to "Every ${c.n} days"
            }
            MonthlyBillRow(bill, day, detail, fmt.string(bill.amount), BillIcons.icon(bill.name, bill.category))
        }.sortedWith(
            compareBy<MonthlyBillRow> { it.dayOfMonth == null } // days of the month first
                .thenBy { it.dayOfMonth ?: 0 }
                .thenBy { it.bill.name }
                .thenBy { it.bill.id },
        )

    private fun weekdayName(d: LocalDate) = EditorLabels.weekdayName(d.dayOfWeek)

    companion object {
        /** How many weeks ahead the list covers, from this week's Monday. */
        const val WEEKS_AHEAD = 8

        /** How far back unpaid occurrences still count as overdue. */
        const val OVERDUE_LOOKBACK_WEEKS = 8
    }
}

// endregion

// region Spending

data class WeekSpendRow(
    val weekStart: LocalDate,
    val spent: Money,
    val budget: Money,
    val variance: Money,
    val hasData: Boolean,
    val hasTotal: Boolean,
    val isCurrent: Boolean,
    /** Running total of variance up to and including this week. Null for the current week. */
    val running: Money?,
) {
    val id: String get() = weekStart.toString()
}

class SpendingModel(
    private val input: ForecastInput,
    currency: String,
    locale: Locale = Locale.getDefault(),
) {
    val today: LocalDate = input.today
    val fmt = FigureFormatter(currency, locale)
    val budget: Money = input.livingBudget

    val currentWeekStart: LocalDate get() = today.mondayOfWeek

    fun spent(weekStart: LocalDate): Money = Spending.spent(weekStart, input.expenses, input.weekSpendTotals)

    /** The week total set for that week, or null. */
    fun total(weekStart: LocalDate): Money? =
        input.weekSpendTotals.firstOrNull { it.weekStart.mondayOfWeek == weekStart.mondayOfWeek }?.amount

    /** The week's quick entries, newest first. */
    fun expenses(weekStart: LocalDate): List<Expense> {
        val week = weekStart.mondayOfWeek.week
        return input.expenses.filter { it.date in week }
            .sortedWith(compareByDescending<Expense> { it.date }.thenByDescending { it.id })
    }

    val spentThisWeek: Money get() = spent(currentWeekStart)
    val leftThisWeek: Money get() = budget - spentThisWeek
    val varianceThisWeek: Money get() = Spending.variance(budget, spentThisWeek)

    /**
     * The last 12 weeks, newest first. The running total counts completed weeks that have recorded spending (a week
     * with nothing logged is unknown, not a saving). It starts at the oldest listed week.
     */
    val history: List<WeekSpendRow>
        get() {
            val starts = (0 until HISTORY_WEEKS).map { currentWeekStart.minusDays(7L * (HISTORY_WEEKS - 1 - it)) }
            var running = Money.ZERO
            val rows = mutableListOf<WeekSpendRow>()
            for (start in starts) {
                val spent = spent(start)
                val hasData = Spending.hasData(start, input.expenses, input.weekSpendTotals)
                val isCurrent = start == currentWeekStart
                val variance = Spending.variance(budget, spent)
                if (hasData && !isCurrent) running += variance
                rows.add(
                    WeekSpendRow(start, spent, budget, variance, hasData, total(start) != null, isCurrent, if (isCurrent) null else running),
                )
            }
            return rows.reversed()
        }

    /** Sum of variance over completed weeks with spending in the last 12 weeks. */
    val runningVariance: Money get() = history.firstOrNull { !it.isCurrent }?.running ?: Money.ZERO

    fun varianceText(variance: Money): String = when {
        variance.cents > 0 -> "${fmt.string(variance)} under"
        variance.cents < 0 -> "${fmt.string(-variance)} over"
        else -> "On budget"
    }

    fun weekTitle(start: LocalDate): String =
        if (start == currentWeekStart) "This week" else "Week of ${DateText.dayMonth(start, fmt.locale)}"

    companion object {
        const val HISTORY_WEEKS = 12
    }
}

// endregion

// region Goals

data class GoalRow(
    val goal: Goal,
    val saved: Money,
    val progressBasisPoints: Int,
    val isPrimary: Boolean,
    /** Newest first. */
    val deposits: List<Deposit>,
    val savedText: String,
    val targetText: String,
    val deadlineText: String?,
    val percentText: String,
) {
    val id: Int get() = goal.id
    val progress: Float get() = progressBasisPoints / 10_000f
}

class GoalsModel(
    private val input: ForecastInput,
    currency: String,
    locale: Locale = Locale.getDefault(),
) {
    val fmt = FigureFormatter(currency, locale)

    /** The primary goal's id: the chosen one, else the first goal (as the forecast does). */
    val primaryID: Int?
        get() = input.primaryGoalID?.takeIf { id -> input.goals.any { it.id == id } } ?: input.goals.firstOrNull()?.id

    val rows: List<GoalRow>
        get() = input.goals.map { goal ->
            val bp = goal.progressBasisPoints(input.deposits)
            val saved = goal.saved(input.deposits)
            GoalRow(
                goal = goal, saved = saved, progressBasisPoints = bp, isPrimary = goal.id == primaryID,
                deposits = input.deposits.filter { it.goalID == goal.id }
                    .sortedWith(compareByDescending<Deposit> { it.date }.thenByDescending { it.id }),
                savedText = fmt.string(saved), targetText = fmt.string(goal.target),
                deadlineText = goal.deadline?.let { "by ${DateText.full(it, fmt.locale)}" },
                percentText = "${bp / 100}%",
            )
        }
}

// endregion
