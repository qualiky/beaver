package au.com.fuzn.beaver.ui.roster

import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.ShiftStatus
import au.com.fuzn.beaver.core.Template
import au.com.fuzn.beaver.core.TemplateExpander
import au.com.fuzn.beaver.core.TemplateShift
import au.com.fuzn.beaver.core.total
import au.com.fuzn.beaver.core.week
import au.com.fuzn.beaver.data.Repository
import au.com.fuzn.beaver.data.TestDb
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
import java.time.LocalTime

/** Roster state tests over an in-memory Room repository with the fictional demo data. */
@RunWith(RobolectricTestRunner::class)
class RosterTests {
    private lateinit var db: TestDb
    private val repo: Repository get() = db.repository
    private val today: LocalDate = LocalDate.of(2026, 11, 23)

    @Before fun setUp() {
        db = TestDb()
        runBlocking { repo.loadDemoData() }
    }

    @After fun tearDown() { db.close() }

    private fun d(s: String): LocalDate = LocalDate.parse(s)

    /** The demo template expanded onto the week starting [monday] and saved as real shifts. */
    private fun rosterTemplate(monday: String): List<Shift> = runBlocking {
        repo.saveShifts(TemplateExpander.shifts(DemoData.SOURCE_ID, listOf(DemoData.template), d(monday).week))
    }

    private fun state(week: String): RosterState = RosterState(repo, today, d(week)).also { runBlocking { it.reload() } }

    // region Week totals

    @Test fun weekTotalsMatchPeriodPayForDemoTemplateWeek() {
        rosterTemplate("2026-11-16")
        val vm = state("2026-11-16")
        val data = vm.data

        assertEquals(1, data.footers.size)
        val footer = data.footers.first()
        assertEquals(137283L, footer.gross.cents)
        assertEquals(110983L, footer.net.cents)
        assertEquals(137283L - 110983L, footer.tax.cents)
        assertEquals(3650 * 60 / 100, footer.paidMinutes)
        // Pays the Wednesday after the week ends.
        assertEquals(d("2026-11-25"), footer.payday)
        assertNull(footer.taxPeriod)
        // Row grossing agrees with the footer.
        assertEquals(137283L, data.dayGroups.flatMap { it.lines }.map { it.gross }.total().cents)
        assertEquals(5, data.dayGroups.size)
    }

    @Test fun cancelledShiftsEarnNothingAndHaveNoPay() {
        val saved = rosterTemplate("2026-11-16")
        val vm = state("2026-11-16")
        val first = saved.first()
        assertTrue(runBlocking { vm.save(first.copy(status = ShiftStatus.cancelled)) })
        assertTrue(vm.data.footers.first().gross.cents < 137283L)
        assertEquals(Money.ZERO, vm.data.dayGroups.flatMap { it.lines }.first { it.id == first.id }.gross)
    }

    @Test fun bandChipsAndHolidayChipsAppearOnShiftLines() {
        val vm = state("2026-12-21")
        runBlocking { vm.save(Shift(sourceID = DemoData.SOURCE_ID, date = d("2026-12-25"), start = LocalTime.of(20, 0), end = LocalTime.of(2, 0))) }
        val line = vm.data.dayGroups.single().lines.single()
        assertEquals(listOf("PH"), line.chips)
        assertEquals(listOf("Christmas Day", "Boxing Day"), line.holidayNames)
    }

    // endregion

    // region Copy last week and apply template

    @Test fun copyLastWeekSkipsExistingAndNeverDuplicates() {
        rosterTemplate("2026-11-16")
        // Wednesday already rostered in the target week, plus a cancelled shift last week that must not be copied.
        runBlocking {
            repo.save(Shift(sourceID = DemoData.SOURCE_ID, date = d("2026-11-25"), start = LocalTime.of(18, 0), end = LocalTime.of(23, 0), breakMinutes = 30))
            repo.save(Shift(sourceID = DemoData.SOURCE_ID, date = d("2026-11-16"), start = LocalTime.of(9, 0), end = LocalTime.of(12, 0), status = ShiftStatus.cancelled))
        }
        val vm = state("2026-11-23")

        val plan = vm.data.copyLastWeekPlan()
        assertEquals(4, plan.toAdd.size)
        assertEquals(1, plan.skippedExisting)
        assertEquals(0, plan.skippedUnavailable)
        assertTrue(plan.toAdd.all { it.status == ShiftStatus.rostered && it.id == 0 })

        runBlocking { vm.copyLastWeek() }
        assertEquals(5, vm.data.weekShifts.size)
        assertTrue(vm.message!!.text.contains("Copied 4 shifts"))

        // A second copy adds nothing and the database holds no duplicates.
        runBlocking { vm.copyLastWeek() }
        assertEquals(5, vm.data.weekShifts.size)
        assertEquals(5, runBlocking { repo.shifts(d("2026-11-23").week) }.size)
        val keys = vm.data.weekShifts.map { Triple(it.sourceID, it.date, it.start) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun copyLastWeekSkipsUnavailableDates() {
        rosterTemplate("2026-11-23")
        val vm = state("2026-11-30")

        val plan = vm.data.copyLastWeekPlan()
        // The demo unavailable range is Wed 2 Dec to Sat 5 Dec: only Sunday 6 Dec is added.
        assertEquals(listOf(d("2026-12-06")), plan.toAdd.map { it.date })
        assertEquals(4, plan.skippedUnavailable)
        runBlocking { vm.copyLastWeek() }
        assertEquals(listOf(d("2026-12-06")), vm.data.weekShifts.map { it.date })
        assertTrue(vm.message!!.text.contains("4 on unavailable dates"))
    }

    @Test fun copyLastWeekWithNothingToCopy() {
        val vm = state("2026-11-30")
        assertFalse(vm.data.copyLastWeekPlan().hasSource)
        runBlocking { vm.copyLastWeek() }
        assertTrue(vm.data.weekShifts.isEmpty())
        assertTrue(vm.message!!.text.startsWith("Nothing to copy"))
    }

    @Test fun applyTemplateOnWeekOf30NovAddsOnlySunday6Dec() {
        val vm = state("2026-11-30")

        val plan = vm.data.applyTemplatePlan()
        assertEquals(1, plan.toAdd.size)
        val shift = plan.toAdd.single()
        assertEquals(d("2026-12-06"), shift.date)
        assertEquals(LocalTime.of(17, 0), shift.start)
        assertEquals(4, plan.skippedUnavailable)

        runBlocking { vm.applyTemplate() }
        assertEquals(1, vm.data.weekShifts.size)
        assertTrue(vm.message!!.text.contains("Added 1 shift"))

        // Applying again never duplicates.
        runBlocking { vm.applyTemplate() }
        assertEquals(1, vm.data.weekShifts.size)
    }

    @Test fun applyTemplateUsesTheTemplateEffectiveForTheWeek() {
        runBlocking {
            repo.save(Template(0, DemoData.SOURCE_ID, "Reduced", d("2026-12-07"),
                listOf(TemplateShift(DayOfWeek.MONDAY, LocalTime.of(10, 0), LocalTime.of(14, 0)))))
        }
        val vm = state("2026-12-07")
        assertEquals(listOf(d("2026-12-07")), vm.data.applyTemplatePlan().toAdd.map { it.date })
    }

    @Test fun saveWeekAsTemplateReplacesRatherThanDuplicates() {
        rosterTemplate("2026-11-16")
        val vm = state("2026-11-16")
        assertEquals(5, runBlocking { vm.saveWeekAsTemplate() })
        assertEquals(5, runBlocking { vm.saveWeekAsTemplate() })
        val templates = runBlocking { repo.templates() }.filter { it.effectiveFrom == d("2026-11-16") }
        assertEquals(1, templates.size)
        assertEquals(5, templates.single().shifts.size)
    }

    // endregion

    // region Delete, warnings, editor

    @Test fun deleteCanBeUndone() {
        rosterTemplate("2026-11-16")
        val vm = state("2026-11-16")
        val victim = vm.data.weekShifts.first()
        runBlocking { vm.delete(victim) }
        assertEquals(4, vm.data.weekShifts.size)
        assertTrue(vm.message!!.undoable)
        runBlocking { vm.undoDelete() }
        assertEquals(5, vm.data.weekShifts.size)
        assertNull(vm.message)
        // The restored shift keeps its id.
        assertTrue(vm.data.weekShifts.any { it.id == victim.id })
    }

    @Test fun weeklyHoursWarningIsShownButNeverBlocksSaving() {
        runBlocking { repo.save(DemoData.source.copy(weeklyCapSoftHours = 10)) }
        rosterTemplate("2026-11-16")
        val vm = state("2026-11-16")
        assertNotNull(vm.data.footers.first().warnings.firstOrNull())
        assertTrue(runBlocking { vm.save(Shift(sourceID = DemoData.SOURCE_ID, date = d("2026-11-17"), start = LocalTime.of(9, 0), end = LocalTime.of(17, 0))) })
    }

    @Test fun editorDefaultsComeFromTheLastShiftEntered() {
        runBlocking {
            repo.save(Shift(sourceID = DemoData.SOURCE_ID, date = d("2026-11-16"), start = LocalTime.of(11, 0), end = LocalTime.of(19, 30), breakMinutes = 45))
        }
        val vm = state("2026-11-16")
        val editor = runBlocking { vm.makeEditor() }!!
        assertEquals(LocalTime.of(11, 0), editor.start)
        assertEquals(LocalTime.of(19, 30), editor.end)
        assertEquals(45, editor.breakMinutes)
        assertFalse(editor.customBreak)
        assertNotNull(editor.pay)
        assertFalse(editor.finishesNextDay)
    }

    @Test fun editorPreviewShowsHolidayAndMidnightCrossing() {
        val vm = state("2026-12-21")
        val editor = runBlocking { vm.makeEditor() }!!
        editor.date = d("2026-12-25")
        editor.start = LocalTime.of(20, 0)
        editor.end = LocalTime.of(2, 0)
        editor.selectBreak(0)
        assertTrue(editor.finishesNextDay)
        assertEquals(listOf("Christmas Day", "Boxing Day"), editor.holidayNames)
        assertEquals(6 * 6000L, editor.pay!!.gross.cents)
        editor.status = ShiftStatus.cancelled
        assertEquals(Money.ZERO, editor.gross)
    }

    @Test fun noHourlySourcesGivesNoEditor() {
        runBlocking { repo.eraseAll() }
        val vm = state("2026-11-16")
        assertTrue(vm.data.hourlySources.isEmpty())
        assertNull(runBlocking { vm.makeEditor() })
        assertTrue(vm.data.dayGroups.isEmpty())
    }

    // endregion

    // region Log pay received

    @Test fun differenceIsActualMinusExpected() {
        assertEquals(4017L, RosterData.difference(Money.dollars(1150), Money(110983)).cents)
        assertEquals(-50983L, RosterData.difference(Money.dollars(600), Money(110983)).cents)
        assertEquals(Money.ZERO, RosterData.difference(Money(110983), Money(110983)))
    }

    @Test fun logPayStateComputesDifferenceAgainstExpectedNet() {
        rosterTemplate("2026-11-16")
        val vm = state("2026-11-16")
        val log = LogPayState(vm.data)
        log.selectSource(DemoData.SOURCE_ID)
        log.selectPayday(d("2026-11-25"))
        log.expected = runBlocking { vm.expectedNet(log.source!!, log.payday) }
        assertEquals(110983L, log.expected!!.cents)

        log.netText = "1,150.00"
        assertEquals(4017L, log.difference!!.cents)
        log.netText = "$1000"
        assertEquals(100000L - 110983L, log.difference!!.cents)
        log.netText = ""
        assertNull(log.difference)
        assertFalse(log.canSave)
    }

    @Test fun loggingPayTwiceUpdatesTheSameRow() {
        val vm = state("2026-11-16")
        // The demo already has a logged pay for 25 Nov.
        val before = runBlocking { repo.paysReceived() }.size
        assertTrue(runBlocking { vm.logPay(DemoData.SOURCE_ID, d("2026-11-25"), Money.dollars(700), null, null, "") })
        assertEquals(before, runBlocking { repo.paysReceived() }.size)
        assertEquals(Money.dollars(700), runBlocking { repo.paysReceived() }.first { it.payDate == d("2026-11-25") }.net)

        // A form for the same source and payday loads the existing record and saves over it.
        val log = LogPayState(vm.data)
        log.selectSource(DemoData.SOURCE_ID)
        log.selectPayday(d("2026-11-25"))
        assertNotNull(log.existing)
        assertEquals("700.00", log.netText)
        log.netText = "725.50"
        val pay = log.toPay()!!
        assertEquals(log.existing!!.id, pay.id)
        assertTrue(runBlocking { vm.logPay(pay.sourceID, pay.payDate, pay.net, pay.gross, pay.tax, pay.note) })
        assertEquals(before, runBlocking { repo.paysReceived() }.size)
        assertEquals(Money(72550), runBlocking { repo.paysReceived() }.first { it.payDate == d("2026-11-25") }.net)

        // A new payday adds a row and shows first in the recent list.
        assertTrue(runBlocking { vm.logPay(DemoData.SOURCE_ID, d("2026-12-02"), Money.dollars(800), Money.dollars(900), Money.dollars(100), "x") })
        assertEquals(before + 1, runBlocking { repo.paysReceived() }.size)
        assertEquals(d("2026-12-02"), vm.data.recentPays.first().payDate)
        assertEquals(d("2026-12-02"), vm.payRows.first().pay.payDate)
    }

    // endregion
}
