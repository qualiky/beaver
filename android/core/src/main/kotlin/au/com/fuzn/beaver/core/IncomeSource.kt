@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class, WeekdaySerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** Whether pay comes from shifts or a fixed amount. */
@Serializable
enum class IncomeKind { hourly, fixed }

/** A source of income: shift work priced by a rate card, or a fixed amount per pay period. */
@Serializable
data class IncomeSource(
    val id: Int,
    val name: String,
    val kind: IncomeKind,
    /** Rate card for `hourly` sources. */
    val rateCardID: Int? = null,
    /** Amount per pay period for `fixed` sources. */
    val fixedAmount: Money? = null,
    /** For fixed sources: the amount is already net (no tax applied). */
    val amountIsNet: Boolean = false,
    val schedule: PaySchedule,
    val taxMethod: TaxMethod = TaxMethod.None,
    /** Optional weekly hours caps (whole hours): soft then hard warning. */
    val weeklyCapSoftHours: Int? = null,
    val weeklyCapHardHours: Int? = null,
    val isActive: Boolean = true,
)

/** One shift in a default week. */
@Serializable
data class TemplateShift(
    val weekday: DayOfWeek,
    val start: LocalTime,
    val end: LocalTime,
    val breakMinutes: Int = 0,
)

/** A default week of shifts for a source. The latest template whose `effectiveFrom` is on or before a week's Monday applies. */
@Serializable
data class Template(
    val id: Int = 0,
    val sourceID: Int,
    val name: String,
    val effectiveFrom: LocalDate? = null,
    val shifts: List<TemplateShift>,
)

/** Pay actually received for a payday. */
@Serializable
data class PayReceived(
    val id: Int = 0,
    val sourceID: Int,
    val payDate: LocalDate,
    val net: Money,
    val gross: Money? = null,
    val tax: Money? = null,
    val note: String = "",
)

/** A public holiday date and its name. */
@Serializable
data class Holiday(val date: LocalDate, val name: String)

/** A range of dates the user cannot work (travel, exams). Template shifts are not generated on them. */
@Serializable
data class UnavailableRange(
    val id: Int = 0,
    val from: LocalDate,
    val to: LocalDate,
    val note: String = "",
) {
    val range: DateRange get() = DateRange(from, to)
}

/** Expands templates into concrete shifts. */
object TemplateExpander {
    private val distantPast: LocalDate = LocalDate.of(0, 1, 1)

    /**
     * The template in force for the week starting [weekStart]: the latest `effectiveFrom` on or before it
     * (a null date counts as the distant past); later entries win ties.
     */
    fun template(sourceID: Int, weekStart: LocalDate, templates: List<Template>): Template? {
        var best: Template? = null
        for (t in templates) {
            if (t.sourceID != sourceID) continue
            val from = t.effectiveFrom
            if (from != null && from > weekStart) continue
            val b = best
            if (b != null) {
                val bFrom = b.effectiveFrom ?: distantPast
                val tFrom = from ?: distantPast
                if (tFrom >= bFrom) best = t
            } else {
                best = t
            }
        }
        return best
    }

    /** Rostered shifts a source's templates would produce in [range], skipping unavailable dates. */
    fun shifts(
        sourceID: Int,
        templates: List<Template>,
        range: DateRange,
        unavailable: List<UnavailableRange> = emptyList(),
    ): List<Shift> {
        val out = mutableListOf<Shift>()
        for (date in range.dates) {
            if (unavailable.any { date in it.range }) continue
            val t = template(sourceID, date.mondayOfWeek, templates) ?: continue
            for (ts in t.shifts) {
                if (ts.weekday == date.dayOfWeek) {
                    out.add(Shift(sourceID = sourceID, date = date, start = ts.start, end = ts.end,
                        breakMinutes = ts.breakMinutes, status = ShiftStatus.rostered))
                }
            }
        }
        return out
    }
}
