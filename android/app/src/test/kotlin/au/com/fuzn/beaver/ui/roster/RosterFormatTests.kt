package au.com.fuzn.beaver.ui.roster

import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RosterFormatTests {
    @Test fun bandChipsUseRateCardBandNames() {
        assertEquals(listOf("PH", "Day", "Eve", "Night", "Sat", "Sun"), DemoData.rateCard.bandNames.map(RosterFormat::chip))
        assertEquals("Unrated", RosterFormat.chip("Unrated"))
        assertEquals("Overtim", RosterFormat.chip("Overtime rate"))
    }

    @Test fun moneyParsingIsCentsSafe() {
        assertEquals(Money(123450), RosterFormat.parseMoney("1,234.50"))
        assertEquals(Money(1200), RosterFormat.parseMoney("$12"))
        assertNull(RosterFormat.parseMoney("12,5")) // shared parser: a comma only groups thousands
        assertEquals(Money(123400), RosterFormat.parseMoney("1,234"))
        assertNull(RosterFormat.parseMoney("0.005")) // more than two decimals
        assertEquals(Money(1999), RosterFormat.parseMoney("19.99"))
        assertEquals(Money(50), RosterFormat.parseMoney(".5"))
        assertNull(RosterFormat.parseMoney(""))
        assertNull(RosterFormat.parseMoney("abc"))
        assertNull(RosterFormat.parseMoney("-5"))
        assertNull(RosterFormat.parseMoney("1e5"))
        assertNull(RosterFormat.parseMoney("99999999999999999999"))
    }

    @Test fun editableTextRoundTrips() {
        assertEquals("1234.50", RosterFormat.editableText(Money(123450)))
        assertEquals("0.05", RosterFormat.editableText(Money(5)))
        assertEquals("", RosterFormat.editableText(null))
        assertEquals(Money(123450), RosterFormat.parseMoney(RosterFormat.editableText(Money(123450))))
    }

    @Test fun hoursFormatting() {
        assertEquals("36.5 h", RosterFormat.hours(3650))
        assertEquals("36 h", RosterFormat.hours(3600))
        assertEquals("4.69 h", RosterFormat.hours(469))
        assertEquals("5.5 h", RosterFormat.hoursFromMinutes(330))
        assertEquals("22 h 30 m", RosterFormat.hoursAndMinutes(1350))
    }

    @Test fun signedMoneyUsesExplicitSigns() {
        val plus = RosterFormat.signedMoney(Money(1230), "AUD")
        val minus = RosterFormat.signedMoney(Money(-400), "AUD")
        assertEquals('+', plus.first())
        assertEquals('-', minus.first())
        assertEquals(RosterFormat.money(Money(0), "AUD"), RosterFormat.signedMoney(Money.ZERO, "AUD"))
    }
}
