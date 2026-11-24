package au.com.fuzn.beaver.ui.editors

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.TaxMethod
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.data.TestDb
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private val TODAY = LocalDate.of(2026, 11, 23)

/** The editor save paths against a real in-memory Room database. */
@RunWith(RobolectricTestRunner::class)
class EditorRepositoryTests {
    private lateinit var db: TestDb
    private val repo get() = db.repository

    @Before fun setUp() { db = TestDb() }
    @After fun tearDown() { db.close() }

    // region Rate card and income source

    @Test fun rateCardEditorRoundTripKeepsItsId() = runBlocking {
        val create = RateCardEditorModel()
        create.name = "Bar rates"
        create.applyTypicalCasual()
        val stored = create.save(repo)
        assertTrue(stored.id > 0)
        assertEquals(listOf(stored), repo.rateCards())

        val edit = RateCardEditorModel(stored)
        edit.setRate(edit.rules[1].id, "31.50")
        val updated = edit.save(repo)
        assertEquals(stored.id, updated.id)
        assertEquals(1, repo.rateCards().size)
        assertEquals(3150, repo.rateCards().single().rules[1].centsPerHour)
    }

    @Test fun invalidRateCardCannotBeSaved() = runBlocking {
        val m = RateCardEditorModel()
        try {
            m.save(repo)
            fail("expected EditorInvalidException")
        } catch (_: EditorInvalidException) {
        }
        assertTrue(m.visibleNameIssue != null && m.visibleRulesIssue != null)
        assertTrue(repo.rateCards().isEmpty())
    }

    private suspend fun newHourly(name: String = "Example Bar"): IncomeSourceEditorModel {
        val m = IncomeSourceEditorModel(null, IncomeKind.hourly, TODAY)
        m.load(repo)
        m.name = name
        m.rateCard.applyTypicalCasual()
        m.taxKind = IncomeTaxKind.Table
        return m
    }

    @Test fun newHourlySourceCreatesOneRateCard() = runBlocking {
        val m = newHourly()
        assertTrue(m.isLoaded)
        assertTrue(m.isValid)
        val source = m.save(repo)
        assertTrue(source.id > 0)
        assertEquals(1, repo.incomeSources().size)
        val cards = repo.rateCards()
        assertEquals(1, cards.size)
        assertEquals(cards.single().id, source.rateCardID)
        assertEquals("Example Bar rates", cards.single().name)
        // The first stored table (the built-in one) is chosen for "Table" tax.
        assertEquals(TaxMethod.Table(WithholdingTable.AU_SCALE2_WEEKLY_ID), source.taxMethod)
    }

    @Test fun editingAnHourlySourceDoesNotCreateASecondRateCard() = runBlocking {
        val first = newHourly().save(repo)

        // Reopen the editor the way the screen does: a model from the stored source, then load().
        repeat(3) { round ->
            val edit = IncomeSourceEditorModel(repo.incomeSources().single(), today = TODAY)
            assertFalse("not saveable before the load finishes", edit.isLoaded)
            edit.load(repo)
            assertEquals(first.rateCardID, edit.rateCard.id)
            assertEquals(6, edit.rateCard.rules.size)
            edit.name = "Renamed $round"
            edit.rateCard.setRate(edit.rateCard.rules[0].id, "${61 + round}.00")
            val saved = edit.save(repo)
            assertEquals(first.id, saved.id)
            assertEquals(first.rateCardID, saved.rateCardID)
            assertEquals("one source after edit $round", 1, repo.incomeSources().size)
            assertEquals("one rate card after edit $round", 1, repo.rateCards().size)
        }
        val card = repo.rateCards().single()
        assertEquals("Renamed 2 rates", card.name)
        assertEquals(6300, card.rules[0].centsPerHour)
    }

    @Test fun editingBeforeLoadStillReusesTheCardId() = runBlocking {
        val first = newHourly().save(repo)
        // Even if the card could not be adopted, the embedded model already carries the existing id.
        val edit = IncomeSourceEditorModel(repo.incomeSources().single(), today = TODAY)
        edit.rateCard.applyTypicalCasual()
        edit.taxKind = IncomeTaxKind.None // tables are not loaded either, so a table choice would be invalid
        edit.save(repo)
        assertEquals(1, repo.rateCards().size)
        assertEquals(first.rateCardID, repo.rateCards().single().id)
    }

    @Test fun fixedSourceRoundTripWithFlatTaxAndCaps() = runBlocking {
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        m.load(repo)
        m.name = "Salary"
        m.fixedAmountText = "2,500.00"
        m.scheduleKind = IncomeScheduleKind.Fortnightly
        m.anchorPayday = LocalDate.of(2026, 11, 25)
        m.taxKind = IncomeTaxKind.Flat
        m.flatPercentText = "15"
        m.softCapText = "30"
        m.hardCapText = "38"
        val saved = m.save(repo)
        assertTrue(repo.rateCards().isEmpty())

        val stored = repo.incomeSources().single()
        assertEquals(saved, stored)
        assertEquals(Money(250_000), stored.fixedAmount)
        assertEquals(PaySchedule.Fortnightly(LocalDate.of(2026, 11, 25), 1), stored.schedule)
        assertEquals(TaxMethod.FlatPercent(1500), stored.taxMethod)
        assertEquals(30, stored.weeklyCapSoftHours)
        assertEquals(38, stored.weeklyCapHardHours)

        val edit = IncomeSourceEditorModel(stored, today = TODAY)
        edit.load(repo)
        assertEquals("2500.00", edit.fixedAmountText)
        assertEquals("15.00", edit.flatPercentText)
        edit.scheduleKind = IncomeScheduleKind.Monthly
        edit.dayOfMonth = 31
        edit.isActive = false
        edit.save(repo)
        assertEquals(PaySchedule.Monthly(31), repo.incomeSources().single().schedule)
        assertFalse(repo.incomeSources().single().isActive)
        assertEquals(1, repo.incomeSources().size)
    }

    @Test fun savingAnInvalidSourceWritesNothing() = runBlocking {
        val m = IncomeSourceEditorModel(null, IncomeKind.hourly, TODAY)
        m.load(repo)
        try {
            m.save(repo)
            fail("expected EditorInvalidException")
        } catch (_: EditorInvalidException) {
        }
        assertTrue(repo.incomeSources().isEmpty())
        assertTrue(repo.rateCards().isEmpty())
        assertEquals("Enter a name, such as Example Bar.", m.visibleIssue(IncomeSourceEditorModel.Field.Name))
    }

    @Test fun deletingASourceDeletesItsRateCardUnlessAnotherSourceUsesIt() = runBlocking {
        val a = newHourly("A").save(repo)
        val editA = IncomeSourceEditorModel(a, today = TODAY).also { it.load(repo) }
        // A second source sharing A's card (as imported data may do).
        repo.save(a.copy(id = 0, name = "B"))
        editA.delete(repo)
        assertEquals(1, repo.incomeSources().size)
        assertEquals(1, repo.rateCards().size)

        val b = repo.incomeSources().single()
        IncomeSourceEditorModel(b, today = TODAY).also { it.load(repo) }.delete(repo)
        assertTrue(repo.incomeSources().isEmpty())
        assertTrue(repo.rateCards().isEmpty())
    }

    @Test fun tablesAreLoadedBuiltInFirst() = runBlocking {
        repo.save(WithholdingTable("custom", "Custom", DemoWithholding.rows))
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        m.load(repo)
        assertEquals(listOf(WithholdingTable.AU_SCALE2_WEEKLY_ID, "custom"), m.tables.map { it.id })
    }

    // endregion

    // region Templates

    @Test fun templateRoundTripAndEffectiveFrom() = runBlocking {
        val source = newHourly().save(repo)
        val m = TemplateEditorModel(source.id, today = TODAY)
        m.addShift()
        m.updateShift(m.shifts.single().id) { it.copy(weekday = DayOfWeek.SATURDAY, start = LocalTime.of(18, 0), end = LocalTime.of(2, 0)) }
        m.addShift() // Monday
        m.hasEffectiveFrom = true
        m.effectiveFrom = LocalDate.of(2027, 1, 4)
        val saved = m.save(repo)
        val stored = repo.templates().single()
        assertEquals(saved, stored)
        assertEquals(LocalDate.of(2027, 1, 4), stored.effectiveFrom)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.SATURDAY), stored.shifts.map { it.weekday })

        val edit = TemplateEditorModel(source.id, stored, TODAY)
        edit.name = "Renamed"
        edit.save(repo)
        assertEquals(1, repo.templates().size)
        assertEquals("Renamed", repo.templates().single().name)
        edit.delete(repo)
        assertTrue(repo.templates().isEmpty())
    }

    @Test fun templatesListIsPerSourceOrderedByEffectiveFrom() = runBlocking {
        val a = newHourly("A").save(repo)
        val b = newHourly("B").save(repo)
        fun model(source: Int, name: String, from: LocalDate?) = TemplateEditorModel(source, today = TODAY).also {
            it.name = name; it.addShift()
            if (from != null) { it.hasEffectiveFrom = true; it.effectiveFrom = from }
        }
        model(a.id, "later", LocalDate.of(2027, 3, 1)).save(repo)
        model(a.id, "always", null).save(repo)
        model(a.id, "middle", LocalDate.of(2027, 1, 1)).save(repo)
        model(b.id, "other source", null).save(repo)

        val list = TemplatesListModel(a.id)
        list.load(repo)
        assertEquals(listOf("always", "middle", "later"), list.templates.map { it.name })
        assertEquals("Always · 1 shifts · Mon", TemplatesListModel.subtitle(list.templates.first()))
        list.delete(repo, list.templates.first().id)
        assertEquals(listOf("middle", "later"), list.templates.map { it.name })
        assertEquals(3, repo.templates().size) // the other source's week and the two left
    }

    // endregion

    // region Bills

    @Test fun billEditorRoundTripForEveryCadence() = runBlocking {
        val cadences = listOf(
            BillCadenceKind.Weekly to Cadence.Weekly,
            BillCadenceKind.Fortnightly to Cadence.Fortnightly,
            BillCadenceKind.EveryNDays to Cadence.EveryNDays(10),
            BillCadenceKind.Monthly to Cadence.Monthly(23),
            BillCadenceKind.Quarterly to Cadence.Quarterly,
            BillCadenceKind.Yearly to Cadence.Yearly,
            BillCadenceKind.OneOff to Cadence.OneOff,
        )
        for ((kind, cadence) in cadences) {
            val m = BillEditorModel(null, TODAY)
            m.name = kind.title; m.amountText = "49.99"; m.cadenceKind = kind; m.everyNDaysText = "10"
            val saved = m.save(repo)
            assertEquals(cadence, saved.cadence)
        }
        val stored = repo.bills()
        assertEquals(7, stored.size)
        assertEquals(cadences.map { it.second }, stored.map { it.cadence })
        assertTrue(stored.all { it.amount == Money(4999) && it.anchorDate == TODAY })
    }

    @Test fun editingAMonthlyBillMovesItsDayWithTheDueDate() = runBlocking {
        val m = BillEditorModel(null, TODAY)
        m.name = "Streaming"; m.amountText = "15.99"
        val saved = m.save(repo)
        assertEquals(Cadence.Monthly(23), saved.cadence)

        val edit = BillEditorModel(repo.bills().single(), TODAY)
        edit.anchorDate = LocalDate.of(2026, 12, 5)
        edit.save(repo)
        val stored = repo.bills().single()
        assertEquals(saved.id, stored.id)
        assertEquals(LocalDate.of(2026, 12, 5), stored.anchorDate)
        assertEquals(Cadence.Monthly(5), stored.cadence)
        // The engine now produces the 5th of each following month.
        assertEquals(
            listOf(LocalDate.of(2026, 12, 5), LocalDate.of(2027, 1, 5)),
            stored.dueDates(au.com.fuzn.beaver.core.DateRange(LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 31))),
        )
    }

    @Test fun billFromPresetThenDelete() = runBlocking {
        val m = BillEditorModel(null, TODAY)
        m.apply(BillPreset.common.first { it.name == "Gym" })
        m.amountText = "25"
        m.account = "Everyday card"
        m.note = "joining fee waived"
        val saved = m.save(repo)
        val stored = repo.bills().single()
        assertEquals(saved, stored)
        assertEquals("Gym", stored.name)
        assertEquals(Cadence.Fortnightly, stored.cadence)
        assertEquals("Everyday card", stored.account)

        val edit = BillEditorModel(stored, TODAY)
        assertFalse(edit.isNew)
        edit.delete(repo)
        assertTrue(repo.bills().isEmpty())
    }

    @Test fun everyDemoBillSurvivesAnEditorRoundTrip() = runBlocking {
        for (bill in DemoData.bills) {
            val clean = repo.save(bill.copy(id = 0))
            val m = BillEditorModel(clean, TODAY)
            assertEquals(clean, m.makeBill())
            assertEquals(clean, m.save(repo))
        }
        assertEquals(DemoData.bills.size, repo.bills().size)
    }

    // endregion

    // region Goals

    @Test fun newGoalWithAlreadySavedCreatesADepositAndBecomesPrimary() = runBlocking {
        val m = GoalEditorModel(today = TODAY)
        m.load(repo)
        assertTrue(m.makePrimary) // first goal
        m.name = "Course fees"; m.targetText = "6000"; m.savedText = "250.00"
        val saved = m.save(repo)
        assertEquals(listOf(saved), repo.goals())
        val deposit = repo.deposits().single()
        assertEquals(saved.id, deposit.goalID)
        assertEquals(Money(25_000), deposit.amount)
        assertEquals("Already saved", deposit.note)
        assertEquals(m.today, deposit.date)
        assertEquals(saved.id, repo.settings().primaryGoalId)
    }

    @Test fun blankOrZeroSavedMakesNoDeposit() = runBlocking {
        for (text in listOf("", "0", "0.00")) {
            val m = GoalEditorModel(today = TODAY)
            m.name = "G $text"; m.targetText = "100"; m.savedText = text
            m.save(repo)
        }
        assertTrue(repo.deposits().isEmpty())
        assertEquals(3, repo.goals().size)
    }

    @Test fun secondGoalIsNotPrimaryUntilAsked() = runBlocking {
        val first = GoalEditorModel(today = TODAY).also { it.load(repo); it.name = "One"; it.targetText = "100" }.save(repo)
        val second = GoalEditorModel(today = TODAY)
        second.load(repo)
        assertFalse(second.makePrimary)
        second.name = "Two"; second.targetText = "200"
        val two = second.save(repo)
        assertEquals(first.id, repo.settings().primaryGoalId)

        val edit = GoalEditorModel(two, isPrimary = false, today = TODAY)
        edit.load(repo)
        assertFalse(edit.makePrimary)
        edit.makePrimary = true
        edit.save(repo)
        assertEquals(two.id, repo.settings().primaryGoalId)

        // Editing never adds a deposit, even if "Already saved" text were set.
        edit.savedText = "50"
        edit.save(repo)
        assertTrue(repo.deposits().isEmpty())

        // Turning primary off for the primary goal clears the setting.
        val off = GoalEditorModel(two, isPrimary = true, today = TODAY)
        off.makePrimary = false
        off.save(repo)
        assertNull(repo.settings().primaryGoalId)
    }

    @Test fun deletingThePrimaryGoalClearsTheSetting() = runBlocking {
        val g = GoalEditorModel(today = TODAY).also { it.name = "G"; it.targetText = "10"; it.makePrimary = true }.save(repo)
        assertEquals(g.id, repo.settings().primaryGoalId)
        GoalEditorModel(g, isPrimary = true, today = TODAY).delete(repo)
        assertTrue(repo.goals().isEmpty())
        assertNull(repo.settings().primaryGoalId)
    }

    // endregion

    // region Limits

    @Test fun limitsLoadSaveAndKeepASingleLimit() = runBlocking {
        repo.save(FortnightLimit(0, 60, LocalDate.of(2026, 1, 5)))
        repo.save(FortnightLimit(0, 76, LocalDate.of(2026, 7, 6)))
        repo.save(Holiday(LocalDate.of(2026, 12, 25), "Christmas Day"))
        repo.save(Holiday(LocalDate.of(2026, 12, 26), "Boxing Day"))
        repo.save(UnavailableRange(0, LocalDate.of(2026, 12, 2), LocalDate.of(2026, 12, 5), "Trip"))

        val m = LimitsEditorModel(null, TODAY)
        assertFalse(m.isLoaded)
        // It refuses to save before the stored values have loaded, so it can never wipe them.
        try {
            m.save(repo)
            fail("expected EditorInvalidException")
        } catch (_: EditorInvalidException) {
        }
        assertEquals(2, repo.hoursLimits().size)

        m.load(repo)
        assertTrue(m.isLoaded)
        assertEquals("76", m.hoursText) // the latest limit is shown
        assertEquals(LocalDate.of(2026, 7, 6), m.effectiveFrom)

        m.hoursText = "80"
        m.effectiveFrom = LocalDate.of(2027, 1, 4)
        m.removeHoliday(LocalDate.of(2026, 12, 26))
        m.saveHoliday(Holiday(LocalDate.of(2027, 1, 1), "New Year's Day"))
        m.saveUnavailable(UnavailableRange(from = LocalDate.of(2027, 2, 1), to = LocalDate.of(2027, 2, 7), note = "Exams"))
        m.removeUnavailable(m.unavailable.first().id)
        val result = m.save(repo)

        val limits = repo.hoursLimits()
        assertEquals(1, limits.size)
        assertEquals(80, limits.single().hoursPerFortnight)
        assertEquals(LocalDate.of(2027, 1, 4), limits.single().effectiveFrom)
        assertEquals(result.fortnightLimit, limits.single())
        assertEquals(
            listOf(LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1)),
            repo.holidays().map { it.date }.sorted(),
        )
        val ranges = repo.unavailableRanges()
        assertEquals(1, ranges.size)
        assertEquals("Exams", ranges.single().note)
        assertTrue(ranges.single().id > 0)
        assertEquals(ranges, result.unavailable)
        assertTrue(m.unavailable.none { it.id < 0 }) // temporary ids are replaced after the save
    }

    @Test fun limitsToggleOffDeletesTheLimitAndHolidayRename() = runBlocking {
        repo.save(FortnightLimit(0, 76, LocalDate.of(2026, 7, 6)))
        repo.save(Holiday(LocalDate.of(2026, 12, 25), "Xmas"))
        val m = LimitsEditorModel(null, TODAY)
        m.load(repo)
        m.hasLimit = false
        m.saveHoliday(Holiday(LocalDate.of(2026, 12, 25), "Christmas Day"), editing = LocalDate.of(2026, 12, 25))
        m.save(repo)
        assertTrue(repo.hoursLimits().isEmpty())
        assertEquals(listOf("Christmas Day"), repo.holidays().map { it.name })
    }

    @Test fun cancelWritesNothing() = runBlocking {
        repo.save(FortnightLimit(0, 76, LocalDate.of(2026, 7, 6)))
        val m = LimitsEditorModel(null, TODAY)
        m.load(repo)
        m.hoursText = "10"
        m.saveHoliday(Holiday(TODAY, "Edited but never saved"))
        // No save call: the stored data is untouched.
        assertEquals(76, repo.hoursLimits().single().hoursPerFortnight)
        assertTrue(repo.holidays().isEmpty())
        assertNotEquals(m.hoursText, repo.hoursLimits().single().hoursPerFortnight.toString())
    }

    // endregion

    private object DemoWithholding {
        val rows = WithholdingTable.auScale2Weekly.rows
    }
}
