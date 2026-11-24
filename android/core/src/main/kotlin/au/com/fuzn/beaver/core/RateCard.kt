@file:UseSerializers(LocalDateSerializer::class, LocalTimeSerializer::class, WeekdaySerializer::class)

package au.com.fuzn.beaver.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek

/** Which days a rate rule applies to. */
@Serializable
enum class DaySelector {
    publicHoliday, saturday, sunday,

    /** Monday to Friday. */
    weekdays,
}

/** A clock window `[startMinute, endMinute)` in minutes since midnight. `endMinute` may be 1440 (24:00). */
@Serializable
data class ClockWindow(val startMinute: Int, val endMinute: Int) {
    fun contains(minute: Int): Boolean = minute >= startMinute && minute < endMinute

    companion object {
        /** Hours-based convenience, e.g. `ClockWindow.hours(19, 24)`. */
        fun hours(fromHour: Int, toHour: Int) = ClockWindow(fromHour * 60, toHour * 60)
    }
}

/** One line of a rate card: a named band, its hourly rate and where it applies. */
@Serializable
data class RateRule(
    val band: String,
    val centsPerHour: Int,
    val days: DaySelector,
    /** Optional clock window; null means all day. */
    val window: ClockWindow? = null,
) {
    internal fun matches(weekday: DayOfWeek, isHoliday: Boolean, minute: Int): Boolean {
        if (window != null && !window.contains(minute)) return false
        return when (days) {
            DaySelector.publicHoliday -> isHoliday
            DaySelector.saturday -> weekday == DayOfWeek.SATURDAY
            DaySelector.sunday -> weekday == DayOfWeek.SUNDAY
            DaySelector.weekdays -> !weekday.isWeekend
        }
    }
}

/** A user's pay rates. Rules are ordered and the first match wins; a public holiday rule overrides the rest on holidays. */
@Serializable
data class RateCard(
    val id: Int = 0,
    val name: String,
    val rules: List<RateRule>,
) {
    /** Distinct band names in rule order. */
    val bandNames: List<String> get() = rules.map { it.band }.distinct()

    /** Index of the rule that prices a given minute, or null if no rule covers it. */
    internal fun ruleIndex(weekday: DayOfWeek, isHoliday: Boolean, minute: Int): Int? {
        if (isHoliday) {
            val i = rules.indexOfFirst { it.days == DaySelector.publicHoliday && it.matches(weekday, true, minute) }
            if (i >= 0) return i
        }
        val i = rules.indexOfFirst { it.days != DaySelector.publicHoliday && it.matches(weekday, isHoliday, minute) }
        return if (i >= 0) i else null
    }

    /** Weekday (Mon-Fri) clock windows that no rule covers. An empty result means the card is complete. */
    fun weekdayGaps(): List<ClockWindow> {
        val gaps = mutableListOf<ClockWindow>()
        var gapStart: Int? = null
        for (minute in 0 until 1440) {
            val covered = rules.any { it.days == DaySelector.weekdays && it.matches(DayOfWeek.MONDAY, false, minute) }
            if (!covered && gapStart == null) gapStart = minute
            if (covered && gapStart != null) {
                gaps.add(ClockWindow(gapStart, minute))
                gapStart = null
            }
        }
        gapStart?.let { gaps.add(ClockWindow(it, 1440)) }
        return gaps
    }

    companion object {
        /** "Typical casual (template)": fictional rates users override in onboarding. */
        fun typicalCasual(id: Int = 0): RateCard = RateCard(
            id, "Typical casual (template)", listOf(
                RateRule("Public holiday", 6000, DaySelector.publicHoliday),
                RateRule("Weekday day", 3000, DaySelector.weekdays, ClockWindow.hours(7, 19)),
                RateRule("Weekday evening", 3300, DaySelector.weekdays, ClockWindow.hours(19, 24)),
                RateRule("Weekday night", 3450, DaySelector.weekdays, ClockWindow.hours(0, 7)),
                RateRule("Saturday", 3750, DaySelector.saturday),
                RateRule("Sunday", 4500, DaySelector.sunday),
            )
        )
    }
}
