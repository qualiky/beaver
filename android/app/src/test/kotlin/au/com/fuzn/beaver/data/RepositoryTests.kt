package au.com.fuzn.beaver.data

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.BillPayment
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Expense
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.WithholdingRow
import au.com.fuzn.beaver.core.WithholdingTable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class RepositoryTests {
    private lateinit var db: TestDb
    private val repo get() = db.repository

    @Before fun setUp() { db = TestDb() }
    @After fun tearDown() { db.close() }

    // region Fresh database

    @Test fun freshDatabaseIsEmptyWithOnboardingFalseAndBuiltInTable() = runBlocking {
        val settings = repo.settings()
        assertFalse(settings.onboardingDone)
        assertEquals("AUD", settings.currency)
        assertNull(settings.lastExportAt)
        assertNull(settings.primaryGoalId)
        assertEquals(Money.ZERO, settings.livingBudget)
        assertEquals(0, repo.userRowCount())
        assertTrue(repo.incomeSources().isEmpty())
        val tables = repo.withholdingTables()
        assertEquals(1, tables.size)
        assertEquals(WithholdingTable.AU_SCALE2_WEEKLY_ID, tables[0].id)
        assertTrue(tables[0].isBuiltin)
        assertEquals(WithholdingTable.auScale2Weekly.rows, tables[0].rows)
        assertEquals(0, repo.forecastInput(DemoData.referenceToday).sources.size)
    }

    @Test fun tablesAndForeignKeysMatchTheIosSchema() = runBlocking {
        assertEquals(
            BeaverDatabase.TABLES_CHILDREN_FIRST.sorted(),
            repo.tableNames().filter { it != "sqlite_sequence" },
        )
        assertTrue(repo.foreignKeysEnabled())
    }

    @Test fun databaseFileIsBeaverDbInNoBackupDirectory() {
        val context = db.context
        val real = BeaverDatabase.open(context)
        try {
            runBlocking { Repository(real).settings() }
            val file = java.io.File(context.noBackupFilesDir, "beaver.db")
            assertTrue(file.exists())
            runBlocking { assertEquals(1, Repository(real).withholdingTables().size) }
        } finally {
            real.close()
        }
    }

    // endregion

    // region Round trips

    @Test fun rateCardRoundTrip() = runBlocking {
        val saved = repo.save(RateCard.typicalCasual())
        assertTrue(saved.id > 0)
        assertEquals(listOf(saved), repo.rateCards())
        val edited = saved.copy(name = "Edited")
        repo.save(edited)
        assertEquals(listOf(edited), repo.rateCards())
        repo.deleteRateCard(saved.id)
        assertTrue(repo.rateCards().isEmpty())
    }

    @Test fun withholdingTableRoundTripAndBuiltInIsReadOnly() = runBlocking {
        val custom = WithholdingTable("mine", "Mine", listOf(WithholdingRow(null, 1000, 0)), isBuiltin = true, note = "x")
        val saved = repo.save(custom)
        assertFalse(saved.isBuiltin) // a saved table is never built-in
        assertEquals(2, repo.withholdingTables().size)
        assertEquals("au-scale2-weekly", repo.withholdingTables().first().id) // built-ins first
        assertEquals(listOf(saved), repo.forecastInput(DemoData.referenceToday).withholdingTables)
        repo.deleteWithholdingTable("mine")
        assertEquals(1, repo.withholdingTables().size)
        try {
            repo.save(WithholdingTable.auScale2Weekly.copy(name = "Hacked"))
            fail("expected BuiltinReadOnly")
        } catch (_: DataError.BuiltinReadOnly) {
        }
        try {
            repo.deleteWithholdingTable(WithholdingTable.AU_SCALE2_WEEKLY_ID)
            fail("expected BuiltinReadOnly")
        } catch (_: DataError.BuiltinReadOnly) {
        }
        assertEquals("AU ATO Schedule 1".take(2), repo.withholdingTables().single().name.take(2))
    }

    @Test fun everyRecordTypeRoundTrips() = runBlocking {
        repo.saveRichData()
        val sources = repo.incomeSources()
        assertEquals(3, sources.size)
        assertEquals(IncomeKind.hourly, sources[0].kind)
        assertEquals(PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1), sources[0].schedule)
        assertEquals(20, sources[0].weeklyCapSoftHours)
        assertEquals(30, sources[0].weeklyCapHardHours)
        assertEquals(Money(250_000), sources[1].fixedAmount)
        assertTrue(sources[1].amountIsNet)
        assertFalse(sources[1].isActive)
        assertEquals(PaySchedule.Monthly(31), sources[2].schedule)

        val templates = repo.templates()
        assertEquals(1, templates.size)
        assertEquals(d("2026-01-05"), templates[0].effectiveFrom)
        assertEquals(listOf(DayOfWeek.FRIDAY, DayOfWeek.SUNDAY), templates[0].shifts.map { it.weekday })
        assertEquals(LocalTime.of(9, 5), templates[0].shifts[1].start)
        assertEquals(30, templates[0].shifts[0].breakMinutes)

        val shift = repo.shifts().single()
        assertEquals(ShiftStatus.worked, shift.status)
        assertEquals("busy", shift.note)
        assertEquals(1, repo.shifts(DateRange(d("2026-11-26"), d("2026-11-26")), sourceId = sources[0].id).size)
        assertEquals(0, repo.shifts(DateRange(d("2026-11-26"), d("2026-11-26")), sourceId = sources[1].id).size)

        val pays = repo.paysReceived()
        assertEquals(2, pays.size)
        assertEquals(Money(70_000), pays[0].gross)
        assertEquals(Money(10_000), pays[0].tax)
        assertNull(pays[1].gross)

        assertEquals("Christmas Day", repo.holidays().single().name)
        assertEquals("Trip", repo.unavailableRanges().single().note)
        assertEquals(48, repo.hoursLimits().single().hoursPerFortnight)

        val bills = repo.bills()
        assertEquals(7, bills.size)
        assertEquals(listOf(Cadence.Weekly, Cadence.Fortnightly, Cadence.EveryNDays(28), Cadence.Monthly(5),
            Cadence.Quarterly, Cadence.Yearly, Cadence.OneOff), bills.map { it.cadence })
        assertEquals("Everyday", bills[0].account)
        assertFalse(bills[2].isActive)
        assertEquals(BillCategory.entries[3], bills[3].category)

        assertEquals(BillPayment(bills[0].id, d("2026-11-21"), d("2026-11-22"), Money(1000)), repo.billPayments().single())
        assertEquals("flat white", repo.expenses().single().note)
        assertEquals(1, repo.expenses(DateRange(d("2026-11-23"), d("2026-11-29"))).size)
        assertEquals(0, repo.expenses(DateRange(d("2026-11-16"), d("2026-11-22"))).size)
        assertEquals(listOf(Money(9900) to d("2026-11-16")), repo.weekSpendTotals().map { it.amount to it.weekStart })
        assertEquals("Course fees", repo.goals().single().name)
        assertEquals("first", repo.deposits().single().note)

        val settings = repo.settings()
        assertEquals("NZD", settings.currency)
        assertEquals("Pacific/Auckland", settings.timeZoneId)
        assertTrue(settings.onboardingDone)
        assertEquals(repo.goals().single().id, settings.primaryGoalId)
        assertEquals(Money(15_000), settings.livingBudget)
        repo.setPrimaryGoalId(null)
        assertNull(repo.settings().primaryGoalId)
    }

    @Test fun templateSaveReplacesShifts() = runBlocking {
        val card = repo.save(RateCard.typicalCasual())
        val source = repo.save(IncomeSource(0, "S", IncomeKind.hourly, card.id, schedule = PaySchedule.Weekly(DayOfWeek.FRIDAY, 1)))
        val t = repo.save(Template(0, source.id, "T", null, listOf(
            au.com.fuzn.beaver.core.TemplateShift(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0), 30))))
        repo.save(t.copy(shifts = listOf(
            au.com.fuzn.beaver.core.TemplateShift(DayOfWeek.TUESDAY, LocalTime.of(10, 0), LocalTime.of(12, 0)))))
        val only = repo.templates().single()
        assertEquals(listOf(DayOfWeek.TUESDAY), only.shifts.map { it.weekday })
    }

    @Test fun markPaidReplacesAndUnmarkPaidClears() = runBlocking {
        val bill = repo.save(Bill(0, "Rent", Money(40_000), Cadence.Weekly, d("2026-11-25")))
        repo.markPaid(BillPayment(bill.id, d("2026-11-25"), d("2026-11-25"), Money(40_000)))
        repo.markPaid(BillPayment(bill.id, d("2026-11-25"), d("2026-11-26"), Money(41_000)))
        val payments = repo.billPayments()
        assertEquals(1, payments.size)
        assertEquals(Money(41_000), payments[0].amount)
        assertEquals(d("2026-11-26"), payments[0].paidDate)
        repo.unmarkPaid(bill.id, d("2026-11-25"))
        assertTrue(repo.billPayments().isEmpty())
    }

    @Test fun setWeekSpendTotalStoresTheMondayAndReplaces() = runBlocking {
        repo.setWeekSpendTotal(d("2026-11-26"), Money(100)) // a Thursday
        repo.setWeekSpendTotal(d("2026-11-24"), Money(200)) // same week
        assertEquals(1, repo.weekSpendTotals().size)
        assertEquals(d("2026-11-23"), repo.weekSpendTotals()[0].weekStart)
        assertEquals(Money(200), repo.weekSpendTotals()[0].amount)
        repo.clearWeekSpendTotal(d("2026-11-29")) // the Sunday of that week
        assertTrue(repo.weekSpendTotals().isEmpty())
    }

    // endregion

    // region Constraints

    @Test fun foreignKeysCascadeAndSetNull() = runBlocking {
        repo.saveRichData()
        val card = repo.rateCards().single()
        repo.deleteRateCard(card.id)
        assertNull(repo.incomeSources()[0].rateCardID) // ON DELETE SET NULL
        val hourly = repo.incomeSources()[0]
        repo.deleteIncomeSource(hourly.id) // cascades to template, template shifts, shifts, pays
        assertTrue(repo.templates().isEmpty())
        assertTrue(repo.shifts().isEmpty())
        assertEquals(1, repo.paysReceived().size)
        repo.deleteBill(repo.bills()[0].id)
        assertTrue(repo.billPayments().isEmpty())
        repo.deleteGoal(repo.goals().single().id)
        assertTrue(repo.deposits().isEmpty())
    }

    @Test fun foreignKeyViolationIsRejected() = runBlocking {
        try {
            repo.save(Shift(0, 999, d("2026-11-26"), LocalTime.of(9, 0), LocalTime.of(10, 0), 0, ShiftStatus.rostered))
            fail("expected a foreign key failure")
        } catch (_: Exception) {
        }
        assertTrue(repo.shifts().isEmpty())
    }

    @Test fun billPaymentIsUniquePerBillAndDueDate() = runBlocking {
        val bill = repo.save(Bill(0, "Rent", Money(40_000), Cadence.Weekly, d("2026-11-25")))
        val dao = db.database.dao()
        dao.upsert(BillPaymentEntity(0, bill.id, "2026-11-25", "2026-11-25", 1))
        try {
            dao.insertBillPayments(listOf(BillPaymentEntity(0, bill.id, "2026-11-25", "2026-11-26", 2)))
            fail("expected a unique violation")
        } catch (_: Exception) {
        }
        assertEquals(1, repo.billPayments().size)
    }

    // endregion

    // region Demo data, erase, forecast

    @Test fun demoDataGivesTheGoldenForecast() = runBlocking {
        repo.loadDemoData()
        val settings = repo.settings()
        assertTrue(settings.onboardingDone)
        assertEquals(DemoData.GOAL_ID, settings.primaryGoalId)
        assertEquals(DemoData.livingBudget, settings.livingBudget)
        val input = repo.forecastInput(DemoData.referenceToday)
        assertEquals(DemoData.forecastInput().copy(withholdingTables = emptyList()), input)
        val forecast = Forecast.compute(input)
        assertEquals(Money(257_899), forecast.gap)
        assertEquals(Money(2_017_795), forecast.totalIncome)
        assertEquals(Money(874_896), forecast.totalBills)
        assertEquals(forecast, Forecast.compute(DemoData.forecastInput()))
    }

    @Test fun eraseAllKeepsOnlyTheBuiltInTable() = runBlocking {
        repo.saveRichData()
        repo.eraseAll()
        assertEquals(0, repo.userRowCount())
        assertFalse(repo.settings().onboardingDone)
        assertEquals(listOf(WithholdingTable.AU_SCALE2_WEEKLY_ID), repo.withholdingTables().map { it.id })
        repo.loadDemoData()
        repo.loadDemoData() // wiping first makes loading twice safe
        assertEquals(1, repo.incomeSources().size)
        assertEquals(1, repo.withholdingTables().size)
    }

    @Test fun expensesAndGoalsFeedTheForecast() = runBlocking {
        repo.loadDemoData()
        repo.save(Expense(0, d("2026-11-17"), Money(20_000)))
        val goal = repo.save(Goal(0, "Other", Money(1_000), null))
        repo.setPrimaryGoalId(goal.id)
        val input = repo.forecastInput(DemoData.referenceToday)
        assertEquals(goal.id, input.primaryGoalID)
        assertEquals(1, input.expenses.size)
        assertEquals(2, input.goals.size)
    }

    // endregion

    // region Observation and AppStore

    @Test fun observeEmitsCurrentThenChanges() = runBlocking {
        assertFalse(repo.observeSettings().first().onboardingDone)
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val flow = repo.observeSettings()
            val next = async(Dispatchers.Default) {
                withTimeout(10_000) { flow.first { it.onboardingDone } }
            }
            kotlinx.coroutines.delay(300)
            repo.setOnboardingDone(true)
            assertTrue(next.await().onboardingDone)
        } finally {
            scope.cancel()
        }
    }

    @Test fun appStoreRecomputesWhenTheDatabaseChanges() = runBlocking {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        try {
            val store = AppStore.create(repo, scope) { DemoData.referenceToday }
            assertFalse(store.settings.value.onboardingDone)
            assertEquals(Money.ZERO, store.forecast.value.totalIncome)

            repo.loadDemoData()
            withTimeout(10_000) { store.forecast.first { it.gap == Money(257_899) } }
            assertTrue(store.settings.value.onboardingDone)
            assertEquals(Money(2_017_795), store.forecast.value.totalIncome)

            // What-if: extra weekly income lifts the gap by one amount per forecast week, without saving anything.
            val weeks = store.forecast.value.weeks.size
            store.setExtraWeeklyIncome(Money(1_000))
            assertEquals(Money(257_899L + 1_000L * weeks), store.forecast.value.gap)
            assertEquals(Money(1_000), store.extraWeeklyIncome.value)
            assertEquals(Money.ZERO, repo.forecastInput(DemoData.referenceToday).extraWeeklyIncome)
            store.setExtraWeeklyIncome(Money.ZERO)
            assertEquals(Money(257_899), store.forecast.value.gap)
        } finally {
            scope.cancel()
        }
    }

    // endregion
}
