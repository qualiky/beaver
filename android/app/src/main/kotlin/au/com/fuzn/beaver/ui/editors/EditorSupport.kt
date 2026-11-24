package au.com.fuzn.beaver.ui.editors

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Train
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.ClockWindow
import au.com.fuzn.beaver.core.DaySelector
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Thrown by an editor's `save` when the form is not valid. Every error is revealed first. */
class EditorInvalidException : Exception("Some fields need attention before this can be saved.")

/** Display strings and icons shared by the editors. Copy matches the iOS editors. */
object EditorLabels {
    fun weekdayName(day: DayOfWeek): String = when (day) {
        DayOfWeek.MONDAY -> "Monday"
        DayOfWeek.TUESDAY -> "Tuesday"
        DayOfWeek.WEDNESDAY -> "Wednesday"
        DayOfWeek.THURSDAY -> "Thursday"
        DayOfWeek.FRIDAY -> "Friday"
        DayOfWeek.SATURDAY -> "Saturday"
        DayOfWeek.SUNDAY -> "Sunday"
    }

    fun shortWeekday(day: DayOfWeek): String = weekdayName(day).take(3)

    fun dayTitle(days: DaySelector): String = when (days) {
        DaySelector.publicHoliday -> "Public holidays"
        DaySelector.saturday -> "Saturdays"
        DaySelector.sunday -> "Sundays"
        DaySelector.weekdays -> "Weekdays (Mon–Fri)"
    }

    fun categoryTitle(category: BillCategory): String = when (category) {
        BillCategory.housing -> "Housing"
        BillCategory.utilities -> "Utilities"
        BillCategory.subscription -> "Subscription"
        BillCategory.transport -> "Transport"
        BillCategory.debt -> "Debt"
        BillCategory.insurance -> "Insurance"
        BillCategory.other -> "Other"
    }

    fun categoryIcon(category: BillCategory): ImageVector = when (category) {
        BillCategory.housing -> Icons.Filled.Home
        BillCategory.utilities -> Icons.Filled.Bolt
        BillCategory.subscription -> Icons.Filled.Subscriptions
        BillCategory.transport -> Icons.Filled.Train
        BillCategory.debt -> Icons.Filled.CreditCard
        BillCategory.insurance -> Icons.Filled.Shield
        BillCategory.other -> Icons.Filled.LocalOffer
    }

    /** "07:00", or "24:00" for minute 1440. */
    fun clock(minute: Int): String = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)

    fun window(w: ClockWindow): String = "${clock(w.startMinute)}–${clock(w.endMinute)}"

    fun date(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}

/** Whole-number parsing for hours and counts. */
object EditorNumber {
    /** A whole number from user text, or null (empty, signs, decimals and other characters are rejected). */
    fun wholeNumber(text: String): Int? {
        val t = text.trim()
        if (t.isEmpty() || t.length > 9 || !t.all { it in '0'..'9' }) return null
        return t.toInt()
    }
}

/** Tracks which fields the user has edited, so a pristine form shows no errors. `revealAll` is set on a save attempt. */
class FieldTouches<F> {
    private var touched by mutableStateOf(emptySet<F>())
    var revealAll by mutableStateOf(false)

    fun touch(field: F) {
        if (field !in touched) touched = touched + field
    }

    fun shows(field: F): Boolean = revealAll || field in touched
}
