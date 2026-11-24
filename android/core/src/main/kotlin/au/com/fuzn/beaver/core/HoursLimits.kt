@file:UseSerializers(LocalDateSerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate

enum class HoursWarningLevel { soft, hard }

/** A warning that hours exceed a limit. Warnings never block saving. */
data class HoursWarning(
    val scope: Scope,
    val period: DateRange,
    val workedMinutes: Int,
    val limitHours: Int,
    val level: HoursWarningLevel,
) {
    sealed interface Scope {
        data class Weekly(val sourceID: Int) : Scope
        data object Fortnight : Scope
    }
}

/** A rolling-fortnight hours limit across all sources, active from a date. */
@Serializable
data class FortnightLimit(
    val id: Int = 0,
    val hoursPerFortnight: Int,
    val effectiveFrom: LocalDate,
) {
    /** Fortnights are anchored on the Monday of `effectiveFrom`. */
    val anchorMonday: LocalDate get() = effectiveFrom.mondayOfWeek
}

/** Hours-limit checks (whole-hour limits compared against paid minutes of non-cancelled shifts). */
object HoursLimits {
    /** Paid minutes of non-cancelled shifts that start inside [range], optionally for one source. */
    fun workedMinutes(shifts: List<Shift>, range: DateRange, sourceID: Int? = null): Int =
        shifts.fold(0) { total, s ->
            if (s.status == ShiftStatus.cancelled || s.date !in range || (sourceID != null && s.sourceID != sourceID)) total
            else total + s.paidMinutes
        }

    /** The warning for the week starting [weekStart] against the source's soft/hard caps, if any. */
    fun weeklyWarning(source: IncomeSource, weekStart: LocalDate, shifts: List<Shift>): HoursWarning? {
        val week = weekStart.mondayOfWeek.week
        val worked = workedMinutes(shifts, week, source.id)
        val scope = HoursWarning.Scope.Weekly(source.id)
        source.weeklyCapHardHours?.let { hard ->
            if (worked > hard * 60) return HoursWarning(scope, week, worked, hard, HoursWarningLevel.hard)
        }
        source.weeklyCapSoftHours?.let { soft ->
            if (worked > soft * 60) return HoursWarning(scope, week, worked, soft, HoursWarningLevel.soft)
        }
        return null
    }

    /** The 14-day window containing [date], or null if it starts before the limit became active. */
    fun fortnight(date: LocalDate, limit: FortnightLimit): DateRange? {
        val k = Math.floorDiv(limit.anchorMonday.daysUntil(date), 14)
        if (k < 0) return null
        val start = limit.anchorMonday.plusDays(k * 14L)
        return DateRange(start, start.plusDays(13))
    }

    /** A (hard) warning when all sources together exceed the fortnight limit in the window containing [date]. */
    fun fortnightWarning(limit: FortnightLimit, date: LocalDate, shifts: List<Shift>): HoursWarning? {
        val window = fortnight(date, limit) ?: return null
        val worked = workedMinutes(shifts, window)
        if (worked <= limit.hoursPerFortnight * 60) return null
        return HoursWarning(HoursWarning.Scope.Fortnight, window, worked, limit.hoursPerFortnight, HoursWarningLevel.hard)
    }
}
