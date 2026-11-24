package au.com.fuzn.beaver.ui

import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.ui.common.DateText
import au.com.fuzn.beaver.ui.common.FigureFormatter
import au.com.fuzn.beaver.ui.common.MoneyInput
import au.com.fuzn.beaver.ui.common.spokenMoney
import au.com.fuzn.beaver.ui.plan.WhatIfState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PlanModelTests {
    @Test
    fun planRowsTagIncomeAndRunBalance() = DemoStore().use { demo ->
        val model = demo.planModel()
        val rows = model.rows
        assertEquals(demo.store.forecast.value.weeks.size, rows.size)
        assertEquals("\$600.00 actual", rows.first().incomeText)
        assertTrue(rows.first().incomeParts.first().isActual)
        assertTrue(rows[1].incomeText.endsWith("est."))
        assertEquals("Pay Wed, Nov 25", rows.first().paydaysText)
        // The last balance equals the projected amount at the deadline.
        assertEquals("\$8,578.99", rows.last().balanceText)
        assertEquals(demo.store.forecast.value.dangerWeeks, rows.filter { it.isDanger }.map { it.week })
        assertTrue(rows.first().isCurrent)
        assertTrue(rows.last().isDeadlineWeek)
    }

    @Test
    fun planSummary() = DemoStore().use { demo ->
        val s = demo.planModel().summary
        assertEquals("+\$2,578.99", s.gapText)
        assertEquals("Ahead of goal", s.gapLabel)
        assertEquals("\$8,578.99", s.projectedText)
        assertEquals("Projected at deadline", s.projectedLabel)
    }

    @Test
    fun whatIfExtraIncomeRecomputesAndResets() = DemoStore().use { demo ->
        val base = demo.planModel()
        assertEquals("+\$2,578.99", base.summary.gapText)

        demo.store.setExtraWeeklyIncome(Money.dollars(50))
        val boosted = demo.planModel()
        assertTrue(boosted.rows.first().incomeText.contains("+ \$50.00 est."))
        assertNotEquals(base.summary.gapText, boosted.summary.gapText)
        assertTrue(boosted.detail(boosted.rows.first()).extraIncomeText == "\$50.00")

        demo.store.setExtraWeeklyIncome(Money.ZERO)
        assertEquals("+\$2,578.99", demo.planModel().summary.gapText)
    }

    @Test
    fun livingBudgetWritesThroughRepository() = DemoStore().use { demo ->
        runBlocking {
            demo.repository.setLivingBudget(Money.dollars(160))
            demo.store.reload()
        }
        assertEquals(Money.dollars(160), demo.store.settings.value.livingBudget)
        assertTrue(demo.store.forecast.value.gap.cents < 257_899)
        assertEquals("\$160.00", demo.planModel().rows.first().livingText)
    }

    @Test
    fun weekDetailListsPaydaysAndBills() = DemoStore().use { demo ->
        val model = demo.planModel()
        val detail = model.detail(model.rows.first())
        assertEquals(listOf(day("2026-11-25")), detail.pays.map { it.payday })
        assertEquals(listOf("Rent", "Gym"), detail.bills.map { it.name })
        assertFalse(detail.isDanger)
    }

    @Test
    fun dangerRowsAreMarkedAndDescribed() = DemoStore().use { demo ->
        val danger = demo.planModel().rows.first { it.isDanger }
        assertTrue(danger.accessibilityLabel.contains("Danger week"))
        assertTrue(danger.surplus.cents < 0)
        assertTrue(danger.surplusText.startsWith("-\$"))
        assertTrue(danger.accessibilityLabel.contains("minus"))
    }
}

class WhatIfStateTests {
    @Test
    fun activeWhenLivingDiffersOrExtraIncomeSet() {
        val start = WhatIfState.starting(Money.dollars(150))
        assertFalse(start.isActive(Money.ZERO))
        assertTrue(start.isActive(Money.dollars(25)))
        assertTrue(start.withLiving(16_000).isActive(Money.ZERO))
        assertFalse(start.withLiving(16_000).withLiving(15_000).isActive(Money.ZERO))
    }

    @Test
    fun livingIsClamped() {
        val start = WhatIfState.starting(Money.dollars(5))
        assertEquals(0, start.withLiving(-1_000).livingCents)
        assertEquals(WhatIfState.MAX_LIVING_CENTS, start.withLiving(99_999_999).livingCents)
    }
}

@RunWith(RobolectricTestRunner::class)
class FormattingTests {
    private val fmt = FigureFormatter("AUD", Locale.US)

    @Test
    fun figureFormatterSigns() {
        assertEquals("\$1,234.05", fmt.string(Money.dollars(1234, 5)))
        assertEquals("+\$5.00", fmt.signed(Money.dollars(5)))
        assertEquals("-\$5.00", fmt.signed(-Money.dollars(5)))
        assertEquals("-\$5.00", fmt.string(-Money.dollars(5)))
        assertEquals("\$0.00", fmt.signed(Money.ZERO))
    }

    @Test
    fun narrowSymbolsAndOtherCurrencies() {
        assertEquals("\$1.00", FigureFormatter("NZD", Locale.US).string(Money.dollars(1)))
        assertEquals("£2.50", FigureFormatter("GBP", Locale.US).string(Money.dollars(2, 50)))
        assertEquals("\$12,345,678.90", fmt.string(Money(1_234_567_890)))
    }

    @Test
    fun moneyInputParsing() {
        assertEquals(1200L, MoneyInput.cents("12"))
        assertEquals(1250L, MoneyInput.cents("12.5"))
        assertEquals(123_456L, MoneyInput.cents("\$1,234.56"))
        assertEquals(99L, MoneyInput.cents(".99"))
        assertNull(MoneyInput.cents("1.234"))
        assertNull(MoneyInput.cents("abc"))
        assertNull(MoneyInput.cents(""))
        assertNull(MoneyInput.cents("-5"))
    }

    @Test
    fun spokenMoneyReadsSigns() {
        assertEquals("minus \$5.00", spokenMoney("-\$5.00"))
        assertEquals("plus \$5.00", spokenMoney("+\$5.00"))
        assertEquals("\$5.00", spokenMoney("\$5.00"))
    }

    @Test
    fun dateText() {
        assertEquals("Wed, Nov 25", DateText.weekdayDayMonth(day("2026-11-25"), Locale.US))
        assertEquals("Nov 25", DateText.dayMonth(day("2026-11-25"), Locale.US))
        assertEquals("Nov 25, 2026", DateText.full(day("2026-11-25"), Locale.US))
        assertEquals("Nov 23 – Nov 29", DateText.range(day("2026-11-23"), day("2026-11-29"), Locale.US))
    }
}
