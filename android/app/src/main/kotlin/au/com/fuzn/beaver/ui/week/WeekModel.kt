package au.com.fuzn.beaver.ui.week

import au.com.fuzn.beaver.core.BillOccurrence
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.HoursLimits
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.Spending
import au.com.fuzn.beaver.core.SplitKind
import au.com.fuzn.beaver.core.mondayOfWeek
import au.com.fuzn.beaver.core.total
import au.com.fuzn.beaver.data.AppSettings
import au.com.fuzn.beaver.ui.common.BillLine
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.common.PayLine
import au.com.fuzn.beaver.ui.common.Tone
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

// Formatting and selection logic for the Week tab. No Compose here, so it is unit-testable.

data class Headline(val text: String, val detail: String?, val tone: Tone)

data class GoalCard(
    val name: String,
    /** 0..1. */
    val progress: Float,
    val savedText: String,
    val targetText: String,
    val requiredText: String,
    val paydaysText: String,
    val deadlineText: String,
)

data class LivingLine(
    val budgetText: String,
    val spentText: String,
    val leftText: String,
    val left: Money,
    val tone: Tone,
    /** Fraction of the budget spent, clamped to 0..1. */
    val fractionSpent: Float,
)

data class SplitLineModel(
    val kind: SplitKind,
    val title: String,
    val fundedText: String,
    val neededText: String,
    /** Set when the pay does not cover this line: the part that comes out of the buffer. */
    val fromBufferText: String?,
)

data class SplitCard(
    val paydayText: String,
    val coversText: String,
    val expectedPayText: String,
    val lines: List<SplitLineModel>,
    val bufferText: String?,
    val totalFromBufferText: String?,
)

data class DangerBanner(
    val weekStart: LocalDate,
    val title: String,
    val shortfallText: String,
    val bills: List<BillLine>,
)

data class HoursBar(val usedMinutes: Int, val limitHours: Int, val window: DateRange, val status: Status) {
    enum class Status { Ok, Near, Over }

    val fraction: Float get() = if (limitHours > 0) minOf(1f, usedMinutes.toFloat() / (limitHours * 60)) else 0f
    val usedText: String get() = hoursText(usedMinutes)
    val summary: String get() = "$usedText of $limitHours h"

    companion object {
        /** Amber from this many hours below the limit. */
        const val WARN_MARGIN_HOURS = 4

        fun hoursText(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (m == 0) "$h h" else "$h h $m min"
        }

        fun status(usedMinutes: Int, limitHours: Int): Status = when {
            usedMinutes > limitHours * 60 -> Status.Over
            usedMinutes >= (limitHours - WARN_MARGIN_HOURS) * 60 -> Status.Near
            else -> Status.Ok
        }

        /**
         * The bar for the fortnight containing [today], or null when no fortnightly limit is active for it.
         * With several limits, the most recently effective one that covers today wins.
         */
        fun make(limits: List<FortnightLimit>, shifts: List<Shift>, today: LocalDate): HoursBar? {
            for (limit in limits.sortedByDescending { it.effectiveFrom }) {
                if (limit.hoursPerFortnight <= 0) continue
                val window = HoursLimits.fortnight(today, limit) ?: continue
                val used = HoursLimits.workedMinutes(shifts, window)
                return HoursBar(used, limit.hoursPerFortnight, window, status(used, limit.hoursPerFortnight))
            }
            return null
        }
    }
}

object BackupReminder {
    const val MAX_AGE_DAYS = 7L

    /** Due when never exported, or the last export is more than 7 days old (7 days exactly is not due). */
    fun isDue(lastExportAt: Instant?, now: Instant): Boolean {
        val last = lastExportAt ?: return true
        return Duration.between(last, now) > Duration.ofDays(MAX_AGE_DAYS)
    }

    fun message(lastExportAt: Instant?, now: Instant): String? {
        if (!isDue(lastExportAt, now)) return null
        val last = lastExportAt ?: return "You haven't backed up yet. Beaver keeps everything on this phone only."
        val days = Duration.between(last, now).toDays()
        return "Your last backup was $days days ago. Beaver keeps everything on this phone only."
    }
}

class WeekModel(
    val forecast: Forecast,
    val input: ForecastInput,
    settings: AppSettings,
    val hoursLimits: List<FortnightLimit> = emptyList(),
    val now: Instant = Instant.now(),
    locale: Locale = Locale.getDefault(),
) {
    val lastExportAt: Instant? = settings.lastExportAt
    val fmt = FigureFormatter(settings.currency, locale)

    val today: LocalDate get() = input.today
    val weekStart: LocalDate get() = today.mondayOfWeek

    /** Nothing set up yet: no income sources and no bills. */
    val isEmpty: Boolean get() = input.sources.isEmpty() && input.bills.isEmpty()

    // region Headline and goal

    val headline: Headline
        get() {
            val goal = forecast.goal
            if (goal == null) {
                val surplus = forecast.currentWeek?.surplus ?: forecast.totalSurplus
                val word = if (surplus.cents < 0) "shortfall" else "surplus"
                return Headline("This week: ${fmt.signed(surplus)} $word", null, Tone.of(surplus))
            }
            val gap = forecast.gap
            val by = forecast.deadline?.let { " by ${DateText.full(it, fmt.locale)}" } ?: ""
            val detail = "Projected ${fmt.string(forecast.projectedAtDeadline)} of ${fmt.string(goal.target)}$by"
            if (gap.cents == 0L) return Headline("Right on target", detail, Tone.Neutral)
            val word = if (gap.cents > 0) "ahead" else "behind"
            return Headline("${fmt.signed(gap)} $word", detail, Tone.of(gap))
        }

    val goalCard: GoalCard?
        get() {
            val goal = forecast.goal ?: return null
            val bp = goal.progressBasisPoints(input.deposits)
            val n = forecast.paydayCount
            return GoalCard(
                name = goal.name,
                progress = bp / 10_000f,
                savedText = fmt.string(forecast.depositsTotal),
                targetText = fmt.string(goal.target),
                requiredText = "${fmt.string(forecast.requiredPerPayday)} needed per payday",
                paydaysText = if (n == 1) "1 payday left" else "$n paydays left",
                deadlineText = goal.deadline?.let { "Deadline ${DateText.full(it, fmt.locale)}" } ?: "No deadline",
            )
        }

    // endregion

    // region This week

    /** The next payday on or after today (the split's payday when there is one). */
    val nextPayday: LocalDate?
        get() = forecast.suggestedSplit?.payday ?: forecast.payEvents.firstOrNull { it.payday >= today }?.payday

    val nextPaydayText: String? get() = nextPayday?.let { DateText.weekdayDayMonth(it, fmt.locale) }

    /** Pay events whose payday falls in the current week. */
    val payLines: List<PayLine> get() = (forecast.currentWeek?.payEvents ?: emptyList()).map { PayLine.of(it, fmt) }

    /**
     * Bills due before the next payday: from the start of this week to the day before it (or to Sunday with no
     * payday), plus unpaid bills that fell due in the 8 weeks before. Paid ones stay listed so they can show a tick.
     */
    val bills: List<BillLine>
        get() {
            val end = nextPayday?.minusDays(1) ?: weekStart.plusDays(6)
            val window = DateRange(weekStart, end)
            val overdueWindow = DateRange(weekStart.minusDays(56), weekStart.minusDays(1))
            val found = mutableListOf<BillOccurrence>()
            for (bill in input.bills) {
                if (!bill.isActive) continue
                found += bill.occurrences(overdueWindow, input.billPayments).filter { !it.isPaid }
                found += bill.occurrences(window, input.billPayments)
            }
            return found
                .sortedWith(compareBy<BillOccurrence>({ it.dueDate }, { it.billID }))
                .map { BillLine.of(it, today, fmt) }
        }

    val billsTotal: Money get() = bills.map { it.amount }.total()
    val billsUnpaidTotal: Money get() = bills.filter { !it.isPaid }.map { it.amount }.total()

    val living: LivingLine
        get() {
            val budget = input.livingBudget
            val spent = Spending.spent(weekStart, input.expenses, input.weekSpendTotals)
            val left = budget - spent
            val fraction = when {
                budget.cents > 0 -> (spent.cents.toFloat() / budget.cents).coerceIn(0f, 1f)
                spent.cents > 0 -> 1f
                else -> 0f
            }
            return LivingLine(
                budgetText = fmt.string(budget), spentText = fmt.string(spent), leftText = fmt.string(left),
                left = left, tone = if (left.cents < 0) Tone.Negative else Tone.Neutral, fractionSpent = fraction,
            )
        }

    // endregion

    // region Suggested split

    val split: SplitCard?
        get() {
            val s = forecast.suggestedSplit ?: return null
            val lines = s.lines.map { line ->
                val short = maxOf(0L, line.needed.cents) - line.funded.cents
                SplitLineModel(
                    kind = line.kind, title = title(line.kind), fundedText = fmt.string(line.funded),
                    neededText = fmt.string(line.needed),
                    fromBufferText = if (short > 0) fmt.string(Money(short)) else null,
                )
            }
            return SplitCard(
                paydayText = DateText.weekdayDayMonth(s.payday, fmt.locale),
                coversText = "Covers until ${DateText.weekdayDayMonth(s.followingPayday, fmt.locale)}",
                expectedPayText = fmt.string(s.expectedPay),
                lines = lines,
                bufferText = if (s.buffer.cents > 0) fmt.string(s.buffer) else null,
                totalFromBufferText = if (s.fromBuffer.cents > 0) fmt.string(s.fromBuffer) else null,
            )
        }

    // endregion

    // region Banners

    /** A banner for each of this week and next week whose surplus is negative. */
    val dangerBanners: List<DangerBanner>
        get() = forecast.weeks.take(2).mapIndexedNotNull { index, week ->
            if (!week.isDanger) return@mapIndexedNotNull null
            val label = if (index == 0) "This week" else "Next week"
            DangerBanner(
                weekStart = week.weekStart,
                title = "$label is tight",
                shortfallText = "${fmt.string(Money(-week.surplus.cents))} short",
                bills = week.bills.map { BillLine.of(it, today, fmt) },
            )
        }

    val hoursBar: HoursBar? get() = HoursBar.make(hoursLimits, input.shifts, today)

    /** Shown when the current fortnight is over the limit or within 4 hours of it. */
    val hoursWarning: String?
        get() {
            val bar = hoursBar ?: return null
            val limit = "${bar.limitHours} h"
            return when (bar.status) {
                HoursBar.Status.Ok -> null
                HoursBar.Status.Near -> "You're close to your $limit fortnight limit: ${bar.summary} used."
                HoursBar.Status.Over -> "You're over your $limit fortnight limit: ${bar.summary} used."
            }
        }

    val backupMessage: String? get() = BackupReminder.message(lastExportAt, now)

    // endregion

    companion object {
        fun title(kind: SplitKind): String = when (kind) {
            SplitKind.bills -> "Bills due"
            SplitKind.living -> "Living budget"
            SplitKind.sinkingFund -> "Bills sinking fund"
            SplitKind.goal -> "Goal"
        }
    }
}
