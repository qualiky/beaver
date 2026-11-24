package au.com.fuzn.beaver.ui.roster

import au.com.fuzn.beaver.core.HoursWarning
import au.com.fuzn.beaver.core.HoursWarningLevel
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.ui.editors.MoneyParser
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Pure formatting and parsing helpers for the Roster screen. No state, so they are easy to test on the JVM. */
object RosterFormat {
    /**
     * A short chip label for a rate-card band name: Day, Eve, Night, Sat, Sun, PH.
     * Unknown names fall back to the first word, shortened to 7 characters.
     */
    fun chip(band: String): String {
        val name = band.lowercase()
        // Order matters: "holiday", "saturday" and "sunday" all contain "day".
        if ("holiday" in name) return "PH"
        if ("saturday" in name || name.startsWith("sat")) return "Sat"
        if ("sunday" in name || name.startsWith("sun")) return "Sun"
        if ("evening" in name || name.startsWith("eve")) return "Eve"
        if ("night" in name) return "Night"
        if ("day" in name) return "Day"
        val first = band.trim().split(" ").firstOrNull().orEmpty().ifEmpty { band }
        return first.take(7)
    }

    /** Hours from hundredths without trailing zeros: 3650 is "36.5 h", 3600 is "36 h". */
    fun hours(hundredths: Int): String {
        val whole = hundredths / 100
        val frac = Math.abs(hundredths % 100)
        if (frac == 0) return "$whole h"
        val text = if (frac % 10 == 0) "${frac / 10}" else frac.toString().padStart(2, '0')
        return "$whole.$text h"
    }

    /** Paid minutes as hours, rounded half up to hundredths. */
    fun hoursFromMinutes(minutes: Int): String = hours((minutes * 100 + 30) / 60)

    /** Whole hours and minutes, e.g. 1350 is "22 h 30 m". */
    fun hoursAndMinutes(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) "$h h" else "$h h $m m"
    }

    private fun currencyFormat(currency: String): NumberFormat {
        val format = NumberFormat.getCurrencyInstance(Locale.getDefault())
        runCatching { format.currency = Currency.getInstance(currency) }
        return format
    }

    /** Money from integer cents, formatted for the locale ("$1,372.83"). The digits never go through a Double. */
    fun money(money: Money, currency: String): String =
        currencyFormat(currency).format(BigDecimal.valueOf(money.cents, 2))

    /** Money with an explicit sign: "+$12.30", "-$4.00", "$0.00". */
    fun signedMoney(money: Money, currency: String): String {
        val text = money(Money(Math.abs(money.cents)), currency)
        return when {
            money.cents > 0 -> "+$text"
            money.cents < 0 -> "-$text"
            else -> text
        }
    }

    /** Typed amount text as [Money] through the shared [MoneyParser] (exact cents, no Double). Null if empty or invalid. */
    fun parseMoney(text: String): Money? = MoneyParser.cents(text)?.let(::Money)

    /** Money as plain editable text ("1234.50"), or empty for null. */
    fun editableText(money: Money?): String = if (money == null) "" else MoneyParser.text(money.cents)

    // region Dates and times

    private val dayFormat get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val shortDateFormat get() = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val timeFormat get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())

    /** "Wed 2 Dec". */
    fun day(date: LocalDate): String = date.format(dayFormat)

    /** "2 Dec". */
    fun shortDate(date: LocalDate): String = date.format(shortDateFormat)

    /** "23 Nov - 29 Nov" (with an en dash). */
    fun weekTitle(weekStart: LocalDate): String = "${shortDate(weekStart)} – ${shortDate(weekStart.plusDays(6))}"

    /** A clock time as a localized string ("6:00 pm" or "18:00"). */
    fun time(time: LocalTime): String = time.format(timeFormat)

    // endregion

    fun warningText(w: HoursWarning, sourceName: String?): String {
        val worked = hoursAndMinutes(w.workedMinutes)
        return when (w.scope) {
            is HoursWarning.Scope.Weekly -> {
                val kind = if (w.level == HoursWarningLevel.hard) "hard" else "soft"
                "${sourceName ?: "This source"}: $worked this week, over the $kind weekly limit of ${w.limitHours} h."
            }
            HoursWarning.Scope.Fortnight ->
                "All sources: $worked in the fortnight ${shortDate(w.period.start)} to ${shortDate(w.period.end)}, " +
                    "over the limit of ${w.limitHours} h."
        }
    }
}
