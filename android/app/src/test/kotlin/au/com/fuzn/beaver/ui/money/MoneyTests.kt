package au.com.fuzn.beaver.ui.money

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Smartphone
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.ForecastInput
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.data.TestDb
import au.com.fuzn.beaver.ui.editors.BillPreset
import au.com.fuzn.beaver.ui.editors.EditorLabels
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.util.Locale

/** Money tab logic over an in-memory Room repository with the fictional demo data. Today is 2026-11-23. */
@RunWith(RobolectricTestRunner::class)
class MoneyTests {
    private lateinit var db: TestDb
    private val repo get() = db.repository
    private val actions get() = MoneyActions(repo)
    private val today: LocalDate = LocalDate.of(2026, 11, 23)
    private val locale = Locale.US

    @Before fun setUp() {
        db = TestDb()
        runBlocking { repo.loadDemoData() }
    }

    @After fun tearDown() { db.close() }

    private fun d(s: String): LocalDate = LocalDate.parse(s)
    private fun input(): ForecastInput = runBlocking { repo.forecastInput(today) }
    private fun bills() = BillsModel(input(), "AUD", locale)
    private fun spending() = SpendingModel(input(), "AUD", locale)
    private fun goals() = GoalsModel(input(), "AUD", locale)
    private fun cents(n: Long) = Money(n)
    private fun billNamed(name: String) = runBlocking { repo.bills() }.first { it.name == name }

    // region Bills

    @Test fun rentOnPaydayIsInThatPaydaysGroup() {
        val groups = bills().groups
        val group = groups.first { it.payday == d("2026-11-25") }
        assertEquals("Before Wed, Nov 25", group.title)
        val rent = group.rows.first { it.name == "Rent" }
        assertEquals(d("2026-11-25"), rent.dueDate)
        // Gym is due the 27th, so it belongs to the next payday.
        assertTrue(group.rows.none { it.name == "Gym" })
        assertTrue(groups.first { it.payday == d("2026-12-02") }.rows.any { it.name == "Gym" })
        // Totals add up.
        assertEquals(group.rows.sumOf { it.amount.cents }, group.total.cents)
        assertEquals("$400.00", group.totalText)
    }

    @Test fun overdueSitsAboveTheGroupsAndLeavesThem() {
        val model = bills()
        // Phone was due 22 Nov and is unpaid.
        assertEquals(listOf("Phone"), model.overdue.map { it.name })
        assertEquals(d("2026-11-22"), model.overdue.first().dueDate)
        assertEquals("$30.00", model.fmt.string(model.overdueTotal))
        assertTrue(model.groups.flatMap { it.rows }.none { it.name == "Phone" && it.dueDate < today })
    }

    @Test fun withNoIncomeSourcesBillsGroupByWeek() {
        runBlocking { repo.deleteIncomeSource(DemoData.SOURCE_ID) }
        val groups = bills().groups
        assertTrue(groups.all { it.payday == null })
        assertEquals("This week", groups.first().title)
        assertTrue(groups.any { it.title == "Week of Nov 30" })
        assertTrue(groups.first().rows.any { it.name == "Rent" })
    }

    @Test fun monthlyViewIsOrderedByDayOfMonth() {
        val rows = bills().monthly
        // Dated bills by day (Streaming 5, Car service 10, Electricity 15), then weekly styles by name.
        assertEquals(
            listOf("Streaming", "Car service", "Electricity", "Gym", "Phone", "Rent"),
            rows.map { it.bill.name },
        )
        assertEquals(listOf(5, 10, 15, null, null, null), rows.map { it.dayOfMonth })
        assertEquals("Day 5 of each month", rows.first().detail)
        assertEquals("Every week, Wednesday", rows.last().detail)
    }

    @Test fun markPaidAndUnmarkRoundTrip() {
        val rent = billNamed("Rent")
        val row = bills().groups.flatMap { it.rows }.first { it.name == "Rent" && it.dueDate == d("2026-11-25") }
        assertFalse(row.isPaid)

        // The sheet pre-fills the planned amount and today's date.
        val form = MarkPaidForm(row, today)
        assertEquals("400.00", form.amountText)
        assertEquals(today, form.paidDate)
        assertTrue(form.isValid)
        runBlocking { form.copy(amountText = "385.50").save(actions) }

        val paid = bills().groups.flatMap { it.rows }.first { it.billID == rent.id && it.dueDate == d("2026-11-25") }
        assertTrue(paid.isPaid)
        assertEquals(38_550L, paid.amount.cents)
        assertEquals(today, paid.occurrence.paidDate)

        runBlocking { actions.unmarkPaid(rent.id, d("2026-11-25")) }
        val back = bills().groups.flatMap { it.rows }.first { it.billID == rent.id && it.dueDate == d("2026-11-25") }
        assertFalse(back.isPaid)
        assertEquals(40_000L, back.amount.cents)
    }

    @Test fun markPaidRejectsBlankAndZeroAmounts() {
        val row = bills().groups.first().rows.first()
        assertFalse(MarkPaidForm(row, "", today).isValid)
        assertFalse(MarkPaidForm(row, "0", today).isValid)
        assertFalse(MarkPaidForm(row, "abc", today).isValid)
        assertTrue(MarkPaidForm(row, "12.50", today).isValid)
    }

    @Test fun paidAmountSurvivesALaterBillAmountEdit() {
        val rent = billNamed("Rent")
        runBlocking { actions.markPaid(rent.id, d("2026-11-25"), today, Money.dollars(385)) }
        runBlocking { repo.save(rent.copy(amount = Money.dollars(450))) }

        val rows = bills().groups.flatMap { it.rows }.filter { it.billID == rent.id }
        assertEquals(Money.dollars(385), rows.first { it.dueDate == d("2026-11-25") }.amount)
        // The next occurrence uses the new amount.
        assertEquals(Money.dollars(450), rows.first { it.dueDate == d("2026-12-02") }.amount)
    }

    @Test fun pauseAndResume() {
        val streaming = billNamed("Streaming")
        runBlocking { actions.setActive(streaming, false) }
        var model = bills()
        assertEquals(listOf("Streaming"), model.pausedBills.map { it.name })
        assertTrue(model.groups.flatMap { it.rows }.none { it.name == "Streaming" })
        assertTrue(model.monthly.none { it.bill.name == "Streaming" })
        assertFalse(model.isEmpty)

        runBlocking { actions.setActive(model.pausedBills.first(), true) }
        model = bills()
        assertTrue(model.pausedBills.isEmpty())
        assertTrue(model.groups.flatMap { it.rows }.any { it.name == "Streaming" })
        assertTrue(model.monthly.any { it.bill.name == "Streaming" })
    }

    // endregion

    // region Icons

    @Test fun billIconComesFromThePresetNotTheCategory() {
        val phone = BillPreset.common.first { it.name == "Phone" }
        // Phone is filed under utilities, whose category icon is the bolt. The preset icon wins.
        assertEquals(BillCategory.utilities, phone.category)
        assertSame(phone.icon, BillIcons.icon("Phone", BillCategory.utilities))
        assertSame(Icons.Filled.Smartphone, BillIcons.icon("Phone", BillCategory.utilities))
        assertNotSame(Icons.Filled.Bolt, BillIcons.icon("Phone", BillCategory.utilities))
        assertSame(Icons.Filled.Bolt, BillIcons.icon("Electricity", BillCategory.utilities))
        // Case, spacing and extra words still find the preset.
        assertSame(phone.icon, BillIcons.icon("  phone ", BillCategory.other))
        assertSame(phone.icon, BillIcons.icon("Phone plan", BillCategory.other))
        // A multi-word preset needs its whole name.
        assertSame(BillPreset.common.first { it.name == "Transport card" }.icon, BillIcons.icon("Transport card", BillCategory.other))
        // No preset: the category icon.
        assertNull(BillIcons.presetFor("Car service"))
        assertSame(EditorLabels.categoryIcon(BillCategory.transport), BillIcons.icon("Car service", BillCategory.transport))
        // Whole words only: "Gasoline" is not "Gas".
        assertNull(BillIcons.presetFor("Gasoline"))
    }

    @Test fun rowsCarryThePresetIcon() {
        val model = bills()
        val phone = model.overdue.first { it.name == "Phone" }
        assertSame(Icons.Filled.Smartphone, phone.icon)
        assertSame(Icons.Filled.Smartphone, model.monthly.first { it.bill.name == "Phone" }.icon)
    }

    // endregion

    // region Spending

    private fun addExpense(date: String, dollars: Int, category: SpendCategory = SpendCategory.food) =
        runBlocking { repo.save(Expense(date = d(date), amount = Money.dollars(dollars), category = category)) }

    @Test fun thisWeekCardFigures() {
        addExpense("2026-11-23", 50)
        addExpense("2026-11-26", 30)
        val m = spending()
        assertEquals(Money.dollars(150), m.budget)
        assertEquals(Money.dollars(80), m.spentThisWeek)
        assertEquals(Money.dollars(70), m.leftThisWeek)
        assertEquals("$70.00 under", m.varianceText(m.varianceThisWeek))
        addExpense("2026-11-27", 100)
        assertEquals("$30.00 over", spending().varianceText(spending().varianceThisWeek))
        assertEquals("On budget", m.varianceText(Money.ZERO))
    }

    @Test fun weekTotalOverridesTheEntriesAndClearRestoresThem() {
        addExpense("2026-11-23", 50)
        addExpense("2026-11-26", 30)
        assertEquals(Money.dollars(80), spending().spentThisWeek)
        assertNull(spending().total(today))

        runBlocking { actions.setWeekTotal(d("2026-11-25"), Money.dollars(200)) } // a Wednesday: keyed to its Monday
        var m = spending()
        assertEquals(Money.dollars(200), m.spentThisWeek)
        assertEquals(Money.dollars(200), m.total(today))
        assertTrue(m.history.first { it.isCurrent }.hasTotal)
        // The entries are kept.
        assertEquals(2, m.expenses(today).size)

        // Changing the total replaces it; zero is allowed (nothing spent).
        runBlocking { WeekTotalForm(d("2026-11-23"), "0").save(actions) }
        assertEquals(Money.ZERO, spending().spentThisWeek)
        assertFalse(WeekTotalForm(d("2026-11-23"), "").isValid)

        runBlocking { actions.clearWeekTotal(today) }
        m = spending()
        assertNull(m.total(today))
        assertEquals(Money.dollars(80), m.spentThisWeek)
        assertFalse(m.history.first { it.isCurrent }.hasTotal)
    }

    @Test fun historyHasTwelveWeeksNewestFirst() {
        val history = spending().history
        assertEquals(12, history.size)
        assertTrue(history.first().isCurrent)
        assertEquals(d("2026-11-23"), history.first().weekStart)
        assertEquals(d("2026-09-07"), history.last().weekStart)
        assertNull(history.first().running)
        assertTrue(history.none { it.hasData })
    }

    @Test fun runningVarianceAddsCompletedWeeksWithData() {
        // Oldest to newest: 2 Nov (total $120: +$30), 9 Nov ($200: -$50), 16 Nov ($100: +$50). Weeks without data are skipped.
        runBlocking { actions.setWeekTotal(d("2026-11-02"), Money.dollars(120)) }
        addExpense("2026-11-10", 120)
        addExpense("2026-11-12", 80)
        addExpense("2026-11-17", 100)
        // The current week has spending too, but it is not complete, so it is not in the running total.
        addExpense("2026-11-24", 400)

        val m = spending()
        val byWeek = m.history.associateBy { it.weekStart.toString() }
        assertEquals(Money.dollars(30), byWeek.getValue("2026-11-02").variance)
        assertEquals(Money.dollars(30), byWeek.getValue("2026-11-02").running)
        assertEquals(Money.dollars(-50), byWeek.getValue("2026-11-09").variance)
        assertEquals(Money.dollars(-20), byWeek.getValue("2026-11-09").running)
        assertEquals(Money.dollars(50), byWeek.getValue("2026-11-16").variance)
        assertEquals(Money.dollars(30), byWeek.getValue("2026-11-16").running)
        // A week before any data keeps a zero running total and shows no data.
        assertFalse(byWeek.getValue("2026-10-26").hasData)
        assertEquals(Money.ZERO, byWeek.getValue("2026-10-26").running)
        assertNull(byWeek.getValue("2026-11-23").running)
        assertEquals(Money.dollars(30), m.runningVariance)
        // The engine agrees (its spendingVariance sums the same weeks).
        assertEquals(m.runningVariance, runBlocking { repo.forecastInput(today) }.let { au.com.fuzn.beaver.core.Forecast.compute(it).spendingVariance })
    }

    @Test fun editingAnExpenseCanChangeItsDateAndMovesItToAnotherWeek() {
        val saved = addExpense("2026-11-24", 40, SpendCategory.coffeeDrinks)
        assertEquals(Money.dollars(40), spending().spentThisWeek)

        val form = ExpenseForm(saved).copy(amountText = "42.50", date = d("2026-11-17"), category = SpendCategory.goingOut, note = " beers ")
        runBlocking { form.save(actions) }

        val m = spending()
        assertEquals(Money.ZERO, m.spentThisWeek)
        val moved = m.expenses(d("2026-11-17")).single()
        assertEquals(saved.id, moved.id)
        assertEquals(Money(4250), moved.amount)
        assertEquals(SpendCategory.goingOut, moved.category)
        assertEquals("beers", moved.note)
        assertEquals(d("2026-11-17"), moved.date)
        assertFalse(ExpenseForm(saved).copy(amountText = "0").isValid)
    }

    @Test fun deletingAnExpenseRemovesIt() {
        val a = addExpense("2026-11-24", 40)
        addExpense("2026-11-25", 10)
        runBlocking { actions.deleteExpense(a.id) }
        assertEquals(Money.dollars(10), spending().spentThisWeek)
        assertEquals(1, spending().expenses(today).size)
    }

    // endregion

    // region Goals

    @Test fun depositsUpdateProgressAndCanBeDeleted() {
        var row = goals().rows.single()
        assertEquals(Money.ZERO, row.saved)
        assertEquals(0, row.progressBasisPoints)
        assertTrue(row.isPrimary)
        assertEquals("$0.00", row.savedText)
        assertEquals("$6,000.00", row.targetText)
        assertEquals("by Mar 31, 2027", row.deadlineText)

        runBlocking { DepositForm(DemoData.GOAL_ID, "600", today, " first ").save(actions) }
        runBlocking { DepositForm(DemoData.GOAL_ID, "900", d("2026-11-20")).save(actions) }
        row = goals().rows.single()
        assertEquals(Money.dollars(1500), row.saved)
        assertEquals(2500, row.progressBasisPoints)
        assertEquals("25%", row.percentText)
        assertEquals(0.25f, row.progress, 0.0001f)
        // Newest first, notes trimmed.
        assertEquals(listOf(d("2026-11-23"), d("2026-11-20")), row.deposits.map { it.date })
        assertEquals("first", row.deposits.first().note)
        // The forecast follows the same deposits.
        assertEquals(Money.dollars(1500), au.com.fuzn.beaver.core.Forecast.compute(input()).depositsTotal)

        runBlocking { actions.deleteDeposit(row.deposits.first().id) }
        row = goals().rows.single()
        assertEquals(Money.dollars(900), row.saved)
        assertEquals(1500, row.progressBasisPoints)
    }

    @Test fun depositFormNeedsAPositiveAmount() {
        assertFalse(DepositForm(1, "", today).isValid)
        assertFalse(DepositForm(1, "0", today).isValid)
        assertTrue(DepositForm(1, "5", today).isValid)
    }

    @Test fun makePrimaryMovesTheBadge() {
        val second = runBlocking { repo.save(au.com.fuzn.beaver.core.Goal(0, "Laptop", Money.dollars(2000), null)) }
        assertEquals(DemoData.GOAL_ID, goals().primaryID)
        runBlocking { actions.makePrimary(second.id) }
        val rows = goals().rows
        assertEquals(second.id, goals().primaryID)
        assertTrue(rows.first { it.id == second.id }.isPrimary)
        assertFalse(rows.first { it.id == DemoData.GOAL_ID }.isPrimary)
        assertNull(rows.first { it.id == second.id }.deadlineText)
        assertNotNull(rows.first { it.id == DemoData.GOAL_ID }.deadlineText)
    }

    // endregion
}
