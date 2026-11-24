package au.com.fuzn.beaver.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.ClockWindow
import au.com.fuzn.beaver.core.DaySelector
import au.com.fuzn.beaver.core.Deposit
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayReceived
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.RateRule
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.SpendCategory
import au.com.fuzn.beaver.core.TaxMethod
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.TemplateShift
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.core.WithholdingRow
import au.com.fuzn.beaver.core.WithholdingTable
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

fun d(s: String): LocalDate = LocalDate.parse(s)

/** A fresh in-memory database and repository for one test. */
class TestDb : AutoCloseable {
    val context: Context = ApplicationProvider.getApplicationContext()
    val database: BeaverDatabase = BeaverDatabase.inMemory(context)
    val repository = Repository(database)
    val backup = Backup(repository, "test")
    override fun close() = database.close()
}

/** Finds a file under the repo root from the Gradle-provided fixture path. */
fun backupFixtureFile(): File = File(requireNotNull(System.getProperty("beaver.fixture.backup")) { "beaver.fixture.backup not set" })

/** Saves a small data set that uses every table and every optional column, and returns nothing: read it back to compare. */
suspend fun Repository.saveRichData() {
    val card = save(RateCard(0, "Card", listOf(
        RateRule("Holiday", 6000, DaySelector.publicHoliday),
        RateRule("Day", 3000, DaySelector.weekdays, ClockWindow.hours(7, 19)),
        RateRule("Sat", 3750, DaySelector.saturday),
    )))
    save(WithholdingTable("custom", "Custom", listOf(WithholdingRow(500, 100, 200), WithholdingRow(null, 3000, 1000)), false, "a note"))
    val hourly = save(IncomeSource(0, "Bar", IncomeKind.hourly, card.id, null, false,
        PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1), TaxMethod.Table("au-scale2-weekly"), 20, 30))
    val fixed = save(IncomeSource(0, "Salary", IncomeKind.fixed, null, Money(250_000), true,
        PaySchedule.Fortnightly(d("2026-11-25"), 1), TaxMethod.FlatPercent(1500), isActive = false))
    save(IncomeSource(0, "Monthly", IncomeKind.fixed, null, Money(100_000), false, PaySchedule.Monthly(31), TaxMethod.None))
    save(Template(0, hourly.id, "Default", d("2026-01-05"), listOf(
        TemplateShift(DayOfWeek.FRIDAY, LocalTime.of(18, 0), LocalTime.of(4, 30), 30),
        TemplateShift(DayOfWeek.SUNDAY, LocalTime.of(9, 5), LocalTime.of(17, 0)),
    )))
    save(Shift(0, hourly.id, d("2026-11-26"), LocalTime.of(18, 0), LocalTime.of(23, 0), 30, ShiftStatus.worked, "busy"))
    save(PayReceived(0, hourly.id, d("2026-11-25"), Money(60_000), Money(70_000), Money(10_000), "slip"))
    save(PayReceived(0, fixed.id, d("2026-12-09"), Money(250_000)))
    save(Holiday(d("2026-12-25"), "Christmas Day"))
    save(UnavailableRange(0, d("2026-12-02"), d("2026-12-05"), "Trip"))
    save(FortnightLimit(0, 48, d("2026-07-01")))
    val cadences = listOf(Cadence.Weekly, Cadence.Fortnightly, Cadence.EveryNDays(28), Cadence.Monthly(5),
        Cadence.Quarterly, Cadence.Yearly, Cadence.OneOff)
    val bills = cadences.mapIndexed { i, c ->
        save(Bill(0, "Bill $i", Money(1000L + i), c, d("2026-11-2${i + 1}"), BillCategory.entries[i], if (i == 0) "Everyday" else null, i != 2, "n$i"))
    }
    markPaid(BillPayment(bills[0].id, d("2026-11-21"), d("2026-11-22"), Money(1000)))
    save(Expense(0, d("2026-11-24"), Money(1250), SpendCategory.coffeeDrinks, "flat white"))
    setWeekSpendTotal(d("2026-11-18"), Money(9900)) // a Wednesday: stored against its Monday
    val goal = save(Goal(0, "Course fees", Money(600_000), d("2027-03-31")))
    save(Deposit(0, goal.id, d("2026-11-20"), Money(5000), "first"))
    setCurrency("NZD"); setTimeZone("Pacific/Auckland"); setOnboardingDone(true)
    setPrimaryGoalId(goal.id); setLivingBudget(Money(15_000))
}
