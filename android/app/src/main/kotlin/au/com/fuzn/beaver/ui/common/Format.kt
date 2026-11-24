package au.com.fuzn.beaver.ui.common

import android.text.format.DateFormat
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.Money
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

/**
 * Formats [Money] in the user's currency with the narrow symbol ("$" for AUD) so amounts stay short.
 * The sign is written by us ("-$5.00", "+$5.00") so it reads the same in every locale.
 */
class FigureFormatter(val currency: String, val locale: Locale = Locale.getDefault()) {
    private fun magnitude(cents: Long): String {
        val format = NumberFormat.getCurrencyInstance(locale) as DecimalFormat
        val code = runCatching { Currency.getInstance(currency) }.getOrNull()
        if (code != null) format.currency = code // resets the symbol, so set ours after this
        format.decimalFormatSymbols = format.decimalFormatSymbols.also { it.currencySymbol = narrowSymbol(currency) }
        format.minimumFractionDigits = 2
        format.maximumFractionDigits = 2
        return format.format(BigDecimal.valueOf(Math.abs(cents)).movePointLeft(2))
    }

    /** "$1,234.00" or "-$5.00". */
    fun string(money: Money): String = if (money.cents < 0) "-" + magnitude(money.cents) else magnitude(money.cents)

    /** "+$1.00", "-$1.00", or "$0.00" for zero. */
    fun signed(money: Money): String = when {
        money.cents > 0 -> "+" + magnitude(money.cents)
        money.cents < 0 -> "-" + magnitude(money.cents)
        else -> magnitude(0)
    }

    companion object {
        private val narrow = mapOf(
            "AUD" to "$", "USD" to "$", "NZD" to "$", "CAD" to "$", "SGD" to "$", "HKD" to "$", "MXN" to "$",
            "GBP" to "£", "EUR" to "€", "JPY" to "¥", "CNY" to "¥", "INR" to "₹", "KRW" to "₩", "ILS" to "₪",
            "PHP" to "₱", "VND" to "₫", "THB" to "฿",
        )

        fun narrowSymbol(code: String): String = narrow[code] ?: code
    }
}

/** Date text built from the locale's best pattern, so the order follows the device ("Wed, Nov 25" or "Wed 25 Nov"). */
object DateText {
    private fun format(day: LocalDate, skeleton: String, fallback: String, locale: Locale): String {
        val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: fallback
        return DateTimeFormatter.ofPattern(pattern, locale).format(day)
    }

    /** "Wed, Nov 25". */
    fun weekdayDayMonth(day: LocalDate, locale: Locale = Locale.getDefault()) = format(day, "EEEdMMM", "EEE, MMM d", locale)

    /** "Nov 25". */
    fun dayMonth(day: LocalDate, locale: Locale = Locale.getDefault()) = format(day, "dMMM", "MMM d", locale)

    /** "Nov 25, 2026". */
    fun full(day: LocalDate, locale: Locale = Locale.getDefault()) = format(day, "dMMMy", "MMM d, y", locale)

    fun range(start: LocalDate, end: LocalDate, locale: Locale = Locale.getDefault()) =
        "${dayMonth(start, locale)} – ${dayMonth(end, locale)}"

    fun range(range: DateRange, locale: Locale = Locale.getDefault()) = range(range.start, range.end, locale)
}

/** Parses a typed amount ("12", "12.5", "$1,234.50") into cents without floating point. Null when invalid. */
object MoneyInput {
    fun cents(text: String): Long? {
        val cleaned = text.filter { !it.isWhitespace() && it != ',' && it != '$' }
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split(".")
        if (parts.size > 2 || parts.any { p -> p.any { it !in '0'..'9' } }) return null
        val whole = parts[0].ifEmpty { "0" }
        var fraction = if (parts.size == 2) parts[1] else ""
        if (fraction.length > 2 || whole.length > 9) return null
        fraction = fraction.padEnd(2, '0')
        return whole.toLong() * 100 + fraction.toLong()
    }
}

/** Screen-reader text for a money string: "-$5.00" is spoken "minus $5.00". */
fun spokenMoney(text: String): String = when {
    text.startsWith("-") -> "minus " + text.drop(1)
    text.startsWith("+") -> "plus " + text.drop(1)
    else -> text
}
