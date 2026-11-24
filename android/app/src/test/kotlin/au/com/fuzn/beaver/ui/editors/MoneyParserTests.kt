package au.com.fuzn.beaver.ui.editors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class MoneyParserTests {
    @Test fun wholeNumber() = assertEquals(1200L, MoneyParser.cents("12"))

    @Test fun oneDecimal() = assertEquals(1250L, MoneyParser.cents("12.5"))

    @Test fun twoDecimals() = assertEquals(1250L, MoneyParser.cents("12.50"))

    @Test fun thousandsSeparator() = assertEquals(123_456L, MoneyParser.cents("1,234.56"))

    @Test fun leadingDotTrailingDotAndDollarSign() {
        assertEquals(50L, MoneyParser.cents(".5"))
        assertEquals(1200L, MoneyParser.cents("12."))
        assertEquals(1200L, MoneyParser.cents("$12"))
        assertEquals(1200L, MoneyParser.cents("  \$ 12  "))
        assertEquals(0L, MoneyParser.cents("0"))
        assertEquals(5L, MoneyParser.cents("0.05"))
    }

    @Test fun largeAmountsStayExact() {
        // 999,999,999,999.99 would lose cents in a Double; here it is exact.
        assertEquals(99_999_999_999_999L, MoneyParser.cents("999,999,999,999.99"))
        assertEquals(99_999_999_999_999L, MoneyParser.cents("999999999999.99"))
    }

    @Test fun badInputIsRejected() {
        val bad = listOf(
            "", "   ", ".", "$", "abc", "12a", "1.2.3", "12.345", "-5", "+5", "--1", "1,23", "1,2345", "12,50",
            "1,234,5", ",123", "1 234.56", "1e3", "\u0661\u0662", "9999999999999", "12,5",
        )
        for (text in bad) assertNull("'$text' should be rejected", MoneyParser.cents(text))
    }

    @Test fun commaIsNeverADecimalSeparator() {
        // A comma-locale user typing "12,50" gets an error, not 1250 or 12.50.
        assertNull(MoneyParser.cents("12,50"))
        assertEquals(123_456L, MoneyParser.cents("1,234.56"))
    }

    @Test fun textRoundTrip() {
        assertEquals("12.50", MoneyParser.text(1250L))
        assertEquals("0.05", MoneyParser.text(5L))
        assertEquals("1234.56", MoneyParser.text(123_456L))
        assertEquals("-0.50", MoneyParser.text(-50L))
        for (cents in listOf(0L, 1L, 99L, 100L, 123_456L, 99_999_999_999_999L)) {
            assertEquals(cents, MoneyParser.cents(MoneyParser.text(cents)))
        }
    }

    @Test fun basisPoints() {
        assertEquals(1250, MoneyParser.basisPoints("12.5"))
        assertEquals(1500, MoneyParser.basisPoints("15"))
        assertNull(MoneyParser.basisPoints("$15"))
        assertNull(MoneyParser.basisPoints("fifteen"))
    }

    @Test fun displayUsesDotAndCommaWhateverTheLocale() {
        assertEquals("$1,234.56", MoneyParser.display(123_456, "AUD", Locale.forLanguageTag("en-AU")))
        assertEquals("$0.05", MoneyParser.display(5, "AUD", Locale.forLanguageTag("en-AU")))
        assertEquals("-$12.00", MoneyParser.display(-1200, "AUD", Locale.forLanguageTag("en-AU")))
        // German formats 1.234,56; this helper still uses the app's dot decimal.
        assertEquals("1,234.56", MoneyParser.display(123_456, "EUR", Locale.GERMANY).replace(Regex("[^0-9,.]"), ""))
    }
}
