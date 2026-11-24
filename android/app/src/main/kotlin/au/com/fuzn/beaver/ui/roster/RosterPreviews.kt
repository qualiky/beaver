package au.com.fuzn.beaver.ui.roster

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import au.com.fuzn.beaver.core.DateRange
import au.com.fuzn.beaver.core.DemoData
import au.com.fuzn.beaver.core.Money
import au.com.fuzn.beaver.core.PayReceived
import au.com.fuzn.beaver.core.Shift
import au.com.fuzn.beaver.core.TemplateExpander
import au.com.fuzn.beaver.core.WithholdingTable
import au.com.fuzn.beaver.ui.theme.BeaverTheme
import java.time.LocalDate

// Previews use only the fictional demo data from the core module.

private val previewWeek: LocalDate = LocalDate.of(2026, 11, 16)

private fun demoData(week: LocalDate = previewWeek, withShifts: Boolean = true): RosterData {
    val input = DemoData.forecastInput()
    val shifts: List<Shift> = if (withShifts) {
        TemplateExpander.shifts(DemoData.SOURCE_ID, input.templates, DateRange(week.minusDays(14), week.plusDays(20)))
            .mapIndexed { i, s -> s.copy(id = i + 1) }
    } else emptyList()
    return RosterData(
        weekStart = week, today = DemoData.referenceToday, sources = input.sources, rateCards = input.rateCards,
        templates = input.templates, holidays = input.holidays, unavailable = input.unavailable,
        tables = listOf(WithholdingTable.auScale2Weekly), pays = input.paysReceived, shifts = shifts,
    )
}

@Composable
private fun PreviewContent(data: RosterData) {
    BeaverTheme {
        Surface(Modifier.fillMaxSize()) {
            RosterContent(
                data = data, currency = "AUD", loaded = true,
                onPreviousWeek = {}, onNextWeek = {}, onThisWeek = {}, onAddShift = {}, onEditShift = {}, onDeleteShift = {},
                onMarkWorked = {}, onCopyLastWeek = {}, onApplyTemplate = {}, onSaveTemplate = {}, onLogPay = {}, onOpenSettings = {},
            )
        }
    }
}

@Preview(name = "Week with shifts", showBackground = true, heightDp = 900)
@Composable
private fun WeekWithShiftsPreview() = PreviewContent(demoData())

@Preview(name = "Empty week", showBackground = true)
@Composable
private fun EmptyWeekPreview() = PreviewContent(demoData(withShifts = false))

@Preview(name = "No hourly sources", showBackground = true)
@Composable
private fun NoSourcesPreview() = PreviewContent(RosterData(previewWeek, DemoData.referenceToday))

@Preview(name = "Large font", showBackground = true, fontScale = 2f, heightDp = 1200)
@Composable
private fun LargeFontPreview() = PreviewContent(demoData())

@Preview(name = "Shift editor", showBackground = true, heightDp = 1000)
@Composable
private fun ShiftEditorPreview() {
    val data = demoData()
    val editor = ShiftEditorState(null, LocalDate.of(2026, 12, 25), data, emptyMap()).apply {
        start = java.time.LocalTime.of(20, 0)
        end = java.time.LocalTime.of(2, 0)
        selectBreak(30)
    }
    BeaverTheme { Surface { ShiftEditorForm(editor, "AUD", onCancel = {}, onSave = {}) } }
}

@Preview(name = "Log pay", showBackground = true, heightDp = 1000)
@Composable
private fun LogPayPreview() {
    val data = demoData()
    val log = LogPayState(data).apply {
        netText = "1150.00"
        expected = Money(110983)
    }
    val rows = listOf(
        PayRow(PayReceived(1, DemoData.SOURCE_ID, LocalDate.of(2026, 11, 25), Money(60000)), DemoData.source.name, Money(110983)),
    )
    BeaverTheme { Surface { LogPayForm(log, "AUD", rows, onClose = {}, onSave = {}, onDeletePay = {}) } }
}
