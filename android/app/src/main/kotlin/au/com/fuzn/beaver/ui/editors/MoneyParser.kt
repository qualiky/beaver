package au.com.fuzn.beaver.ui.editors

import java.util.Currency
import java.util.Locale

/**
 * Parses and formats user-typed amounts as integer cents. Never uses `Double` or `BigDecimal`.
 *
 * Accepted: "12", "12.5", "12.50", "1,234.56", ".5", "12.", "$12". The decimal separator is always a dot, whatever
 * the device locale, and a comma is a thousands separator only when it groups exactly three digits. Rejected: empty
 * text, signs, more than two decimals, letters, bad grouping, and amounts with more than 12 whole digits.
 */
object MoneyParser {
    /** Text as integer cents, or null if it is not a valid amount. */
    fun cents(text: String): Long? {
        var t = text.trim()
        if (t.startsWith("$")) t = t.removePrefix("$").trim()
        if (t.isEmpty()) return null

        val dot = t.indexOf('.')
        val wholeText = if (dot < 0) t else t.substring(0, dot)
        val fractionText = if (dot < 0) "" else t.substring(dot + 1)
        if (wholeText.isEmpty() && fractionText.isEmpty()) return null
        if (fractionText.length > 2 || !fractionText.all(::isAsciiDigit)) return null
        val whole = wholeDigits(wholeText) ?: return null

        val fraction = if (fractionText.isEmpty()) 0L
        else fractionText.toLong() * (if (fractionText.length == 1) 10 else 1)
        return whole * 100 + fraction
    }

    /** The whole part with valid comma grouping removed. Empty text is 0 (for ".5"). */
    private fun wholeDigits(text: String): Long? {
        if (text.isEmpty()) return 0
        val digits: String
        if (text.contains(',')) {
            val groups = text.split(',')
            if (groups.first().length !in 1..3) return null
            if (!groups.drop(1).all { it.length == 3 }) return null
            digits = groups.joinToString("")
        } else {
            digits = text
        }
        if (digits.length > 12 || !digits.all(::isAsciiDigit)) return null
        return digits.toLong()
    }

    private fun isAsciiDigit(c: Char) = c in '0'..'9'

    /** Plain editable text for an amount: 1250 becomes "12.50". */
    fun text(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val a = kotlin.math.abs(cents)
        return "$sign${a / 100}.${(a % 100).toString().padStart(2, '0')}"
    }

    fun text(cents: Int): String = text(cents.toLong())

    /** Percentage text such as "12.5" as basis points (1250). Same rules as money (two decimals). */
    fun basisPoints(percentText: String): Int? {
        if (percentText.contains('$')) return null
        val v = cents(percentText) ?: return null
        return if (v > Int.MAX_VALUE) null else v.toInt()
    }

    /** The currency symbol for an ISO code, e.g. "$" for AUD in an Australian locale. Falls back to the code. */
    fun symbol(currencyCode: String, locale: Locale = Locale.getDefault()): String =
        try {
            Currency.getInstance(currencyCode).getSymbol(locale)
        } catch (_: IllegalArgumentException) {
            currencyCode
        }

    /** Display text such as "$1,234.56" with a dot decimal separator and comma grouping, whatever the locale. */
    fun display(cents: Long, currencyCode: String = "AUD", locale: Locale = Locale.getDefault()): String {
        val a = kotlin.math.abs(cents)
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        val sign = if (cents < 0) "-" else ""
        return "$sign${symbol(currencyCode, locale)}$whole.${(a % 100).toString().padStart(2, '0')}"
    }
}
