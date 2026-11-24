package au.com.fuzn.beaver.ui.onboarding

import androidx.compose.runtime.saveable.SaverScope
import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Forecast
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.IncomeSource
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.data.TestDb
import au.com.fuzn.beaver.data.backupFixtureFile
import au.com.fuzn.beaver.data.saveRichData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val TODAY: LocalDate = DemoData.referenceToday

/** Onboarding step logic against a real in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
class OnboardingTests {
    private lateinit var db: TestDb
    private val repo get() = db.repository

    @Before fun setUp() { db = TestDb() }
    @After fun tearDown() { db.close() }

    private fun model(mode: OnboardingMode = OnboardingMode.FirstRun) = OnboardingModel(
        repo, db.backup, mode, deviceCurrency = "AUD", deviceZone = ZoneId.of("Australia/Sydney"),
        clock = { TODAY }, locale = Locale.US,
    )

    // region Weekly equivalents

    @Test fun weeklyEquivalentRoundsHalfUpLikeIos() {
        assertEquals(40_000, WeeklyEquivalent.cents(Money.dollars(400), Cadence.Weekly))
        assertEquals(369, WeeklyEquivalent.cents(Money.dollars(15, 99), Cadence.Monthly(1)))
        assertEquals(1846, WeeklyEquivalent.cents(Money.dollars(240), Cadence.Quarterly))
        assertEquals(10_000, WeeklyEquivalent.cents(Money.dollars(200), Cadence.Fortnightly))
        assertEquals(1000, WeeklyEquivalent.cents(Money.dollars(520), Cadence.Yearly))
        assertEquals(500, WeeklyEquivalent.cents(Money.dollars(10), Cadence.EveryNDays(14)))
        assertEquals(0, WeeklyEquivalent.cents(Money.dollars(900), Cadence.OneOff))
        assertEquals(1, WeeklyEquivalent.roundDiv(1, 2)) // a tie goes up
        assertEquals(0, WeeklyEquivalent.cents(Money(-500), Cadence.Weekly)) // never negative
    }

    @Test fun weeklyBillsSkipsInactiveAndOneOff() {
        val anchor = LocalDate.of(2026, 11, 23)
        val bills = listOf(
            Bill(1, "Rent", Money.dollars(400), Cadence.Weekly, anchor),
            Bill(2, "Phone", Money.dollars(15, 99), Cadence.Monthly(23), anchor),
            Bill(3, "Old", Money.dollars(50), Cadence.Weekly, anchor, isActive = false),
            Bill(4, "Fine", Money.dollars(80), Cadence.OneOff, anchor),
        )
        assertEquals(Money(40_369), WeeklyEquivalent.bills(bills))
    }

    @Test fun weeklyIncomeOfDemoDataIsPositive() = runBlocking {
        repo.loadDemoData()
        val m = model()
        m.refresh()
        assertTrue(m.weeklyIncome.cents > 0)
        assertEquals(WeeklyEquivalent.income(DemoData.forecastInput()), m.weeklyIncome)
    }

    // endregion

    // region Navigation

    @Test fun nextWalksAllSevenStepsAndBackReturns() = runBlocking {
        val m = model()
        m.load()
        assertEquals(OnboardingStep.Welcome, m.step)
        assertEquals(7, m.stepCount)
        assertTrue(m.isFirst)
        repeat(6) { assertTrue(m.next()) }
        assertEquals(OnboardingStep.Summary, m.step)
        assertTrue(m.isLast)
        assertEquals(1f, m.progress, 0f)
        assertFalse(m.next()) // nothing after the summary
        m.back()
        assertEquals(OnboardingStep.Limits, m.step)
        repeat(5) { m.back() }
        assertEquals(OnboardingStep.Welcome, m.step)
        m.back()
        assertEquals(OnboardingStep.Welcome, m.step)
    }

    @Test fun onlyStepsTwoToSixCanBeSkipped() = runBlocking {
        val m = model()
        m.load()
        assertFalse(m.canSkip)
        assertFalse(m.skip())
        assertEquals(OnboardingStep.Welcome, m.step)
        m.next()
        repeat(5) {
            assertTrue(m.canSkip)
            assertTrue(m.skip())
        }
        assertEquals(OnboardingStep.Summary, m.step)
        assertFalse(m.canSkip)
        assertFalse(m.skip())
    }

    @Test fun skipNeverWritesAnything() = runBlocking {
        val m = model()
        m.load()
        m.next() // Welcome saves the currency and time zone; everything after must not write
        val before = repo.backupTables()
        m.next() // Income (nothing to commit)
        m.next() // Bills
        m.livingText = "150" // typed, then skipped
        m.skip()
        m.skip()
        m.skip()
        assertEquals(OnboardingStep.Summary, m.step)
        assertEquals(before, repo.backupTables())
        assertEquals(Money.ZERO, repo.settings().livingBudget)
        assertFalse(repo.settings().onboardingDone)
    }

    @Test fun welcomeNextSavesTheDeviceDefaults() = runBlocking {
        val m = model()
        m.load()
        assertEquals("AUD", m.currency)
        m.setCurrency("NZD")
        m.next()
        assertEquals("NZD", repo.settings().currency)
        assertEquals("Australia/Sydney", repo.settings().timeZoneId)
    }

    @Test fun livingBudgetIsValidatedAndSavedOnNext() = runBlocking {
        val m = model()
        m.load()
        repeat(3) { m.next() }
        assertEquals(OnboardingStep.Living, m.step)
        m.livingText = "abc"
        assertNotNull(m.livingIssue)
        assertFalse(m.canAdvance)
        assertFalse(m.next())
        assertEquals(OnboardingStep.Living, m.step)
        assertTrue(m.skip()) // an invalid budget blocks Next, not Skip
        m.back()
        m.livingText = "150.50"
        assertNull(m.livingIssue)
        assertTrue(m.next())
        assertEquals(Money(15_050), repo.settings().livingBudget)
    }

    @Test fun emptyLivingBudgetLeavesTheStoredValueAlone() = runBlocking {
        repo.setLivingBudget(Money(9_900))
        val m = model()
        m.load()
        assertEquals("99.00", m.livingText)
        m.livingText = ""
        repeat(3) { m.next() }
        assertTrue(m.next())
        assertEquals(Money(9_900), repo.settings().livingBudget)
    }

    // endregion

    // region Finish

    @Test fun finishSetsOnboardingDone() = runBlocking {
        val m = model()
        m.load()
        assertFalse(repo.settings().onboardingDone)
        assertTrue(m.finish())
        assertTrue(m.isFinished)
        assertTrue(repo.settings().onboardingDone)
    }

    @Test fun rerunFinishDoesNotChangeOnboardingDone() = runBlocking {
        repo.loadDemoData()
        assertTrue(repo.settings().onboardingDone)
        val m = model(OnboardingMode.Rerun)
        m.load()
        assertEquals("AUD", m.currency)
        assertTrue(m.sources.isNotEmpty()) // existing data is shown
        assertTrue(m.bills.isNotEmpty())
        assertTrue(m.livingText.isNotEmpty())
        assertTrue(m.finish())
        assertTrue(m.isFinished)
        assertTrue(repo.settings().onboardingDone)

        // And a re-run never turns the flag on by itself.
        repo.setOnboardingDone(false)
        val other = model(OnboardingMode.Rerun)
        other.load()
        other.finish()
        assertTrue(other.isFinished)
        assertFalse(repo.settings().onboardingDone)
    }

    // endregion

    // region Restore

    @Test fun restoreFromTheIosFixtureCompletesOnboardingWithTheGoldenGap() = runBlocking {
        val m = model()
        m.load()
        assertTrue(m.restore(backupFixtureFile().readText()))
        assertTrue(m.isFinished)
        assertNull(m.restoreError)
        assertTrue(repo.settings().onboardingDone)
        val forecast = Forecast.compute(repo.forecastInput(TODAY))
        assertEquals(Money(257_899), forecast.gap)
        assertEquals(Money(257_899), Forecast.compute(m.input!!).gap)
        assertEquals("$2,578.99 ahead", m.summary.headline)
    }

    @Test fun restoreSetsTheFlagWhenTheBackupLacksIt() = runBlocking {
        repo.loadDemoData()
        repo.setOnboardingDone(false)
        val text = db.backup.export() // exported part-way through setup
        val other = TestDb()
        try {
            val m = OnboardingModel(other.repository, other.backup, OnboardingMode.FirstRun, "AUD", clock = { TODAY }, locale = Locale.US)
            assertTrue(m.restore(text))
            assertTrue(other.repository.settings().onboardingDone)
        } finally {
            other.close()
        }
    }

    @Test fun anInvalidFileLeavesTheDatabaseUntouched() = runBlocking {
        repo.saveRichData()
        val before = repo.backupTables()
        val m = model()
        m.load()
        for (bad in listOf("not json at all", "{}", """{"schemaVersion":99,"appVersion":"x","exportedAt":"x","tables":{}}""")) {
            assertFalse(m.restore(bad))
            assertNotNull(m.restoreError)
            assertFalse(m.isFinished)
            assertEquals(before, repo.backupTables())
        }
        m.restoreError = null
        // Right version, broken content: the import is one transaction, so nothing changes.
        assertFalse(m.restore("""{"schemaVersion":1,"appVersion":"x","exportedAt":"x","tables":{"bill":[{"id":"oops"}]}}"""))
        assertNotNull(m.restoreError)
        assertEquals(before, repo.backupTables())
    }

    // endregion

    // region Summary

    @Test fun summaryWithoutAGoalShowsTheWeeklySurplus() = runBlocking {
        repo.save(IncomeSource(0, "Job", IncomeKind.fixed, null, Money.dollars(1000), true, PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1)))
        repo.save(Bill(0, "Rent", Money.dollars(100), Cadence.Weekly, TODAY))
        repo.setLivingBudget(Money.dollars(150))
        val m = model()
        m.load()
        val s = m.summary
        assertEquals("Weekly surplus ≈ $750.00", s.headline)
        assertFalse(s.hasGoal)
        assertFalse(s.isEmpty)
        assertEquals(Money.dollars(750), s.weeklySurplus)
    }

    @Test fun summaryShowsAShortfallAndAnEmptySetup() = runBlocking {
        val m = model()
        m.load()
        assertTrue(m.summary.isEmpty)
        repo.save(Bill(0, "Rent", Money.dollars(100), Cadence.Weekly, TODAY))
        m.refresh()
        assertEquals("Weekly shortfall ≈ $100.00", m.summary.headline)
    }

    @Test fun deletingASourceAlsoDeletesItsUnsharedRateCard() = runBlocking {
        repo.loadDemoData()
        val m = model(OnboardingMode.Rerun)
        m.load()
        val hourly = m.sources.first { it.kind == IncomeKind.hourly }
        val cardId = hourly.rateCardID!!
        m.deleteSource(hourly)
        assertTrue(m.sources.none { it.id == hourly.id })
        assertTrue(repo.rateCards().none { it.id == cardId })
    }

    // endregion

    // region Saved state and helpers

    @Test fun savedStateKeepsStepFieldsAndOpenEditor() = runBlocking {
        val m = model()
        m.load()
        m.setCurrency("NZD")
        repeat(3) { m.next() }
        m.livingText = "88"
        m.sheet = OnboardingSheet.NewBill("Rent")
        m.usualWeekPrompt = 7
        val saver = OnboardingModel.saver { model() }
        val saved = with(saver) { SaverScope { true }.save(m) }
        val restored = saver.restore(saved!!)!!
        assertEquals(OnboardingStep.Living, restored.step)
        assertEquals("NZD", restored.currency)
        assertEquals("88", restored.livingText)
        assertEquals(OnboardingSheet.NewBill("Rent"), restored.sheet)
        assertEquals(7, restored.usualWeekPrompt)
        restored.load() // a restored model keeps what the user typed
        assertEquals("88", restored.livingText)
        assertEquals("NZD", restored.currency)
    }

    @Test fun sheetKeysRoundTrip() {
        val sheets = listOf(
            OnboardingSheet.NewIncome(IncomeKind.hourly), OnboardingSheet.NewIncome(IncomeKind.fixed),
            OnboardingSheet.EditIncome(3), OnboardingSheet.UsualWeek(4), OnboardingSheet.NewBill("Cloud storage"),
            OnboardingSheet.NewBill(null), OnboardingSheet.EditBill(9), OnboardingSheet.EditGoal(null),
            OnboardingSheet.EditGoal(2), OnboardingSheet.Limits,
        )
        sheets.forEach { assertEquals(it, OnboardingSheet.parse(it.key)) }
        assertNull(OnboardingSheet.parse("nonsense"))
    }

    @Test fun timeZoneChoicesPutTheCurrentZoneFirstAndSearchWorks() {
        val zones = OnboardingModel.timeZoneChoices("Australia/Sydney")
        assertEquals("Australia/Sydney", zones.first())
        assertEquals(1, zones.count { it == "Australia/Sydney" })
        assertTrue("Europe/London" in zones)
        assertTrue(zones.none { it.startsWith("Etc/") || it.startsWith("SystemV/") })
        assertEquals(listOf("America/New_York"), OnboardingModel.filterZones(zones, "new york"))
        assertEquals(zones, OnboardingModel.filterZones(zones, "  "))
        assertTrue(OnboardingModel.filterZones(zones, "zzzz").isEmpty())
    }

    @Test fun currencyChoicesIncludeTheDeviceCurrency() {
        assertEquals(OnboardingModel.COMMON_CURRENCIES, model().currencyChoices)
        val krona = OnboardingModel(repo, db.backup, deviceCurrency = "sek")
        assertEquals("SEK", krona.currency)
        assertEquals(listOf("SEK") + OnboardingModel.COMMON_CURRENCIES, krona.currencyChoices)
        assertEquals("AUD", OnboardingModel(repo, db.backup, deviceCurrency = null).currency)
    }

    // endregion
}
