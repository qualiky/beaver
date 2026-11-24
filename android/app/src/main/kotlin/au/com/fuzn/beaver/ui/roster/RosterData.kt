package au.com.fuzn.beaver.ui.roster

import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.HoursLimits
import au.com.fuzn.beaver.core.HoursWarning
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayFrequency
import au.com.fuzn.beaver.core.PayReceived
import au.com.fuzn.beaver.core.PeriodPay
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftPay
import au.com.fuzn.beaver.core.ShiftPayResult
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.TemplateExpander
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.core.mondayOfWeek
import au.com.fuzn.beaver.core.week
import java.time.LocalDate

/** One shift as shown in the week list. */
data class ShiftLine(
    val shift: Shift,
    val sourceName: String,
    val pay: ShiftPayResult?,
    val holidayNames: List<String>,
    /** Chip labels for the bands the shift touches, e.g. ["Eve", "Night"]. */
    val chips: List<String>,
) {
    val id: Int get() = shift.id

    /** Gross counted for this shift (cancelled shifts earn nothing). */
    val gross: Money get() = if (shift.status == ShiftStatus.cancelled) Money.ZERO else (pay?.gross ?: Money.ZERO)
}

/** The shifts that start on one date. */
data class DayGroup(val date: LocalDate, val lines: List<ShiftLine>)

/** Week totals for one income source. */
data class SourceFooter(
    val source: IncomeSource,
    val paidMinutes: Int,
    val gross: Money,
    val tax: Money,
    val net: Money,
    val payday: LocalDate?,
    /** Set when tax and net cover a longer pay period (fortnightly pay) than the week shown. */
    val taxPeriod: DateRange?,
    val warnings: List<HoursWarning>,
)

/** What a bulk action (copy last week, apply template) would do, worked out before anything is saved. */
data class BulkPlan(
    val toAdd: List<Shift> = emptyList(),
    val skippedUnavailable: Int = 0,
    val skippedExisting: Int = 0,
    /** False when there was nothing to copy from (no shifts last week, or no template). */
    val hasSource: Boolean = true,
)

/** A logged pay with the net we expected for it. */
data class PayRow(val pay: PayReceived, val sourceName: String, val expected: Money?) {
    val difference: Money? get() = expected?.let { pay.net - it }
}

/**
 * Everything the Roster screen reads, as one immutable snapshot, plus all the pure calculations on it.
 * No database or UI here, so the same code runs in unit tests and `@Preview`s.
 * [shifts] holds every shift from two weeks before the week shown to three weeks after it.
 */
data class RosterData(
    val weekStart: LocalDate,
    val today: LocalDate,
    val sources: List<IncomeSource> = emptyList(),
    val rateCards: List<RateCard> = emptyList(),
    val templates: List<Template> = emptyList(),
    val holidays: List<Holiday> = emptyList(),
    val unavailable: List<UnavailableRange> = emptyList(),
    val limits: List<FortnightLimit> = emptyList(),
    val tables: List<WithholdingTable> = emptyList(),
    val pays: List<PayReceived> = emptyList(),
    val shifts: List<Shift> = emptyList(),
) {
    val week: DateRange get() = weekStart.week
    val isThisWeek: Boolean get() = weekStart == today.mondayOfWeek
    val holidayDates: Set<LocalDate> get() = holidays.map { it.date }.toSet()

    /** Active hourly sources: the only ones that can have shifts. */
    val hourlySources: List<IncomeSource> get() = sources.filter { it.kind == IncomeKind.hourly && it.isActive }

    /** Sources a pay can be logged against. */
    val sourcesForPay: List<IncomeSource> get() = sources.filter { it.isActive }

    fun source(id: Int): IncomeSource? = sources.firstOrNull { it.id == id }

    fun rateCard(source: IncomeSource?): RateCard? {
        val id = source?.rateCardID ?: return null
        return rateCards.firstOrNull { it.id == id }
    }

    fun holidayName(date: LocalDate): String? = holidays.firstOrNull { it.date == date }?.name

    val weekShifts: List<Shift> get() = shifts.filter { it.date in week }

    private fun shiftsIn(range: DateRange, sourceId: Int): List<Shift> =
        shifts.filter { it.sourceID == sourceId && it.date in range }

    fun line(shift: Shift): ShiftLine {
        val source = source(shift.sourceID)
        val pay = rateCard(source)?.let { ShiftPay.calculate(shift, it, holidayDates) }
        val names = mutableListOf<String>()
        holidayName(shift.date)?.let { names.add(it) }
        if (shift.crossesMidnight) holidayName(shift.endDate)?.let { if (it !in names) names.add(it) }
        return ShiftLine(shift, source?.name ?: "Unknown source", pay, names, pay?.bands.orEmpty().map { RosterFormat.chip(it.band) })
    }

    /** Shifts for the week shown, grouped by start date (only days that have shifts). */
    val dayGroups: List<DayGroup>
        get() {
            val byDate = weekShifts.groupBy { it.date }
            return week.dates.mapNotNull { date ->
                val list = byDate[date]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                DayGroup(date, list.sortedWith(compareBy({ it.start }, { it.id })).map(::line))
            }
        }

    /** One footer per hourly source that has a shift this week, in source order. */
    val footers: List<SourceFooter>
        get() = sources.mapNotNull { s ->
            if (s.kind != IncomeKind.hourly || shiftsIn(week, s.id).isEmpty()) null else footer(s)
        }

    private fun footer(source: IncomeSource): SourceFooter {
        val card = rateCard(source)
        val weekShifts = shiftsIn(week, source.id)
        val payday = source.schedule.paydayForWorkDate(weekStart)
        val period = payday?.let { source.schedule.workPeriod(it) } ?: week
        val weekPay = PeriodPay.calculate(weekShifts, card, holidayDates, source.taxMethod, PayFrequency.weekly, tables)
        var tax = weekPay.tax
        var net = weekPay.net
        var taxPeriod: DateRange? = null
        if (source.schedule.frequency != PayFrequency.weekly || period != week) {
            // Tax is worked out once on the whole pay period, so show the period's tax and net.
            val periodPay = PeriodPay.calculate(
                shiftsIn(period, source.id), card, holidayDates, source.taxMethod, source.schedule.frequency, tables,
            )
            tax = periodPay.tax
            net = periodPay.net
            taxPeriod = period
        }
        val warnings = listOfNotNull(HoursLimits.weeklyWarning(source, weekStart, shifts))
        return SourceFooter(source, weekPay.paidMinutes, weekPay.gross, tax, net, payday, taxPeriod, warnings)
    }

    /** The fortnight limit warning (all sources together) for the fortnight that holds the week shown. */
    val fortnightWarning: HoursWarning?
        get() {
            val limit = limits.filter { it.effectiveFrom <= week.end }.maxByOrNull { it.effectiveFrom } ?: return null
            return HoursLimits.fortnightWarning(limit, weekStart, shifts)
        }

    // region Copy last week and apply template

    private fun key(s: Shift) = "${s.sourceID}|${s.date}|${s.start}"

    private fun isUnavailable(date: LocalDate) = unavailable.any { date in it.range }

    /**
     * Last week's shifts (cancelled ones left out) moved forward seven days. Dates the user is unavailable are skipped,
     * and a shift is never added when one already exists for the same source, date and start.
     */
    fun copyLastWeekPlan(): BulkPlan {
        val hourlyIds = hourlySources.map { it.id }.toSet()
        val lastWeek = weekStart.minusDays(7).week
        val previous = shifts.filter { it.date in lastWeek && it.sourceID in hourlyIds && it.status != ShiftStatus.cancelled }
        val seen = weekShifts.map(::key).toMutableSet()
        val toAdd = mutableListOf<Shift>()
        var skippedUnavailable = 0
        var skippedExisting = 0
        for (old in previous.sortedWith(compareBy({ it.date }, { it.start }, { it.id }))) {
            val new = Shift(
                sourceID = old.sourceID, date = old.date.plusDays(7), start = old.start, end = old.end,
                breakMinutes = old.breakMinutes, status = ShiftStatus.rostered, note = old.note,
            )
            if (isUnavailable(new.date)) { skippedUnavailable++; continue }
            if (!seen.add(key(new))) { skippedExisting++; continue }
            toAdd.add(new)
        }
        return BulkPlan(toAdd, skippedUnavailable, skippedExisting, hasSource = previous.isNotEmpty())
    }

    /** Shifts from the template in force for the week shown, with the same skip rules as copy. */
    fun applyTemplatePlan(): BulkPlan {
        var hasSource = false
        var skippedUnavailable = 0
        var skippedExisting = 0
        val seen = weekShifts.map(::key).toMutableSet()
        val toAdd = mutableListOf<Shift>()
        for (source in hourlySources) {
            if (TemplateExpander.template(source.id, weekStart, templates) == null) continue
            hasSource = true
            val all = TemplateExpander.shifts(source.id, templates, week)
            val kept = TemplateExpander.shifts(source.id, templates, week, unavailable)
            skippedUnavailable += all.size - kept.size
            for (shift in kept) {
                if (seen.add(key(shift))) toAdd.add(shift) else skippedExisting++
            }
        }
        return BulkPlan(toAdd, skippedUnavailable, skippedExisting, hasSource)
    }

    // endregion

    /** Template rows for a source from the non-cancelled shifts of the week shown (sorted), or empty. */
    fun templateRows(sourceId: Int): List<au.com.fuzn.beaver.core.TemplateShift> =
        shiftsIn(week, sourceId).filter { it.status != ShiftStatus.cancelled }
            .sortedWith(compareBy({ it.date }, { it.start }))
            .map { au.com.fuzn.beaver.core.TemplateShift(it.date.dayOfWeek, it.start, it.end, it.breakMinutes) }

    // region Expected and logged pay

    /**
     * Expected net for a payday from the [periodShifts] of its work period (or the template when the period has none),
     * taxed once on the period gross. Fixed sources use their amount.
     */
    fun expectedNet(source: IncomeSource, payday: LocalDate, periodShifts: List<Shift>): Money {
        when (source.kind) {
            IncomeKind.fixed -> {
                val amount = source.fixedAmount ?: Money.ZERO
                if (source.amountIsNet) return amount
                return amount - source.taxMethod.tax(amount, source.schedule.frequency, tables)
            }
            IncomeKind.hourly -> {
                val period = source.schedule.workPeriod(payday) ?: return Money.ZERO
                val shifts = periodShifts.ifEmpty { TemplateExpander.shifts(source.id, templates, period, unavailable) }
                return PeriodPay.calculate(
                    shifts, rateCard(source), holidayDates, source.taxMethod, source.schedule.frequency, tables,
                ).net
            }
        }
    }

    /** Paydays to choose from for a source, newest first: the next payday within a week and earlier ones. */
    fun paydays(source: IncomeSource, count: Int = 12): List<LocalDate> {
        val range = DateRange(today.minusDays(180), today.plusDays(7))
        return source.schedule.paydays(range).reversed().take(count)
    }

    val recentPays: List<PayReceived> get() = pays.sortedWith(compareByDescending<PayReceived> { it.payDate }.thenByDescending { it.id })

    // endregion

    companion object {
        /** Actual minus expected. Positive means paid more than expected. */
        fun difference(actual: Money, expected: Money): Money = actual - expected

        /** "Added 3 shifts. Skipped 1 on unavailable dates and 2 already on the roster." */
        fun summary(added: Int, plan: BulkPlan, verb: String): String {
            var text = if (added == 0) "No shifts added." else "$verb $added ${if (added == 1) "shift" else "shifts"}."
            val skipped = mutableListOf<String>()
            if (plan.skippedUnavailable > 0) skipped.add("${plan.skippedUnavailable} on unavailable dates")
            if (plan.skippedExisting > 0) skipped.add("${plan.skippedExisting} already on the roster")
            if (skipped.isNotEmpty()) text += " Skipped " + skipped.joinToString(" and ") + "."
            return text
        }
    }
}
