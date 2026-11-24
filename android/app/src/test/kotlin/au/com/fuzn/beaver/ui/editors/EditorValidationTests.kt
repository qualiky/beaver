package au.com.fuzn.beaver.ui.editors

import au.com.fuzn.beaver.core.Bill
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.Cadence
import au.com.fuzn.beaver.core.ClockWindow
import au.com.fuzn.beaver.core.DaySelector
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.FortnightLimit
import au.com.fuzn.beaver.core.Goal
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PaySchedule
import au.com.fuzn.beaver.core.RateCard
import au.com.fuzn.beaver.core.TaxMethod
import au.com.fuzn.beaver.core.UnavailableRange
import au.com.fuzn.beaver.core.WithholdingTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private val TODAY = LocalDate.of(2026, 11, 23)

@RunWith(RobolectricTestRunner::class)
class RateCardEditorValidationTests {
    @Test fun pristineNewCardShowsNoErrors() {
        val m = RateCardEditorModel()
        assertNull(m.visibleNameIssue)
        assertNull(m.visibleRulesIssue)
        assertFalse(m.isValid)
        assertNull(m.makeCard())
    }

    @Test fun errorsAppearAfterEditOrRevealAll() {
        val m = RateCardEditorModel()
        m.name = "  "
        assertEquals("Enter a name for this rate card.", m.visibleNameIssue)
        assertNull(m.visibleRulesIssue)
        m.revealAllIssues()
        assertEquals("Add at least one rate, or start from the template.", m.visibleRulesIssue)
    }

    @Test fun blankRuleShowsNoErrorsUntilEdited() {
        val m = RateCardEditorModel()
        m.addRule()
        val d = m.rules.single()
        assertNull(m.visibleBandIssue(d))
        assertNull(m.visibleRateIssue(d))
        assertFalse(m.rulesAreValid)
        m.setBand(d.id, "")
        m.setRate(d.id, "abc")
        assertEquals("Enter a band name, such as Weekday evening.", m.visibleBandIssue(m.rules.single()))
        assertEquals("Enter an hourly rate such as 30.00.", m.visibleRateIssue(m.rules.single()))
    }

    @Test fun rateMustBePositiveAndWindowMustEndAfterStart() {
        val m = RateCardEditorModel()
        m.addRule()
        val id = m.rules.single().id
        m.setBand(id, "Day"); m.setRate(id, "0")
        assertEquals("The rate must be more than zero.", m.rules.single().rateIssue)
        m.setRate(id, "30.00")
        assertTrue(m.rules.single().isValid)
        m.setHasWindow(id, true); m.setFrom(id, 600); m.setTo(id, 600)
        assertNotNull(m.rules.single().windowIssue)
        assertFalse(m.rulesAreValid)
        m.setTo(id, 1440)
        assertNull(m.rules.single().windowIssue)
        assertEquals(ClockWindow(600, 1440), m.rules.single().rule!!.window)
    }

    @Test fun typicalCasualTemplateFillsRulesAndName() {
        val m = RateCardEditorModel()
        m.applyTypicalCasual()
        assertEquals("Typical casual (template)", m.name)
        assertEquals(RateCard.typicalCasual().rules, m.validRules)
        assertTrue(m.isValid)
        assertNull(m.gapWarning)
        // A typed name is kept.
        val named = RateCardEditorModel().also { it.name = "My card" }
        named.applyTypicalCasual()
        assertEquals("My card", named.name)
    }

    @Test fun weekdayGapsWarnLiveButNeverBlockSaving() {
        val m = RateCardEditorModel(DemoData.rateCard)
        assertNull(m.gapWarning)
        val evening = m.rules.first { it.band == "Weekday evening" }
        m.removeRule(evening.id)
        assertEquals(listOf(ClockWindow.hours(19, 24)), m.gaps)
        assertEquals(
            "No rate covers weekdays at 19:00–24:00. Shifts in those times would be paid nothing.", m.gapWarning,
        )
        assertTrue(m.isValid)
        // Editing a rule's window updates the warning at once.
        val day = m.rules.first { it.band == "Weekday day" }
        m.setTo(day.id, 24 * 60)
        assertNull(m.gapWarning)
    }

    @Test fun addRemoveAndReorder() {
        val m = RateCardEditorModel(DemoData.rateCard)
        val first = m.rules[0].band
        val second = m.rules[1].band
        m.moveRule(m.rules[0].id, 1)
        assertEquals(second, m.rules[0].band)
        assertEquals(first, m.rules[1].band)
        m.moveRule(m.rules[0].id, -1) // already at the top: no change
        assertEquals(second, m.rules[0].band)
        m.moveRule(m.rules.last().id, 1) // already at the bottom: no change
        val count = m.rules.size
        m.addRule()
        assertEquals(count + 1, m.rules.size)
        m.removeRule(m.rules.last().id)
        assertEquals(count, m.rules.size)
        assertEquals(DaySelector.entries.toSet(), m.rules.map { it.days }.toSet())
    }

    @Test fun nameOverrideAndAdopt() {
        val m = RateCardEditorModel(DemoData.rateCard)
        assertEquals("Sam rates", m.makeCard("Sam rates")!!.name)
        assertEquals(DemoData.RATE_CARD_ID, m.makeCard()!!.id)
        m.adopt(RateCard(9, "Other", RateCard.typicalCasual().rules.take(1)))
        assertEquals(9, m.id)
        assertEquals(1, m.rules.size)
    }
}

@RunWith(RobolectricTestRunner::class)
class IncomeSourceEditorValidationTests {
    private fun IncomeSourceEditorModel.field(f: IncomeSourceEditorModel.Field) = visibleIssue(f)

    @Test fun pristineFormShowsNoErrorsAndCannotSave() {
        for (kind in IncomeKind.entries) {
            val m = IncomeSourceEditorModel(null, kind, TODAY)
            IncomeSourceEditorModel.Field.entries.forEach { assertNull("$kind $it", m.visibleIssue(it)) }
            assertFalse(m.isValid)
            assertNull(m.makeSource(null))
        }
    }

    @Test fun errorsRevealOnEditAndOnSaveAttempt() {
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        m.name = " "
        assertEquals("Enter a name, such as Example Bar.", m.visibleIssue(IncomeSourceEditorModel.Field.Name))
        assertNull(m.visibleIssue(IncomeSourceEditorModel.Field.Amount))
        m.revealAllIssues()
        assertEquals("Enter an amount such as 1,200.00.", m.visibleIssue(IncomeSourceEditorModel.Field.Amount))
    }

    @Test fun validFixedSource() {
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        m.name = "Salary"; m.fixedAmountText = "1,200.00"
        assertTrue(m.isValid)
        val s = m.makeSource(null)!!
        assertEquals(Money(120_000), s.fixedAmount)
        assertNull(s.rateCardID)
        assertEquals(PaySchedule.Weekly(DayOfWeek.WEDNESDAY, 1), s.schedule)
        assertEquals(TaxMethod.None, s.taxMethod)
        m.fixedAmountText = "0"
        assertEquals("The amount must be more than zero.", m.issue(IncomeSourceEditorModel.Field.Amount))
    }

    @Test fun hourlyNeedsValidRates() {
        val m = IncomeSourceEditorModel(null, IncomeKind.hourly, TODAY)
        m.name = "Bar"
        assertFalse(m.isValid)
        assertEquals("Add at least one valid rate below.", m.issue(IncomeSourceEditorModel.Field.Rates))
        m.rateCard.applyTypicalCasual()
        assertTrue(m.isValid)
    }

    @Test fun monthlyIsFixedOnlyAndHourlyFallsBackToWeekly() {
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        assertEquals(
            listOf(IncomeScheduleKind.Weekly, IncomeScheduleKind.Fortnightly, IncomeScheduleKind.Monthly),
            m.availableSchedules,
        )
        m.scheduleKind = IncomeScheduleKind.Monthly
        m.dayOfMonth = 31
        assertEquals(PaySchedule.Monthly(31), m.schedule)
        m.kind = IncomeKind.hourly
        assertEquals(IncomeScheduleKind.Weekly, m.scheduleKind)
        assertEquals(listOf(IncomeScheduleKind.Weekly, IncomeScheduleKind.Fortnightly), m.availableSchedules)
    }

    @Test fun taxMethods() {
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        m.name = "Salary"; m.fixedAmountText = "1000"
        assertEquals(TaxMethod.None, m.taxMethod)
        m.taxKind = IncomeTaxKind.Flat
        assertEquals("Enter a percentage such as 15 or 12.5.", m.issue(IncomeSourceEditorModel.Field.Tax))
        m.flatPercentText = "0"
        assertEquals("The percentage must be above 0 and at most 100.", m.issue(IncomeSourceEditorModel.Field.Tax))
        m.flatPercentText = "100.01"
        assertNotNull(m.issue(IncomeSourceEditorModel.Field.Tax))
        m.flatPercentText = "12.5"
        assertEquals(TaxMethod.FlatPercent(1250), m.taxMethod)
        assertTrue(m.isValid)
        m.taxKind = IncomeTaxKind.Table
        assertEquals("No withholding tables are stored yet.", m.issue(IncomeSourceEditorModel.Field.Tax))
        m.tables.add(WithholdingTable.auScale2Weekly)
        assertEquals("Choose a withholding table.", m.issue(IncomeSourceEditorModel.Field.Tax))
        m.tableId = WithholdingTable.AU_SCALE2_WEEKLY_ID
        assertEquals(TaxMethod.Table(WithholdingTable.AU_SCALE2_WEEKLY_ID), m.taxMethod)
        // Net pay is never taxed, whatever the tax kind says.
        m.amountIsNet = true
        assertFalse(m.taxApplies)
        assertNull(m.issue(IncomeSourceEditorModel.Field.Tax))
        assertEquals(TaxMethod.None, m.makeSource(null)!!.taxMethod)
        assertTrue(m.makeSource(null)!!.amountIsNet)
    }

    @Test fun weeklyCaps() {
        val m = IncomeSourceEditorModel(null, IncomeKind.fixed, TODAY)
        assertNull(m.issue(IncomeSourceEditorModel.Field.SoftCap))
        m.softCapText = "0"
        assertEquals("Enter whole hours from 1 to 168, or leave blank.", m.issue(IncomeSourceEditorModel.Field.SoftCap))
        m.softCapText = "169"
        assertNotNull(m.issue(IncomeSourceEditorModel.Field.SoftCap))
        m.softCapText = "1.5"
        assertNotNull(m.issue(IncomeSourceEditorModel.Field.SoftCap))
        m.softCapText = "30"; m.hardCapText = "20"
        assertEquals("The hard limit can't be below the soft limit.", m.issue(IncomeSourceEditorModel.Field.HardCap))
        m.hardCapText = "38"
        assertNull(m.issue(IncomeSourceEditorModel.Field.HardCap))
        m.name = "X"; m.fixedAmountText = "10"
        val s = m.makeSource(null)!!
        assertEquals(30, s.weeklyCapSoftHours)
        assertEquals(38, s.weeklyCapHardHours)
    }

    @Test fun editingKeepsTheExistingValues() {
        val m = IncomeSourceEditorModel(DemoData.source, today = TODAY)
        assertFalse(m.isNew)
        assertEquals("Example Bar", m.name)
        assertEquals(IncomeTaxKind.Table, m.taxKind)
        assertEquals(WithholdingTable.AU_SCALE2_WEEKLY_ID, m.tableId)
        // The embedded card starts with the existing card's id, so a save can never create a second card.
        assertEquals(DemoData.RATE_CARD_ID, m.rateCard.id)
        assertFalse(m.isLoaded)
    }
}

@RunWith(RobolectricTestRunner::class)
class TemplateEditorValidationTests {
    @Test fun pristineNewTemplateShowsNoErrors() {
        val m = TemplateEditorModel(DemoData.SOURCE_ID, today = TODAY)
        assertNull(m.visibleNameIssue)
        assertNull(m.visibleShiftsIssue)
        assertFalse(m.isValid)
        m.revealAllIssues()
        assertEquals("Add at least one shift.", m.visibleShiftsIssue)
    }

    @Test fun shiftRules() {
        val m = TemplateEditorModel(DemoData.SOURCE_ID, today = TODAY)
        m.addShift()
        val id = m.shifts.single().id
        assertTrue(m.isValid)
        m.updateShift(id) { it.copy(end = it.start) }
        assertEquals("Start and end can't be the same time.", m.shifts.single().issue)
        m.updateShift(id) { it.copy(start = LocalTime.of(9, 0), end = LocalTime.of(10, 0), breakMinutes = 60) }
        assertEquals("The break must be shorter than the shift.", m.shifts.single().issue)
        assertFalse(m.isValid)
        m.updateShift(id) { it.copy(breakMinutes = 30) }
        assertTrue(m.isValid)
        m.name = " "
        assertEquals("Enter a name, such as Semester 1.", m.visibleNameIssue)
        assertFalse(m.isValid)
    }

    @Test fun overnightShiftIsAllowedAndOrdered() {
        val m = TemplateEditorModel(DemoData.SOURCE_ID, DemoData.template, TODAY)
        val friday = m.shifts.first { it.weekday == DayOfWeek.FRIDAY }
        assertTrue(friday.crossesMidnight)
        assertEquals(10 * 60 + 30, friday.durationMinutes)
        // Shuffle: the saved order is by weekday then start.
        m.addShift()
        m.updateShift(m.shifts.last().id) { it.copy(weekday = DayOfWeek.MONDAY, start = LocalTime.of(8, 0), end = LocalTime.of(12, 0)) }
        val shifts = m.makeTemplate()!!.shifts
        assertEquals(shifts.sortedWith(compareBy({ it.weekday }, { it.start })), shifts)
        assertEquals(DayOfWeek.MONDAY, shifts.first().weekday)
    }

    @Test fun addShiftPicksFirstFreeWeekday() {
        val m = TemplateEditorModel(DemoData.SOURCE_ID, today = TODAY)
        m.addShift(); m.addShift()
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), m.shifts.map { it.weekday })
    }

    @Test fun effectiveFromIsOptional() {
        val m = TemplateEditorModel(DemoData.SOURCE_ID, DemoData.template, TODAY)
        assertNull(m.makeTemplate()!!.effectiveFrom)
        m.hasEffectiveFrom = true
        m.effectiveFrom = LocalDate.of(2027, 2, 1)
        assertEquals(LocalDate.of(2027, 2, 1), m.makeTemplate()!!.effectiveFrom)
    }
}

@RunWith(RobolectricTestRunner::class)
class BillEditorValidationTests {
    @Test fun pristineFormShowsNoErrors() {
        val m = BillEditorModel(null, TODAY)
        BillEditorModel.Field.entries.forEach { assertNull(m.visibleIssue(it)) }
        assertFalse(m.isValid)
        m.revealAllIssues()
        assertEquals("Enter a name, such as Rent.", m.visibleIssue(BillEditorModel.Field.Name))
        assertEquals("Enter an amount such as 49.99.", m.visibleIssue(BillEditorModel.Field.Amount))
    }

    @Test fun amountAndName() {
        val m = BillEditorModel(null, TODAY)
        m.name = "Rent"; m.amountText = "0"
        assertEquals("The amount must be more than zero.", m.visibleIssue(BillEditorModel.Field.Amount))
        m.amountText = "400"
        assertTrue(m.isValid)
        assertEquals(Money(40_000), m.makeBill()!!.amount)
    }

    @Test fun everyCadenceBuilds() {
        val m = BillEditorModel(null, TODAY)
        m.name = "X"; m.amountText = "10"
        val expected = mapOf(
            BillCadenceKind.Weekly to Cadence.Weekly,
            BillCadenceKind.Fortnightly to Cadence.Fortnightly,
            BillCadenceKind.EveryNDays to Cadence.EveryNDays(28),
            BillCadenceKind.Monthly to Cadence.Monthly(23),
            BillCadenceKind.Quarterly to Cadence.Quarterly,
            BillCadenceKind.Yearly to Cadence.Yearly,
            BillCadenceKind.OneOff to Cadence.OneOff,
        )
        assertEquals(BillCadenceKind.entries.toSet(), expected.keys)
        for ((kind, cadence) in expected) {
            m.cadenceKind = kind
            assertEquals("$kind", cadence, m.makeBill()!!.cadence)
        }
    }

    @Test fun everyNDaysRange() {
        val m = BillEditorModel(null, TODAY)
        m.name = "X"; m.amountText = "10"; m.cadenceKind = BillCadenceKind.EveryNDays
        for (bad in listOf("0", "366", "abc", "", "1.5", "-3")) {
            m.everyNDaysText = bad
            assertEquals(bad, "Enter a number of days from 1 to 365.", m.issue(BillEditorModel.Field.Cadence))
            assertFalse(m.isValid)
        }
        m.everyNDaysText = "365"
        assertTrue(m.isValid)
        assertEquals(Cadence.EveryNDays(365), m.makeBill()!!.cadence)
    }

    @Test fun monthlyDayFollowsTheDueDate() {
        val m = BillEditorModel(null, TODAY) // starts monthly on the 23rd
        m.name = "X"; m.amountText = "10"
        assertEquals(Cadence.Monthly(23), m.makeBill()!!.cadence)
        m.anchorDate = LocalDate.of(2026, 12, 5)
        assertEquals(5, m.dayOfMonth)
        assertEquals(Cadence.Monthly(5), m.makeBill()!!.cadence)
        m.anchorDate = LocalDate.of(2027, 1, 31)
        assertEquals(Cadence.Monthly(31), m.makeBill()!!.cadence)
    }

    @Test fun monthlyDayIsKeptWhenOtherCadencesChangeTheDate() {
        val m = BillEditorModel(null, TODAY)
        m.cadenceKind = BillCadenceKind.Weekly
        m.anchorDate = LocalDate.of(2026, 12, 5)
        // Switching to monthly afterwards takes the day from the current due date.
        m.cadenceKind = BillCadenceKind.Monthly
        assertEquals(5, m.dayOfMonth)
        // Setting the same date again changes nothing.
        m.dayOfMonth = 9
        m.anchorDate = LocalDate.of(2026, 12, 5)
        assertEquals(9, m.dayOfMonth)
    }

    @Test fun existingMonthlyBillKeepsItsStoredDay() {
        // The stored day is 31 but the first due date is 28 Feb (clamped): opening the editor must not change it.
        val bill = Bill(7, "Rent", Money(1000), Cadence.Monthly(31), LocalDate.of(2027, 2, 28))
        val m = BillEditorModel(bill, TODAY)
        assertEquals(31, m.dayOfMonth)
        assertEquals(Cadence.Monthly(31), m.makeBill()!!.cadence)
    }

    @Test fun presetFillsNameCategoryAndCadenceButNotAmount() {
        val m = BillEditorModel(null, TODAY)
        m.apply(BillPreset.common.first { it.name == "Electricity" })
        assertEquals("Electricity", m.name)
        assertEquals(BillCategory.utilities, m.category)
        assertEquals(BillCadenceKind.Quarterly, m.cadenceKind)
        assertEquals("", m.amountText)
        assertEquals("Electricity", m.presetName)
        assertFalse(m.isValid)
    }

    @Test fun accountBlankBecomesNull() {
        val m = BillEditorModel(null, TODAY)
        m.name = "X"; m.amountText = "10"; m.account = "  "
        assertNull(m.makeBill()!!.account)
        m.account = " Everyday card "
        assertEquals("Everyday card", m.makeBill()!!.account)
    }

    @Test fun thirteenPresetsEachWithItsOwnIcon() {
        val presets = BillPreset.common
        assertEquals(
            listOf(
                "Rent", "Phone", "Internet", "Electricity", "Gas", "Water", "Transport card", "Gym", "Streaming",
                "Music", "Cloud storage", "Insurance", "Loan repayment",
            ),
            presets.map { it.name },
        )
        assertEquals(13, presets.map { it.icon.name }.toSet().size)
        assertEquals(
            listOf(
                "Filled.House", "Filled.Smartphone", "Filled.Wifi", "Filled.Bolt", "Filled.LocalFireDepartment",
                "Filled.WaterDrop", "Filled.DirectionsBus", "Filled.FitnessCenter", "Filled.Tv", "Filled.MusicNote",
                "Filled.Cloud", "Filled.Shield", "Filled.CreditCard",
            ),
            presets.map { it.icon.name },
        )
    }
}

@RunWith(RobolectricTestRunner::class)
class GoalEditorValidationTests {
    @Test fun pristineFormShowsNoErrors() {
        val m = GoalEditorModel(today = TODAY)
        GoalEditorModel.Field.entries.forEach { assertNull(m.visibleIssue(it)) }
        assertFalse(m.isValid)
        m.revealAllIssues()
        assertEquals("Enter a name, such as Course fees.", m.visibleIssue(GoalEditorModel.Field.Name))
        assertEquals("Enter a target such as 6,000.00.", m.visibleIssue(GoalEditorModel.Field.Target))
    }

    @Test fun validGoalAndDeposit() {
        val m = GoalEditorModel(today = TODAY)
        m.name = "Course fees"; m.targetText = "6,000"
        assertTrue(m.isValid)
        assertNull(m.initialDepositCents)
        m.savedText = "250.50"
        assertEquals(25_050L, m.initialDepositCents)
        m.savedText = "x"
        assertEquals("Enter an amount such as 250.00, or leave blank.", m.visibleIssue(GoalEditorModel.Field.Saved))
        assertFalse(m.isValid)
        m.savedText = "0"
        assertTrue(m.isValid)
        assertNull(m.initialDepositCents)
    }

    @Test fun deadlineMustBeInTheFutureUnlessUnchanged() {
        val m = GoalEditorModel(today = TODAY)
        m.name = "G"; m.targetText = "100"
        m.hasDeadline = true
        assertEquals(TODAY.plusMonths(6), m.deadline)
        assertTrue(m.isValid)
        m.deadline = TODAY
        assertEquals("Choose a date after today.", m.visibleIssue(GoalEditorModel.Field.Deadline))
        assertFalse(m.isValid)

        val old = Goal(3, "Old", Money(10_000), LocalDate.of(2020, 1, 1))
        val edit = GoalEditorModel(old, today = TODAY)
        assertTrue(edit.isValid)
        edit.deadline = LocalDate.of(2020, 2, 1)
        assertFalse(edit.isValid)
    }

    @Test fun savedFieldIsHiddenWhenEditing() {
        val m = GoalEditorModel(DemoData.goal, isPrimary = true, today = TODAY)
        m.savedText = "garbage"
        assertTrue(m.isValid)
        assertNull(m.initialDepositCents)
        assertTrue(m.makePrimary)
    }

    @Test fun firstGoalDefaultsToPrimary() {
        assertTrue(GoalEditorModel(today = TODAY).makePrimary)
        assertFalse(GoalEditorModel(isFirstGoal = false, today = TODAY).makePrimary)
    }
}

@RunWith(RobolectricTestRunner::class)
class LimitsEditorValidationTests {
    @Test fun pristineFormShowsNoErrorsAndTogglingOffNeedsNoHours() {
        val m = LimitsEditorModel(LimitsValue(), TODAY)
        assertNull(m.visibleIssue(LimitsEditorModel.Field.Hours))
        assertTrue(m.isValid)
        m.hasLimit = true
        assertNull(m.visibleIssue(LimitsEditorModel.Field.Hours)) // not edited yet
        assertFalse(m.isValid)
        m.revealAllIssues()
        assertEquals("Enter whole hours from 1 to 336.", m.visibleIssue(LimitsEditorModel.Field.Hours))
    }

    @Test fun hoursRange() {
        val m = LimitsEditorModel(LimitsValue(), TODAY)
        m.hasLimit = true
        for (bad in listOf("0", "337", "x", "", "7.5")) {
            m.hoursText = bad
            assertFalse(bad, m.isValid)
        }
        m.hoursText = "336"
        assertTrue(m.isValid)
        assertEquals(336, m.makeValue()!!.fortnightLimit!!.hoursPerFortnight)
    }

    @Test fun holidayValidation() {
        val m = LimitsEditorModel(LimitsValue(holidays = DemoData.holidays), TODAY)
        val xmas = LocalDate.of(2026, 12, 25)
        assertEquals("Enter a name, such as Christmas Day.", m.holidayIssue(" ", xmas, null))
        assertEquals("A holiday is already set on this date.", m.holidayIssue("Other", xmas, null))
        assertNull(m.holidayIssue("Christmas", xmas, xmas)) // editing the same row
        assertFalse(m.saveHoliday(Holiday(xmas, "Dup")))
        assertTrue(m.saveHoliday(Holiday(xmas, "Christmas"), editing = xmas))
        assertTrue(m.saveHoliday(Holiday(LocalDate.of(2026, 12, 24), " Eve "))) // trimmed
        assertEquals("Eve", m.holidays.first().name)
        assertEquals(m.holidays.sortedBy { it.date }, m.holidays)
        m.removeHoliday(xmas)
        assertTrue(m.holidays.none { it.date == xmas })
    }

    @Test fun unavailableRangeValidation() {
        val m = LimitsEditorModel(LimitsValue(), TODAY)
        val a = LocalDate.of(2026, 12, 2)
        assertEquals("The end date can't be before the start date.", m.unavailableIssue(a, a.minusDays(1)))
        assertFalse(m.saveUnavailable(UnavailableRange(from = a, to = a.minusDays(1))))
        assertTrue(m.saveUnavailable(UnavailableRange(from = a, to = a, note = " trip ")))
        assertTrue(m.saveUnavailable(UnavailableRange(from = a.minusDays(10), to = a.minusDays(8))))
        // New rows get distinct negative temporary ids and are sorted by start date.
        assertEquals(listOf(-2, -1), m.unavailable.map { it.id })
        assertEquals("trip", m.unavailable.last().note)
        // They are mapped back to id 0 for saving.
        assertEquals(listOf(0, 0), m.makeValue()!!.unavailable.map { it.id })
        // Editing a temporary row replaces it.
        assertTrue(m.saveUnavailable(m.unavailable.last().copy(to = a.plusDays(3))))
        assertEquals(2, m.unavailable.size)
    }

    @Test fun existingLimitKeepsItsIdAndDate() {
        val m = LimitsEditorModel(LimitsValue(FortnightLimit(4, 76, LocalDate.of(2026, 7, 1))), TODAY)
        assertTrue(m.hasLimit)
        assertEquals("76", m.hoursText)
        assertEquals(LocalDate.of(2026, 7, 1), m.effectiveFrom)
        assertEquals(4, m.makeValue()!!.fortnightLimit!!.id)
        m.hasLimit = false
        assertNull(m.makeValue()!!.fortnightLimit)
    }
}
