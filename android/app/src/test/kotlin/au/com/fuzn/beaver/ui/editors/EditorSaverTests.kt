package au.com.fuzn.beaver.ui.editors

import androidx.compose.runtime.saveable.SaverScope
import au.com.fuzn.beaver.core.BillCategory
import au.com.fuzn.beaver.core.DaySelector
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Holiday
import au.com.fuzn.beaver.core.IncomeKind
import au.com.fuzn.beaver.core.UnavailableRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/** What a rotation does to an editor: the typed fields go through the Saver and come back unchanged. */
class EditorSaverTests {
    private fun <M : EditorStateSaving> roundTrip(create: () -> M, model: M): M {
        val saver = editorSaver(create)
        val saved = with(saver) { SaverScope { true }.save(model) }!!
        return saver.restore(saved)!!
    }

    @Test fun billKeepsTypedFields() {
        val m = BillEditorModel(null, LocalDate.of(2026, 11, 23))
        m.name = "Phone"; m.amountText = "49.99"; m.cadenceKind = BillCadenceKind.EveryNDays; m.everyNDaysText = "9"
        m.category = BillCategory.utilities; m.account = "Joint"; m.note = "n"; m.isActive = false
        m.anchorDate = LocalDate.of(2026, 12, 1)
        val r = roundTrip({ BillEditorModel(null, LocalDate.of(2026, 11, 23)) }, m)
        assertEquals("Phone", r.name)
        assertEquals("49.99", r.amountText)
        assertEquals(BillCadenceKind.EveryNDays, r.cadenceKind)
        assertEquals("9", r.everyNDaysText)
        assertEquals(BillCategory.utilities, r.category)
        assertEquals("Joint", r.account)
        assertFalse(r.isActive)
        assertEquals(LocalDate.of(2026, 12, 1), r.anchorDate)
        assertTrue(r.touches.shows(BillEditorModel.Field.Name))
        assertFalse(r.touches.shows(BillEditorModel.Field.Cadence) && !m.touches.shows(BillEditorModel.Field.Cadence))
    }

    @Test fun goalKeepsTypedFields() {
        val today = LocalDate.of(2026, 11, 23)
        val m = GoalEditorModel(today = today)
        m.name = "Car"; m.targetText = "6000"; m.hasDeadline = true; m.deadline = today.plusMonths(3); m.savedText = "250"
        m.makePrimary = false
        val r = roundTrip({ GoalEditorModel(today = today) }, m)
        assertEquals("Car", r.name)
        assertEquals("6000", r.targetText)
        assertTrue(r.hasDeadline)
        assertEquals(today.plusMonths(3), r.deadline)
        assertEquals("250", r.savedText)
        assertFalse(r.makePrimary)
        assertTrue(r.isValid)
    }

    @Test fun rateCardKeepsRules() {
        val m = RateCardEditorModel()
        m.name = "Casual"
        m.addRule()
        val id = m.rules.single().id
        m.setBand(id, "Evening"); m.setRate(id, "31.50"); m.setDays(id, DaySelector.saturday)
        m.setHasWindow(id, true); m.setFrom(id, 18 * 60); m.setTo(id, 22 * 60)
        val r = roundTrip({ RateCardEditorModel() }, m)
        assertEquals("Casual", r.name)
        assertEquals(m.rules.toList(), r.rules.toList())
        assertTrue(r.isValid)
    }

    @Test fun templateKeepsShifts() {
        val m = TemplateEditorModel(1, today = LocalDate.of(2026, 11, 23))
        m.name = "Semester 2"; m.hasEffectiveFrom = true
        m.addShift()
        val s = m.shifts.single()
        m.updateShift(s.id) { it.copy(weekday = DayOfWeek.FRIDAY, start = LocalTime.of(8, 0), end = LocalTime.of(14, 30), breakMinutes = 15) }
        val r = roundTrip({ TemplateEditorModel(1, today = LocalDate.of(2026, 11, 23)) }, m)
        assertEquals("Semester 2", r.name)
        assertTrue(r.hasEffectiveFrom)
        assertEquals(m.shifts.toList(), r.shifts.toList())
    }

    @Test fun limitsKeepsHolidaysAndRangesAndIsNotOverwrittenByLoad() {
        val today = LocalDate.of(2026, 11, 23)
        val m = LimitsEditorModel(today = today)
        m.hasLimit = true; m.hoursText = "48"
        m.saveHoliday(Holiday(today.plusDays(10), "Away day"))
        m.saveUnavailable(UnavailableRange(from = today, to = today.plusDays(3), note = "Exams"))
        val r = roundTrip({ LimitsEditorModel(today = today) }, m)
        assertTrue(r.hasLimit)
        assertEquals("48", r.hoursText)
        assertEquals(m.holidays, r.holidays)
        assertEquals(m.unavailable, r.unavailable)
    }

    @Test fun incomeSourceKeepsFieldsAndRateCard() {
        val today = LocalDate.of(2026, 11, 23)
        val m = IncomeSourceEditorModel(null, IncomeKind.hourly, today)
        m.name = "Cafe"; m.softCapText = "20"; m.hardCapText = "24"; m.payWeekday = DayOfWeek.THURSDAY
        m.taxKind = IncomeTaxKind.Flat; m.flatPercentText = "15"
        m.rateCard.name = "Cafe card"
        m.rateCard.addRule()
        val r = roundTrip({ IncomeSourceEditorModel(null, IncomeKind.hourly, today) }, m)
        assertEquals("Cafe", r.name)
        assertEquals("20", r.softCapText)
        assertEquals("24", r.hardCapText)
        assertEquals(DayOfWeek.THURSDAY, r.payWeekday)
        assertEquals(IncomeTaxKind.Flat, r.taxKind)
        assertEquals("15", r.flatPercentText)
        assertEquals("Cafe card", r.rateCard.name)
        assertEquals(1, r.rateCard.rules.size)
    }

    @Test fun existingBillRestoresOverItsOriginal() {
        val bill = DemoData.bills[2]
        val m = BillEditorModel(bill)
        m.note = "edited"
        val r = roundTrip({ BillEditorModel(bill) }, m)
        assertEquals("edited", r.note)
        assertEquals(bill.id, r.id)
    }
}
